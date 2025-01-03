package org.http4s.build

import sbt._
import sbt.Keys._
import verizon.build.RigPlugin
import xerial.sbt.Sonatype.autoImport.sonatypeProfileName

object CentralRequirementsPlugin extends AutoPlugin {
  override def trigger = allRequirements

  override def requires = RigPlugin

  override lazy val projectSettings = Seq(
    sonatypeProfileName := "org.http4s",
    developers ++= List(
      Developer("aeons"                , "Bjørn Madsen"          , "bm@aeons.dk"                      , url("https://github.com/aeons")),
      Developer("before"               , "André Rouel"           , ""                                 , url("https://github.com/before")),
      Developer("bfritz"               , "Brad Fritz"            , ""                                 , url("https://github.com/bfritz")),
      Developer("bryce-anderson"       , "Bryce L. Anderson"     , "bryce.anderson22@gmail.com"       , url("https://github.com/bryce-anderson")),
      Developer("casualjim"            , "Ivan Porto Carrero"    , "ivan@flanders.co.nz"              , url("https://github.com/casualjim")),
      Developer("cencarnacion"         , "Carlos Encarnacion"    , ""                                 , url("https://github.com/cencarnacion")),
      Developer("ChristopherDavenport" , "Christopher Davenport" , "chris@christopherdavenport.tech"  , url("https://github.com/ChristopherDavenport")),
      Developer("cquiroz"              , "Carlos Quiroz"         , ""                                 , url("https://github.com/cquiroz")),
      Developer("hvesalai"             , "Heikki Vesalainen"     , ""                                 , url("https://github.com/hvesalai")),
      Developer("jedesah"              , "Jean-Rémi Desjardins"  , ""                                 , url("https://github.com/jedesah")),
      Developer("jmcardon"             , "Jose Cardona"          , ""                                 , url("https://github.com/jmcardon")),      
      Developer("julien-truffaut"      , "Julien Truffaut"       , ""                                 , url("https://github.com/julien-truffaut")),
      Developer("kryptt"               , "Rodolfo Hansen"        , ""                                 , url("https://github.com/kryptt")),
      Developer("reactormonk"          , "Simon Hafner"          , ""                                 , url("https://github.com/reactormonk")),
      Developer("refried"              , "Arya Irani"            , ""                                 , url("https://github.com/refried")),
      Developer("rossabaker"           , "Ross A. Baker"         , "ross@rossabaker.com"              , url("https://github.com/rossabaker")),
      Developer("shengc"               , "Sheng Chen"            , ""                                 , url("https://github.com/shengc"))
    ),
    licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.html")),
    homepage := Some(url("https://http4s.org/")),
    scmInfo := Some(ScmInfo(url("https://github.com/http4s/http4s"), "git@github.com:http4s/http4s.git")),
    startYear := Some(2013)
  )
}
