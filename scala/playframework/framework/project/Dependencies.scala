/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
import sbt._
import Keys._

import buildinfo.BuildInfo

object Dependencies {

  val akkaVersion = "2.5.16"
  val akkaHttpVersion = "10.0.14"
  val playJsonVersion = "2.6.10"

  val logback = "ch.qos.logback" % "logback-classic" % "1.2.3"

  val specsVersion = "3.8.9"
  val specsBuild = Seq(
    "specs2-core",
    "specs2-junit",
    "specs2-mock"
  ).map("org.specs2" %% _ % specsVersion) ++ Seq(logback)

  val specsMatcherExtra = "org.specs2" %% "specs2-matcher-extra" % specsVersion

  val specsSbt = specsBuild

  val jacksonVersion = "2.8.11"
  val jacksonDatabindVersion = "2.8.11.1"
  val jacksons = Seq(
    "com.fasterxml.jackson.core" % "jackson-core",
    "com.fasterxml.jackson.core" % "jackson-annotations",
    "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8",
    "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310"
  ).map(_ % jacksonVersion) ++
    Seq(
      "com.fasterxml.jackson.core" % "jackson-databind" % jacksonDatabindVersion
    )

  val playJson = "com.typesafe.play" %% "play-json" % playJsonVersion

  val slf4jVersion = "1.7.25"
  val slf4j = Seq("slf4j-api", "jul-to-slf4j", "jcl-over-slf4j").map("org.slf4j" % _ % slf4jVersion)
  val slf4jSimple = "org.slf4j" % "slf4j-simple" % slf4jVersion

  val guava = "com.google.guava" % "guava" % "22.0"
  val findBugs = "com.google.code.findbugs" % "jsr305" % "3.0.2" // Needed by guava
  val mockitoAll = "org.mockito" % "mockito-all" % "1.10.19"

  val h2database = "com.h2database" % "h2" % "1.4.196"
  val derbyDatabase = "org.apache.derby" % "derby" % "10.13.1.1"

  val acolyteVersion = "1.0.46"
  val acolyte = "org.eu.acolyte" % "jdbc-driver" % acolyteVersion

  val jettyAlpnAgent = "org.mortbay.jetty.alpn" % "jetty-alpn-agent" % "2.0.7"

  val jjwt = "io.jsonwebtoken" % "jjwt" % "0.7.0"
  // currently jjwt needs the JAXB Api package in JDK 9+
  // since it actually uses javax/xml/bind/DatatypeConverter
  // See: https://github.com/jwtk/jjwt/issues/317
  val jaxbApi = "javax.xml.bind" % "jaxb-api" % "2.3.0"

  val jdbcDeps = Seq(
    "com.jolbox" % "bonecp" % "0.8.0.RELEASE",
    "com.zaxxer" % "HikariCP" % "2.7.9",
    "com.googlecode.usc" % "jdbcdslog" % "1.0.6.2",
    h2database % Test,
    acolyte % Test,
    "tyrex" % "tyrex" % "1.0.1") ++ specsBuild.map(_ % Test)

  val jpaDeps = Seq(
    "org.hibernate.javax.persistence" % "hibernate-jpa-2.1-api" % "1.0.0.Final",
    "org.hibernate" % "hibernate-entitymanager" % "5.2.11.Final" % "test"
  )

  val scalaJava8Compat = "org.scala-lang.modules" %% "scala-java8-compat" % "0.8.0"
  def scalaParserCombinators(scalaVersion: String) = CrossVersion.partialVersion(scalaVersion) match {
    case Some((2, major)) if major >= 11 => Seq("org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.6")
    case _ => Nil
  }

  val springFrameworkVersion = "4.3.11.RELEASE"

  val javaDeps = Seq(
    scalaJava8Compat,

    ("org.reflections" % "reflections" % "0.9.11")
      .exclude("com.google.code.findbugs", "annotations")
      .classifier(""),

    // Used by the Java routing DSL
    "net.jodah" % "typetools" % "0.5.0"
  ) ++ specsBuild.map(_ % Test)

  val joda = Seq(
    "joda-time" % "joda-time" % "2.9.9",
    "org.joda" % "joda-convert" % "1.8.3"
  )

  val javaFormsDeps = Seq(

    "org.hibernate" % "hibernate-validator" % "5.4.1.Final",

    ("org.springframework" % "spring-context" % springFrameworkVersion)
      .exclude("org.springframework", "spring-aop")
      .exclude("org.springframework", "spring-beans")
      .exclude("org.springframework", "spring-core")
      .exclude("org.springframework", "spring-expression")
      .exclude("org.springframework", "spring-asm"),

    ("org.springframework" % "spring-core" % springFrameworkVersion)
      .exclude("org.springframework", "spring-asm")
      .exclude("commons-logging", "commons-logging"),

    ("org.springframework" % "spring-beans" % springFrameworkVersion)
      .exclude("org.springframework", "spring-core")

  ) ++ specsBuild.map(_ % Test)

