package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.cfact.CFACT
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.home.menuItem
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.mail.MCOPY
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.user.AFRAG
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * A client's overrides (issue #916): the copy and interface acme's and globex's own configurations change, as the
 * sample declares them in source; the scoping every clientAdmin retrieve has; the overview's counts; and -- last,
 * since it changes acme for the rest of the boot -- a stored config winning over acme's source one.
 */
class ClientOverridesEndpointTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "clientOverrides", "clientOverridesTest", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )
    val admin = TestUser.createFullAdmin(cxt, "overrides-admin@example.com")
    val acmeConfig = "${SC.acme}Client"

    fun overrides(user: TestUser, client: String? = null): Map<String, Any?> =
        user.getItem(UADEP.clientOverrides, client?.let { mapOf(COV.client to it) } ?: emptyMap())

    fun copy(result: Map<String, Any?>): Map<String, Map<String, Any?>> =
        result[COV.copy].toJsonListOrEmpty().map { it.toJsonMapOrEmpty() }
            .associateBy { "${it[COV.fileId]}:${it[COV.namespaceField]}.${it[COV.key]}" }

    fun blockRows(result: Map<String, Any?>): Map<String, Map<String, Any?>> =
        result[COV.blocks].toJsonListOrEmpty().map { it.toJsonMapOrEmpty() }
            .associateBy { "${it[COV.blockId]}:${it[COV.itemId] ?: ""}" }

    fun fields(row: Map<String, Any?>): Map<String, Map<String, Any?>> =
        row[COV.fields].toJsonListOrEmpty().map { it.toJsonMapOrEmpty() }.associateBy { it[COV.field] as String }

    "acme's copy: each key its config sets, with the shipped value it replaces" {
        val rows = copy(overrides(admin, SC.acme))
        rows.keys shouldBe setOf(
            "${SF.content}:${SF.welcome}.${SF.title}", "${SF.content}:${SF.welcome}.${SF.support}",
            "${HFRAG.home}:home.brand", "${HFRAG.home}:${HFRAG.formsNs}.${HFRAG.noWorkflows}",
            "${AFRAG.mail}:${MCOPY.common}.${MCOPY.footer}",
        )
        val brand = rows.getValue("${HFRAG.home}:home.brand")
        brand[COV.baseValue] shouldBe "KDR"
        brand[COV.value] shouldBe "ACME KDR"
        brand[COV.configName] shouldBe acmeConfig
        brand[COV.origin] shouldBe GedraConfigOrigin.source.name
        brand[COV.orphan] shouldBe false
        brand.containsKey(COV.sourceValue) shouldBe false
        rows.getValue("${AFRAG.mail}:${MCOPY.common}.${MCOPY.footer}")[COV.audience] shouldBe "backend"
    }

    "acme's interface: a renamed and an added nav item, and home-menu items hidden and shown" {
        val rows = blockRows(overrides(admin, SC.acme))
        rows.keys shouldBe setOf(
            "${SB.nav}:${SB.overview}", "${SB.nav}:${SB.siteAudits}",
            "${HMENU.block}:${HMENU.cfactReference}", "${HMENU.block}:${HMENU.workflows}",
        )
        val overview = rows.getValue("${SB.nav}:${SB.overview}")
        overview[COV.path] shouldBe SB.items
        overview[COV.added] shouldBe false
        fields(overview).getValue(SB.label).let {
            it[COV.baseValue] shouldBe "Overview"
            it[COV.value] shouldBe "Acme overview"
            it[COV.configName] shouldBe acmeConfig
        }
        rows.getValue("${SB.nav}:${SB.siteAudits}")[COV.added] shouldBe true
        rows.getValue("${HMENU.block}:${HMENU.cfactReference}")[COV.hidden] shouldBe true
        val workflows = rows.getValue("${HMENU.block}:${HMENU.workflows}")
        workflows[COV.hidden] shouldBe false
        fields(workflows).getValue(UIB.cfactExpression)[COV.baseValue] shouldBe CFACT.neverName
    }

    "globex changes only its mail footer, and the overview counts both" {
        val globex = overrides(admin, SC.globex)
        copy(globex).keys shouldBe setOf("${AFRAG.mail}:${MCOPY.common}.${MCOPY.footer}")
        globex[COV.blocks].toJsonListOrEmpty().shouldBeEmpty()

        val listed = admin.getItems(UADEP.clientsOverview).associateBy { it[CLD.clientId] as String }
        (listed.getValue(SC.acme)[CLD.copyOverrides] as Number).toInt() shouldBe 5
        (listed.getValue(SC.acme)[CLD.blockOverrides] as Number).toInt() shouldBe 4
        (listed.getValue(SC.globex)[CLD.copyOverrides] as Number).toInt() shouldBe 1
        (listed.getValue(SC.globex)[CLD.blockOverrides] as Number).toInt() shouldBe 0
    }

    "a client-scoped administrator sees their own client's overrides and may not name another" {
        val scoped = TestUser.create(cxt, "overrides-scoped@acme.test", level = ROLE.admin, userClient = SC.acme)
        overrides(scoped)[COV.client] shouldBe SC.acme
        scoped.expectError(EXC.notAuthorized, UADEP.clientOverrides, args = mapOf(COV.client to SC.globex))
        val selfAdmin = TestUser.create(cxt, "overrides-self@example.com", level = ROLE.admin, userClient = CL.public)
        selfAdmin.expectError(EXC.notAuthorized, UADEP.clientOverrides)
    }

    // Last: it changes acme's configuration for the rest of the boot.
    "a stored config's value wins over acme's source one, and the row keeps the source value" {
        val edits = gedraConfig(cxt, "edits916", clientNamespace(SC.acme), SC.acme) {
            fragmentOverlay(HFRAG.home) { namespace("home") { key("brand", "Acme Co") } }
            uiBlockOverlay(HMENU.block) { items(HFLD.menu) { menuItem(HMENU.cfactReference, "Acme facts") } }
        }
        val writer = cxt.mkSubContext("edits", SC.acme).also { it.userId = 9160L }
        val svc = GedraConfigService.get(cxt)
        svc.writeConfig(writer, edits)
        // Published: acme has a sandbox (issue #994), so it runs only what is published.
        svc.publish(writer, GedraId.of(GedraConfigType.configDoc, SC.acme, "edits916"))
        GedraConfigReload.reloadClient(cxt, SC.acme)

        val result = overrides(admin, SC.acme)
        val brand = copy(result).getValue("${HFRAG.home}:home.brand")
        brand[COV.value] shouldBe "Acme Co"
        brand[COV.configName] shouldBe "edits916"
        brand[COV.origin] shouldBe GedraConfigOrigin.stored.name
        brand[COV.sourceValue] shouldBe "ACME KDR"
        // What acme's people are actually served, not only what the report says.
        MarkdownFragmentService.get(cxt).effectiveFragmentsFor(cxt, HFRAG.home, SC.acme)!!
            .content.getValue("home")["brand"] shouldBe "Acme Co"

        // One item, two configs: the stored label beside the source-set condition, each attributed to its own.
        val facts = fields(blockRows(result).getValue("${HMENU.block}:${HMENU.cfactReference}"))
        facts.getValue(HFLD.label)[COV.origin] shouldBe GedraConfigOrigin.stored.name
        facts.getValue(UIB.cfactExpression)[COV.origin] shouldBe GedraConfigOrigin.source.name
    }
})
