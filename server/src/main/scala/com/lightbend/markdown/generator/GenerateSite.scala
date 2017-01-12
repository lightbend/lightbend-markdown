/*
 * Copyright (C) 2009-2016 Lightbend Inc. <https://www.lightbend.com>
 */
package com.lightbend.markdown.generator

import java.io.File
import java.nio.file.Files

import com.lightbend.markdown.server.{AggregateFileRepository, PrefixedRepository, SourceFinder}
import com.lightbend.markdown.theme.MarkdownTheme
import com.lightbend.docs.TOC
import com.lightbend.markdown.{DocPath, Documentation, GenerateDocumentationConfig}
import play.api.libs.json.Json
import play.core.PlayVersion
import play.doc._
import play.twirl.api.{Content, Html}

object GenerateSite extends App {

  val jsonPath = new File(args.head)
  val configJson = Json.parse(Files.readAllBytes(jsonPath.toPath))
  val generateConfig = configJson.as[GenerateDocumentationConfig]

  import generateConfig._
  import config._

  val markdownTheme = MarkdownTheme.load(this.getClass.getClassLoader, theme)

  case class Docs(
    documentation: Documentation,
    repo: FileRepository
  )

  documentation.foreach { docs =>
    val repo = new AggregateFileRepository(docs.docsPaths.map {
      case DocPath(path, "." | "") => new FilesystemRepository(path)
      case DocPath(path, prefix) => new PrefixedRepository(prefix + "/", new FilesystemRepository(path))
    })

    val playDoc = new PlayDoc(repo, repo, "resources", PlayVersion.current, PageIndex.parseFrom(repo, docs.homePageTitle, None),
        markdownTheme.playDocTemplates, Some("html"))

    val index = playDoc.pageIndex.getOrElse(throw new IllegalStateException("Generating documentation only works for indexed documentation"))

    val outputPath = new File(outputDir, docs.name)

    def render(toc: Toc): Seq[String] = {
      toc.nodes.flatMap {
        case (_, childToc: Toc) => render(childToc)
        case (_, page: TocPage) =>
          println("Generating " + page.page + "...")
          playDoc.renderPage(page.page).map { rendered =>
            val sourcePath = for {
              url <- sourceUrl
              path <- SourceFinder.findPathFor(documentationRoot, docs.docsPaths, rendered.path)
            } yield s"$url$path"

            val renderedHtml: Content = markdownTheme.renderPage(projectName, None, docs.homePage, Html(rendered.html),
              rendered.sidebarHtml.map(Html.apply), rendered.breadcrumbsHtml.map(Html.apply), docs.apiDocs.toSeq, sourcePath)
            val pageName = page.page + ".html"
            Files.write(new File(outputPath, pageName).toPath, renderedHtml.body.getBytes("utf-8"))
            pageName
          }.toSeq
      }
    }

    // Ensure parent directory exists
    Files.createDirectories(outputPath.toPath)

    // Render all pages
    val allPages = render(index.toc).toSet

    // Delete all pages that don't exist
    outputPath.list().filterNot(allPages).foreach(new File(outputPath, _).delete())

    if (generateIndex) {
      def convertToc(toc: TocTree): TOC = {
        toc match {
          case childToc: Toc =>
            TOC(childToc.title, None, None, nostyle = false, None, childToc.nodes.map(_._2).map(convertToc))
          case page: TocPage =>
            val sourcePath = for {
              url <- sourceUrl
              indexPage <- index.get(page.page)
              path <- SourceFinder.findPathFor(documentationRoot, docs.docsPaths, indexPage.fullPath + ".md")
            } yield {
              s"$url$path"
            }

            TOC(page.title, Some(page.page + ".html"), sourcePath, nostyle = false, None, Nil)
        }
      }

      val tocJson = Json.toJson(convertToc(index.toc))
      val indexFile = new File(outputPath, "index.json")
      Files.write(indexFile.toPath, Json.prettyPrint(tocJson).getBytes("utf-8"))
    }
  }

  println("Done!")
}
