/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.database

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import org.rogach.scallop.{ScallopConfBase, Subcommand}
import spray.json.{JsObject, JsString, JsValue, RootJsonFormat}
import whisk.common.{Logging, TransactionId}
import whisk.core.cli.{CommandError, CommandMessages, IllegalState, WhiskCommand}
import whisk.core.database.LimitsCommand.LimitEntity
import whisk.core.entity.types.AuthStore
import whisk.core.entity.{DocId, DocInfo, DocRevision, EntityName, Subject, UserLimits, WhiskAuth, WhiskDocumentReader}
import whisk.http.Messages
import whisk.spi.SpiLoader

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.classTag
import scala.util.{Properties, Try}

class LimitsCommand extends Subcommand("limits") with WhiskCommand {
  descr("manage namespace-specific limits")

  val set = new Subcommand("set") {
    descr("set limits for a given namespace")

    val namespace = trailArg[String](descr = "the namespace to set limits for")

    //name is explicitly mentioned for backward compatability
    //otherwise scallop would convert it to - separated names
    val invocationsPerMinute =
      opt[Int](
        descr = "invocations per minute allowed",
        argName = "INVOCATIONSPERMINUTE",
        validate = _ >= 0,
        name = "invocationsPerMinute",
        noshort = true)
    val firesPerMinute =
      opt[Int](
        descr = "trigger fires per minute allowed",
        argName = "FIRESPERMINUTE",
        validate = _ >= 0,
        name = "firesPerMinute",
        noshort = true)
    val concurrentInvocations =
      opt[Int](
        descr = "concurrent invocations allowed for this namespace",
        argName = "CONCURRENTINVOCATIONS",
        validate = _ >= 0,
        name = "concurrentInvocations",
        noshort = true)

    lazy val limits: LimitEntity =
      new LimitEntity(
        EntityName(namespace()),
        UserLimits(invocationsPerMinute.toOption, firesPerMinute.toOption, concurrentInvocations.toOption))
  }
  addSubcommand(set)

  val get = new Subcommand("get") {
    descr("get limits for a given namespace (if none exist, system defaults apply)")
    val namespace = trailArg[String](descr = "the namespace to get limits for`")
  }
  addSubcommand(get)

  val delete = new Subcommand("delete") {
    descr("delete limits for a given namespace (system defaults apply)")
    val namespace = trailArg[String](descr = "the namespace to delete limits for")

  }
  addSubcommand(delete)

  def exec(cmd: ScallopConfBase)(implicit system: ActorSystem,
                                 logging: Logging,
                                 materializer: ActorMaterializer,
                                 transid: TransactionId): Future[Either[CommandError, String]] = {
    implicit val executionContext = system.dispatcher
    val authStore = LimitsCommand.createDataStore()
    val result = cmd match {
      case `set`    => setLimits(authStore)
      case `get`    => getLimits(authStore)
      case `delete` => delLimits(authStore)
    }
    result.onComplete { _ =>
      authStore.shutdown()
    }
    result
  }

  def setLimits(authStore: AuthStore)(implicit transid: TransactionId,
                                      ec: ExecutionContext): Future[Either[CommandError, String]] = {
    authStore
      .get[LimitEntity](set.limits.docinfo)
      .flatMap { limits =>
        val newLimits = set.limits.revision[LimitEntity](limits.rev)
        authStore.put(newLimits).map(_ => Right(CommandMessages.limitsSuccessfullyUpdated(limits.name.asString)))
      }
      .recoverWith {
        case _: NoDocumentException =>
          authStore.put(set.limits).map(_ => Right(CommandMessages.limitsSuccessfullySet(set.limits.name.asString)))
      }
  }

  def getLimits(authStore: AuthStore)(implicit transid: TransactionId,
                                      ec: ExecutionContext): Future[Either[CommandError, String]] = {
    val info = DocInfo(LimitsCommand.limitIdOf(EntityName(get.namespace())))
    authStore
      .get[LimitEntity](info)
      .map { le =>
        val l = le.limits
        val msg = Seq(
          l.concurrentInvocations.map(ci => s"concurrentInvocations =  $ci"),
          l.invocationsPerMinute.map(i => s"invocationsPerMinute = $i"),
          l.firesPerMinute.map(i => s"firesPerMinute = $i")).flatten.mkString(Properties.lineSeparator)
        Right(msg)
      }
      .recover {
        case _: NoDocumentException =>
          Right(CommandMessages.defaultLimits)
      }
  }

  def delLimits(authStore: AuthStore)(implicit transid: TransactionId,
                                      ec: ExecutionContext): Future[Either[CommandError, String]] = {
    val info = DocInfo(LimitsCommand.limitIdOf(EntityName(delete.namespace())))
    authStore
      .get[LimitEntity](info)
      .flatMap { l =>
        authStore.del(l.docinfo).map(_ => Right(CommandMessages.limitsDeleted))
      }
      .recover {
        case _: NoDocumentException =>
          Left(IllegalState(CommandMessages.limitsNotFound(delete.namespace())))
      }
  }
}

object LimitsCommand {
  def limitIdOf(name: EntityName) = DocId(s"${name.name}/limits")

  def createDataStore()(implicit system: ActorSystem,
                        logging: Logging,
                        materializer: ActorMaterializer): ArtifactStore[WhiskAuth] =
    SpiLoader
      .get[ArtifactStoreProvider]
      .makeStore[WhiskAuth]()(classTag[WhiskAuth], LimitsFormat, WhiskDocumentReader, system, logging, materializer)

  class LimitEntity(val name: EntityName, val limits: UserLimits) extends WhiskAuth(Subject(), Set.empty) {
    override def docid: DocId = limitIdOf(name)

    //There is no api to write limits. So piggy back on WhiskAuth but replace auth json
    //with limits!
    override def toJson: JsObject = UserLimits.serdes.write(limits).asJsObject
  }

  private object LimitsFormat extends RootJsonFormat[WhiskAuth] {
    override def read(json: JsValue): WhiskAuth = {
      val r = Try[LimitEntity] {
        val limits = UserLimits.serdes.read(json)
        val JsString(id) = json.asJsObject.fields("_id")
        val JsString(rev) = json.asJsObject.fields("_rev")
        val Array(name, _) = id.split('/')
        new LimitEntity(EntityName(name), limits).revision[LimitEntity](DocRevision(rev))
      }
      if (r.isSuccess) r.get else throw DocumentUnreadable(Messages.corruptedEntity)
    }

    override def write(obj: WhiskAuth): JsValue = obj.toDocumentRecord
  }
}
