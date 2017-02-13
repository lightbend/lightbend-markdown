/**
  * Copyright (C) 2016 Lightbend Inc. <http://www.ligtbend.com>
  */
package com.lightbend.docs

import play.api.libs.json._
import play.api.libs.functional.syntax._
import java.net.URI

// This JSON format for representing the index of some documentation was developed by jroper and rkuhn specifically
// to meet the requirements of both Play and Akka docs, as an interchangeable documentation index format for consumption
// by a common documentation server.

/** The index. Consists of a map of languages to tables of contents. */
case class Index(languages: Map[String, TOC])
object Index {
  implicit val jsonFormat: Format[Index] = Json.format
  val empty = Index(Map.empty)
}

/**
  * A set of resources to be added to the head section of pages rendered.
  *
  * @param stylesheets Stylesheets that need to be added.
  * @param scripts Scripts that need to be added.
  */
case class Resources(stylesheets: Seq[String], scripts: Seq[String]) {
  def ++(other: Resources) = Resources(stylesheets ++ other.stylesheets, scripts ++ other.scripts)
  def map(f: String => String) = Resources(stylesheets.map(f), scripts.map(f))
}

object Resources {
  val empty = Resources(Vector.empty, Vector.empty)
  implicit val jsonReads: Reads[Resources] = (
    (__ \ "stylesheets").readNullable[Seq[String]].map(_ getOrElse Nil) and
      (__ \ "scripts").readNullable[Seq[String]].map(_ getOrElse Nil)
    )(Resources.apply _)
  implicit val jsonWrites: Writes[Resources] = (
    (__ \ "stylesheets").writeNullable[Seq[String]] and
      (__ \ "scripts").writeNullable[Seq[String]]
    )(unlift(Resources.unapply) andThen { case (css, scr) => (if (css.isEmpty) None else Some(css), if (scr.isEmpty) None else Some(scr)) })
}

case class Context(title: String, parent: Option[Context], sourceUrl: Option[String], nostyle: Boolean,
                   resources: Resources, children: Seq[(String, String)], prefix: String, nextUrls: Option[Seq[String]])

/**
  * The table of contents.  A recursive structure.
  *
  * @param title The title. This will appear as the title of this item in the table of contents.
  * @param url The URL associated with this element of the TOC. There is not necessarily a URL.
  * @param sourceUrl The URL of the source code for this documentation
  * @param nostyle Whether this page should be styled or not. Some pages in the documentation should not be styled.
  * @param resources The resources, eg stylesheets/scripts, that need to be added to the head section of pages rendered.
  * @param children The children of this TOC section.
  * @param nextUrls The URLs of the next pages, if overridden from the default navigation.
  */
case class TOC(title: String, url: Option[String], sourceUrl: Option[String], nostyle: Boolean, resources: Option[Resources], children: Seq[TOC], nextUrls: Option[Seq[String]]) {
  lazy val mappings: Map[String, Context] = mkMappings(None, Resources.empty)

  private def mkMappings(parent: Option[Context], rsrc: Resources): Map[String, Context] = {
    val myresources = if (resources.isEmpty) rsrc else rsrc ++ resources.get
    val childLinks = children map (toc => toc.title -> findLink(toc))
    val (myMap, me) = url match {
      case None =>
        Map.empty[String, Context] -> Context(title, parent, sourceUrl, nostyle, myresources, childLinks, "", nextUrls)
      case Some(u) =>
        val depth = u.count(_ == '/')
        val prefix = "../" * depth
        val mappedResources = myresources.map(addPrefix(prefix))
        val me = Context(title, parent, sourceUrl, nostyle, mappedResources, childLinks, prefix, nextUrls)
        Map(u -> me) -> me
    }
    children.foldLeft(myMap)((map, toc) => map ++ toc.mkMappings(Some(me), myresources))
  }

  private def findLink(toc: TOC): String =
    toc.url match {
      case Some(u) => u
      case None    =>
        require(children.size > 0, s"cannot find link for section $toc")
        findLink(toc.children(0))
    }

  private def addPrefix(p: String)(url: String): String = {
    val uri = URI.create(url)
    if (uri.isAbsolute() || url.startsWith("/")) url else p + url
  }

  override def toString = s"TOC($title,$url,$resources,${children.size} children)"
}

object TOC {
  implicit lazy val jsonReads: Reads[TOC] = (
    (__ \ "title").read[String] and
      (__ \ "url").readNullable[String] and
      (__ \ "sourceUrl").readNullable[String] and
      (__ \ "nostyle").readNullable[Boolean].map(_ getOrElse false) and
      (__ \ "resources").readNullable[Resources] and
      (__ \ "children").lazyReadNullable(Reads.seq[TOC](jsonReads)).map(_.getOrElse(Nil)) and
      (__ \ "nextUrls").readNullable[Seq[String]]
    )(TOC.apply _)
  implicit lazy val jsonWrites: Writes[TOC] = (
    (__ \ "title").write[String] and
      (__ \ "url").writeNullable[String] and
      (__ \ "sourceUrl").writeNullable[String] and
      (__ \ "nostyle").writeNullable[Boolean] and
      (__ \ "resources").writeNullable[Resources] and
      (__ \ "children").lazyWriteNullable(Writes.seq[TOC](jsonWrites)) and
      (__ \ "nextUrls").writeNullable[Seq[String]]
    )(unlift(TOC.unapply) andThen {
    case (title, url, sourceUrl, nostyle, res, children, nextUrls) =>
      (title, url, sourceUrl, if (nostyle) Some(true) else None, res, if (children.isEmpty) None else Some(children), nextUrls) })
}