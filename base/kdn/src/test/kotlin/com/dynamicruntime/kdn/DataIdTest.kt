package com.dynamicruntime.kdn

import com.dynamicruntime.common.app.APP
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.node.InstanceConfigService
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeEmpty

/**
 * The data id (issue #1099): written once when a database is first initialized and read back on every boot after,
 * so it holds across restarts over one database and differs on a fresh one -- and a test instance serves it in the
 * app config, where the Simulations page keeps its recent runs against it.
 */
class DataIdTest : StringSpec({
    fun dataId(cxt: KdrCxt) = InstanceConfigService.get(cxt).dataId

    fun served(cxt: KdrCxt): Any? =
        TestHttpClient(cxt.instanceConfig).sendJsonGetRequest(APP.uiConfig)[EP.results].toJsonMapOrEmpty()[UIC.settings]
            .toJsonMapOrEmpty()[APP.dataId]

    "the data id holds across a restart over one database, and differs on another" {
        // Its own instances (issue #1075): two boots over one database -- the restart is what is under test.
        val first = Startup.mkTestBootCxt("dataIdA", "dataIdNodeA", mapOf("KDR_DB_NAME" to "dataId1099"))
        val restarted = Startup.mkTestBootCxt("dataIdB", "dataIdNodeB", mapOf("KDR_DB_NAME" to "dataId1099"))
        dataId(first).shouldNotBeEmpty()
        dataId(restarted) shouldBe dataId(first)
        // A database of its own is data of its own.
        val other = Startup.mkTestBootCxt("dataIdC", "dataIdNodeC", mapOf("KDR_DB_NAME" to "dataId1099other"))
        dataId(other) shouldNotBe dataId(first)
    }

    "a test instance serves its data id in the app config; another instance does not" {
        val cxt = TestInstances.default("dataIdServed")
        served(cxt) shouldBe dataId(cxt)
        served(TestInstances.notTestInstance("dataIdWithheld")) shouldBe null
    }
})
