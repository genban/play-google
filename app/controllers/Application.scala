package controllers

import java.util.Base64

import akka.stream.Materializer
import com.google.inject.Inject
import play.api.http.HttpEntity
import play.api.libs.ws.{StreamedResponse, WSClient}
import play.api.mvc._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class Application @Inject() (ws: WSClient, implicit val mat: Materializer) extends Controller {
  val ignoreHeaders = Set("host", "play_session", "x-request-id", "x-forwarded-for", "x-forwarded-proto", "x-forwarded-port", "via", "connect-time", "x-request-start", "total-route-time")

  def get(path: String, bbaassee: String) = Action.async{ request =>
    path match {
      case path if path.contains("204") =>
        Future.successful(Results.NoContent)

      //request.uri sometimes start with http...
      case path =>
        val refinedPath = path match {
          case p if p == "/" =>
            "http://www.google.com/"
          case p if p.startsWith("/") =>
            s"http://www.google.com${p}"
          case p =>
            s"http://www.google.com/${p}"
        }

        val refinedRawQueryStr = bbaassee match {
          case base if base.trim == "" =>
            request.rawQueryString

          case base =>
            val bytes = Base64.getUrlDecoder.decode(base)
            new String(bytes, "utf-8")
        }

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


        //val req = ws.url(url).withMethod("GET").withHeaders(headers.toList: _*)
        val req = ws.url(refinedPath + "?" + refinedRawQueryStr).withMethod("GET").withHeaders(headers.toList: _*)
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
                      .replace("www.google.com",  s"${refinedHost}/blackHole")
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
                      .replace("www.google.com",  s"${refinedHost}/blackHole")
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
            } else if(response.status == 304) {
              Future.successful(NotModified)
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
}
