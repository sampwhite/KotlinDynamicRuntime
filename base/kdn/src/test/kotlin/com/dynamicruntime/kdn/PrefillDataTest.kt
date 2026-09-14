package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GSRC
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.SVY
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.prefillFromOwner
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * The `prefillData` event and `prefillFromOwner` end to end (issue #679): a survey task declares a
 * `prefillFromOwner` function, and the resolved view defaults the target trait's field from the form owner's
 * attribute -- as a **view-only** entry sourced [GSRC.prefill]. It is presented as entered but never written and
 * never counted toward requiredness: a prefilled required trait still reads as missing in the survey state.
 *
 * Driven over the real `workflowView` endpoint (a per-test dynamic client), so the handler's owner read and the
 * resolver's decoration are both exercised.
 */
class PrefillDataTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("prefill679", "prefill679")
    val client = "prefill679"

    fun asClient(): KdrCxt = cxt.mkSubContext("setup", client).also { it.userId = 9000L }

    // A survey whose task requires `participant` (with a `fullName` field) and prefills it from the owner's
    // public name; `note` is an optional trait, so a form can exist without a participant entry.
    val config = gedraConfig(cxt, "${client}cfg", "${client}config", client) {
        defineClient(
            ClientDef(
                clientId = client, name = client, usageType = ClientUsageType.dev,
                audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
            ),
        )
        trait("ParticipantEntry", "participant", setOf(GedraDataType.formDoc), "Who is taking part.") {
            property("fullName", "The participant's full name.", required = true)
        }
        trait("NoteEntry", "note", setOf(GedraDataType.formDoc), "An aside the survey does not require.") {
            property("text", "A value.")
        }
        // A trait with a nested field, for the dotted targetValuePath case.
        trait("ProfileEntry", "profile", setOf(GedraDataType.formDoc), "The owner's profile.") {
            property("contact", "Contact block.") {
                type = SCT.kObject
                property("fullName", "The owner's full name.")
            }
        }
        workflow("reviewForm", WfEntry.survey) {
            task("only", "Review") {
                trait("participant")
                trait("note", required = false)
                function(
                    prefillFromOwner {
                        userAttribute = "publicName"
                        targetTrait = "participant"
                        targetValuePath = "fullName"
                    },
                )
                save("save", "Save", WfSaveKind.edit)
            }
        }
        // A creation workflow whose prefill defaults from the caller (no form yet) into a dotted path.
        workflow("createForm", WfEntry.creation) {
            task("start", "Start") {
                trait("profile", required = false)
                function(
                    prefillFromOwner {
                        userAttribute = "publicName"
                        targetTrait = "profile"
                        targetValuePath = "contact.fullName"
                    },
                )
                save("create", "Create")
            }
        }
    }
    GedraConfigService.get(cxt).writeConfig(asClient(), config)
    GedraConfigReload.reloadClient(cxt, client)

    val user = TestUser.create(cxt, "u@$client.test", userClient = client)
    val viewPath = clientPath(GEP.workflowView, client)
    val ownerPublicName = UserService.get(cxt)
        .queryUsersByIds(cxt, listOf(user.userId), ReadScope.unrestricted)[user.userId]
        .shouldNotBeNull().publicName()

    fun create(vararg entries: Map<String, Any?>): String =
        user.postItem(GEP.formDocCreate, mapOf(GDF.entries to entries.toList()))[GDF.gedraId].toOptStr().orEmpty()

    fun participantEntry(gid: String): Map<String, Any?> {
        val task = user.getData(viewPath, mapOf(GDF.gedraId to gid))[WFD.tasks].toJsonListOfMaps().single()
        return task[WVF.entries].toJsonListOfMaps().first { it[GE.traitId].toOptStr() == "participant" }
    }

    fun surveyCompletion(gid: String): Map<String, Any?> {
        val row = user.getItems(GEP.formDocs, mapOf(GDF.withStates to true)).first { it[GDF.gedraId] == gid }
        return row[GDF.states].toJsonListOfMaps().first { it[GE.traitId].toOptStr() == SVY.surveyCompletion }[GE.data].toJsonMapOrEmpty()
    }

    "an absent trait is prefilled from the owner, as a view-only entry sourced prefill" {
        // A form with only a `note`, so `participant` has no entry -- the prefill supplies one.
        val gid = create(mapOf(GE.traitId to "note", GE.data to mapOf("text" to "hi")))

        val participant = participantEntry(gid)
        participant[GE.data].toJsonMapOrEmpty()["fullName"] shouldBe ownerPublicName
        participant[GE.source] shouldBe GSRC.prefill
    }

    "a prefill never overrides a trait that already has an entry" {
        // The form already carries a real participant; the prefill must leave it alone.
        val gid = create(mapOf(GE.traitId to "participant", GE.data to mapOf("fullName" to "Real Name")))

        val participant = participantEntry(gid)
        participant[GE.data].toJsonMapOrEmpty()["fullName"] shouldBe "Real Name"
        participant[GE.source] shouldBe GSRC.user   // the real entry a person created, not a prefill
    }

    "a creation view prefills from the caller, into a dotted path" {
        // No form yet, so the owner is the caller; the dotted targetValuePath nests under `contact`.
        val v = user.getData(viewPath, mapOf(GDF.workflowId to "createForm"))
        val task = v[WFD.tasks].toJsonListOfMaps().single()
        val profile = task[WVF.entries].toJsonListOfMaps().first { it[GE.traitId].toOptStr() == "profile" }
        profile[GE.source] shouldBe GSRC.prefill
        profile[GE.data].toJsonMapOrEmpty()["contact"].toJsonMapOrEmpty()["fullName"] shouldBe ownerPublicName
    }

    "a prefilled required trait does not count toward completeness" {
        // Prefilled in the view, but the backend judges completeness on real entries only.
        val gid = create(mapOf(GE.traitId to "note", GE.data to mapOf("text" to "hi")))

        // The view presents the prefill...
        participantEntry(gid)[GE.source] shouldBe GSRC.prefill
        // ...but the survey state, computed on real data, still reports the required trait missing.
        val completion = surveyCompletion(gid)
        completion[SVY.complete] shouldBe false
        completion[SVY.missingTraits].toJsonListOrEmpty() shouldContain "participant"
    }
})
