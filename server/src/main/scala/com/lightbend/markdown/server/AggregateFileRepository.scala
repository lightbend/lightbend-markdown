/*
 * Copyright (C) 2009-2016 Lightbend Inc. <https://www.lightbend.com>
 */
package com.lightbend.markdown.server

import java.io.{File, InputStream}

import com.lightbend.markdown.DocPath
import play.doc.FileHandle
import play.doc.FileRepository

/**
  * A file repository that aggregates multiple file repositories
  *
  * @param repos The repositories to aggregate
  */
class AggregateFileRepository(repos: Seq[FileRepository]) extends FileRepository {

  private def fromFirstRepo[A](load: FileRepository => Option[A]) = repos.collectFirst(Function.unlift(load))

  def loadFile[A](path: String)(loader: (InputStream) => A) = fromFirstRepo(_.loadFile(path)(loader))

  def handleFile[A](path: String)(handler: (FileHandle) => A) = fromFirstRepo(_.handleFile(path)(handler))

  def findFileWithName(name: String) = fromFirstRepo(_.findFileWithName(name))
}

class PrefixedRepository(prefix: String, repo: FileRepository) extends FileRepository {

  private def withPrefixStripped[T](path: String)(block: String => Option[T]): Option[T] = {
    if (path.startsWith(prefix)) {
      block(path.stripPrefix(prefix))
    } else None
  }

  override def loadFile[A](path: String)(loader: (InputStream) => A): Option[A] =
    withPrefixStripped(path)(repo.loadFile[A](_)(loader))

  override def handleFile[A](path: String)(handler: (FileHandle) => A): Option[A] =
    withPrefixStripped(path)(repo.handleFile[A](_)(handler))

  override def findFileWithName(name: String): Option[String] =
    repo.findFileWithName(name).map(prefix + _)
}

object SourceFinder {
  def findPathFor(rootDir: File, docPaths: Seq[DocPath], path: String): Option[String] = {
    docPaths.collect {
      case DocPath(file, prefix) if prefix == "." => new File(file, path)
      case DocPath(file, prefix) if path.startsWith(prefix) => new File(file, path.stripPrefix(prefix))
    }.collectFirst {
      case file if file.exists() => file.getCanonicalPath.stripPrefix(rootDir.getCanonicalPath).stripPrefix(File.separator)
    }
  }
}