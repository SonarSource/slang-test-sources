/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package play.sbt

import sbt.Keys._
import sbt._

/**
 * This plugin enables Play Logback
 */
object PlayLogback extends AutoPlugin {
  override def requires = Play

  // add this plugin automatically if Play is added.
  override def trigger = AllRequirements

  override def projectSettings = Seq(
    libraryDependencies ++= {
      Seq(PlayImport.logback)
    }
  )
}
