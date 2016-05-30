import javax.inject._

import akka.stream.Materializer
import filters.GoogleFilter
import play.api.http.HttpFilters
import play.filters.gzip.GzipFilter

@Singleton
class Filters @Inject() (googleFilter: GoogleFilter, implicit val mat: Materializer) extends HttpFilters {
  override val filters =
    Seq(
      googleFilter,
      new GzipFilter(shouldGzip = (request, response) => {
          val contentType = response.header.headers.find(t => t._1.trim.toLowerCase == "content-type").map(_._2).getOrElse("").toLowerCase
          println("GzipFilter " + request.path + " - " + contentType)
          contentType.contains("text") || contentType.contains("json") || contentType.contains("javascript")
        }
      )
    )

}
