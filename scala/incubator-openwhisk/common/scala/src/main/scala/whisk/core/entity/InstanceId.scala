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

package whisk.core.entity

import spray.json.DefaultJsonProtocol
import whisk.core.entity.ControllerInstanceId.LEGAL_CHARS
import whisk.core.entity.ControllerInstanceId.MAX_NAME_LENGTH

case class InvokerInstanceId(val instance: Int, name: Option[String] = None) {
  def toInt: Int = instance
}

case class ControllerInstanceId(val asString: String) {
  require(
    asString.length <= MAX_NAME_LENGTH && asString.matches(LEGAL_CHARS),
    "Controller instance id contains invalid characters")
}

object InvokerInstanceId extends DefaultJsonProtocol {
  implicit val serdes = jsonFormat2(InvokerInstanceId.apply)
}

object ControllerInstanceId extends DefaultJsonProtocol {
  // controller ids become part of a kafka topic, hence, hence allow only certain characters
  // see https://github.com/apache/kafka/blob/trunk/clients/src/main/java/org/apache/kafka/common/internals/Topic.java#L29
  private val LEGAL_CHARS = "[a-zA-Z0-9._-]+"

  // reserve some number of characters as the prefix to be added to topic names
  private val MAX_NAME_LENGTH = 249 - 121

  implicit val serdes = jsonFormat1(ControllerInstanceId.apply)
}
