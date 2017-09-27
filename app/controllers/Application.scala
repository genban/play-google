package controllers

import java.util.Base64
import javax.inject.Inject

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import filters.GoogleFilter
import play.api.Configuration
import play.api.http.HttpEntity
import play.api.libs.json.Json
import play.api.libs.ws.{DefaultWSProxyServer, WSClient}
import play.api.mvc._
import play.libs.ws.ahc.StreamedResponse

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Random

class Application @Inject() (ws: WSClient, config: Configuration, implicit val mat: Materializer) extends Controller {
  val googleLang = config.getString("google.language").getOrElse("en")
  val useHttpProxy = config.getBoolean("httpProxy.enable").getOrElse(false)
  val httpProxyList = config.getStringSeq("httpProxy.list").getOrElse(Seq.empty[String])

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

    var req = ws.url(s"https://${requestHost}${requestPath}?${amendedQueryString}")
                .withRequestTimeout(10 seconds)
                .withMethod(request.method).withHeaders(request.headers.toSimpleMap.toList.filter(_._1.trim.toLowerCase != "host"): _*)
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
      //case StreamedResponse(response, body) =>
        if (wsResp.status >= 200 && wsResp.status < 300) {
          val contentType   = wsResp.headers.find(t => t._1.trim.toLowerCase == "content-type").map(_._2.mkString("; ")).getOrElse("application/octet-stream").toLowerCase
          //处理文字搜索的rwt函数不能影响到图片搜索的rwt函数
          if((contentType.contains("html") || contentType.contains("json") || contentType.contains("javascript")) && wsResp.status != 204){
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
                .withHeaders(wsResp.headers.filter(t => t._1.trim.toLowerCase != "content-length" && t._1.trim.toLowerCase != "transfer-encoding" && t._1.trim.toLowerCase != "content-encoding").map(t => (t._1, t._2.mkString("; "))).toList: _*)
            }
          } else {
            // If there's a content length, send that, otherwise return the body chunked
            wsResp.headers.find(t => t._1.trim.toLowerCase == "content-length").map(_._2) match {
              case Some(Seq(length)) =>
                Future.successful(Ok.sendEntity(HttpEntity.Streamed(wsResp.bodyAsSource, Some(length.toLong), None)).withHeaders(wsResp.headers.map(t => (t._1, t._2.mkString("; "))).toList: _*))
              case _ =>
                Future.successful(Ok.chunked(wsResp.bodyAsSource).withHeaders(wsResp.headers.map(t => (t._1, t._2.mkString("; "))).toList: _*))
            }
          }
        } else if(wsResp.status >= 300 && wsResp.status < 500) {
          Future.successful{
            Status(wsResp.status)
              .withHeaders(wsResp.headers.filter(t => t._1.trim.toLowerCase == "location" || t._1.trim.toLowerCase == "set-cookie").map(t => (t._1, t._2.mkString("; "))).toList: _*)
          }
        } else {
          Future.successful(InternalServerError("Sorry, server return " + wsResp.status))
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
