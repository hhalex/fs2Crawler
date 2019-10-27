package exystence

import java.util.Locale
import java.util.concurrent.TimeUnit

import cats.effect.{ExitCode, IO, IOApp}
import com.github.nscala_time.time.Imports._
import org.http4s.Uri
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.client.middleware.FollowRedirect
import org.jsoup.Jsoup

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._

case class BlogPost(title: String,
                    date: DateTime,
                    permanentLink: Uri,
                    albumImage: Uri,
                    categories: Seq[String],
                    tags: Seq[String])

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
          .covary[IO]
          .metered(FiniteDuration(1, TimeUnit.SECONDS))
          .evalMap(uri => {
            println(s"Fetching and processing link: $uri")
            httpClientBuilder.resource
              .map(FollowRedirect(2))
              .use(_.expect[String](uri))
              .map(htmlDetailedPage => {
                val detailedPage = Jsoup
                  .parse(htmlDetailedPage)
                val postTitle =
                  detailedPage.selectFirst("div.posttop").ownText()
                val postDate = detailedPage
                  .selectFirst("div.postmetatop div.date span")
                  .ownText()

                val albumImage = Uri.unsafeFromString(
                  detailedPage
                    .selectFirst("div.postcontent img")
                    .parent()
                    .attr("href")
                )

                val parsedPostDate = DateTime.parse(
                  postDate,
                  DateTimeFormat
                    .forPattern("MMMM dd, yyyy")
                    .withLocale(new Locale(("en")))
                )
                val postCategories = detailedPage
                  .select("div.postmetatop div.categs a[rel]")
                  .asScala
                  .toList
                  .map(_.text())

                val postTags = detailedPage
                  .select("div.postmetabottom a[rel~=tag]")
                  .asScala
                  .toList
                  .map(_.text())

                val blogPost = BlogPost(
                  postTitle,
                  parsedPostDate,
                  uri,
                  albumImage,
                  postCategories,
                  postTags
                )

                println(s"""
                     |Title: '$postTitle'
                     |Date: '$parsedPostDate'
                     |PermanentUri: '$uri'
                     |AlbumImage: '$albumImage'
                     |Categories: '${postCategories.mkString("', '")}'
                     |Tags: '${postTags.mkString("', '")}'
               """.stripMargin)

                blogPost
              })
          })

      })
      .compile
      .drain
      .map(_ => ExitCode.Success)
  }
}
