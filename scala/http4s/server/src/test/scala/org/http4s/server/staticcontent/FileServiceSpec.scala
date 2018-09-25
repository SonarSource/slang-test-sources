package org.http4s
package server
package staticcontent

import cats.effect._
import fs2._
import java.io.File
import org.http4s.server.middleware.TranslateUri

class FileServiceSpec extends Http4sSpec with StaticContentShared {
  val s = fileService(FileService.Config[IO](new File(getClass.getResource("/").toURI).getPath))

  "FileService" should {

    "Respect UriTranslation" in {
      val s2 = TranslateUri("/foo")(s)

      {
        val req = Request[IO](uri = uri("foo/testresource.txt"))
        s2.orNotFound(req) must returnBody(testResource)
        s2.orNotFound(req) must returnStatus(Status.Ok)
      }

      {
        val req = Request[IO](uri = uri("testresource.txt"))
        s2.orNotFound(req) must returnStatus(Status.NotFound)
      }
    }

    "Return a 200 Ok file" in {
      val req = Request[IO](uri = uri("testresource.txt"))
      s.orNotFound(req) must returnBody(testResource)
      s.orNotFound(req) must returnStatus(Status.Ok)
    }

    "Return index.html if request points to a directory" in {
      val req = Request[IO](uri = uri("testDir/"))
      val rb = runReq(req)

      rb._2.as[String] must returnValue("<html>Hello!</html>")
      rb._2.status must_== Status.Ok
    }

    "Not find missing file" in {
      val req = Request[IO](uri = uri("testresource.txtt"))
      s.orNotFound(req) must returnStatus(Status.NotFound)
    }

    "Return a 206 PartialContent file" in {
      val range = headers.Range(4)
      val req = Request[IO](uri = uri("testresource.txt")).replaceAllHeaders(range)
      s.orNotFound(req) must returnStatus(Status.PartialContent)
      s.orNotFound(req) must returnBody(Chunk.bytes(testResource.toArray.splitAt(4)._2))
    }

    "Return a 206 PartialContent file" in {
      val range = headers.Range(-4)
      val req = Request[IO](uri = uri("testresource.txt")).replaceAllHeaders(range)
      s.orNotFound(req) must returnStatus(Status.PartialContent)
      s.orNotFound(req) must returnBody(
        Chunk.bytes(testResource.toArray.splitAt(testResource.size - 4)._2))
    }

    "Return a 206 PartialContent file" in {
      val range = headers.Range(2, 4)
      val req = Request[IO](uri = uri("testresource.txt")).replaceAllHeaders(range)
      s.orNotFound(req) must returnStatus(Status.PartialContent)
      s.orNotFound(req) must returnBody(Chunk.bytes(testResource.toArray.slice(2, 4 + 1))) // the end number is inclusive in the Range header
    }

    "Return a 200 OK on invalid range" in {
      val ranges = Seq(
        headers.Range(2, -1),
        headers.Range(2, 1),
        headers.Range(200),
        headers.Range(200, 201),
        headers.Range(-200)
      )
      val reqs = ranges.map(r => Request[IO](uri = uri("testresource.txt")).replaceAllHeaders(r))
      forall(reqs) { req =>
        s.orNotFound(req) must returnStatus(Status.Ok)
        s.orNotFound(req) must returnBody(testResource)
      }
    }
  }

}
