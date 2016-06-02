package controllers

import java.util.Base64
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.google.inject.Inject
import filters.GoogleFilter
import play.api.Configuration
import play.api.http.HttpEntity
import play.api.libs.json.Json
import play.api.libs.ws.{DefaultWSProxyServer, StreamedResponse, WSClient}
import play.api.mvc._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Random

class Application @Inject() (ws: WSClient, config: Configuration, implicit val mat: Materializer) extends Controller {
  val ignoreHeaders = Set("host", "play_session", "x-request-id", "x-forwarded-for", "x-forwarded-proto", "x-forwarded-port", "via", "connect-time", "x-request-start", "total-route-time")
  val useHttpProxy = config.getBoolean("httpProxy.enable").getOrElse(false)
  val httpProxyList = config.getStringSeq("httpProxy.list").getOrElse(Seq.empty[String])

  def executeRequest(pathPart: String) = Action.async(parse.raw) { request =>
    var requestHost = "www.google.com"
    var requestPath = request.path
    if(requestPath.startsWith("/dark_room/")){
      requestPath = requestPath.replace("/dark_room/", "")
      val pos = requestPath.indexOf("/")
      if(pos > 0){
        requestHost = requestPath.substring(0, pos)
        requestPath = requestPath.replace(requestHost, "")
      }
    }

    var req = ws.url(s"https://${requestHost}${requestPath}?${request.rawQueryString}")
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
    req.stream().flatMap {
      case StreamedResponse(response, body) =>
        if (response.status >= 200 && response.status < 300) {
          val refinedHost =
            if(request.host.contains(":")){
              request.host
            }else{
              s"${request.host}:80"
            }

          val contentType = response.headers.find(t => t._1.trim.toLowerCase == "content-type").map(_._2.mkString("; ")).getOrElse("application/octet-stream").toLowerCase
          if(contentType.contains("html") || contentType.contains("javascript")){
            //Remove blocked request
            body.runReduce(_.concat(_)).map(_.utf8String)map{ bodyStr =>
              println("refinedHost: " + refinedHost + " =================================================")
              var content =
                bodyStr
                  .replace("www.google.com",  s"${refinedHost}")
                  .replace("ssl.gstatic.com", s"${refinedHost}/dark_room/ssl.gstatic.com")
                  .replace("www.gstatic.com", s"${refinedHost}/dark_room/www.gstatic.com")
                  .replace("id.google.com",   s"${refinedHost}/dark_room/id.google.com")
                  .replaceAll("encrypted-tbn0.gstatic.com",   s"${refinedHost}/dark_room/encrypted-tbn0.gstatic.com")
                  .replaceAll("encrypted-tbn1.gstatic.com",   s"${refinedHost}/dark_room/encrypted-tbn1.gstatic.com")
                  .replaceAll("encrypted-tbn2.gstatic.com",   s"${refinedHost}/dark_room/encrypted-tbn2.gstatic.com")
                  .replaceAll("encrypted-tbn3.gstatic.com",   s"${refinedHost}/dark_room/encrypted-tbn3.gstatic.com")

              if(contentType.contains("text/html")) {
                content += """<script>function rwt(link){ link.target="_blank"; link.click(); }</script>"""
              } else if(contentType.contains("text/javascript")){
                content += """ function rwt(link){ link.target="_blank"; link.click(); } """
              }
              Ok(content)
                .withHeaders(response.headers.filter(t => t._1.trim.toLowerCase != "content-length" && t._1.trim.toLowerCase != "transfer-encoding" && t._1.trim.toLowerCase != "content-encoding").map(t => (t._1, t._2.mkString("; "))).toList: _*)
            }
          } else {
            // If there's a content length, send that, otherwise return the body chunked
            response.headers.find(t => t._1.trim.toLowerCase == "content-length").map(_._2) match {
              case Some(Seq(length)) =>
                Future.successful(Ok.sendEntity(HttpEntity.Streamed(body, Some(length.toLong), None)).withHeaders(response.headers.map(t => (t._1, t._2.mkString("; "))).toList: _*))
              case _ =>
                Future.successful(Ok.chunked(body).withHeaders(response.headers.map(t => (t._1, t._2.mkString("; "))).toList: _*))
            }
          }
        } else if(response.status >= 300 && response.status < 500) {
          Future.successful{
            Status(response.status)
              .withHeaders(response.headers.filter(t => t._1.trim.toLowerCase == "location" || t._1.trim.toLowerCase == "set-cookie").map(t => (t._1, t._2.mkString("; "))).toList: _*)
          }
        } else {
          Future.successful(InternalServerError("Sorry, server return " + response.status))
        }
    }
  }

