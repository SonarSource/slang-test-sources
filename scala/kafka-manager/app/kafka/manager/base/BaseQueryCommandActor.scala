/**
 * Copyright 2015 Yahoo Inc. Licensed under the Apache License, Version 2.0
 * See accompanying LICENSE file.
 */

package kafka.manager.base

import kafka.manager.model.ActorModel
import ActorModel.{ActorRequest, CommandRequest, QueryRequest}

/**
 * @author hiral
 */
abstract class BaseQueryCommandActor extends BaseActor {
  final def processActorRequest(request: ActorRequest): Unit = {
    request match  {
      case queryRequest: QueryRequest =>
        processQueryRequest(queryRequest)
      case queryRequest: CommandRequest =>
        processCommandRequest(queryRequest)
    }
  }

  def processQueryRequest(request: QueryRequest): Unit

  def processCommandRequest(request: CommandRequest): Unit

  @scala.throws[Exception](classOf[Exception])
  override def preStart(): Unit = super.preStart()

  @scala.throws[Exception](classOf[Exception])
  override def postStop(): Unit = super.postStop()

  @scala.throws[Exception](classOf[Exception])
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = super.preRestart(reason, message)

}
