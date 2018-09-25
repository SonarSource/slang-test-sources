// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark

import java.io.File
import java.net.URI

import com.jcraft.jsch.{JSch, Session}
import org.apache.commons.io.IOUtils

object PortForwarding {

  lazy val jsch = new JSch()

  def forwardPortToRemote(username: String,
                          sshHost: String,
                          sshPort: Int,
                          remotePortStart: Int,
                          localHost: String,
                          localPort: Int,
                          keyDir: Option[String],
                          keySas: Option[String],
                          maxRetries: Int
                         ): (Session, Int) = {
    keyDir.foreach(kd =>
      new File(kd).listFiles().foreach(f =>
        try {jsch.addIdentity(f.getAbsolutePath)} catch {
          case e:com.jcraft.jsch.JSchException =>
          case e: Exception => throw e
        }
      )
    )

    keySas.foreach { ks =>
      val privateKeyBytes = IOUtils.toByteArray(new URI(ks))
      jsch.addIdentity("forwardingKey", privateKeyBytes, null, null)
    }

    val session = jsch.getSession(username, sshHost, sshPort)
    session.setConfig("StrictHostKeyChecking", "no")
    session.connect()
    var attempt = 0
    var foundPort: Option[Int] = None
    while (foundPort.isEmpty && attempt <= maxRetries) {
      try {
        session.setPortForwardingR(remotePortStart + attempt,
          localHost, localPort)
        foundPort = Some(remotePortStart + attempt)
      } catch {
        case e: Exception =>
          attempt += 1
      }
    }
    if (foundPort.isEmpty) {
      throw new RuntimeException(s"Could not find open port between " +
        s"$remotePortStart and ${remotePortStart + maxRetries}")
    }
    println(s"forwarding to ${foundPort.get}")
    (session, foundPort.get)
  }

  def forwardPortToRemote(options: Map[String, String]): (Session, Int) = {
    forwardPortToRemote(
      options("forwarding.username"),
      options("forwarding.sshhost"),
      options.getOrElse("forwarding.sshport", "22").toInt,
      options.get("forwarding.remoteportstart")
        .orElse(options.get("forwarding.localport")).get.toInt,
      options.getOrElse("forwarding.localhost", "0.0.0.0"),
      options("forwarding.localport").toInt,
      options.get("forwarding.keydir"),
      options.get("forwarding.keysas"),
      options.getOrElse("forwarding.maxretires", "50").toInt
    )
  }

}
