package com.prisma.subscriptions.protocol

import com.prisma.messagebus.Conversions
import play.api.libs.json.Json

object SubscriptionRequest {
  implicit val requestFormat = Json.format[SubscriptionRequest]

  implicit val requestUnmarshaller = Conversions.Unmarshallers.ToJsonBackedType[SubscriptionRequest]()
  implicit val requestMarshaller   = Conversions.Marshallers.FromJsonBackedType[SubscriptionRequest]()
}

case class SubscriptionRequest(sessionId: String, projectId: String, body: String)
