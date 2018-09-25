// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark

import org.apache.spark.ml.feature.{StringIndexer, Tokenizer}
import com.microsoft.ml.spark.schema.DatasetExtensions._
import org.apache.spark.ml.PipelineModel
import org.apache.spark.ml.util.MLReadable
import scala.collection.mutable

class MultiColumnAdapterSpec extends TestBase with EstimatorFuzzing[MultiColumnAdapter] {

  val wordDF = session.createDataFrame(Seq(
    (0, "This is a test", "this is one too"),
    (1, "could be a test", "bar"),
    (2, "foo", "bar"),
    (3, "foo", "maybe not")))
    .toDF("label", "words1", "words2")
  val inputCols  = Array[String]("words1",  "words2")
  val outputCols = Array[String]("output1", "output2")
  val stage = new StringIndexer()
  val adaptedEstimator =
    new MultiColumnAdapter().setBaseStage(stage)
          .setInputCols(inputCols).setOutputCols(outputCols)

  test("parallelize transformers") {
    val stage1 = new Tokenizer()
    val transformer =
      new MultiColumnAdapter().setBaseStage(stage1)
            .setInputCols(inputCols).setOutputCols(outputCols)
    val tokenizedDF = transformer.fit(wordDF).transform(wordDF)
    val lines = tokenizedDF.getColAs[Array[String]]("output2")
    val trueLines = Array(
      Array("this", "is", "one", "too"),
      Array("bar"),
      Array("bar"),
      Array("maybe", "not")
    )
    assert(lines === trueLines)
  }

  test("parallelize estimator") {
    val stringIndexedDF = adaptedEstimator.fit(wordDF).transform(wordDF)
    val lines1 = stringIndexedDF.getColAs[Array[String]]("output1")
    val trueLines1 = mutable.ArraySeq(1, 2, 0, 0)
    assert(lines1 === trueLines1)

    val lines2 = stringIndexedDF.getColAs[Array[String]]("output2")
    val trueLines2 = mutable.ArraySeq(1, 0, 0, 2)
    assert(lines2 === trueLines2)
  }
  def testObjects(): Seq[TestObject[MultiColumnAdapter]] = List(new TestObject(adaptedEstimator, wordDF))

  override def reader: MLReadable[_] = MultiColumnAdapter

  override def modelReader: MLReadable[_] = PipelineModel

}
