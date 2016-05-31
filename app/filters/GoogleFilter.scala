package filters

import javax.inject.{Singleton, Inject}
import akka.stream.Materializer
import play.api.mvc._
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext

@Singleton
class GoogleFilter @Inject() (implicit val mat: Materializer) extends Filter {
  val ignoreHeaders = Set("host", "play_session", "x-request-id", "x-forwarded-for", "x-forwarded-proto", "x-forwarded-port", "via", "connect-time", "x-request-start", "total-route-time")
  val hostMap = Map("a.jikewenku.cn" -> "www.google.com")

  def apply(nextFilter: RequestHeader => Future[Result])(requestHeader: RequestHeader): Future[Result] = {
    println("GoogleFilter " + requestHeader.path)
    val reqHost = requestHeader.host.split(":")(0).toLowerCase()
    hostMap.get(reqHost) match {
      case Some(toHost) =>
        //# 处理请求
        //## 移除多余的请求头
        var headers = requestHeader.headers.headers.filter{ t =>
          !ignoreHeaders.contains(t._1.trim.toLowerCase())
        }
        //## 修正referer请求头
        headers = headers.map{
          case (k, v) if k.trim.toLowerCase == "referer" =>
            (k, v.replaceAll(reqHost, toHost))
          case other => other
        }

        //# 处理响应
        val refinedRequestHeaders = requestHeader.copy(headers = Headers(headers: _*))
        nextFilter(refinedRequestHeaders).flatMap { result =>
          println("GoogleFilter----------------------" + result.body.contentType.getOrElse("Nulllllllll"))
          result.header.headers.foreach{ t =>
            println(t._1 + ": " + t._2)
          }
          println("GoogleFilter----------------------")

          //## 处理Set-Cookie响应头
          val respHeaders = result.header.headers.map{
            case (k, v) if k.trim.toLowerCase == "set-cookie" =>
              (k, v.replaceAll(toHost, reqHost))
            case other => other
          }

          val contentType = result.header.headers.find(t => t._1.trim.toLowerCase == "content-type").map(_._2).getOrElse("application/octet-stream").toLowerCase
          if(contentType.contains("text/html")){
            //Remove blocked request
            result.body.dataStream.runReduce(_.concat(_)).map(_.utf8String).map{ bodyStr =>
              var content =
                bodyStr
                  .replace(toHost, reqHost)
              content += """<script>function rwt(link){ link.target="_blank"; link.click(); }</script>"""

              Results.Status(result.header.status)(bodyStr)
                .withHeaders(respHeaders.filter(t => !Set("content-length", "transfer-encoding", "content-encoding").contains(t._1.trim.toLowerCase)).toList: _*)
            }
          } else {
            Future.successful(result)
          }
        }

      case None =>
        //Future.successful(Results.Forbidden("No toHost mapping for " + reqHost))
        Future.successful(Results.Ok("No toHost mapping for " + reqHost).as("text/html"))
    }
  }
}
