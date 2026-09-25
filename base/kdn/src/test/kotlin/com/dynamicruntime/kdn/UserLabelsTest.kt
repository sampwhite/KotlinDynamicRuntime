package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.WFC
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.userHasLabel
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.ULIM
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * User labels and the `userHasLabel` workflow function (issue #786), over a per-test dynamic client that
 * suggests two labels and runs a normal workflow whose review task tests for `reviewer`.
 *
 * What is pinned: an administrator applies labels (normalized, replacing), reads the client's suggestions, and
 * is the only one who may; a label on the viewer surfaces as the `reviewer` cfact on the task -- computed per
 * view, so it follows the label as soon as it changes; and a workflow naming a label its client does not suggest
 * is refused when the configuration is loaded, since a misspelled label would otherwise never fire.
 */
class UserLabelsTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("labels786", "labels786")
    val client = "labels786"

    fun asClient(id: String): KdrCxt = cxt.mkSubContext("setup", id).also { it.userId = 9000L }

    fun labelsConfig(id: String, testedLabel: String): GedraConfig = gedraConfig(cxt, "${id}cfg", "${id}config", id) {
        defineClient(
            ClientDef(
                clientId = id, name = id, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                enabledEnvironments = setOf(ENV.unit, ENV.local), userLabels = listOf("reviewer", "siteLead"),
            ),
        )
        // Named per client, and the misspelling case below needs a second.
        trait("${id.replaceFirstChar { it.uppercase() }}NoteEntry", "${id}Note", setOf(GedraDataType.formDoc), "Something to review.") {
            property("text", "A value.")
        }
        workflow("approve", WfEntry.normal) {
            task("review", "Review the form") {
                trait("${id}Note")
                // Approval authority, the motivating case: the viewer is a reviewer when they carry the label.
                function(userHasLabel { label = testedLabel })
                save("save", "Save", WfSaveKind.edit)
            }
        }
    }
    GedraConfigService.get(cxt).writeConfig(asClient(client), labelsConfig(client, "reviewer"))
    GedraConfigReload.reloadClient(cxt, client)

    val admin = TestUser.create(cxt, "admin@$client.test", userClient = client, level = ROLE.admin)
    val plain = TestUser.create(cxt, "plain@$client.test", userClient = client)

    fun labelsOf(info: Map<String, Any?>) = info[ADF.labels].toJsonListOrEmpty().map { it.toOptStr() }

    "an administrator replaces a user's labels, normalized: trimmed, blanks and repeats dropped, case kept" {
        val info = admin.postData(
            UADEP.userSetLabels,
            mapOf(ADF.userId to plain.userId, ADF.labels to listOf("  reviewer ", "siteLead", "reviewer", "", "Reviewer")),
        )
        labelsOf(info) shouldContainExactly listOf("reviewer", "siteLead", "Reviewer")
        // A replacement, not a merge -- and empty clears.
        labelsOf(admin.postData(UADEP.userSetLabels, mapOf(ADF.userId to plain.userId, ADF.labels to emptyList<String>())))
            .shouldContainExactly(emptyList())
    }

    "only an administrator may apply labels" {
        plain.expectError(403, UADEP.userSetLabels, data = mapOf(ADF.userId to plain.userId, ADF.labels to listOf("reviewer")))
    }

    "the label suggestions are the client's, and another client's need allClients" {
        val own = admin.getData(UADEP.userLabelSuggestions)
        own[ADF.client] shouldBe client
        labelsOf(own) shouldContainExactly listOf("reviewer", "siteLead")
        admin.expectError(EXC.badInput, UADEP.userLabelSuggestions, args = mapOf(ADF.client to "someOtherClient"))
    }

    "the full-scope admin surface serves the same calls, and allClients reads another client's suggestions" {
        // Wired on both surfaces from one module; this pins the `admin` half, which the rest of the suite leaves
        // to the `clientAdmin` paths.
        val full = TestUser.createFullAdmin(cxt, "full@example.com")
        labelsOf(full.postData(ADEP.userSetLabels, mapOf(ADF.userId to plain.userId, ADF.labels to listOf("siteLead"))))
            .shouldContainExactly(listOf("siteLead"))
        // The positive half of the client rule: with `allClients`, naming a client other than your own is allowed.
        val theirs = full.getData(ADEP.userLabelSuggestions, mapOf(ADF.client to client))
        theirs[ADF.client] shouldBe client
        labelsOf(theirs) shouldContainExactly listOf("reviewer", "siteLead")
        full.postData(ADEP.userSetLabels, mapOf(ADF.userId to plain.userId, ADF.labels to emptyList<String>()))
    }

    "a client administrator cannot label a user outside their own client" {
        // Out of scope reads as not found -- the answer must not confirm the id belongs to somebody.
        val outsider = TestUser.create(cxt, "outsider@elsewhere786.test")
        admin.expectError(
            EXC.notFound, UADEP.userSetLabels, data = mapOf(ADF.userId to outsider.userId, ADF.labels to listOf("reviewer")),
        )
    }

    "labels arrive as a bounded array: no string splitting, no unbounded list or label" {
        fun refused(labels: Any) = admin.expectError(
            EXC.badInput, UADEP.userSetLabels, data = mapOf(ADF.userId to plain.userId, ADF.labels to labels),
        )
        // A string is not coerced into a list -- it would be comma-split, making a comma unsendable.
        refused("reviewer, siteLead")
        refused((0..ULIM.maxLabels).map { "label$it" })
        refused(listOf("x".repeat(ULIM.maxLabelLength + 1)))
    }

    "a viewer carrying the label is a reviewer of the task, and stops being one when it is removed" {
        val gid = plain.postItem(
            GEP.formDocCreate,
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to "${client}Note", GE.data to mapOf("text" to "to review")))),
        )[GDF.gedraId].toOptStr()!!
        fun reviewFacts(): List<String?> = plain.getData(
            clientPath(GEP.workflowView, client), mapOf(GDF.workflowId to "approve", GDF.gedraId to gid),
        )[WFD.tasks].toJsonListOfMaps().single()[WVF.facts].toJsonListOrEmpty().map { it.toOptStr() }

        reviewFacts() shouldNotContain WFC.reviewer
        // Computed as the view is assembled, from the viewer's labels as they stand -- so it follows the label at
        // once, with nothing stored to recompute.
        admin.postData(UADEP.userSetLabels, mapOf(ADF.userId to plain.userId, ADF.labels to listOf("reviewer")))
        reviewFacts() shouldContain WFC.reviewer
        admin.postData(UADEP.userSetLabels, mapOf(ADF.userId to plain.userId, ADF.labels to emptyList<String>()))
        reviewFacts() shouldNotContain WFC.reviewer
    }

    "a workflow testing for a label its client does not suggest is refused when the configuration loads" {
        // A misspelling is the case this exists for: a user would simply never carry `reviwer`, and the function
        // would silently never fire. The suggestion list is the one place it can be caught.
        val bad = "${client}bad"
        GedraConfigService.get(cxt).writeConfig(asClient(bad), labelsConfig(bad, "reviwer"))
        val e = shouldThrow<KdrException> { GedraConfigReload.reloadClient(cxt, bad) }
        e.message.orEmpty() shouldContain "does not suggest"
    }

    "a permanently deleted user keeps no labels" {
        val gone = TestUser.create(cxt, "gone@$client.test", userClient = client)
        admin.postData(UADEP.userSetLabels, mapOf(ADF.userId to gone.userId, ADF.labels to listOf("reviewer")))
        val deleted = admin.deleteData(UADEP.userDelete, mapOf(ADF.userId to gone.userId, ADF.permanent to true))
        labelsOf(deleted).shouldContainExactly(emptyList())
    }
})