  /**
    * Proxy all requests to Google Search.
 *
    * @param pathPart match the sub path in request.
    * @param bbaassee raw query parameters encoded in base64 format.
    * @return Result
    */
  def get(pathPart: String, bbaassee: String) = Action.async{ request =>
    request.path match {
      case path if path.toLowerCase.startsWith("/gen_204") =>
        Future.successful(Results.NoContent)
      case path if path.toLowerCase.startsWith("/url") =>
        request.getQueryString("url") match {
          case Some(url) =>
            Future.successful(TemporaryRedirect(url))
          case _ =>
            Future.successful(Ok("Redirect to invalid url."))
        }
      case path =>
        //Remove proxy headers
        var headers = request.headers.toSimpleMap.filterKeys{ key =>
          !ignoreHeaders.contains(key.trim.toLowerCase())
        }
        //Refine referer
        headers = headers.map{
          case (k, v) if k.trim.toLowerCase == "referer" =>
            (k, v.replaceFirst("""//[^/]+/?""", "//www.google.com/"))
          case other => other
        }
        val refinedRawQueryStr = bbaassee match {
          case base if base.trim == "" =>
            request.rawQueryString
          case base =>
            val bytes = Base64.getUrlDecoder.decode(base)
            new String(bytes, "utf-8")
        }
        val url =
          if(request.path == "/"){
            s"https://www.google.com"
          } else {
            s"https://www.google.com${request.path}?${refinedRawQueryStr}"
          }
        var req = ws.url(url).withRequestTimeout(15 seconds).withMethod("GET").withHeaders(headers.toList: _*)
        if(useHttpProxy){
          val randHttpProxy = httpProxyList(Random.nextInt(httpProxyList.size))
          val proxySplitArr = randHttpProxy.split(":")
          req = req.withProxyServer(DefaultWSProxyServer(proxySplitArr(0), proxySplitArr(1).toInt))
        }
        req
          //.withRequestFilter(AhcCurlRequestLogger())
          .stream().flatMap {
          case StreamedResponse(response, body) =>
            //Refine response headers
            val respHeaders = response.headers.map{
              case (k, seq) if k.trim.toLowerCase == "set-cookie" =>
                (k, seq.map(v => v.replaceAll("""domain=[^;=]+\.com;?""", "")))
              case other => other
            }

            if (response.status >= 200 && response.status < 300) {
              val refinedHost =
                if(request.host.contains(":")){
                  request.host
                }else{
                  s"${request.host}:80"
                }

              val contentType = response.headers.find(t => t._1.trim.toLowerCase == "content-type").map(_._2.mkString("; ")).getOrElse("application/octet-stream").toLowerCase
              if(contentType.contains("text/html")){
                //Remove blocked request
                body.runReduce(_.concat(_)).map(_.utf8String)map{ bodyStr =>
                  var content =
                    bodyStr
                      .replace("ssl.gstatic.com", s"${refinedHost}/blackHole")
                      .replace("www.gstatic.com", s"${refinedHost}/blackHole")
                      .replace("www.google.com",  s"${refinedHost}")
                      .replace("id.google.com",   s"${refinedHost}/blackHole")
                  content += """<script>function rwt(link){ link.target="_blank"; link.click(); }</script>"""

                  Ok(content)
                    .withHeaders(respHeaders.filter(t => t._1.trim.toLowerCase != "content-length" && t._1.trim.toLowerCase != "transfer-encoding" && t._1.trim.toLowerCase != "content-encoding").map(t => (t._1, t._2.mkString("; "))).toList: _*)
                }
              } else if(contentType.contains("text/javascript")){
                //Remove blocked request
                body.runReduce(_.concat(_)).map(_.utf8String).map{ bodyStr =>
                  val content =
                    bodyStr
                      .replace("ssl.gstatic.com", s"${refinedHost}/blackHole")
                      .replace("www.gstatic.com", s"${refinedHost}/blackHole")
                      .replace("www.google.com",  s"${refinedHost}")
                      .replace("id.google.com",   s"${refinedHost}/blackHole")

                  Ok(content + """function rwt(link){ link.target="_blank"; link.click(); }""")
                    .withHeaders(respHeaders.filter(t => t._1.trim.toLowerCase != "content-length" && t._1.trim.toLowerCase != "transfer-encoding" && t._1.trim.toLowerCase != "content-encoding").map(t => (t._1, t._2.mkString("; "))).toList: _*)
                }
              } else {
                // If there's a content length, send that, otherwise return the body chunked
                response.headers.find(t => t._1.trim.toLowerCase == "content-length").map(_._2) match {
                  case Some(Seq(length)) =>
                    Future.successful(Ok.sendEntity(HttpEntity.Streamed(body, Some(length.toLong), None)).withHeaders(respHeaders.map(t => (t._1, t._2.mkString("; "))).toList: _*))
                  case _ =>
                    Future.successful(Ok.chunked(body).withHeaders(respHeaders.map(t => (t._1, t._2.mkString("; "))).toList: _*))
                }
              }
            } else if(response.status >= 300 && response.status < 500) {
              Future.successful{
                Status(response.status)
                  .withHeaders(respHeaders.filter(t => t._1.trim.toLowerCase == "location" || t._1.trim.toLowerCase == "set-cookie").map(t => (t._1, t._2.mkString("; "))).toList: _*)
              }
            } else {
              Future.successful(InternalServerError("Sorry, server return " + response.status))
            }
        }
    }
  }

  def post(path: String) = Action {
    Results.NoContent
  }

  def blackHole(path: String) = Action {
    Results.NoContent
  }

  def robots = Action {
    Ok("""User-agent: *
         |Disallow: /
       """.stripMargin
    )
  }
}
