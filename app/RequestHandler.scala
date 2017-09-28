import javax.inject.Inject

import play.api.Configuration
import play.api.http._
import play.api.mvc._
import play.api.routing.Router

import scala.concurrent.Future

class RequestHandler @Inject() (router: Router, errorHandler: HttpErrorHandler,
  configuration: HttpConfiguration, filters: HttpFilters, config: Configuration) extends DefaultHttpRequestHandler(router, errorHandler, configuration, filters) {
  val domain = config.getOptional[String]("domain").getOrElse("")
  val loginCheck = config.getOptional[Boolean]("loginCheck").getOrElse(false)

  override def routeRequest(request: RequestHeader) = {
    val reqPath = request.path
    //println(request.method + ": " + reqPath)
    if (loginCheck && request.session.get("login").isEmpty){
      Some(Action(Results.Forbidden))
    } else if (domain != "" && !request.host.contains(domain)){
      Some(Action(Results.Forbidden))
    } else if(reqPath.startsWith("/gen_204")){
      Some(Action(Results.NoContent))
    } else if(reqPath.startsWith("/url")){
      request.getQueryString("url") match {
        case Some(url) =>
          Some(Action(Results.TemporaryRedirect(url)))
        case _ =>
          Some(Action(Results.Ok("Redirect to invalid url.")))
      }
    } else if(
      reqPath == "/" ||
      reqPath == "/ncr" ||
      reqPath == "/preferences" ||
      reqPath == "/setprefs" ||
      reqPath.startsWith("/url") ||
      reqPath.startsWith("/xjs/") ||
      reqPath.startsWith("/images/") ||
      reqPath.startsWith("/gen_204") ||
      reqPath.startsWith("/search") ||
      reqPath.startsWith("/complete/search") ||
      reqPath.startsWith("/favicon.ico") ||
      reqPath.startsWith("/robots.txt") ||
      reqPath.startsWith("/logo/") ||
      reqPath.startsWith("/logos/") ||
      reqPath.startsWith("/maps") ||
      reqPath.startsWith("/imghp") ||
      reqPath.startsWith("/imgrc") ||
      reqPath.startsWith("/imgres") ||
      reqPath.startsWith("/imgevent") ||
      reqPath.startsWith("/async/irc") ||
      reqPath.startsWith("/ajax/pi/imgdisc") ||
      reqPath.startsWith("/searchbyimage/upload") ||
      reqPath.startsWith("/routing_for/") ||
      reqPath.startsWith("/textinputassistant/tia.png")
    ){
      //println("Pass: " + request.path)
      super.routeRequest(request)
    } else {
      //println("Intercept: " + request.path)
      Some(Action(Results.NoContent))
    }
  }
}
