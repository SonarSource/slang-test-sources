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

package reforest.data.tree

import reforest.TypeInfo
import reforest.data.{RawData, RawDataLabeled, WorkingData}
import reforest.rf.feature.RFFeatureSizer

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
  * A tree of the forest. The nodes are stored in an array.
  *
  * @param maxDepth the maximum allowed (configured) depth
  * @tparam T raw data type
  * @tparam U working data type
  */
class TreeFull[T, U](val maxDepth: Int) extends Tree[T, U] {
  private val _maxNodeNumber = Math.pow(2, maxDepth + 1).toInt - 1

  var _label: Array[Int] = Array.tabulate(_maxNodeNumber)(_ => -1)
  // TODO CHECK I CAN USE LESS SPLIT I THINK
  private var _split: Array[Option[TCut[T, U]]] = Array.tabulate(_maxNodeNumber)(_ => Option.empty)
  private var _leaf: mutable.BitSet = mutable.BitSet.empty

  override def toString = {
    var toReturn = ""
    var nodeId = 0
    while (nodeId < _maxNodeNumber) {
      if (_split(nodeId).isDefined || _label(nodeId) != -1) {
        val space = Array.fill[String](getLevel(nodeId))("\t").mkString("")
        toReturn += space + " " + nodeId + " " + (if (_leaf(nodeId)) "LEAF ") + _label(nodeId) + " " + _split(nodeId) + "\n"
      }
      nodeId += 1
    }

    toReturn
  }

  /**
    * Merge this tree with another tree. The merge is in place
    *
    * @param other_ another tree to merge with this
    */
  override def merge(other_ : Tree[T, U]): Unit = {

    other_ match {
      case o: TreeFull[T, U] => {
        if (_split(0).isEmpty) {
          _label = o._label
          _split = o._split
          _leaf = o._leaf
        } else {
          var count = 0
          while (count < _maxNodeNumber) {
            if (_label(count) < 0) {
              _label(count) = o._label(count)
            }
            if (count < _split.length && _split(count).isEmpty) {
              _split(count) = o._split(count)
            }
            count += 1
          }
          _leaf.union(o._leaf)
        }
      }
      case o: Tree[T, U] => {
        var count = 0
        while (count < _maxNodeNumber) {
          if (_label(count) < 0) {
            _label(count) = o.getLabel(count)
          }
          if (count < _split.length && _split(count).isEmpty) {
            _split(count) = o.getSplit(count)
          }
          count += 1
        }
        _leaf.union(o.getAllLeaf)
      }
    }


  }

  /**
    * It predicts the class label of the given element starting from a given node index
    *
    * @param maxDepth the maximum depth to visit
    * @param nodeId   the node index from which to start
    * @param data     the data to predict
    * @param typeInfo the type information of the war data
    * @return the predicted class label
    */
  override def predict(maxDepth: Int, nodeId: Int, data: RawData[T, U], typeInfo: TypeInfo[T]): Int = {
    if (isLeaf(nodeId) || getLevel(nodeId) >= maxDepth || _split(nodeId).isEmpty) {
      _label(nodeId)
    } else {
      val splitLeft = _split(nodeId).get.shouldGoLeft(data, typeInfo)

      if (splitLeft) {
        predict(maxDepth, getLeftChild(nodeId), data, typeInfo)
      } else {
        predict(maxDepth, getRightChild(nodeId), data, typeInfo)
      }
    }
  }

  /**
    * It returns a list of nodes that still require to be processed
    *
    * @param nodeId_   the node index from which to start
    * @param toReturn_ the list to populate with additional nodes
    * @return a list of node that require computation
    */
  override def getNodeToBeComputed(nodeId_ : Int, toReturn_ : ListBuffer[Int]): ListBuffer[Int] = {
    if (_split(nodeId_).isEmpty) {
      toReturn_ += nodeId_
    } else {
      val leftChild = getLeftChild(nodeId_)
      if (leftChild != -1) {
        getNodeToBeComputed(leftChild, toReturn_)
      }
      val rightChild = getRightChild(nodeId_)
      if (rightChild != -1) {
        getNodeToBeComputed(rightChild, toReturn_)
      }
    }
    toReturn_
  }

  /**
    * To get the left child of a given node
    *
    * @param nodeId_ the node index
    * @return the left child of the node nodeId
    */
  override def getLeftChild(nodeId_ : Int): Int = {
    val child = nodeId_ * 2 + 1
    if (child < _maxNodeNumber) {
      child
    } else {
      -1
    }
  }

  /**
    * To get the right child of a given node
    *
    * @param nodeId_ the node index
    * @return the left child of the node nodeId
    */
  override def getRightChild(nodeId_ : Int): Int = {
    val child = nodeId_ * 2 + 2
    if (child < _maxNodeNumber) {
      child
    } else {
      -1
    }
  }

  def getLabel(nodeId_ : Int): Int = {
    _label(nodeId_)
  }

  /**
    * Set the class label of the given node
    *
    * @param nodeId_ the node index for which setting the class label
    * @param label_  the label to set in the node nodeId
    */
  override def setLabel(nodeId_ : Int, label_ : Int) = {
    _label(nodeId_) = label_
  }

  /**
    * Get the split of a give node
    *
    * @param nodeId_ the node index for which getting the split
    * @return
    */
  override def getSplit(nodeId_ : Int): Option[TCut[T, U]] = {
    _split(nodeId_)
  }

  override def setSplit(nodeId_ : Int, cut_ : CutDetailed[T, U]): Unit = {
    _split(nodeId_) = Some(cut_)
  }

  /**
    * Set the given node as a leaf
    *
    * @param nodeId_ the node index to set as a leaf
    */
  override def setLeaf(nodeId_ : Int) = {
    _leaf += nodeId_
  }

  /**
    * Check if the given node is a leaf
    *
    * @param nodeId_ the node index to check
    * @return true if the given node is a leaf
    */
  override def isLeaf(nodeId_ : Int): Boolean = {
    _leaf.contains(nodeId_)
  }

  /**
    * Check if the given node is defined
    *
    * @param nodeId_ the node index to check
    * @return true if the given node is defined
    */
  override def isDefined(nodeId_ : Int): Boolean = {
    _label(nodeId_) != -1
  }

  override def getAllLeaf(): mutable.BitSet = _leaf
}