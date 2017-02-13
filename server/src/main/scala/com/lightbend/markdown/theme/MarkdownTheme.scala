/*
 * Copyright (C) 2009-2016 Lightbend Inc. <https://www.lightbend.com>
 */
package com.lightbend.markdown.theme

import play.doc.{TocTree, PlayDocTemplates, Toc}
import play.twirl.api.Html

/**
  * A theme for markdown documentation.
  */
trait MarkdownTheme {

  /** Render a page of documentation */
  def renderPage(projectName: Option[String], title: Option[String], home: String, content: Html,
                 sidebar: Option[Html], breadcrumbs: Option[Html], apiDocs: Seq[(String, String)], sourceUrl: Option[String]): Html =
    html.documentation(projectName, title, home, content, sidebar, apiDocs, sourceUrl)

  /** Render the sidebar */
  def renderSidebar(hierarchy: List[play.doc.Toc]): Html =
    html.sidebar(hierarchy)

  /** Render the next link */
  def renderNextLink(toc: play.doc.TocTree): Html =
    html.nextLink(toc)

  /** Render the next links */
  def renderNextLinks(toc: List[play.doc.TocTree]): Html =
    Html(toc.foldLeft("")((html, toc) => html + renderNextLink(toc).body))

  /** Render the table of contents */
  def renderToc(toc: Toc): Html =
    html.toc(toc)

  def renderBreadcrumbs(hierarchy: List[play.doc.Toc]): Html =
    html.breadcrumbs(hierarchy)

  /** The markdown theme as a Play doc templates theme */
  final val playDocTemplates: PlayDocTemplates = new PlayDocTemplates {
    override def nextLink(toc: TocTree): String = renderNextLink(toc).body
    override def nextLinks(toc: List[TocTree]): String = renderNextLinks(toc).body
    override def sidebar(hierarchy: List[Toc]): String = renderSidebar(hierarchy).body
    override def toc(toc: Toc): String = renderToc(toc).body
    override def breadcrumbs(hierarchy: List[Toc]): String = renderBreadcrumbs(hierarchy).body
  }
}

object MarkdownTheme {
  def load(classLoader: ClassLoader, objectName: Option[String]): MarkdownTheme = {
    objectName match {
      case Some("bare") => BareTheme
      case Some(name) => classLoader.loadClass(name + "$").getField("MODULE$").get(null).asInstanceOf[MarkdownTheme]
      case None => DefaultMarkdownTheme
    }
  }
}

object DefaultMarkdownTheme extends MarkdownTheme

trait BareTheme extends MarkdownTheme {
  override def renderPage(projectName: Option[String], title: Option[String], home: String, content: Html,
                          sidebar: Option[Html], breadcrumbs: Option[Html], apiDocs: Seq[(String, String)], sourceUrl: Option[String]): Html = content
  override def renderSidebar(hierarchy: List[Toc]): Html = Html("")
  override def renderNextLink(toc: TocTree): Html = Html("")
  override def renderNextLinks(toc: List[TocTree]): Html = Html("")
  override def renderBreadcrumbs(hierarchy: List[Toc]): Html = Html("")
}

object BareTheme extends BareTheme