package com.lightbend.markdown.theme.builtin

import com.lightbend.markdown.theme.MarkdownTheme
import play.twirl.api.Html

object BuiltinMarkdownTheme extends MarkdownTheme {
  override def renderPage(projectName: Option[String], title: Option[String], home: String, content: Html,
                 sidebar: Option[Html], breadcrumbs: Option[Html], apiDocs: Seq[(String, String)], sourceUrl: Option[String]): Html =
    html.documentation(projectName, title, home, content, sidebar, apiDocs, sourceUrl)
}
