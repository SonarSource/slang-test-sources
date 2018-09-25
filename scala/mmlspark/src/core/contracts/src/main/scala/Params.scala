// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark

import org.apache.spark.ml.param._
import org.apache.spark.ml.util.DefaultParamsWritable

//Trait used to opt into code generation
trait Wrappable extends Params

trait HasInputCol extends Wrappable {
  /** The name of the input column
    * @group param
    */
  val inputCol = new Param[String](this, "inputCol", "The name of the input column")
  /** @group setParam */
  def setInputCol(value: String): this.type = set(inputCol, value)
  /** @group getParam */
  def getInputCol: String = $(inputCol)
}

trait HasOutputCol extends Wrappable {
  /** The name of the output column
    * @group param
    */
  val outputCol = new Param[String](this, "outputCol", "The name of the output column")
  /** @group setParam */
  def setOutputCol(value: String): this.type = set(outputCol, value)
  /** @group getParam */
  def getOutputCol: String = $(outputCol)
}

trait HasInputCols extends Wrappable {
  /** The names of the inputColumns
    * @group param
    */
  val inputCols = new StringArrayParam(this, "inputCols", "The names of the input columns")
  /** @group setParam */
  def setInputCols(value: Array[String]): this.type = set(inputCols, value)
  /** @group getParam */
  def getInputCols: Array[String] = $(inputCols)
}

trait HasOutputCols extends Wrappable {
  /** The names of the output columns
    * @group param
    */
  val outputCols = new StringArrayParam(this, "outputCols", "The names of the output columns")
  /** @group setParam */
  def setOutputCols(value: Array[String]): this.type = set(outputCols, value)
  /** @group getParam */
  def getOutputCols: Array[String] = $(outputCols)
}

trait HasLabelCol extends Wrappable {
  /** The name of the label column
    * @group param
    */
  val labelCol = new Param[String](this, "labelCol", "The name of the label column")
  /** @group setParam */
  def setLabelCol(value: String): this.type = set(labelCol, value)
  /** @group getParam */
  def getLabelCol: String = $(labelCol)
}

trait HasFeaturesCol extends Wrappable {
  /** The name of the features column
    * @group param
    */
  val featuresCol = new Param[String](this, "featuresCol", "The name of the features column")
  /** @group setParam */
  def setFeaturesCol(value: String): this.type = set(featuresCol, value)
  /** @group getParam */
  def getFeaturesCol: String = $(featuresCol)
}

trait HasScoredLabelsCol extends Wrappable {
  /** The name of the scored labels column
    * @group param
    */
  val scoredLabelsCol =
    new Param[String](this, "scoredLabelsCol",
                "Scored labels column name, only required if using SparkML estimators")
  /** @group setParam */
  def setScoredLabelsCol(value: String): this.type = set(scoredLabelsCol, value)
  /** @group getParam */
  def getScoredLabelsCol: String = $(scoredLabelsCol)
}

trait HasScoresCol extends Wrappable {
  /** The name of the scores column
    * @group param
    */
  val scoresCol =
    new Param[String](this, "scoresCol",
                "Scores or raw prediction column name, only required if using SparkML estimators")
  /** @group setParam */
  def setScoresCol(value: String): this.type = set(scoresCol, value)
  /** @group getParam */
  def getScoresCol: String = $(scoresCol)
}

trait HasScoredProbabilitiesCol extends Wrappable {
  /** The name of the scored probabilities column
    * @group param
    */
  val scoredProbabilitiesCol =
    new Param[String](this, "scoredProbabilitiesCol",
                "Scored probabilities, usually calibrated from raw scores, only required if using SparkML estimators")
  /** @group setParam */
  def setScoredProbabilitiesCol(value: String): this.type = set(scoredProbabilitiesCol, value)
  /** @group getParam */
  def getScoredProbabilitiesCol: String = $(scoredProbabilitiesCol)
}

trait HasEvaluationMetric extends Wrappable {
  val evaluationMetric: Param[String] =
    new Param[String](this, "evaluationMetric", "Metric to evaluate models with")

  /** @group getParam */
  def getEvaluationMetric: String = $(evaluationMetric)

  /** @group setParam */
  def setEvaluationMetric(value: String): this.type = set(evaluationMetric, value)
}
