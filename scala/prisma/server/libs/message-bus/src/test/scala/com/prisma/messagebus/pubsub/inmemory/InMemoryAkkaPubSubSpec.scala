package com.prisma.messagebus.pubsub.inmemory

import akka.testkit.{TestKit, TestProbe}
import com.prisma.akkautil.SingleThreadedActorSystem
import com.prisma.messagebus.pubsub.{Everything, Message, Only}
import com.prisma.messagebus.{PubSub, PubSubPublisher}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}

import scala.concurrent.duration._

class InMemoryAkkaPubSubSpec
    extends TestKit(SingleThreadedActorSystem("pubsub-spec"))
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach {
  override def afterAll = shutdown(verifySystemShutdown = true)

  val testTopic = Only("testTopic")
  val testMsg   = "testMsg"

  def withInMemoryAkkaPubSub(checkFn: (PubSub[String], TestProbe) => Unit): Unit = {
    val testProbe = TestProbe()
    val pubSub    = InMemoryAkkaPubSub[String]()

    checkFn(pubSub, testProbe)
  }

  "The in-memory PubSub implementation" should {

    /**
      * Callback tests
      */
    "call the specified callback if a message for the subscription arrives" in {
      withInMemoryAkkaPubSub { (pubsub, probe) =>
        val testCallback = (msg: Message[String]) => probe.ref ! msg

        pubsub.subscribe(testTopic, testCallback)
        Thread.sleep(50)
        pubsub.publish(testTopic, testMsg)
        probe.expectMsg(Message[String](testTopic.topic, testMsg))
      }
    }

    "not call the specified callback if the message doesn't match" in {
      withInMemoryAkkaPubSub { (pubsub, probe) =>
        val testCallback = (msg: Message[String]) => probe.ref ! msg

        pubsub.subscribe(Only("NOPE"), testCallback)
        Thread.sleep(50)
        pubsub.publish(testTopic, testMsg)
        probe.expectNoMessage(6.seconds)
      }
    }

    "not call the specified callback if the subscriber unsubscribed" in {
      withInMemoryAkkaPubSub { (pubsub, probe) =>
        val testCallback = (msg: Message[String]) => probe.ref ! msg
        val subscription = pubsub.subscribe(testTopic, testCallback)

        Thread.sleep(50)
        pubsub.unsubscribe(subscription)
        pubsub.publish(testTopic, testMsg)
        probe.expectNoMessage(6.seconds)
      }
    }

    "send messages from different topics to the given callback when subscribed to everything" in {
      withInMemoryAkkaPubSub { (pubsub, probe) =>
        val testCallback = (msg: Message[String]) => probe.ref ! msg
        val testMsg2     = "testMsg2"

        pubsub.subscribe(Everything, testCallback)
        Thread.sleep(50)
        pubsub.publish(testTopic, testMsg)
        pubsub.publish(Only("testTopic2"), testMsg2)
        probe.expectMsgAllOf(Message[String](testTopic.topic, testMsg), Message[String]("testTopic2", testMsg2))
      }
    }

    /**
      * Actor tests
      */
    "send the unmarshalled message to the given actor" in {
      withInMemoryAkkaPubSub { (pubsub, probe) =>
        pubsub.subscribe(testTopic, probe.ref)
        Thread.sleep(50)
        pubsub.publish(testTopic, testMsg)
        probe.expectMsg(Message[String](testTopic.topic, testMsg))
      }
    }

    "not send the message to the given actor if the message doesn't match" in {
      withInMemoryAkkaPubSub { (pubsub, probe) =>
        pubsub.subscribe(Only("NOPE"), probe.ref)
        Thread.sleep(50)
        pubsub.publish(testTopic, testMsg)
        probe.expectNoMessage(6.seconds)
      }
    }

    "not send the message to the given actor if the subscriber unsubscribed" in {
      withInMemoryAkkaPubSub { (pubsub, probe) =>
        val subscription = pubsub.subscribe(testTopic, probe.ref)

        Thread.sleep(50)
        pubsub.unsubscribe(subscription)
        pubsub.publish(testTopic, testMsg)
        probe.expectNoMessage(6.seconds)
      }
    }

    "send the unmarshalled messages from different topics to the given actor when subscribed to everything" in {
      withInMemoryAkkaPubSub { (pubsub, probe) =>
        val testMsg2 = "testMsg2"

        pubsub.subscribe(Everything, probe.ref)
        Thread.sleep(50)
        pubsub.publish(testTopic, testMsg)
        pubsub.publish(Only("testTopic2"), testMsg2)
        probe.expectMsgAllOf(Message[String](testTopic.topic, testMsg), Message[String]("testTopic2", testMsg2))
      }
    }

    "remap the message to a different type if used with a mapping pubsub subscriber" in {
      withInMemoryAkkaPubSub { (pubsub, probe) =>
        val msg       = "1234"
        val converter = (s: String) => s.toInt
        val newPubSub = pubsub.map[Int](converter)
        val newProbe  = TestProbe()

        pubsub.subscribe(testTopic, probe.ref)
        newPubSub.subscribe(testTopic, newProbe.ref)
        Thread.sleep(50)
        pubsub.publish(testTopic, msg)
        probe.expectMsg(Message[String](testTopic.topic, msg))
        newProbe.expectMsg(Message[Int](testTopic.topic, 1234))
      }
    }

    "remap the message to a different type if used with a mapping pubsub publisher" in {
      withInMemoryAkkaPubSub { (pubsub: PubSub[String], probe) =>
        val msg                             = 1234
        val converter                       = (int: Int) => int.toString
        val newPubSub: PubSubPublisher[Int] = pubsub.map[Int](converter)

        pubsub.subscribe(testTopic, probe.ref)
        Thread.sleep(50)
        newPubSub.publish(testTopic, msg)
        probe.expectMsg(Message[String](testTopic.topic, "1234"))
      }
    }
  }
}
