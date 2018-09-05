import javax.inject._

import akka.stream.Materializer
import filters.GoogleFilter
import play.api.http.HttpFilters
import play.api.mvc.EssentialAction
import play.filters.gzip.GzipFilter

@Singleton
class Filters @Inject() (googleFilter: GoogleFilter, implicit val mat: Materializer) extends HttpFilters {
  val next: EssentialAction = null
  val ret = googleFilter(next)

  override val filters =
    Seq(
      new GzipFilter(shouldGzip = (request, response) => {
          val contentType = response.body.contentType.getOrElse("").toLowerCase
          contentType.contains("text") || contentType.contains("json") || contentType.contains("javascript")
        }
      ),
      googleFilter
    )

}
