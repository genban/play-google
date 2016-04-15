import javax.inject._

import akka.stream.Materializer
import play.api.http.HttpFilters

@Singleton
class Filters @Inject() (implicit val mat: Materializer) extends HttpFilters {
  override val filters = Nil
/*  override val filters =
    Seq(
      new GzipFilter(shouldGzip = (request, response) => {
          val contentType = response.header.headers.find(t => t._1.trim.toLowerCase == "content-type").map(_._2).getOrElse("").toLowerCase
          contentType.contains("text/html") || contentType.contains("application/json") || contentType.contains("text/javascript")
        }
      )
    )*/

}
