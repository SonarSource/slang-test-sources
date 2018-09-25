package com.prisma.workers.payloads

import com.prisma.messagebus.Conversions
import com.prisma.messagebus.Conversions.{ByteMarshaller, ByteUnmarshaller}
import play.api.libs.json._

object JsonConversions {

  implicit val mapStringReads: Reads[Map[String, String]]               = Reads.mapReads[String]
  implicit val mapStringWrites: OWrites[collection.Map[String, String]] = Writes.mapWrites[String]

  implicit val webhookFormat: OFormat[Webhook] = Json.format[Webhook]

  implicit val webhookMarshaller: ByteMarshaller[Webhook]     = Conversions.Marshallers.FromJsonBackedType[Webhook]()
  implicit val webhookUnmarshaller: ByteUnmarshaller[Webhook] = Conversions.Unmarshallers.ToJsonBackedType[Webhook]()

}
