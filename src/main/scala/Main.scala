package exystence

import java.util.concurrent.TimeUnit

import cats.effect.{ExitCode, IO, IOApp}
import org.http4s.Uri
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.client.middleware.FollowRedirect
import org.jsoup.Jsoup

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._

case class Post(date: String, tags: Seq[String])
case class Album(permanentLink: Uri, name: String)

object Main extends IOApp {
  def run(args: List[String]) = {

    val httpClientBuilder = BlazeClientBuilder[IO](global)

    fs2.Stream
      .range(1, 1046)
      .covary[IO]
      .metered(FiniteDuration(5, TimeUnit.SECONDS))
      .evalMap(page => {
        val uriString = s"http://exystence.net/page/$page"
        println(s"Start downloading from '$uriString'")
        val url = Uri.unsafeFromString(uriString)
        httpClientBuilder.resource
          .map(FollowRedirect(2))
          .use(_.expect[String](url))
          .map((page, _))
      })
      .flatMap(htmlPage => {
        println(s"Start parsing page '${htmlPage._1}'")
        val permanentLinks = Jsoup
          .parse(htmlPage._2)
          .select("div.posttop div.date a")
          .asScala
          .toList

        fs2.Stream
          .emits(permanentLinks.map { l =>
            val href = l.attr("href")
            println(s"Extracted permanentLink '$href'")
            Uri.unsafeFromString(href)
          })
          .evalMap(uri => IO {
            println(s"processing link: $uri")
          })
          .metered(FiniteDuration(1, TimeUnit.SECONDS))

      })
      .compile
      .drain
      .map(_ => ExitCode.Success)
  }
}
