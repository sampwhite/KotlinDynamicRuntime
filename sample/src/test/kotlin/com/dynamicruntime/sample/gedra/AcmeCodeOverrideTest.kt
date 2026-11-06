package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientConfigIssues
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Acme, the sample's client defined in code, edited through stored configuration (issue #1634): a stored trait of the
 * same id as one of its code's used to collide with it, so a code client could not be edited at all. Marked
 * `"overrides": "code"`, it stands over the code's; unmarked, the write is refused, saying what it would replace. Its
 * own instance, since what it stores changes what Acme runs.
 */
class AcmeCodeOverrideTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "acmeOverride", "acmeCodeOverride1634", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )
    val writer = cxt.mkSubContext("acmeOverrideWrite", SC.acme).also { it.userId = 16341L }

    fun write(marked: Boolean) = GedraConfigService.get(cxt).writeConfig(
        writer,
        gedraConfig(cxt, "over", "${clientNamespace(SC.acme)}.over", SC.acme) {
            trait("StoredFollowUpEntry", SC.siteFollowUpTrait, setOf(GedraDataType.formDoc), "A follow-up, as stored.") {
                property(SC.followUpBy, "Who made the follow-up visit.", required = true)
                property("revisitOn", "When to go back.")
            }
            if (marked) overridesCode(SC.siteFollowUpTrait)
        },
        trial = true,
    )

    "unmarked, a stored trait of the code's id is refused; marked, it stands over the code's and runs" {
        shouldThrow<KdrException> { write(marked = false) }.message.shouldNotBeNull() shouldContain
            "holds trait '${SC.siteFollowUpTrait}', which its code's '${SC.acme}Client' also declares"

        write(marked = true)
        // Acme has a sandbox, so it runs what it has published.
        GedraConfigService.get(cxt).publish(writer, listOf(GedraId.of(GedraConfigType.configDoc, SC.acme, "over")))
        GedraConfigReload.reloadClient(cxt, SC.acme).issues.shouldBeEmpty()
        val trait = SchemaService.get(cxt).gedraTraitsFor(SC.acme).single { it.traitId == SC.siteFollowUpTrait }
        trait.typeName shouldBe "${clientNamespace(SC.acme)}.over.StoredFollowUpEntry"
        // The code's workflow that collects the trait runs with it, as before.
        WorkflowService.get(cxt).forClient(SC.acme).workflow(SW.siteFollowUp).shouldNotBeNull()
        ClientConfigIssues.get(cxt).issuesFor(SC.acme).shouldBeEmpty()
    }
})
