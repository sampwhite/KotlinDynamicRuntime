package com.dynamicruntime.kdn

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.simulation.DesignDemo
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.test.SIM
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * Simulations (issue #997): the `design-demo` simulation provisions what its report says -- a client defined in data
 * and users to sign in as -- a suffix makes an independent copy, a rerun is safe, and the listing offers it to a caller
 * without env auth.
 */
class SimulationTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("simulation997", "simulation997")
    // An ordinary signed-in user, no env auth: whoever is testing the instance.
    val tester = TestUser.create(cxt, "tester@simulation997.test")
    val designDemo = SIM.pathRoot + DesignDemo.simulationName

    fun usersOf(report: Map<String, Any?>) = report[SIM.users].toJsonListOfMaps()

    "design-demo provisions the client and the users its report lists" {
        val report = tester.postData(designDemo, emptyMap())
        report[SIM.clients].toJsonListOfStrings() shouldBe listOf(DesignDemo.client)
        report[SIM.startPage] shouldNotBe null
        val designer = usersOf(report).single { it[SIM.level] == ROLE.admin }
        designer[SIM.client] shouldBe DesignDemo.client

        // Signing in as the reported designer reaches the client's workflow page in Design View.
        val signedIn = TestUser.create(cxt, designer[SIM.email] as String, level = ROLE.admin, userClient = DesignDemo.client)
        val view = signedIn.getData(clientPath(GEP.workflowView, DesignDemo.client), mapOf(EP.view to DSV.design))
        view.containsKey(DSV.designBlock) shouldBe true
    }

    "a rerun is safe: the same client, the same users" {
        val first = usersOf(tester.postData(designDemo, emptyMap())).map { it[SIM.email] }
        val second = usersOf(tester.postData(designDemo, emptyMap())).map { it[SIM.email] }
        second shouldBe first
    }

    "a suffix makes an independent copy, and a bad one is refused" {
        val report = tester.postData(designDemo, mapOf(SIM.suffix to "two"))
        report[SIM.clients].toJsonListOfStrings() shouldBe listOf("${DesignDemo.client}two")
        // Its types take its own namespace, so the copy shares no definition with the demo client.
        val schema = SchemaService.get(cxt)
        schema.storeFor("${DesignDemo.client}two").defs.keys shouldContain "client.${DesignDemo.client}two.${DesignDemo.contactType}"
        schema.storeFor(DesignDemo.client).defs.keys shouldContain "client.${DesignDemo.client}.${DesignDemo.contactType}"

        tester.expectError(400, designDemo, mapOf(SIM.suffix to "Two!")).toString() shouldContain "lowercase letters and digits"
    }

    "the listing offers the simulations to a caller without env auth, in the catalog's shape" {
        val listed = tester.getData(SIM.list)[EI.endpoints].toJsonListOfMaps()
        listed.map { it[EI.path] } shouldContain designDemo
        // Only simulations: everything listed carries the tag.
        listed.all { SIM.tag in it[EI.tags].toJsonListOfStrings() } shouldBe true
        // The ordinary catalog, by contrast, confines this caller to the published API.
        tester.getData("/schema/endpoints").toJsonMapOrEmpty()[EI.endpoints].toJsonListOfMaps()
            .none { it[EI.path] == designDemo } shouldBe true
    }
})
