// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark

import java.util.UUID
import java.util.concurrent.TimeUnit

import com.microsoft.ml.spark.FileUtilities.File
import com.microsoft.ml.spark.ServingImplicits._
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.spark.sql.execution.streaming.continuous._
import org.apache.spark.sql.execution.streaming.{HTTPSinkProvider, HTTPSourceProvider}
import org.apache.spark.sql.functions.{col, length}
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.types.BinaryType

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class ContinuousHTTPSuite extends TestBase with HTTPTestUtils {

  test("continuous mode"){
    val server = session
      .readStream
      .continuousServer
      .address(host, port, apiName)
      .load()
      .withColumn("foo", col("id.requestId"))
      .makeReply("foo")
      .writeStream
      .continuousServer
      .option("name", apiName)
      .queryName("foo").option("checkpointLocation",
        new File(tmpDir.toFile, s"checkpoints-${UUID.randomUUID()}").toString)
      .trigger(Trigger.Continuous("1 second"))  // only change in query
      .start()

    Thread.sleep(5000)

    val client = HttpClientBuilder.create().build()

    val responsesWithLatencies = (1 to 100).map( i =>
      sendStringRequest(client)
    )

    val latencies = responsesWithLatencies.drop(3).map(_._2.toInt).toList
    val meanLatency = mean(latencies)
    val stdLatency = stddev(latencies, meanLatency)
    println(s"Latency = $meanLatency +/- $stdLatency")
    assert(meanLatency < 5)

    println(HTTPSourceStateHolder.serviceInfoJson(apiName))
    println("stopping server")
    server.stop()
  }

  test("continuous mode with files"){
    val server = session
      .readStream
      .continuousServer
      .address(host, port, apiName)
      .load()
      .parseRequest(BinaryType)
      .withColumn("length", length(col("bytes")))
      .makeReply("length")
      .writeStream
      .continuousServer
      .option("name", apiName)
      .queryName("foo").option("checkpointLocation",
      new File(tmpDir.toFile, s"checkpoints-${UUID.randomUUID()}").toString)
      .trigger(Trigger.Continuous("1 second"))  // only change in query
      .start()

    Thread.sleep(5000)

    val client = HttpClientBuilder.create().build()

    val responsesWithLatencies = (1 to 10).map( i =>
      sendFileRequest(client)
    )

    val latencies = responsesWithLatencies.drop(3).map(_._2.toInt).toList
    val meanLatency = mean(latencies)
    val stdLatency = stddev(latencies, meanLatency)
    println(s"Latency = $meanLatency +/- $stdLatency")
    println("stopping server")
    server.stop()
  }

  ignore("forwarding ports to vm"){
    val server = session
      .readStream
      .continuousServer
      .address(host, 9010, apiName)
      .option("forwarding.enabled", true)
      .option("forwarding.username", "marhamil")
      .option("forwarding.sshHost", "104.208.238.80")
      //.option("forwarding.keySas","")
      //.option("forwarding.keyDir", "/home/marhamil/.ssh")
      .load()
      .withColumn("foo", col("id.requestId"))
      .makeReply("foo")
      .writeStream
      .continuousServer
      .option("name", apiName)
      .queryName("foo").option("checkpointLocation",
      new File(tmpDir.toFile, s"checkpoints-${UUID.randomUUID()}").toString)
      .trigger(Trigger.Continuous("1 second"))
      .start()

    Thread.sleep(100000)
    println("stopping server")
    server.stop()
  }

  test("async"){
    val server = session
      .readStream
      .continuousServer
      .address(host, port, apiName)
      .load()
      .withColumn("foo", col("id.requestId"))
      .makeReply("foo")
      .writeStream
      .continuousServer
      .option("name", apiName)
      .queryName("foo").option("checkpointLocation",
      new File(tmpDir.toFile, s"checkpoints-${UUID.randomUUID()}").toString)
      .trigger(Trigger.Continuous("1 second"))  // only change in query
      .start()

    Thread.sleep(5000)

    val client = HttpClientBuilder.create().build()

    val futures = (1 to 10).map( i =>
      sendStringRequestAsync(client)
    )

    futures.foreach { f =>
      val resp = Await.result(f, Duration(5, TimeUnit.SECONDS))
      println(resp)
    }

    println("stopping server")
    server.stop()
  }

  test("non continuous mode"){
    val server = session
      .readStream
      .format(classOf[HTTPSourceProvider].getName)
      .option("host", host)
      .option("port", port.toString)
      .option("name", apiName)
      .load()
      .withColumn("foo", col("id"))
      .makeReply("foo")
      .writeStream
      .format(classOf[HTTPSinkProvider].getName)
      .option("name", apiName)
      .queryName("foo").option("checkpointLocation",
        new File(tmpDir.toFile, s"checkpoints-${UUID.randomUUID()}").toString)
      .start()

    Thread.sleep(5000)

    val client = HttpClientBuilder.create().build()

    val responsesWithLatencies = (1 to 100).map( i =>
      sendStringRequest(client)
    )

    val latencies = responsesWithLatencies.drop(3).map(_._2.toInt).toList
    val meanLatency = mean(latencies)
    val stdLatency = stddev(latencies, meanLatency)
    println(s"Latency = $meanLatency +/- $stdLatency")

    println("stopping server")
    server.stop()
  }

}
