package com.prisma.subscriptions.resolving

import akka.actor.{Actor, ActorRef, Props, Terminated}
import com.prisma.akkautil.{LogUnhandled, LogUnhandledExceptions}
import com.prisma.messagebus.pubsub.Only
import com.prisma.shared.models.ModelMutationType.ModelMutationType
import com.prisma.subscriptions.SubscriptionDependencies
import com.prisma.subscriptions.protocol.StringOrInt
import com.prisma.subscriptions.resolving.SubscriptionsManager.Requests.CreateSubscription
import play.api.libs.json._

import scala.collection.mutable

object SubscriptionsManager {
  object Requests {
    sealed trait SubscriptionsManagerRequest

    case class CreateSubscription(
        id: StringOrInt,
        projectId: String,
        sessionId: String,
        query: sangria.ast.Document,
        variables: Option[JsObject],
        authHeader: Option[String],
        operationName: Option[String]
    ) extends SubscriptionsManagerRequest

    case class EndSubscription(
        id: StringOrInt,
        sessionId: String,
        projectId: String
    ) extends SubscriptionsManagerRequest
  }

  object Responses {
    sealed trait CreateSubscriptionResponse

    case class CreateSubscriptionSucceeded(request: CreateSubscription)                      extends CreateSubscriptionResponse
    case class CreateSubscriptionFailed(request: CreateSubscription, errors: Seq[Exception]) extends CreateSubscriptionResponse
    case class SubscriptionEvent(subscriptionId: StringOrInt, payload: JsValue)
    case class ProjectSchemaChanged(subscriptionId: StringOrInt)
  }

  object Internal {
    case class ResolverType(modelId: String, mutation: ModelMutationType)
  }
}

case class SubscriptionsManager()(
    implicit dependencies: SubscriptionDependencies
) extends Actor
    with LogUnhandled
    with LogUnhandledExceptions {

  import SubscriptionsManager.Requests._

  val reporter                = dependencies.reporter
  val invalidationSubscriber  = dependencies.invalidationSubscriber
  private val projectManagers = mutable.HashMap.empty[String, ActorRef]

  override def receive: Receive = logUnhandled {
    case create: CreateSubscription => projectActorFor(create.projectId).forward(create)
    case end: EndSubscription       => projectActorFor(end.projectId).forward(end)
    case Terminated(ref)            => projectManagers.retain { case (_, projectActor) => projectActor != ref }
  }

  private def projectActorFor(projectId: String): ActorRef = {
    projectManagers.getOrElseUpdate(
      projectId, {
        val ref = context.actorOf(Props(SubscriptionsManagerForProject(projectId)), projectId)
        invalidationSubscriber.subscribe(Only(projectId), ref)
        context.watch(ref)
      }
    )
  }
}
