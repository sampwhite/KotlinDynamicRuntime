package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.naming.clientNamespace
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Customers as the load judges them (issue #1107): who owns a set of clients, and the prefix of their ids. Strict
 * outside production for a source declaration, as every client check is; in production a customer at fault is
 * dropped, and a client whose link or id is at fault is reported and **stands** -- its link is read through
 * `ClientService.customerOf`, which answers only for a customer that is there.
 */
class CustomerCheckTest : StringSpec({
    fun cxtIn(env: String): KdrCxt = KdrCxt("customers", KdrInstanceConfig("customers-$env", env, ENV.liveSource))
    val devCxt = cxtIn(ENV.local)
    val prodCxt = cxtIn(ENV.prod)

    /** A config filed under [client], declaring it (linked to [customerId]) and the [customers] given. */
    fun config(
        client: String,
        customerId: String? = null,
        customers: List<CustomerDef> = emptyList(),
        origin: GedraConfigOrigin = GedraConfigOrigin.source,
        defineIt: Boolean = true,
        configName: String = "${client}Client",
        retired: Boolean = false,
    ): GedraConfig = gedraConfig(devCxt, configName, clientNamespace(client), client, origin) {
        customers.forEach { defineCustomer(it) }
        if (defineIt) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.customer,
                    enabledEnvironments = setOf(ENV.unit, ENV.local), customerId = customerId, retired = retired,
                ),
            )
        }
    }
    fun customer(id: String, root: String? = null) = CustomerDef(id, "Customer $id", rootClientId = root)
    fun collectorOf(vararg configs: GedraConfig) = GedraConfigCollector().apply { configs.forEach { add(devCxt, it) } }
    fun refusal(vararg configs: GedraConfig): String =
        shouldThrow<KdrException> { checkClientDefs(devCxt, collectorOf(*configs)) }.fullMessage()

    "a customer is kept with the config that declared it, and its clients' links hold" {
        val result = checkClientDefs(
            devCxt,
            collectorOf(config("acme", "acme", listOf(customer("acme", root = "acme"))), config("acme_east", "acme")),
        )
        result.issues.shouldBeEmpty()
        result.customers.keys shouldContainExactly setOf("acme")
        result.customers.getValue("acme").isSource shouldBe true
        result.clients.getValue("acme_east").customerId shouldBe "acme"
    }

    "core's own customer is declared beside hub, and hub and public belong to it" {
        val ctx = KdrCxt("core", KdrInstanceConfig("customers-core", ENV.local, ENV.liveSource))
        val result = checkClientDefs(ctx, collectorOf(*coreClients(ctx).toTypedArray()))
        result.issues.shouldBeEmpty()
        result.customers.getValue(CUST.kdr).def.rootClientId shouldBe CL.hub
        result.clients.getValue(CL.hub).customerId shouldBe CUST.kdr
        result.clients.getValue(CL.public).customerId shouldBe CUST.kdr
    }

    // --- the customer on its own ------------------------------------------------

    "a customer id is letters and digits, starting with a letter" {
        refusal(config("acme", customers = listOf(customer("ac_me")))) shouldContain "Customer id 'ac_me' declared by 'gc.cd.acme.acmeClient' holds '_'"
        refusal(config("acme", customers = listOf(customer("1acme")))) shouldContain "starts with '1'"
    }

    "a reserved id is no customer's, case aside -- kdr only as core declares it" {
        refusal(config("acme", customers = listOf(customer("Free")))) shouldContain "is the reserved 'free'"
        refusal(config("acme", customers = listOf(customer("kdr")))) shouldContain "is the reserved 'kdr'"
        refusal(config(CL.hub, customers = listOf(customer("public")))) shouldContain "is the reserved 'public'"
    }

    "a customer is declared once, case aside, and a stored one never redefines a source one" {
        refusal(config("acme", customers = listOf(customer("acme"))), config("beta", customers = listOf(customer("ACME"))))
            .shouldContain("Customer 'ACME', declared by 'gc.cd.beta.betaClient', differs from the customer 'acme' only by case")
        // The stored declaration is the second whatever its place: source declarations are taken first.
        val stored = config(CL.hub, customers = listOf(customer("acme")), origin = GedraConfigOrigin.stored, defineIt = false, configName = "customers")
        val result = checkClientDefs(prodCxt, collectorOf(stored, config("acme", "acme", listOf(customer("acme")))))
        result.customers.getValue("acme").isSource shouldBe true
        result.issues.single().message shouldContain "Customer 'acme' is defined twice; 'gc.cd.hub.customers' is the second"
    }

    "a customer's root is one of its own clients" {
        refusal(config("acme", "acme", listOf(customer("acme", root = "beta"))), config("beta"))
            .shouldContain("names the root client 'beta', which is no customer's")
        val result = checkClientDefs(prodCxt, collectorOf(config("acme", "acme", listOf(customer("acme", root = "gone")))))
        result.customers.keys shouldContainExactly setOf("acme")
        result.issues.single().degradedTo shouldBe "The customer stands, with no root counted."
    }

    // --- a client beside the customers ------------------------------------------

    "a client naming a customer that is not there is refused, and in production stands" {
        refusal(config("acme", "acme")) shouldContain "Client 'acme' names the customer 'acme', which this deployment does not declare"
        val result = checkClientDefs(prodCxt, collectorOf(config("acme", "acme")))
        result.clients.getValue("acme").customerId shouldBe "acme"
        result.issues.single().degradedTo shouldBe "The client stands, with no customer of it counted."
    }

    "a source client names a source customer" {
        val stored = config(CL.hub, customers = listOf(customer("acme")), origin = GedraConfigOrigin.stored, defineIt = false, configName = "customers")
        refusal(stored, config("acme", "acme")) shouldContain "is defined in source code and names the customer 'acme', which only stored configuration declares"
        // A stored client may name either.
        checkClientDefs(prodCxt, collectorOf(stored, config("beta", "acme", origin = GedraConfigOrigin.stored))).issues.shouldBeEmpty()
    }

    "an id holding '_' falls under a customer's prefix or the self-serve one" {
        refusal(config("acme_east")) shouldContain "Client id 'acme_east' falls under the prefix 'acme', which no customer holds"
        checkClientDefs(devCxt, collectorOf(config("free_x7k2"))).issues.shouldBeEmpty()
        // The link is the authority after creation: a client under one customer's prefix may belong to another.
        checkClientDefs(
            devCxt,
            collectorOf(config("acme", customers = listOf(customer("acme"), customer("beta"))), config("acme_moved", "beta")),
        ).issues.shouldBeEmpty()
    }

    // Issue #1111: the root's administrators act for the customer, so it is one they can sign in to.
    "a customer's root is not a retired client" {
        refusal(config("acme", "acme", listOf(customer("acme", root = "acme")), retired = true))
            .shouldContain("names the root client 'acme', which is retired")
    }

    "a root client reloaded away from its customer is refused, whoever declared the customer" {
        val customers = config(CL.hub, customers = listOf(customer("acme", root = "acme")), defineIt = false, configName = "customers")
        val running = checkClientDefs(devCxt, collectorOf(customers, config("acme", "acme")))
        running.issues.shouldBeEmpty()
        shouldThrow<KdrException> {
            checkClientDefs(devCxt, collectorOf(customers, config("acme")), setOf("acme"), running.clients, running.customers)
        }.fullMessage() shouldContain "names the root client 'acme', which is no customer's"
    }

    // --- a customer arriving or leaving on a reload ------------------------------

    "a customer new to a reload may not take the prefix of another customer's client, but may adopt an unlinked one" {
        val base = config("acme", "acme", listOf(customer("acme")))
        val running = checkClientDefs(prodCxt, collectorOf(base, config("beta_one", "acme"), config("gamma")))
        // hub's stored configuration registers `beta`, whose prefix acme's client `beta_one` holds, and `gamma`, an
        // unlinked client's id.
        val stored = config(
            CL.hub, customers = listOf(customer("beta"), customer("gamma")), origin = GedraConfigOrigin.stored,
            defineIt = false, configName = "customers",
        )
        val result = checkClientDefs(
            prodCxt, collectorOf(base, config("beta_one", "acme"), config("gamma"), stored), setOf(CL.hub),
            running.clients, running.customers,
        )
        result.customers.keys shouldContainExactly setOf("acme", "gamma")
        result.issues.single().message shouldContain "Customer 'beta' declared by 'gc.cd.hub.customers' would take the prefix of 'beta_one'"
    }

    "a customer leaving a reload while a client still uses it is reported" {
        val stored = config(CL.hub, customers = listOf(customer("beta")), origin = GedraConfigOrigin.stored, defineIt = false, configName = "customers")
        val running = checkClientDefs(prodCxt, collectorOf(stored, config("beta_one", "beta", origin = GedraConfigOrigin.stored)))
        running.issues.shouldBeEmpty()
        val emptied = config(CL.hub, origin = GedraConfigOrigin.stored, defineIt = false, configName = "customers")
        val result = checkClientDefs(
            prodCxt, collectorOf(emptied, config("beta_one", "beta", origin = GedraConfigOrigin.stored)), setOf(CL.hub),
            running.clients, running.customers,
        )
        result.customers.keys.shouldBeEmpty()
        result.issues.single().message shouldContain "Customer 'beta' is no longer declared, but 'beta_one' still use(s) it"
    }

    "a stored config of any client but hub may not declare a customer" {
        val stored = config("acme", customers = listOf(customer("acme")), origin = GedraConfigOrigin.stored)
        storedOwnershipProblem(stored)!! shouldContain "A customer is the platform's to declare"
        storedOwnershipProblem(config(CL.hub, customers = listOf(customer("acme")), origin = GedraConfigOrigin.stored, defineIt = false)) shouldBe null
    }
})
