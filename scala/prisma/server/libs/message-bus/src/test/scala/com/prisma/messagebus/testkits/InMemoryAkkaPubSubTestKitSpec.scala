package com.prisma.messagebus.testkits

import com.prisma.akkautil.SingleThreadedActorSystem
import com.prisma.messagebus.pubsub.{Message, Only}
import com.prisma.messagebus.testkits.spechelpers.InMemoryMessageBusTestKits
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}

class InMemoryAkkaPubSubTestKitSpec
    extends InMemoryMessageBusTestKits(SingleThreadedActorSystem("pubsub-spec"))
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with ScalaFutures {

  case class TestMessage(id: String, testOpt: Option[Int], testSeq: Seq[String])

  val testRK: Only = Only("test")

  override def afterAll = shutdownTestKit

  "The in-memory pubsub testing kit" should {

    /**
      * Incoming messages expectation tests
      */
    "should expect an incoming message correctly" in {
      withPubSubTestKit[TestMessage] { testKit =>
        val testMsg = Message[TestMessage](testRK.topic, TestMessage("someId1", None, Seq("1", "2")))

        testKit.withTestSubscriber
        testKit.publish(testRK, testMsg.payload)
        testKit.expectMsg(testMsg)
        testKit.messagesReceived.length shouldEqual 1
      }
    }

    "should blow up it expects a message and none arrives" in {
      withPubSubTestKit[TestMessage] { testKit =>
        val testMsg = Message[TestMessage](testRK.topic, TestMessage("someId2", None, Seq("1", "2")))

        testKit.withTestSubscriber

        an[AssertionError] should be thrownBy {
          testKit.expectMsg(testMsg)
        }
      }
    }

    "should expect no message correctly" in {
      withPubSubTestKit[TestMessage] { testKit =>
        testKit.withTestSubscriber
        testKit.expectNoMsg()
      }
    }

    "should blow up if no message was expected but one arrives" in {
      withPubSubTestKit[TestMessage] { testKit =>
        val testMsg = TestMessage("someId3", None, Seq("1", "2"))

        testKit.withTestSubscriber
        testKit.publish(testRK, testMsg)

        an[AssertionError] should be thrownBy {
          testKit.expectNoMsg()
        }
      }
    }

    "should expect a message count correctly" in {
      withPubSubTestKit[TestMessage] { testKit =>
        val testMsg  = TestMessage("someId4", None, Seq("1", "2"))
        val testMsg2 = TestMessage("someId5", Some(123), Seq("2", "1"))

        testKit.withTestSubscriber
        testKit.publish(testRK, testMsg)
        testKit.publish(testRK, testMsg2)
        testKit.expectMsgCount(2)
        testKit.messagesReceived.length shouldEqual 2
      }
    }

    "should expect double the message count with double the subscribers" in {
      withPubSubTestKit[TestMessage] { testKit =>
        val testMsg = TestMessage("someId4", None, Seq("1", "2"))

        // 2 Subscribers to Everything
        testKit.withTestSubscriber
        testKit.withTestSubscriber

        // Published messages should be counted twice
        testKit.publish(testRK, testMsg)
        testKit.expectMsgCount(2)
        testKit.messagesReceived.length shouldEqual 2
      }
    }

    "should blow up if it expects a message count and less arrive" in {
      withPubSubTestKit[TestMessage] { testKit =>
        val testMsg = TestMessage("someId6", None, Seq("1", "2"))

        testKit.withTestSubscriber
        testKit.publish(testRK, testMsg)

        an[AssertionError] should be thrownBy {
          testKit.expectMsgCount(2)
        }
      }
    }

    "should blow up if it expects a message count and more arrive" in {
      withPubSubTestKit[TestMessage] { testKit =>
        val testMsg  = TestMessage("someId7", None, Seq("1", "2"))
        val testMsg2 = TestMessage("someId8", Some(123), Seq("2", "1"))

        testKit.withTestSubscriber
        testKit.publish(testRK, testMsg)
        testKit.publish(testRK, testMsg2)

        an[AssertionError] should be thrownBy {
          testKit.expectMsgCount(1)
        }
      }
    }

    /**
      * Published messages expectation tests
      */
    "should expect a published message correctly" in {
      withPubSubTestKit[TestMessage] { testKit =>
        val testMsg = Message[TestMessage](testRK.topic, TestMessage("someId1", None, Seq("1", "2")))

        testKit.publish(testRK, testMsg.payload)
        testKit.expectPublishedMsg(testMsg)
        testKit.messagesPublished.length shouldEqual 1
      }
    }

    "should blow up it expects a published message and none arrives" in {
      withPubSubTestKit[TestMessage] { testKit =>
        val testMsg = Message[TestMessage](testRK.topic, TestMessage("someId2", None, Seq("1", "2")))

        an[AssertionError] should be thrownBy {
          testKit.expectPublishedMsg(testMsg)
        }
      }
    }

    "should expect no published message correctly" in {
      withPubSubTestKit[TestMessage] { testKit =>
        testKit.expectNoPublishedMsg()
      }
    }

    "should blow up if no published message was expected but one arrives" in {
      withPubSubTestKit[TestMessage] { testKit =>
        val testMsg = TestMessage("someId3", None, Seq("1", "2"))

        testKit.publish(testRK, testMsg)

        an[AssertionError] should be thrownBy {
          testKit.expectNoPublishedMsg()
        }
      }
    }

    "should expect a published message count correctly" in {
      withPubSubTestKit[TestMessage] { testKit =>
        val testMsg  = TestMessage("someId4", None, Seq("1", "2"))
        val testMsg2 = TestMessage("someId5", Some(123), Seq("2", "1"))

        testKit.publish(testRK, testMsg)
        testKit.publish(testRK, testMsg2)
        testKit.expectPublishCount(2)
        testKit.messagesPublished.length shouldEqual 2
      }
    }

    "should blow up if it expects a published message count and less arrive" in {
      withPubSubTestKit[TestMessage] { testKit =>
        val testMsg = TestMessage("someId6", None, Seq("1", "2"))

        testKit.publish(testRK, testMsg)

        an[AssertionError] should be thrownBy {
          testKit.expectPublishCount(2)
        }
      }
    }

    "should blow up if it expects a published message count and more arrive" in {
      withPubSubTestKit[TestMessage] { testKit =>
        val testMsg  = TestMessage("someId7", None, Seq("1", "2"))
        val testMsg2 = TestMessage("someId8", Some(123), Seq("2", "1"))

        testKit.publish(testRK, testMsg)
        testKit.publish(testRK, testMsg2)

        an[AssertionError] should be thrownBy {
          testKit.expectPublishCount(1)
        }
      }
    }
  }
}
