/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package play.api.cache

import java.util.concurrent.{ Callable, CompletableFuture, CompletionStage }

import org.specs2.concurrent.ExecutionEnv
import play.api.test.{ PlaySpecification, WithApplication }
import play.cache.{ AsyncCacheApi => JavaAsyncCacheApi, SyncCacheApi => JavaSyncCacheApi }

import scala.compat.java8.FutureConverters._
import scala.concurrent.Await
import scala.concurrent.duration._

class JavaCacheApiSpec(implicit ee: ExecutionEnv) extends PlaySpecification {

  sequential

  "Java AsyncCacheApi" should {
    "set cache values" in new WithApplication {
      val cacheApi = app.injector.instanceOf[JavaAsyncCacheApi]
      Await.result(cacheApi.set("foo", "bar").toScala, 1.second)
      cacheApi.get[String]("foo").toScala must beEqualTo("bar").await
    }
    "set cache values with an expiration time" in new WithApplication {
      val cacheApi = app.injector.instanceOf[JavaAsyncCacheApi]
      Await.result(cacheApi.set("foo", "bar", 1 /* second */ ).toScala, 1.second)

      Thread.sleep(2.seconds.toMillis)
      cacheApi.get[String]("foo").toScala must beNull.await
    }
    "set cache values with an expiration time" in new WithApplication {
      val cacheApi = app.injector.instanceOf[JavaAsyncCacheApi]
      Await.result(cacheApi.set("foo", "bar", 10 /* seconds */ ).toScala, 1.second)

      Thread.sleep(2.seconds.toMillis)
      cacheApi.get[String]("foo").toScala must beEqualTo("bar").await
    }
    "get or update" should {
      "get value when it exists" in new WithApplication {
        val cacheApi = app.injector.instanceOf[JavaAsyncCacheApi]
        Await.result(cacheApi.set("foo", "bar").toScala, 1.second)
        cacheApi.get[String]("foo").toScala must beEqualTo("bar").await
      }
      "update cache when value does not exists" in new WithApplication {
        val cacheApi = app.injector.instanceOf[JavaAsyncCacheApi]
        val future = cacheApi.getOrElseUpdate[String]("foo", new Callable[CompletionStage[String]] {
          override def call() = CompletableFuture.completedFuture[String]("bar")
        }).toScala

        future must beEqualTo("bar").await
        cacheApi.get[String]("foo").toScala must beEqualTo("bar").await
      }
      "update cache with an expiration time when value does not exists" in new WithApplication {
        val cacheApi = app.injector.instanceOf[JavaAsyncCacheApi]
        val future = cacheApi.getOrElseUpdate[String]("foo", new Callable[CompletionStage[String]] {
          override def call() = CompletableFuture.completedFuture[String]("bar")
        }, 1 /* second */ ).toScala

        future must beEqualTo("bar").await

        Thread.sleep(2.seconds.toMillis)
        cacheApi.get[String]("foo").toScala must beNull.await
      }
    }
    "remove values from cache" in new WithApplication {
      val cacheApi = app.injector.instanceOf[JavaAsyncCacheApi]
      Await.result(cacheApi.set("foo", "bar").toScala, 1.second)
      cacheApi.get[String]("foo").toScala must beEqualTo("bar").await

      Await.result(cacheApi.remove("foo").toScala, 1.second)
      cacheApi.get[String]("foo").toScala must beNull.await
    }

    "remove all values from cache" in new WithApplication {
      val cacheApi = app.injector.instanceOf[JavaAsyncCacheApi]
      Await.result(cacheApi.set("foo", "bar").toScala, 1.second)
      cacheApi.get[String]("foo").toScala must beEqualTo("bar").await

      Await.result(cacheApi.removeAll().toScala, 1.second)
      cacheApi.get[String]("foo").toScala must beNull.await
    }
  }

  "Java SyncCacheApi" should {
    "set cache values" in new WithApplication {
      val cacheApi = app.injector.instanceOf[JavaSyncCacheApi]
      cacheApi.set("foo", "bar")
      cacheApi.get[String]("foo") must beEqualTo("bar")
    }
    "set cache values with an expiration time" in new WithApplication {
      val cacheApi = app.injector.instanceOf[JavaSyncCacheApi]
      cacheApi.set("foo", "bar", 1 /* second */ )

      Thread.sleep(2.seconds.toMillis)
      cacheApi.get[String]("foo") must beNull
    }
    "set cache values with an expiration time" in new WithApplication {
      val cacheApi = app.injector.instanceOf[JavaSyncCacheApi]
      cacheApi.set("foo", "bar", 10 /* seconds */ )

      Thread.sleep(2.seconds.toMillis)
      cacheApi.get[String]("foo") must beEqualTo("bar")
    }
    "get or update" should {
      "get value when it exists" in new WithApplication {
        val cacheApi = app.injector.instanceOf[JavaSyncCacheApi]
        cacheApi.set("foo", "bar")
        cacheApi.get[String]("foo") must beEqualTo("bar")
      }
      "update cache when value does not exists" in new WithApplication {
        val cacheApi = app.injector.instanceOf[JavaSyncCacheApi]
        val value = cacheApi.getOrElseUpdate[String]("foo", new Callable[String] {
          override def call() = "bar"
        })

        value must beEqualTo("bar")
        cacheApi.get[String]("foo") must beEqualTo("bar")
      }
      "update cache with an expiration time when value does not exists" in new WithApplication {
        val cacheApi = app.injector.instanceOf[JavaSyncCacheApi]
        val future = cacheApi.getOrElseUpdate[String]("foo", new Callable[String] {
          override def call() = "bar"
        }, 1 /* second */ )

        future must beEqualTo("bar")

        Thread.sleep(2.seconds.toMillis)
        cacheApi.get[String]("foo") must beNull
      }
    }
    "remove values from cache" in new WithApplication {
      val cacheApi = app.injector.instanceOf[JavaSyncCacheApi]
      cacheApi.set("foo", "bar")
      cacheApi.get[String]("foo") must beEqualTo("bar")

      cacheApi.remove("foo")
      cacheApi.get[String]("foo") must beNull
    }
  }
}
