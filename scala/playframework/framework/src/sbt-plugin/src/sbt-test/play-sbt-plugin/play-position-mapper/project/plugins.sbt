//
// Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
//
addSbtPlugin("com.typesafe.play" % "sbt-plugin" % sys.props("project.version"))

unmanagedSourceDirectories in Compile += baseDirectory.value.getParentFile / "project" / s"scala-sbt-${sbtBinaryVersion.value}"