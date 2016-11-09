lazy val root = (project in file("."))
  .settings(common: _*)
  .settings(
    name := "lightbend-markdown",
    publish := {},
    PgpKeys.publishSigned := {},
    publishTo := Some(Resolver.file("dummy", target.value / "dummy"))
  ).aggregate(server, plugin, theme)

lazy val playDoc = "com.typesafe.play" %% "play-doc" % "1.6.0"

lazy val server = (project in file("server"))
  .settings(common: _*)
  .enablePlugins(SbtTwirl)
  .settings(
    name := "lightbend-markdown-server",
    scalaVersion := "2.11.7",
    libraryDependencies ++= Seq(
      "com.typesafe.play" %% "play-netty-server" % "2.5.9",
      "com.typesafe.play" %% "play-logback" % "2.5.9",
      playDoc,
      "com.github.scopt" %% "scopt" % "3.3.0",
      "org.webjars" % "webjars-locator-core" % "0.30"
    )
  )

lazy val plugin = (project in file("plugin"))
  .settings(common: _*)
  .settings(
    name := "sbt-lightbend-markdown",
    libraryDependencies ++= Seq(
      "org.webjars" % "webjars-locator-core" % "0.30",
      playDoc,
      "com.typesafe.play" %% "play-json" % "2.4.8"
    ),
    sbtPlugin := true,
    resourceGenerators in Compile <+= generateVersionFile
  )

lazy val theme = (project in file("theme"))
  .enablePlugins(SbtWeb, SbtTwirl)
  .settings(common: _*)
  .settings(
    name := "lightbend-markdown-builtin-theme",
    scalaVersion := "2.11.7",
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
  releasePublishArtifactsAction := PgpKeys.publishSigned.value,

  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runTest,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    publishArtifacts,
    releaseStepTask(bintrayRelease),
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

