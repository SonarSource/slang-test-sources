// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark

import org.apache.spark.ml.param.{DoubleParam, IntParam, Param}
import org.apache.spark.ml.util.DefaultParamsWritable

/** Defines common parameters across all LightGBM learners.
  */
trait LightGBMParams extends Wrappable with DefaultParamsWritable {
  val parallelism = new Param[String](this, "parallelism",
    "Tree learner parallelism, can be set to data_parallel or voting_parallel")
  setDefault(parallelism->"data_parallel")

  def getParallelism: String = $(parallelism)
  def setParallelism(value: String): this.type = set(parallelism, value)

  val defaultListenPort = new IntParam(this, "defaultListenPort",
    "The default listen port on executors, used for testing")

  def getDefaultListenPort: Int = $(defaultListenPort)
  def setDefaultListenPort(value: Int): this.type = set(defaultListenPort, value)

  setDefault(defaultListenPort -> LightGBMConstants.defaultLocalListenPort)

  val numIterations = new IntParam(this, "numIterations",
    "Number of iterations, LightGBM constructs num_class * num_iterations trees")
  setDefault(numIterations->100)

  def getNumIterations: Int = $(numIterations)
  def setNumIterations(value: Int): this.type = set(numIterations, value)

  val learningRate = new DoubleParam(this, "learningRate", "Learning rate or shrinkage rate")
  setDefault(learningRate -> 0.1)

  def getLearningRate: Double = $(learningRate)
  def setLearningRate(value: Double): this.type = set(learningRate, value)

  val numLeaves = new IntParam(this, "numLeaves", "Number of leaves")
  setDefault(numLeaves -> 31)

  def getNumLeaves: Int = $(numLeaves)
  def setNumLeaves(value: Int): this.type = set(numLeaves, value)

  val objective = new Param[String](this, "objective",
    "The Objective. For regression applications, this can be: " +
    "regression_l2, regression_l1, huber, fair, poisson, quantile, mape, gamma or tweedie. " +
    "For classification applications, this can be: binary, multiclass, or multiclassova. ")
  setDefault(objective -> "regression")

  def getObjective: String = $(objective)
  def setObjective(value: String): this.type = set(objective, value)

  val maxBin = new IntParam(this, "maxBin", "Max bin")
  setDefault(maxBin -> 255)

  def getMaxBin: Int = $(maxBin)
  def setMaxBin(value: Int): this.type = set(maxBin, value)

  val baggingFraction = new DoubleParam(this, "baggingFraction", "Bagging fraction")
  setDefault(baggingFraction->1)

  def getBaggingFraction: Double = $(baggingFraction)
  def setBaggingFraction(value: Double): this.type = set(baggingFraction, value)

  val baggingFreq = new IntParam(this, "baggingFreq", "Bagging frequency")
  setDefault(baggingFreq->0)

  def getBaggingFreq: Int = $(baggingFreq)
  def setBaggingFreq(value: Int): this.type = set(baggingFreq, value)

  val baggingSeed = new IntParam(this, "baggingSeed", "Bagging seed")
  setDefault(baggingSeed->3)

  def getBaggingSeed: Int = $(baggingSeed)
  def setBaggingSeed(value: Int): this.type = set(baggingSeed, value)

  val earlyStoppingRound = new IntParam(this, "earlyStoppingRound", "Early stopping round")
  setDefault(earlyStoppingRound -> 0)

  def getEarlyStoppingRound: Int = $(earlyStoppingRound)
  def setEarlyStoppingRound(value: Int): this.type = set(earlyStoppingRound, value)

  val featureFraction = new DoubleParam(this, "featureFraction", "Feature fraction")
  setDefault(featureFraction->1)

  def getFeatureFraction: Double = $(featureFraction)
  def setFeatureFraction(value: Double): this.type = set(featureFraction, value)

  val maxDepth = new IntParam(this, "maxDepth", "Max depth")
  setDefault(maxDepth-> -1)

  def getMaxDepth: Int = $(maxDepth)
  def setMaxDepth(value: Int): this.type = set(maxDepth, value)

  val minSumHessianInLeaf = new DoubleParam(this, "minSumHessianInLeaf", "Minimal sum hessian in one leaf")
  setDefault(minSumHessianInLeaf -> 1e-3)

  def getMinSumHessianInLeaf: Double = $(minSumHessianInLeaf)
  def setMinSumHessianInLeaf(value: Double): this.type = set(minSumHessianInLeaf, value)

  val timeout = new DoubleParam(this, "timeout", "Timeout in seconds")
  setDefault(timeout->120)

  def getTimeout: Double = $(timeout)
  def setTimeout(value: Double): this.type = set(timeout, value)

  val modelString = new Param[String](this, "modelString", "LightGBM model to retrain")
  setDefault(modelString -> "")

  def getModelString: String = $(modelString)
  def setModelString(value: String): this.type = set(modelString, value)
}