  val junitInterface = "com.novocode" % "junit-interface" % "0.11"
  val junit = "junit" % "junit" % "4.12"

  val javaTestDeps = Seq(
    junit,
    junitInterface,
    "org.easytesting" % "fest-assert" % "1.4",
    mockitoAll,
    logback
  ).map(_ % Test)

  val guiceVersion = "4.1.0"
  val guiceDeps = Seq(
    "com.google.inject" % "guice" % guiceVersion,
    "com.google.inject.extensions" % "guice-assistedinject" % guiceVersion
  )

  def runtime(scalaVersion: String) =
    slf4j ++
    Seq("akka-actor", "akka-slf4j").map("com.typesafe.akka" %% _ % akkaVersion) ++
    Seq("akka-testkit").map("com.typesafe.akka" %% _ % akkaVersion % Test) ++
    jacksons ++
    Seq(
      "commons-codec" % "commons-codec" % "1.10",

      playJson,

      guava,
      jjwt,
      jaxbApi,

      "org.apache.commons" % "commons-lang3" % "3.6",

      "javax.transaction" % "jta" % "1.1",
      "javax.inject" % "javax.inject" % "1",

      "org.scala-lang" % "scala-reflect" % scalaVersion,
      scalaJava8Compat
    ) ++ scalaParserCombinators(scalaVersion) ++
    specsBuild.map(_ % Test) ++
    javaTestDeps

  val nettyVersion = "4.1.29.Final"

  val netty = Seq(
    "com.typesafe.netty" % "netty-reactive-streams-http" % "2.0.0",
    "io.netty" % "netty-transport-native-epoll" % nettyVersion classifier "linux-x86_64"
  ) ++ specsBuild.map(_ % Test)

  val nettyUtilsDependencies = slf4j

  val jimfs = "com.google.jimfs" % "jimfs" % "1.1"

  val okHttp = "com.squareup.okhttp3" % "okhttp" % "3.9.0"

  def routesCompilerDependencies(scalaVersion: String) = Seq(
    "commons-io" % "commons-io" % "2.5",
    specsMatcherExtra % Test
  ) ++ specsBuild.map(_ % Test) ++ scalaParserCombinators(scalaVersion)

  private def sbtPluginDep(moduleId: ModuleID, sbtVersion: String, scalaVersion: String) = {
    Defaults.sbtPluginExtra(moduleId, CrossVersion.binarySbtVersion(sbtVersion), CrossVersion.binaryScalaVersion(scalaVersion))
  }

  def playFileWatch(sbtVersion: String): ModuleID = CrossVersion.binarySbtVersion(sbtVersion) match {
    case "1.0" => "com.lightbend.play" %% "play-file-watch" % "1.1.7"
    case "0.13" => "com.lightbend.play" %% "play-file-watch" % "1.0.0"
  }

  def runSupportDependencies(sbtVersion: String): Seq[ModuleID] = Seq(playFileWatch(sbtVersion)) ++ specsBuild.map(_ % Test)

  // use partial version so that non-standard scala binary versions from dbuild also work
  def sbtIO(sbtVersion: String, scalaVersion: String): ModuleID = CrossVersion.partialVersion(scalaVersion) match {
    case Some((2, major)) if major >= 11 => "org.scala-sbt" %% "io" % "0.13.16" % "provided"
    case _ => "org.scala-sbt" % "io" % sbtVersion % "provided"
  }

  val typesafeConfig = "com.typesafe" % "config" % "1.3.3"

  def sbtDependencies(sbtVersion: String, scalaVersion: String) = {
    def sbtDep(moduleId: ModuleID) = sbtPluginDep(moduleId, sbtVersion, scalaVersion)

    Seq(
      "org.scala-lang" % "scala-reflect" % scalaVersion % "provided",
      typesafeConfig,
      slf4jSimple,
      playFileWatch(sbtVersion),
      sbtDep("com.typesafe.sbt" % "sbt-twirl" % BuildInfo.sbtTwirlVersion),
      sbtDep("com.typesafe.sbt" % "sbt-native-packager" % BuildInfo.sbtNativePackagerVersion),
      sbtDep("com.lightbend.sbt" % "sbt-javaagent" % BuildInfo.sbtJavaAgentVersion),
      sbtDep("com.typesafe.sbt" % "sbt-web" % "1.4.3"),
      sbtDep("com.typesafe.sbt" % "sbt-js-engine" % "1.2.2")
    ) ++ specsBuild.map(_ % Test)
  }

  val playdocWebjarDependencies = Seq(
    "org.webjars" % "jquery"   % "3.2.1"    % "webjars",
    "org.webjars" % "prettify" % "4-Mar-2013-1" % "webjars"
  )

  val playDocVersion = "1.8.1"
  val playDocsDependencies = Seq(
    "com.typesafe.play" %% "play-doc" % playDocVersion
  ) ++ playdocWebjarDependencies

