package filters

import javax.inject.{Singleton, Inject}
import akka.stream.Materializer
import play.api.mvc._
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext

object GoogleFilter{
  val ignoreHeaders = Set("play_session", "x-request-id", "x-forwarded-for", "x-forwarded-proto", "x-forwarded-port", "via", "connect-time", "x-request-start", "total-route-time")
}

@Singleton
class GoogleFilter @Inject() (implicit val mat: Materializer) extends Filter {
  import GoogleFilter._

  def apply(nextFilter: RequestHeader => Future[Result])(requestHeader: RequestHeader): Future[Result] = {
    //# 处理请求
    //## 移除多余的请求头
    var headers = requestHeader.headers.headers.filter{ t =>
      !ignoreHeaders.contains(t._1.trim.toLowerCase())
    }
    //## 修正referer请求头
    headers = headers.map{
      case (k, v) if k.trim.toLowerCase == "referer" =>
        //(k, v.replaceAll(requestHeader.host, "www.google.com"))
        (k, v.replaceFirst("""//[^/]+/?""", "//www.google.com/"))
      case other => other
    }

    //# 处理响应
    val refinedRequestHeaders = requestHeader.copy(headers = Headers(headers: _*))
    nextFilter(refinedRequestHeaders).map{ result =>
      val reqHost =
        if(requestHeader.host.contains(":")){
          requestHeader.host.split(":")(0)
        } else {
          requestHeader.host
        }
      //## 处理Set-Cookie响应头
      val respHeaders = result.header.headers.map{
        case (k, v) if k.trim.toLowerCase == "set-cookie" =>
          val setCookies = Cookies.decodeSetCookieHeader(v).map{ cookie =>
            cookie.copy(domain = Some(reqHost))
          }
          (k, Cookies.encodeSetCookieHeader(setCookies))

        case (k, v) if k.trim.toLowerCase == "location" =>
          (k, v.replaceFirst("""//[^/]+/?""", s"//${requestHeader.host}/"))

        case other => other
      }

      result.copy(header = result.header.copy(headers = respHeaders))
    }
  }
}
