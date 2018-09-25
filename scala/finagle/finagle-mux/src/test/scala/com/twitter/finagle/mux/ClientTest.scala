package com.twitter.finagle.mux

import com.twitter.finagle.Mux
import com.twitter.finagle.filter.NackAdmissionFilter
import org.scalatest.FunSuite

class ClientTest extends FunSuite {
  test("client stack includes exactly one NackAdmissionFilter") {
    val client = Mux.client
    val stack = client.stack

    assert(stack.tails.count(_.head.role == NackAdmissionFilter.role) == 1)
  }
}
