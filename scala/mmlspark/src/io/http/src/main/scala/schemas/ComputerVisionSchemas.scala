// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark.cognitive

// OCR Schema

import com.microsoft.ml.spark.schema.{SparkBindings}
import org.apache.spark.sql.Row
import spray.json.RootJsonFormat

case class DSIRResponse(requestId: String,
                        metadata: DSIRMetadata,
                        result: DSIRResult)

object DSIRResponse extends SparkBindings[DSIRResponse]

case class DSIRMetadata(width: Int, height: Int, format: String)

case class DSIRResult(celebrities: Option[Seq[DSIRCelebrity]],
                      landmarks: Option[Seq[DSIRLandmark]])

case class DSIRLandmark(name: String, confidence: Double)

case class DSIRCelebrity(name: String, faceRectangle: Rectangle, confidence: Double)

case class OCRResponse(language: String,
                       textAngle: Option[Double],
                       orientation: String,
                       regions: Seq[OCRRegion])

object OCRResponse extends SparkBindings[OCRResponse]

case class OCRRegion(boundingBox: String, lines: Seq[OCRLine])

case class OCRLine(boundingBox: String, words: Seq[OCRWord])

case class OCRWord(boundingBox: String, text: String)

case class RTResponse(status: String, recognitionResult: RTResult)

object RTResponse extends SparkBindings[RTResponse]

case class RTResult(lines: Array[RTLine])

case class RTLine(boundingBox: Array[Int], text: String, words: Array[RTWord])

case class RTWord(boundingBox: Array[Int], text: String)

case class AIResponse(categories: Option[Seq[AICategory]],
                      adult: Option[AIAdult],
                      tags: Option[Seq[AITag]],
                      description: Option[AIDescription],
                      requestId: String,
                      metadata: AIMetadata,
                      faces: Option[Seq[AIFace]],
                      color: Option[AIColor],
                      imageType: Option[AIImageType])

object AIResponse extends SparkBindings[AIResponse]

case class AICategory(name: String, score: Double, detail: Option[AIDetail])

case class AIDetail(celebrities: Option[Seq[DSIRCelebrity]],
                    landmarks: Option[Seq[DSIRLandmark]])

case class AIAdult(isAdultContent: Boolean,
                   isRacyContent: Boolean,
                   adultScore: Double,
                   racyScore: Double)

case class AITag(name: String, confidence: Double)

case class AICaption(text: String, confidence: Double)

case class AIDescription(tags: Seq[String], captions: Seq[AICaption])

case class AIMetadata(width: Int, height: Int, format: String)

case class AIFace(age: Int, gender: String, faceRectangle: Rectangle)

case class AIColor(dominantColorForeground: String,
                   dominantColorBackground: String,
                   dominantColors: Seq[String],
                   accentColor: String,
                   isBwImg: Option[Boolean])

case class AIImageType(clipArtType: Int, lineDrawingType: Int)
