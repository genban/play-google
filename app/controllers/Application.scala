package controllers

import java.util.Base64
import javax.inject.Inject

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.typesafe.config.ConfigList
import filters.GoogleFilter
import play.api.Configuration
import play.api.http.HttpEntity
import play.api.libs.json.Json
import play.api.libs.ws.{DefaultWSProxyServer, WSClient}
import play.api.mvc._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

class Application @Inject() (cc: ControllerComponents, ws: WSClient, config: Configuration)(implicit ec: ExecutionContext, mat: Materializer, parser: BodyParsers.Default) extends AbstractController(cc) {
  val ignoredResponseHeaders = Set("content-length", "transfer-encoding", "content-encoding", "content-type")

  val googleLang = config.getOptional[String]("google.language").getOrElse("en")
  val useSniProxy = config.getOptional[Boolean]("sniProxy.enable").getOrElse(false)
  val sniProxyList = config.getOptional[Seq[String]]("sniProxy.list").getOrElse(Seq.empty[String])
  val useHttpProxy = config.getOptional[Boolean]("httpProxy.enable").getOrElse(false)
  val httpProxyList = config.getOptional[Seq[String]]("httpProxy.list").getOrElse(Seq.empty[String])
  /**
    * Proxy all requests to Google Search.
    *
    * @param pathPart match the sub path in request.
    * @return Future[Result]
    */
  def executeRequest(pathPart: String) = Action.async(parse.raw) { request =>
    var requestHost = "www.google.com"
    var requestPath = if(request.path.trim == "/" && !request.cookies.exists(c => c.name.toLowerCase == "nid")){ "/ncr" } else { request.path.trim }
    if(requestPath.startsWith("/routing_for/")){
      requestPath = requestPath.replace("/routing_for/", "")
      val pos = requestPath.indexOf("/")
      if(pos > 0){
        requestHost = requestPath.substring(0, pos)
        requestPath = requestPath.replace(requestHost, "")
      }
    }

    val amendedQueryString =
      if(requestPath == "/"){
        if(request.rawQueryString.trim == ""){
          s"hl=${googleLang}&lang=${googleLang}&lr=lang_${googleLang}"
        }else{
          request.rawQueryString + s"&hl=${googleLang}&lang=${googleLang}&lr=lang_${googleLang}"
        }
      } else {
        request.rawQueryString
      }

    // For Debug with Burp
    /*var req = ws.url(s"https://${requestHost}${requestPath}?${amendedQueryString}")
                .withRequestTimeout(15 seconds)
                .withMethod(request.method)
                .withHttpHeaders(request.headers.toSimpleMap.toList.filter(_._1.toLowerCase != "host"): _*)
    req = req.withProxyServer(DefaultWSProxyServer("127.0.0.1", 8080))*/

    val hostInUrl = if (useSniProxy && sniProxyList.nonEmpty) { sniProxyList.head } else { requestHost }
    var req = ws.url(s"https://${hostInUrl}${requestPath}?${amendedQueryString}")
      .withVirtualHost(requestHost)
      .withRequestTimeout(10 seconds)
      .withMethod(request.method)
      .withHttpHeaders(request.headers.toSimpleMap.toList.filter(_._1.toLowerCase != "host"): _*)
      .addHttpHeaders("Host" -> requestHost)

    val bodyOpt = request.body.asBytes()
    if(bodyOpt.nonEmpty){
      req = req.withBody(bodyOpt.get)
    }

    if(useHttpProxy){
      val randHttpProxy = httpProxyList(Random.nextInt(httpProxyList.size))
      val proxySplitArr = randHttpProxy.split(":")
      req = req.withProxyServer(DefaultWSProxyServer(proxySplitArr(0), proxySplitArr(1).toInt))
    }

    req.stream().flatMap { wsResp =>
      if (wsResp.status >= 200 && wsResp.status < 300) {
        val contentType   = wsResp.headers.find(t => t._1.trim.toLowerCase == "content-type").map(_._2.mkString("; ")).getOrElse("application/octet-stream").toLowerCase
        //处理文字搜索的rwt函数不能影响到图片搜索的rwt函数
        if((contentType.contains("html") || contentType.contains("javascript")) && wsResp.status != 204){
          //Remove blocked request
          wsResp.bodyAsSource.runReduce(_.concat(_)).map(_.utf8String)map{ bodyStr =>
            val scheme = if(request.secure){ "https" } else { "http" }
            var content =
              bodyStr
                .replaceAll("http[s]?://www.google.com",  s"${scheme}://${request.host}")
                .replaceAll("www.google.com",  s"${request.host}")
                .replaceAll("http[s]?://ssl.gstatic.com", s"${scheme}://${request.host}/routing_for/ssl.gstatic.com")
                .replaceAll("ssl.gstatic.com", s"${request.host}/routing_for/ssl.gstatic.com")
                .replaceAll("http[s]?://www.gstatic.com", s"${scheme}://${request.host}/routing_for/www.gstatic.com")
                .replaceAll("www.gstatic.com", s"${request.host}/routing_for/www.gstatic.com")
                .replaceAll("http[s]?://id.google.com",   s"${scheme}://${request.host}/routing_for/id.google.com")
                .replaceAll("http[s]?://apis.google.com",   s"${scheme}://${request.host}/routing_for/apis.google.com")
                .replaceAll("apis.google.com",   s"${request.host}/routing_for/apis.google.com")
                .replaceAll("http[s]?://encrypted-tbn(\\d+).gstatic.com",   s"${scheme}://${request.host}/routing_for/encrypted-tbn$$1.gstatic.com")
                .replaceAll("http[s]?://lh(\\d+).googleusercontent.com",   s"${scheme}://${request.host}/routing_for/lh$$1.googleusercontent.com")

            if(request.path == "/"){
              content += """<script>function rwt_(link){ link.target="_blank"; link.click(); }</script>"""
            } else if(request.path == "/search" && contentType.contains("html")){
              content = content.replace("rwt(this,", "rwt_(this,")
              content += """<script>function rwt_(link){ link.target="_blank"; link.click(); }</script>"""
            } else if(request.path == "/search" && contentType.contains("json")){
              content = content.replace("rwt(this,", "rwt_(this,")
            }

            Ok(content)
              .withHeaders(wsResp.headers.filter(t => !ignoredResponseHeaders.contains(t._1.toLowerCase)).map(t => (t._1, t._2.mkString("; "))).toList: _*)
              .as(contentType)
          }
        } else {
          // If there's a content length, send that, otherwise return the body chunked
          wsResp.headers.find(t => t._1.toLowerCase == "content-length").map(_._2) match {
            case Some(Seq(length)) =>
              Future.successful{
                Ok.sendEntity(HttpEntity.Streamed(wsResp.bodyAsSource, Some(length.toLong), Some(contentType)))
                  .withHeaders(wsResp.headers.filter(t => !ignoredResponseHeaders.contains(t._1.toLowerCase)).map(t => (t._1, t._2.mkString("; "))).toList: _*)
              }
            case _ =>
              Future.successful{
                Ok.chunked(wsResp.bodyAsSource)
                  .withHeaders(wsResp.headers.filter(t => !ignoredResponseHeaders.contains(t._1.toLowerCase)).map(t => (t._1, t._2.mkString("; "))).toList: _*)
                  .as(contentType)
              }
          }
        }
      } else if(wsResp.status >= 300 && wsResp.status < 500) {
        Future.successful{
          Status(wsResp.status)
            .withHeaders(wsResp.headers.filter(t => t._1.trim.toLowerCase == "location" || t._1.trim.toLowerCase == "set-cookie").map(t => (t._1, t._2.mkString("; "))).toList: _*)
        }
      } else {
        Future.successful(InternalServerError(wsResp.status.toString))
      }
    }
  }

  def robots = Action {
    Ok("""User-agent: *
         |Disallow: /
       """.stripMargin
    )
  }
}
