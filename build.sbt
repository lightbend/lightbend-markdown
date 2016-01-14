lazy val root = (project in file("."))
  .settings(common: _*)
  .settings(
    name := "typesafe-markdown",
    publish := {},
    publishTo := None
  ).aggregate(server, plugin)

lazy val playDoc = "com.typesafe.play" %% "play-doc" % "1.3.0"

lazy val server = (project in file("server"))
  .enablePlugins(SbtWeb, SbtTwirl)
  .settings(common: _*)
  .settings(
    name := "typesafe-markdown-server",
    scalaVersion := "2.11.7",
    libraryDependencies ++= Seq(
      "com.typesafe.play" %% "play-netty-server" % "2.5.0-M1",
      playDoc,
      "com.github.scopt" %% "scopt" % "3.3.0",
      "org.webjars" % "jquery" % "1.9.0",
      "org.webjars" % "prettify" % "4-Mar-2013",
      "org.webjars" % "webjars-locator-core" % "0.30"
    ),
    pipelineStages in Assets := Seq(uglify),
    LessKeys.compress := true
  )

lazy val plugin = (project in file("plugin"))
  .settings(common: _*)
  .settings(
    name := "sbt-typesafe-markdown",
    libraryDependencies ++= Seq(
      "com.github.seratch" %% "awscala" % "0.5.5",
      "org.webjars" % "webjars-locator-core" % "0.30",
      playDoc
    ),
    sbtPlugin := true,
    resourceGenerators in Compile <+= generateVersionFile
  )


def common: Seq[Setting[_]] = Seq(
  organization := "com.typesafe.markdown",

  licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.html")),
  bintrayOrganization := Some("typesafe"),
  bintrayRepository := "ivy-releases",
  bintrayPackage := "typesafe-markdown",
  bintrayReleaseOnPublish := false,
  publishMavenStyle := false
)

def generateVersionFile = Def.task {
  val version = (Keys.version in server).value
  val file = (resourceManaged in Compile).value / "typesafe-markdown.version.properties"
  val content = s"typesafe-markdown.version=$version"
  IO.write(file, content)
  Seq(file)
}