  val streamsDependencies = Seq(
    "org.reactivestreams" % "reactive-streams" % "1.0.2",
    "com.typesafe.akka" %% "akka-stream" % akkaVersion,
    scalaJava8Compat
  ) ++ specsBuild.map(_ % Test) ++ javaTestDeps



  val scalacheckDependencies = Seq(
    "org.specs2"     %% "specs2-scalacheck" % specsVersion % Test,
    "org.scalacheck" %% "scalacheck"        % "1.13.5"     % Test
  )

  val playServerDependencies = Seq(
    guava % Test
  ) ++ specsBuild.map(_ % Test)

  val seleniumVersion = "3.5.3"
  val testDependencies = Seq(junit) ++ specsBuild.map(_ % Test) ++ Seq(
    junitInterface,
    guava,
    findBugs,
    "org.fluentlenium" % "fluentlenium-core" % "3.3.0" exclude("org.jboss.netty", "netty"),
    // htmlunit-driver uses an open range to selenium dependencies. This is slightly
    // slowing down the build. So the open range deps were removed and we can re-add
    // them using a specific version. Using an open range is also not good for the
    // local cache.
    "org.seleniumhq.selenium" % "htmlunit-driver" % "2.27" excludeAll(
      ExclusionRule("org.seleniumhq.selenium", "selenium-api"),
      ExclusionRule("org.seleniumhq.selenium", "selenium-support")
    ),
    "org.seleniumhq.selenium" % "selenium-api" % seleniumVersion,
    "org.seleniumhq.selenium" % "selenium-support" % seleniumVersion,
    "org.seleniumhq.selenium" % "selenium-firefox-driver" % seleniumVersion
  ) ++ guiceDeps

  val playCacheDeps = specsBuild.map(_ % Test)

  val jcacheApi = Seq(
    "javax.cache" % "cache-api" % "1.0.0"
  )

  // Must use a version of ehcache that supports jcache 1.0.0
  val ehcacheVersion = "2.10.4"
  val playEhcacheDeps = Seq(
    "net.sf.ehcache" % "ehcache" % ehcacheVersion,
    "org.ehcache" % "jcache" % "1.0.1"
  ) ++ jcacheApi

  val caffeineVersion = "2.5.6"
  val playWsStandaloneVersion = "1.1.10"
  val playWsDeps = Seq(
    "com.typesafe.play" %% "play-ws-standalone" % playWsStandaloneVersion,
    "com.typesafe.play" %% "play-ws-standalone-xml" % playWsStandaloneVersion,
    "com.typesafe.play" %% "play-ws-standalone-json" % playWsStandaloneVersion
  ) ++
    (specsBuild :+ specsMatcherExtra).map(_ % Test) :+
    mockitoAll % Test

  val playAhcWsDeps = Seq(
    "com.typesafe.play" %% "play-ahc-ws-standalone" % playWsStandaloneVersion,
    "com.typesafe.play" % "shaded-asynchttpclient" % playWsStandaloneVersion,
    "com.typesafe.play" % "shaded-oauth" % playWsStandaloneVersion,
    "com.github.ben-manes.caffeine" % "jcache" % caffeineVersion % Test
  ) ++ jcacheApi

  val playDocsSbtPluginDependencies = Seq(
    "com.typesafe.play" %% "play-doc" % playDocVersion
  )

}

/*
 * How to use this:
 *    $ sbt -J-XX:+UnlockCommercialFeatures -J-XX:+FlightRecorder -Dakka-http.sources=$HOME/code/akka-http '; project Play-Akka-Http-Server; test:run'
 *
 * Make sure Akka-HTTP has 2.12 as the FIRST version (or that scalaVersion := "2.12.6", otherwise it won't find the artifact
 *    crossScalaVersions := Seq("2.12.6", "2.11.12"),
 */
 object AkkaDependency {
  // Needs to be a URI like git://github.com/akka/akka.git#master or file:///xyz/akka
  val akkaSourceDependencyUri = sys.props.getOrElse("akka-http.sources", "")
  val shouldUseSourceDependency = akkaSourceDependencyUri != ""
  val akkaRepository = uri(akkaSourceDependencyUri)

  implicit class RichProject(project: Project) {
    /** Adds either a source or a binary dependency, depending on whether the above settings are set */
    def addAkkaModuleDependency(module: String, config: String = ""): Project =
      if (shouldUseSourceDependency) {
        val moduleRef = ProjectRef(akkaRepository, module)
        val withConfig: ClasspathDependency =
          if (config == "") {
            println("  Using Akka-HTTP directly from sources, from: " + akkaSourceDependencyUri)
            moduleRef
          } else moduleRef % config

        project.dependsOn(withConfig)
      } else {
        val dep = "com.typesafe.akka" %% module % Dependencies.akkaHttpVersion
        val withConfig =
          if (config == "") dep
          else dep % config
        project.settings(libraryDependencies += withConfig)
      }
  }
}
