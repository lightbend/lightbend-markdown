package com.lightbend.markdown

import java.io.File

import play.api.libs.json._

object FileFormat {
  implicit val fileFormat: Format[File] = Format(
    implicitly[Reads[String]].map(new File(_)),
    Writes(file => JsString(file.getAbsolutePath))
  )
}

import FileFormat._

case class GenerateDocumentationConfig(
  outputDir: File,
  generateIndex: Boolean,
  config: DocumentationConfiguration
)

object GenerateDocumentationConfig {
  implicit val format: Format[GenerateDocumentationConfig] = Json.format[GenerateDocumentationConfig]
}

case class DocumentationServerConfig(
  projectPath: File = new File("."),
  port: Int = 9000,
  config: DocumentationConfiguration
)

object DocumentationServerConfig {
  implicit val format: Format[DocumentationServerConfig] = Json.format[DocumentationServerConfig]
}

case class DocumentationConfiguration(
  documentationRoot: File = new File("."),
  projectName: Option[String] = None,
  theme: Option[String] = None,
  sourceUrl: Option[String] = None,
  documentation: Seq[Documentation] = Nil
)

object DocumentationConfiguration {
  implicit val format: Format[DocumentationConfiguration] = Json.format[DocumentationConfiguration]
}

case class Documentation(
  name: String,
  docsPaths: Seq[DocPath] = Nil,
  homePage: String = "Home.html",
  homePageTitle: String = "Home",
  apiDocs: Map[String, String] = Map(
    "api/java/index.html" -> "Java",
    "api/scala/index.html" -> "Scala"
  )
)

case class DocPath(file: File, path: String)

object DocPath {
  implicit val format: Format[DocPath] = Json.format[DocPath]
}

object Documentation {
  implicit val format: Format[Documentation] = Json.format[Documentation]
}
