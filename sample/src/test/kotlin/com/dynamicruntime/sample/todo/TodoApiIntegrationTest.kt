package com.dynamicruntime.sample.todo

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration test for the Todo endpoints. Instead of a separate HTTP server, it drives the real request
 * pipeline in-process via [TestHttpClient] (which routes through [com.dynamicruntime.common.http.request.RequestHandler]
 * exactly as a live request would, including input coercion and output-schema validation). Each case boots
 * its own instance -- and therefore a fresh in-memory repository seeded with the two default todos -- so the
 * cases are independent.
 */
class TodoApiIntegrationTest : StringSpec({
    val counter = AtomicInteger(0)

    // Registers the sample component (idempotent, VM-global) and boots a brand-new instance so its
    // TodoService/repository start fresh. mkTestBootCxt turns on output-schema validation, so a
    // non-conforming endpoint response would fail these tests.
    fun freshClient(): TestHttpClient {
        InstanceRegistry.register(listOf(SampleComponent()))
        val cxt = Startup.mkTestBootCxt("test", "sample-test-${counter.incrementAndGet()}")
        return TestHttpClient(cxt.instanceConfig)
    }

    fun Map<String, Any?>.int(key: String): Int = (this[key] as Number).toInt()
    fun Map<String, Any?>.items(): List<Map<String, Any?>> = (this["items"] as List<*>).map { it!!.toJsonMap() }
    fun Map<String, Any?>.results(): Map<String, Any?> = this["results"]!!.toJsonMap()

    "GET /todo/list returns the seed todos under `items`" {
        val client = freshClient()
        val titles = client.sendJsonGetRequest("/todo/list").items().map { it["title"] }
        titles shouldBe listOf("Try the KDR endpoint framework", "Render it with Ant Design and React")
    }

    "POST /todo/add creates a todo and it appears in the list" {
        val client = freshClient()
        val created = client.sendJsonPostRequest("/todo/add", mapOf("title" to "Buy milk")).results()
        created["title"] shouldBe "Buy milk"
        created["completed"] shouldBe false

        val titles = client.sendJsonGetRequest("/todo/list").items().map { it["title"] }
        titles shouldContain "Buy milk"
    }

    "POST /todo/add with a blank title is rejected with 400" {
        val client = freshClient()
        val resp = client.sendJsonPostRequest("/todo/add", mapOf("title" to "   "))
        resp.int(EP.status) shouldBe EXC.badInput
    }

    "POST /todo/update edits title and completion" {
        val client = freshClient()
        val updated = client.sendJsonPostRequest(
            "/todo/update",
            mapOf("id" to 1, "title" to "Renamed", "completed" to true),
        ).results()

        updated.int("id") shouldBe 1
        updated["title"] shouldBe "Renamed"
        updated["completed"] shouldBe true
    }

    "POST /todo/update of a missing id returns 404" {
        val client = freshClient()
        val resp = client.sendJsonPostRequest("/todo/update", mapOf("id" to 9999, "completed" to true))
        resp.int(EP.status) shouldBe EXC.notFound
    }

    "POST /todo/delete removes a todo; a second delete is 404" {
        val client = freshClient()

        client.sendJsonPostRequest("/todo/delete", mapOf("id" to 1)).results()["deleted"] shouldBe true
        client.sendJsonPostRequest("/todo/delete", mapOf("id" to 1)).int(EP.status) shouldBe EXC.notFound

        // Id 1 is gone; only the second seed remains.
        val ids = client.sendJsonGetRequest("/todo/list").items().map { it.int("id") }
        ids shouldBe listOf(2)
    }
})
