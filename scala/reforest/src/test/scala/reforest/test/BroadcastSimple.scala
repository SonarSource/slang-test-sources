/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reforest.test

import org.apache.spark.broadcast.Broadcast
import reforest.rf.RFCategoryInfo
import reforest.util.{GCInstrumented, GCInstrumentedEmpty}
import reforest.{TypeInfo, TypeInfoDouble, TypeInfoInt}
import test.RFResourceFactory

import scala.reflect.ClassTag

class BroadcastSimple[T: ClassTag](v: T) extends Broadcast[T](0) {
  override def value: T = v

  override def getValue(): T = v

  override def doDestroy(blocking: Boolean) = {}

  override def doUnpersist(blocking: Boolean) = {}
}

object BroadcastSimple {
  val typeInfoInt = new BroadcastSimple[TypeInfoInt](new TypeInfoInt(false, -100))
  val typeInfoDouble : Broadcast[TypeInfo[Double]] = new BroadcastSimple[TypeInfo[Double]](new TypeInfoDouble(false, -100))
  val gcInstrumentedEmpty : Broadcast[GCInstrumented] = new BroadcastSimple[GCInstrumented](new GCInstrumentedEmpty)
  val categoryInfoEmpty : Broadcast[RFCategoryInfo] = new BroadcastSimple[RFCategoryInfo](RFResourceFactory.getCategoricalInfo)
}
