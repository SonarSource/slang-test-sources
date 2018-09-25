// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark

import com.microsoft.ml.spark.schema.{CategoricalColumnInfo, CategoricalUtilities, SparkSchema}
import org.apache.spark.ml.util.MLReadable
import org.apache.spark.sql.Row

import scala.collection.immutable.Seq

trait ValueIndexerUtilities extends TestBase {
  import session.implicits._

  /** sample dataframe */
  protected val DF = Seq[(Int, Long, Double, Boolean, String)](
    (-3, 24L, 0.32534, true, "piano"),
    (1, 5L, 5.67, false, "piano"),
    (-3, 5L, 0.32534, false, "guitar"))
    .toDF("int", "long", "double", "bool", "string")

  /** sample dataframe with Null values*/
  protected val nullDF = Seq[(String, java.lang.Integer, java.lang.Double)](
    ("Alice", null, 44.3),
    (null, 60, null),
    ("Josh", 25, Double.NaN),
    ("Bob", 25, 77.7),
    ("Fred", 55, Double.NaN),
    ("Josh", 21, 33.3))
    .toDF("string", "int", "double")
}

/** Tests to validate the functionality of Train Classifier module. */
class VerifyIndexToValue extends ValueIndexerUtilities with TransformerFuzzing[IndexToValue] {
  val col = "string"
  private val newName = col + "_cat"
  private val df = new ValueIndexer().setInputCol(col).setOutputCol(newName).fit(DF).transform(DF)
  private val testName = col + "_noncat"

  test("Test: Going to Categorical and Back") {
    for (mmlStyle <- List(false, true)) {
      val df1 = new IndexToValue().setInputCol(newName).setOutputCol(testName).transform(df)
      df1.select(col, testName).collect.foreach(row => assert(row(0) == row(1), "two columns should be the same"))
    }
  }

  override def testObjects(): scala.Seq[TestObject[IndexToValue]] = Seq(new TestObject(
    new IndexToValue().setInputCol(newName).setOutputCol(testName), df
  ))

  override def reader: MLReadable[_] = IndexToValue
}

/** Tests to validate the functionality of Train Classifier module. */
class VerifyValueIndexer extends ValueIndexerUtilities with EstimatorFuzzing[ValueIndexer] {

  /** test CategoricalMap for different undelying types */
  test("Test: Convert the regular column into categorical") {
    for (col <- DF.columns) {
      val newName = col + "_cat"
      val df      = new ValueIndexer().setInputCol(col).setOutputCol(newName).fit(DF).transform(DF)

      assert(!SparkSchema.isCategorical(df, col), "Check for non-categorical columns")
      assert(SparkSchema.isCategorical(df, newName), "Check for categorical columns")

      val info = new CategoricalColumnInfo(df, newName)

      assert(info.isCategorical, "the column is supposed to be categorical")
      assert(info.isMML, "wrong metadata style in categorical column")
      assert(!info.isOrdinal, "wrong ordinal style in categorical column")
      assert(info.dataType == DF.schema(col).dataType, "categorical data type is not correct")
    }
  }

  test("Test: String categorical levels") {
    val col = "string"
    val true_levels = DF.select("string").collect().map(_(0).toString).distinct.sorted

    for (mmlStyle <- List(false, true)) {
      val newName = col + "_cat"
      val df = new ValueIndexer().setInputCol(col).setOutputCol(newName).fit(DF).transform(DF)

      val map = CategoricalUtilities.getMap[String](df.schema(newName).metadata)

      val levels = map.levels.sorted

      (true_levels zip levels).foreach {
        case (a, b) => assert(a == b, "categorical levels are not the same")
      }
    }
  }

  ignore("test with null or missing values, going to categorical and back") {
    for (col <- nullDF.columns) {
      val newName = col + "_cat"
      val df = new ValueIndexer().setInputCol(col).setOutputCol(newName).fit(nullDF).transform(nullDF)

      assert(!SparkSchema.isCategorical(df, col), "Check for non-categorical columns")
      assert(SparkSchema.isCategorical(df, newName), "Check for categorical columns")

      val info = new CategoricalColumnInfo(df, newName)

      assert(info.isCategorical, "the column is supposed to be categorical")
      assert(info.isMML, "wrong metadata style in categorical column")
      assert(!info.isOrdinal, "wrong ordinal style in categorical column")
      assert(info.dataType == nullDF.schema(col).dataType, "categorical data type is not correct")

      val testName = col + "_noncat"
      val df1 = new IndexToValue().setInputCol(newName).setOutputCol(testName).transform(df)
      def validationFunc: Row => Boolean =
        if (col == "double") {
          row: Row => {
            // Double NaNs are treated similar to nulls, as missing values (consistent with Spark)
            val actualValue = if (row(0).asInstanceOf[Double].isNaN) null else row(0)
            val expectedValue = row(1)
            actualValue == expectedValue
          }
        } else {
          row: Row => row(0) == row(1)
        }
      val results = df1.select(col, testName).collect
      results.foreach(row => assert(validationFunc(row), "two columns should be the same"))
    }
  }

  val inputCol = "text"

  override def testObjects(): Seq[TestObject[ValueIndexer]] = List(new TestObject[ValueIndexer](
    new ValueIndexer().setInputCol(DF.columns.head).setOutputCol("foo"), DF))

  override def reader: MLReadable[_] = ValueIndexer
  override def modelReader: MLReadable[_] = ValueIndexerModel
}
