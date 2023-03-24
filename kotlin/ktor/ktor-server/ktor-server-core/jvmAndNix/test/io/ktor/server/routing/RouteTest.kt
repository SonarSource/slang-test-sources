/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.tests.routing

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.*

class RouteTest {

    @Test
    fun testToStringSimple() {
        val root = Route(parent = null, selector = PathSegmentConstantRouteSelector("root"))
        val simpleChild = Route(parent = root, selector = PathSegmentConstantRouteSelector("simpleChild"))
        val simpleGrandChild =
            Route(parent = simpleChild, selector = PathSegmentConstantRouteSelector("simpleGrandChild"))

        val slashChild = Route(parent = root, selector = TrailingSlashRouteSelector)
        val slashGrandChild = Route(parent = slashChild, selector = TrailingSlashRouteSelector)
        val simpleChildInSlash = Route(parent = slashGrandChild, PathSegmentConstantRouteSelector("simpleChildInSlash"))
        val slashChildInSimpleChild = Route(parent = simpleChildInSlash, TrailingSlashRouteSelector)

        assertEquals("/root", root.toString())
        assertEquals("/root/simpleChild", simpleChild.toString())
        assertEquals("/root/simpleChild/simpleGrandChild", simpleGrandChild.toString())
        assertEquals("/root/", slashChild.toString())
        assertEquals("/root/", slashGrandChild.toString())
        assertEquals("/root/simpleChildInSlash", simpleChildInSlash.toString())
        assertEquals("/root/simpleChildInSlash/", slashChildInSimpleChild.toString())
    }

    @Test
    fun testCreateChildKeepsDevelopmentMode() {
        val root = Route(parent = null, selector = PathSegmentConstantRouteSelector("root"), developmentMode = true)
        val simpleChild = root.createChild(PathSegmentConstantRouteSelector("simpleChild"))
        assertTrue(root.developmentMode)
        assertTrue(simpleChild.developmentMode)
    }

    @Test
    fun testGetAllRoutes() = testApplication {
        application {
            val root = routing {
                route("/shop") {
                    route("/customer") {
                        get("/{id}") {
                            call.respondText("OK")
                        }
                        post("/new") { }
                    }

                    route("/order") {
                        route("/shipment") {
                            get { }
                            post {
                                call.respondText("OK")
                            }
                            put {
                                call.respondText("OK")
                            }
                        }
                    }
                }

                route("/info", HttpMethod.Get) {
                    post("new") {}

                    handle {
                        call.respondText("OK")
                    }
                }
            }

            val endpoints = root.getAllRoutes()
            assertTrue { endpoints.size == 7 }
            val expected = setOf(
                "/shop/customer/{id}/(method:GET)",
                "/shop/customer/new/(method:POST)",
                "/shop/order/shipment/(method:GET)",
                "/shop/order/shipment/(method:PUT)",
                "/shop/order/shipment/(method:POST)",
                "/info/(method:GET)",
                "/info/(method:GET)/new/(method:POST)"
            )
            assertEquals(expected, endpoints.map { it.toString() }.toSet())
        }
    }
}
