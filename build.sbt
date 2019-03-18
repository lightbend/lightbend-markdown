val runtimeLibrarySettings = Seq(
  crossScalaVersions := Seq("2.12.8", "2.11.12"),
  scalaVersion := crossScalaVersions.value.head
)
val sbtPluginSettings = Seq(
  crossScalaVersions := Seq("2.12.8"),
  scalaVersion := crossScalaVersions.value.head
)

val PlayVersion = "2.7.0"
val PlayJsonVersion = "2.7.2"

def playLibrary(name: String): ModuleID =
  "com.typesafe.play" %% name % PlayVersion

lazy val `lightbend-markdown` = (project in file("."))
  .settings(
    common,
    crossScalaVersions := Nil,
    skip in publish := true,
  )
  .aggregate(server, plugin, theme)

lazy val playDoc = "com.typesafe.play" %% "play-doc" % "2.0.0"

lazy val server = (project in file("server"))
  .enablePlugins(SbtTwirl)
  .settings(
    name := "lightbend-markdown-server",
    common,
    runtimeLibrarySettings,
    libraryDependencies ++= Seq(
      playLibrary("play-akka-http-server"),
      playLibrary("play-logback"),
      playDoc,
      "com.github.scopt" %% "scopt" % "3.6.0",
      "org.webjars" % "webjars-locator-core" % "0.30"
    )
  )

lazy val plugin = (project in file("plugin"))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-lightbend-markdown",
    common,
    sbtPluginSettings,
    libraryDependencies ++= Seq(
      "org.webjars" % "webjars-locator-core" % "0.30",
      playDoc,
      "com.typesafe.play" %% "play-json" % PlayJsonVersion
    ),
    resourceGenerators in Compile += generateVersionFile.taskValue,
  )

lazy val theme = (project in file("theme"))
  .enablePlugins(SbtWeb, SbtTwirl)
  .settings(
    name := "lightbend-markdown-builtin-theme",
    common,
    runtimeLibrarySettings,
    libraryDependencies ++= Seq(
      "org.webjars" % "jquery" % "1.9.0",
      "org.webjars" % "prettify" % "4-Mar-2013"
    ),
    pipelineStages in Assets := Seq(uglify),
    LessKeys.compress := true
  ).dependsOn(server)

import ReleaseTransformations._

def common: Seq[Setting[_]] = Seq(
  organization := "com.lightbend.markdown",

  licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.html")),
  bintrayOrganization := Some("typesafe"),
  bintrayRepository := "ivy-releases",
  bintrayPackage := "lightbend-markdown",
  bintrayReleaseOnPublish := false,
  publishMavenStyle := false,

  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runTest,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    publishArtifacts,
    releaseStepTask(bintrayRelease in thisProjectRef.value),
    setNextVersion,
    commitNextVersion,
    pushChanges
  )
)

def generateVersionFile = Def.task {
  val version = (Keys.version in server).value
  val file = (resourceManaged in Compile).value / "lightbend-markdown.version.properties"
  val content = s"lightbend-markdown.version=$version"
  IO.write(file, content)
  Seq(file)
}

