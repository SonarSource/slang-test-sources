/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
import sbt._

object Common {
  def allFiles(file: File): Seq[File] = file.allPaths.filter(_.isFile).get
}