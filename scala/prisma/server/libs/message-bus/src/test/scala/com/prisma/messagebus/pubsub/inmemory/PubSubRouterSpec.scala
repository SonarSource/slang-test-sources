package com.prisma.messagebus.pubsub.inmemory

import akka.actor.Props
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import com.prisma.akkautil.SingleThreadedActorSystem
import com.prisma.messagebus.pubsub.PubSubProtocol.{Publish, Subscribe, Unsubscribe}
import com.prisma.messagebus.pubsub.PubSubRouter
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}

import scala.concurrent.duration._

class PubSubRouterSpec
    extends TestKit(SingleThreadedActorSystem("pubsub-router-spec"))
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach {
  override def afterAll = shutdown(verifySystemShutdown = true)

  "The PubSubRouter implementation" should {
    "subscribe subscribers correctly and route messages" in {
      val routerActor = TestActorRef(Props[PubSubRouter])
      val router      = routerActor.underlyingActor.asInstanceOf[PubSubRouter]
      val probe       = TestProbe()
      val topic       = "testTopic"

      routerActor ! Subscribe(topic, probe.ref)
      router.subscribers.values.map(_.size).sum shouldEqual 1

      routerActor ! Publish(topic, "test")
      probe.expectMsg("test")
      probe.expectNoMessage(max = 1.second)

      routerActor ! Publish("testTopic2", "test2")
      probe.expectNoMessage(max = 1.second)
    }

    "unsubscribe subscribers correctly" in {
      val routerActor = TestActorRef(Props[PubSubRouter])
      val router      = routerActor.underlyingActor.asInstanceOf[PubSubRouter]
      val probe       = TestProbe()
      val topic       = "testTopic"

      routerActor ! Subscribe(topic, probe.ref)
      router.subscribers.values.map(_.size).sum shouldEqual 1

      routerActor ! Unsubscribe(topic, probe.ref)
      router.subscribers.values.map(_.size).sum shouldEqual 0

      routerActor ! Publish(topic, "test")
      probe.expectNoMessage(max = 1.second)
    }

    "handle actor terminations" in {
      val routerActor = TestActorRef(Props[PubSubRouter])
      val router      = routerActor.underlyingActor.asInstanceOf[PubSubRouter]
      val probe       = TestProbe()
      val topic       = "testTopic"

      routerActor ! Subscribe(topic, probe.ref)
      router.subscribers.values.map(_.size).sum shouldEqual 1

      system.stop(probe.ref)
      Thread.sleep(50)
      router.subscribers.values.map(_.size).sum shouldEqual 0
    }
  }
}
