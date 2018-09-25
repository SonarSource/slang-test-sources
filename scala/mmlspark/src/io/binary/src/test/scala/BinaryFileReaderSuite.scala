// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark

import java.io.FileOutputStream
import java.net.URI

import com.microsoft.ml.spark.Binary.implicits._
import com.microsoft.ml.spark.FileUtilities.{File, zipFolder}
import com.microsoft.ml.spark.schema.BinaryFileSchema
import com.microsoft.ml.spark.schema.BinaryFileSchema.isBinaryFile
import org.apache.commons.io.{FileUtils, IOUtils}
import org.apache.spark.binary.BinaryFileFormat

trait FileReaderUtils {
  val fileLocation = s"${sys.env("DATASETS_HOME")}"
  val imagesDirectory: String = fileLocation + "/Images"
  val groceriesDirectory: String = imagesDirectory + "/Grocery"
  val cifarDirectory: String = imagesDirectory + "/CIFAR"

  def createZip(directory: String): Unit ={
    val dir = new File(directory)
    val zipfile = new File(directory + ".zip")
    if (!zipfile.exists()) zipFolder(dir, zipfile)
  }

  def createZips(): Unit ={
    createZip(groceriesDirectory)
    createZip(cifarDirectory)
  }

}

class BinaryFileReaderSuite extends TestBase with FileReaderUtils with DataFrameEquality {

  test("binary dataframe") {
    val data = session.readBinaryFiles(groceriesDirectory, recursive = true)
    println(time { data.count })
    assert(isBinaryFile(data, "value"))
    val paths = data.select("value.path") //make sure that SQL has access to the sub-fields
    assert(paths.count == 31)             //note that text file is also included
  }

  test("reads the right values"){
    val data = session.readBinaryFiles(groceriesDirectory, recursive = true)
      .limit(1).select("value.*")
    val data2 = BinaryFileReader.readFromPaths(data.select("path"), "path", "bytes", 2, 600000)

    val path = data.collect().head.getString(0)
    val bytes1 = data.collect().head.getAs[Array[Byte]](1)
    val bytes2 = data2.collect().head.getAs[Array[Byte]](1)
    val trueBytes = IOUtils.toByteArray(new URI(path))
    assert(bytes1 === trueBytes)
    assert(bytes2 === trueBytes)
  }

  test("read from paths yields same values") {
    val data = session.readBinaryFiles(groceriesDirectory, recursive = true).limit(1)
    val df2 = BinaryFileReader.readFromPaths(data.select("value.path"), "path", "bytes", 2, 600000)
    assert(df2 === data.select("value.*"))
  }

  test("sample ratio test") {
    val all = session.readBinaryFiles(groceriesDirectory, recursive = true, sampleRatio = 1.0)
    val sampled = session.readBinaryFiles(groceriesDirectory, recursive = true, sampleRatio = 0.5)
    val count = sampled.count
    assert(count > 0 && count < all.count, "incorrect sampling behavior")
  }

  test("with zip file") {
    /* remove when datasets/Images is updated */
    createZips()

    val images = session.readBinaryFiles(imagesDirectory, recursive = true)
    assert(images.count == 74)

    val images1 = session.readBinaryFiles(imagesDirectory, recursive = true, inspectZip = false)
    assert(images1.count == 39)
  }

  test("handle folders with spaces") {
    /* remove when datasets/Images is updated */
    val newDirTop = new File(tmpDir.toFile, "foo bar")
    val newDirMid = new File(newDirTop, "fooey barey")
    FileUtils.forceMkdir(newDirMid)
    try {
      val fos = new FileOutputStream(new File(newDirMid, "foo.txt"))
      try {
        fos.write((1 to 10).map(_.toByte).toArray)
      } finally {
        fos.close()
      }
      val files = session.readBinaryFiles(newDirTop.getAbsolutePath, recursive = true)
      assert(files.count == 1)
    } finally {
      FileUtils.forceDelete(newDirTop)
    }
  }

  test("binary files should allow recursion") {
    val df = session
      .read
      .format(classOf[BinaryFileFormat].getName)
      .load(groceriesDirectory + "**/*")
    assert(df.count()==31)
    df.printSchema()
  }

  test("static load with new reader") {
    val df = session
      .read
      .format(classOf[BinaryFileFormat].getName)
      .option("subsample", .5)
      .load(cifarDirectory)
    assert(df.count()==3)
  }

  test("structured streaming with binary files") {
    val imageDF = session
      .readStream
      .format(classOf[BinaryFileFormat].getName)
      .schema(BinaryFileSchema.schema)
      .load(cifarDirectory)

    val q1 = imageDF.writeStream
      .format("memory")
      .queryName("images")
      .start()

    tryWithRetries(){ () =>
      val df = session.sql("select * from images")
      assert(df.count() == 6)
    }
    q1.stop()
  }

}
