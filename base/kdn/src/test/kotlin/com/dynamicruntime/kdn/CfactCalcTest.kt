package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.CFC
import com.dynamicruntime.common.gedra.workflow.CfactCalcFn
import com.dynamicruntime.common.gedra.workflow.CfactCalcParams
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WfDefBuilder
import com.dynamicruntime.common.gedra.workflow.computeCFactsFromData
import com.dynamicruntime.common.gedra.workflow.parseWfDef
import com.dynamicruntime.common.gedra.workflow.runCfactCalc
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * The `cfactCalc` event and `computeCFactsFromData` end to end (issue #678): a survey workflow declares a
 * `computeCFactsFromData` function, and creating/editing a form emits the mapped cfacts into the form's derived,
 * form-singleton state -- read back through the survey completion entry, the shared `cfacts` state, and
 * `assembleFormCfacts`. The function runs *inside* the survey's derived-state recompute (decision 5), which
 * #675's post-write hook drives on every write, so the cfacts follow the data on a create and a raw patch alike.
 *
 * A **per-test dynamic client** (the `kdr-test-clients` technique), because the machinery is production
 * `base/common` code and a purpose-built survey proves the whole path without leaning on acme's shapes. The
 * runtime unknown-cfact guard (decision 8) is exercised separately by calling [runCfactCalc] directly, since a
 * literal undeclared cfact is already refused at boot and so never reaches the runtime drop.
 */
class CfactCalcTest : StringSpec({
    // The strict client is defined at boot (a testFeatures flag does not survive a config write/reload), the
    // survey client below as a runtime dynamic client -- the two ways a client reaches a running node.
    val cxt = Startup.mkTestBootCxt(
        "cfactCalc678", "cfactCalc678",
        mapOf(StrictCfactFixture.loadFlag.name to "true"),
        additionalComponents = listOf(StrictCfactFixture()),
    )
    val client = "cfactcalc678"

    fun asClient(c: String): KdrCxt = cxt.mkSubContext("setup", c).also { it.userId = 9000L }

    // The demo the design gives: a `projects` trait whose `projectChoices` list maps to per-project cfacts. The
    // survey collects `projects`, and its workflow-global cfactCalc function turns the choices into cfacts.
    val config = gedraConfig(cxt, "${client}cfg", "${client}config", client) {
        defineClient(
            ClientDef(
                clientId = client, name = client, usageType = ClientUsageType.dev,
                audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
            ),
        )
        cfact("projectA", "Projects", "The form's owner is signed up for project A.")
        cfact("projectB", "Projects", "The form's owner is signed up for project B.")
        trait("ProjectsEntry", "projects", setOf(GedraDataType.formDoc), "The projects the form's owner chose.") {
            property("projectChoices", "The chosen project keys.") {
                type = SCT.array
                items { type = SCT.string }
            }
        }
        workflow("reviewForm", WfEntry.survey) {
            function(
                computeCFactsFromData {
                    trait = "projects"
                    valuePath = "projectChoices"
                    map("projectAForClient", "projectA")
                    map("projectBForClient", "projectB")
                },
            )
            task("only", "Review") { trait("projects", required = false); save("save", "Save", WfSaveKind.edit) }
        }
    }

    GedraConfigService.get(cxt).writeConfig(asClient(client), config)
    GedraConfigReload.reloadClient(cxt, client)
    val user = TestUser.create(cxt, "u@$client.test", userClient = client)

    fun create(vararg choices: String): String =
        user.postItem(
            GEP.formDocCreate,
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to "projects", GE.data to mapOf("projectChoices" to choices.toList())))),
        )[GDF.gedraId].toOptStr().orEmpty()

    fun storedCfacts(gid: String): List<Any?> {
        val row = user.getItems(GEP.formDocs, mapOf(GDF.withStates to true)).first { it[GDF.gedraId] == gid }
        return row[GDF.states].toJsonListOrEmpty().map { it.toJsonMapOrEmpty() }
            .first { it[GE.traitId].toOptStr() == "cfacts" }[GE.data].toJsonMapOrEmpty()["facts"].toJsonListOrEmpty()
    }

    fun setChoices(gid: String, vararg choices: String) = user.postItems(
        GEP.patch,
        mapOf(
            GPF.targets to mapOf(
                GedraDataType.formDoc.name to listOf(
                    mapOf(
                        GDF.gedraId to gid,
                        GPF.edits to listOf(
                            mapOf(
                                GED.action to GedraEditAction.addOrReplace.name,
                                GE.traitId to "projects", GE.data to mapOf("projectChoices" to choices.toList()),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    "a choice in the mapping emits its cfact, through both the stored state and assembleFormCfacts" {
        val gid = create("projectAForClient")

        // Emitted into the form-singleton cfacts state, beside the survey's own facts.
        storedCfacts(gid) shouldContain "projectA"
        storedCfacts(gid) shouldNotContain "projectB"

        // And through the bridge the eligibility layer reads. Read under a context scoped to the form's client,
        // since assembleFormCfacts narrows to the cfacts that context's client declares.
        val readCxt = cxt.mkSubContext("read", client).also { it.userId = user.userId }
        val assembled = GedraDataService.get(cxt).assembleFormCfacts(readCxt, GedraId.parse(gid), ReadScope.ofClient(client))
        assembled shouldContain "projectA"
        assembled shouldNotContain "projectB"
    }

    "a value in no mapping emits nothing" {
        val gid = create("somethingElse")
        storedCfacts(gid) shouldNotContain "projectA"
        storedCfacts(gid) shouldNotContain "projectB"
    }

    "a later edit recomputes the derived cfacts from the new data" {
        val gid = create("projectAForClient")
        storedCfacts(gid) shouldContain "projectA"

        // A raw patch (not the survey save) swaps the choice; the post-write hook recomputes, so projectA gives
        // way to projectB -- the derived cfacts follow the data on every write path.
        setChoices(gid, "projectBForClient")
        storedCfacts(gid) shouldContain "projectB"
        storedCfacts(gid) shouldNotContain "projectA"
    }

    "an undeclared emitted cfact is dropped by default and thrown for a strict client" {
        // A resolved function that emits a cfact neither client declares -- the shape a future computed cfact
        // could take, which the boot check cannot catch. Built here directly, since a literal one is refused at
        // boot and never runs.
        val def = parseWfDef(
            cxt,
            WfDefBuilder("ghostWf", WfEntry.survey).apply {
                task("only", "Review") { trait("projects", required = false); save("save", "Save", WfSaveKind.edit) }
            }.build(),
        )
        def.resolvedFunctions = listOf(GhostCfactFn("ghostCfact"))

        // The plain client drops it (and logs); the result carries none of it.
        runCfactCalc(cxt, def, entries = emptyList(), client = client) shouldBe emptySet()

        // The strict client escalates the same drop to an error.
        shouldThrow<KdrException> { runCfactCalc(cxt, def, entries = emptyList(), client = StrictCfactFixture.strictClient) }
    }
})

/** A cfactCalc function that emits a fixed cfact, for exercising the runtime unknown-cfact guard. */
private class GhostCfactFn(private val ghost: String) : CfactCalcFn {
    override val fn: String = "ghost"
    override val priority: Int = 0
    override fun computeCfacts(cxt: KdrCxt, params: CfactCalcParams) {
        params.emit(ghost)
    }
}

/**
 * A boot fixture that defines one client opting into strict unknown-cfact handling ([CFC.strictUnknownCfact]).
 * Boot-defined rather than reloaded, because `testFeatures` is a boot-only field a config write/reload drops.
 */
private class StrictCfactFixture : ComponentDefinition {
    override val providerName: String = "strictCfactFixture"

    override fun isLoaded(cxt: KdrCxt): Boolean = cxt.getEnvBool(loadFlag) == true

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "${strictClient}cfg", "${strictClient}config", strictClient) {
            defineClient(
                ClientDef(
                    clientId = strictClient, name = strictClient, usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit),
                    testFeatures = setOf(CFC.strictUnknownCfact),
                ),
            )
        },
    )

    @Suppress("ConstPropertyName")
    companion object {
        val loadFlag = EnvVarDef(
            "KDR_LOAD_STRICT_CFACT_FIXTURE", group = ENVGRP.application, defaultDoc = "off",
            description = "Test-only flag that loads the strict unknown-cfact client fixture.",
        )
        const val strictClient = "cfactcalc678strict"
    }
}
