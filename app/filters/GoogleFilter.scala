package filters

import javax.inject.{Singleton, Inject}
import akka.stream.Materializer
import play.api.mvc._
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext

object GoogleFilter{
  val ignoreHeaders = Set("play_session", "x-request-id", "x-forwarded-for", "x-forwarded-proto", "x-forwarded-port", "via", "connect-time", "x-request-start", "total-route-time")
  val hostMap = Map("a.jikewenku.cn" -> "www.google.com")
}

@Singleton
class GoogleFilter @Inject() (implicit val mat: Materializer) extends Filter {
  import GoogleFilter._

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
          case (k, v) if k.trim.toLowerCase == "host" =>
            if(requestHeader.host.contains(":")){
              (k, toHost + ":" + requestHeader.host.split(":")(1))
            } else {
              (k, toHost)
            }
          case other => other
        }

        //# 处理响应
        val refinedRequestHeaders = requestHeader.copy(headers = Headers(headers: _*))
        nextFilter(refinedRequestHeaders).map{ result =>
          //## 处理Set-Cookie响应头
          val respHeaders = result.header.headers.map{
            case (k, v) if k.trim.toLowerCase == "set-cookie" =>
              (k, v.replaceAll(toHost, reqHost))
            case other => other
          }

          result.copy(header = result.header.copy(headers = respHeaders))
        }

      case None =>
        //Future.successful(Results.Forbidden("No toHost mapping for " + reqHost))
        Future.successful(Results.Ok("No toHost mapping for " + reqHost).as("text/html"))
    }
  }
}
