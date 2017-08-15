/*
 * Copyright (C) 2009-2016 Lightbend Inc. <https://www.lightbend.com>
 */
package com.lightbend.markdown.server

import java.io.File
import java.nio.file.Files

import akka.stream.scaladsl.StreamConverters
import com.lightbend.markdown.{DocPath, Documentation, DocumentationServerConfig}
import com.lightbend.markdown.theme.MarkdownTheme
import org.webjars.WebJarAssetLocator
import play.api.http._
import play.api.libs.json.Json
import play.api.mvc.Results._
import play.api.mvc._
import play.core._
import play.core.server._
import play.doc._
import play.api.routing.sird._
import play.twirl.api.Html

/**
  * Used to start the documentation server.
  */
object DocumentationServer extends App {

  val jsonPath = new File(args.head)
  val configJson = Json.parse(Files.readAllBytes(jsonPath.toPath))
  val serverConfig = configJson.as[DocumentationServerConfig]

  import serverConfig._
  import config._

  val markdownTheme = MarkdownTheme.load(this.getClass.getClassLoader, theme)

  val nameExists = documentation.map(_.name).toSet

  case class Docs(
    documentation: Documentation,
    repo: FileRepository
  )

  val docs = documentation.map { d =>
    d.name -> Docs(d, new AggregateFileRepository(d.docsPaths.map {
      case DocPath(path, "." | "") => new FilesystemRepository(path)
      case DocPath(path, prefix) => new PrefixedRepository(prefix + "/", new FilesystemRepository(path))
    }))
  }.toMap

  def playDoc(docs: Docs) = {
    new PlayDoc(docs.repo, docs.repo, "resources", PlayVersion.current, PageIndex.parseFrom(docs.repo, docs.documentation.homePageTitle, None),
      markdownTheme.playDocTemplates, Some("html"))
  }

  val webjarLocator = new WebJarAssetLocator()

  val akkaHttpServerConfig = ServerConfig(rootDir = projectPath, port = Some(port))
  val server = AkkaHttpServer.fromRouterWithComponents(akkaHttpServerConfig) { components =>
    implicit val mimeTypes = components.fileMimeTypes
    import components.{defaultActionBuilder => Action}
    {
      case GET(p"/") => Action {
        Ok(markdownTheme.renderPage(projectName, None, "/", Html(
          documentation.map { d =>
            s"""<li><a href="/${d.name}/${d.homePage}">${d.homePageTitle}</a></li>"""
          }.mkString("<p>Select your language:</p><ul>", "", "</ul>")
        ), None, None, Nil, None))
      }

      case GET(p"/$name/$pageName.html") if nameExists(name) => Action {
        (for {
          doc <- docs.get(name)
          page <- playDoc(doc).renderPage(pageName)
        } yield {

          val sourcePath = for {
            url <- sourceUrl
            path <- SourceFinder.findPathFor(documentationRoot, doc.documentation.docsPaths, page.path)
          } yield s"$url$path"

          Ok(markdownTheme.renderPage(projectName, None, doc.documentation.homePage,
            Html(page.html), page.sidebarHtml.map(Html.apply), page.breadcrumbsHtml.map(Html.apply), doc.documentation.apiDocs.toSeq, sourcePath))
        }).getOrElse {
          NotFound(markdownTheme.renderPage(projectName, None, "/", Html("Page " + pageName + " not found."),
            None, None, Nil, None))
        }
      }

      case GET(p"/$name/resources/$path*") if nameExists(name) => Action {
        sendFileInline(name, path).getOrElse(NotFound("Resource not found [" + path + "]"))
      }

      case GET(p"/webjars/$webjar/$path*") => Action {
        val resource = Option(webjarLocator.getFullPathExact(webjar, path))
        resource.fold[Result](NotFound)(Ok.sendResource(_))
      }

      case GET(p"/$name/api/$path*") if nameExists(name) => Action {
        sendFileInline(name, "api/" + path).getOrElse(NotFound("API doc resource not found [" + path + "]"))
      }

      case _ => Action {
        Redirect("/")
      }
    }
  }

  Runtime.getRuntime.addShutdownHook(new Thread {
    override def run() = server.stop()
  })

  private def sendFileInline(docsName: String, path: String)(implicit mt: FileMimeTypes): Option[Result] = {
    docs.get(docsName).flatMap { doc =>
      doc.repo.handleFile(path) { handle =>
        Ok.sendEntity(
          HttpEntity.Streamed(
            StreamConverters.fromInputStream(() => handle.is),
            Some(handle.size),
            mt.forFileName(handle.name).orElse(Some(ContentTypes.BINARY))
          )
        )
      }
    }
  }
}


