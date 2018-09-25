// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark

import java.io._

import com.microsoft.CNTK.CNTKExtensions._
import com.microsoft.CNTK.{SerializableFunction => CNTKFunction, _}
import com.microsoft.ml.spark.FileUtilities.File
import com.microsoft.ml.spark.StreamUtilities._
import org.apache.commons.io.IOUtils

import scala.collection.JavaConversions._

class CNTKBindingSuite extends LinuxOnly with CNTKTestUtils {

  def toSeqSeq(fvv: FloatVectorVector): Seq[Seq[Float]] = {
    (0 until fvv.size.toInt).map(i =>
      (0 until fvv.get(i).size().toInt).map(j => fvv.get(i).get(j)))
  }

  def toFVV(minibatch: Seq[Seq[Float]]): FloatVectorVector = {
    minibatch.foldLeft(new FloatVectorVector()) {
      case (fvv, floats) =>
        fvv.add(floats.foldLeft(new FloatVector()) { case (fv, f) => fv.add(f); fv })
        fvv
    }
  }

  def randomSeqSeq(outerSize: Int, dim: Int, seed: Int): Seq[Seq[Float]] = {
    val r = scala.util.Random
    r.setSeed(seed.toLong)
    (1 to outerSize).map(i => {
      (1 to dim).map(j => {
        r.nextFloat()
      })
    })
  }

  def randomFVV(batchSize: Int, dim: Int, seed: Int): FloatVectorVector = {
    toFVV(randomSeqSeq(batchSize, dim, seed))
  }

  def evaluateRandomMinibatch(model: CNTKFunction, batchSize: Int,
                              outputNum: Int = 3, seed: Int = 123): Seq[Seq[Float]] = {
    evaluateFVV(
      model, randomFVV(batchSize,32*32*3, seed), new FloatVectorVector(), outputNum)
  }

  def evaluateFVV(model: CNTKFunction,
                  inFvv: FloatVectorVector,
                  outFvv: FloatVectorVector,
                  outputNum: Int = 3): Seq[Seq[Float]] = {
    val inputVar = model.getArguments()(0)
    val inputShape = inputVar.getShape
    val inputVal = Value.createDenseFloat(inputShape, inFvv, DeviceDescriptor.getCPUDevice)
    val inputDataMap = new UnorderedMapVariableValuePtr()
    inputDataMap.add(inputVar, inputVal)

    val outputDataMap = new UnorderedMapVariableValuePtr()
    val outputVar = model.getOutputs.get(outputNum)
    outputDataMap.add(outputVar, null)

    println(s"evaluating shape ${inputVal.getShape.getDimensions.toList}")
    model.evaluate(inputDataMap, outputDataMap, DeviceDescriptor.getCPUDevice)
    outputDataMap.getitem(outputVar).copyVariableValueToFloat(outputVar, outFvv)
    toSeqSeq(outFvv)
  }

  test(" A serializable CNTKModel should be serializable") {
    val bytes = IOUtils.toByteArray(new FileInputStream(new File(modelPath)))

    val model = CNTKFunction.load(modelPath, DeviceDescriptor.useDefaultDevice)
    using(new FileOutputStream(saveFile)) { fileOut =>
      using(new ObjectOutputStream(fileOut)) { out =>
        out.writeObject(model)
      }
    }

    val model2: CNTKFunction = using(new FileInputStream(saveFile)){fileIn =>
      using(new ObjectInputStream(fileIn)){in =>
        in.readObject().asInstanceOf[CNTKFunction]
      }
    }.get.get

    val out1 = evaluateRandomMinibatch(model, 5)
    val out2 = evaluateRandomMinibatch(model2, 5)
    assert(out1 === out2)
  }

  test("Evaluate should be able be called twice") {
    val model = CNTKFunction.load(modelPath, DeviceDescriptor.useDefaultDevice)
    evaluateRandomMinibatch(model, 2)
    evaluateRandomMinibatch(model, 2, seed=1)
  }

  test("Evaluate should be able to change batch size ") {
    val model = CNTKFunction.load(modelPath, DeviceDescriptor.useDefaultDevice)
    evaluateRandomMinibatch(model, 1)
    evaluateRandomMinibatch(model, 3)
    evaluateRandomMinibatch(model, 2)
  }

}
