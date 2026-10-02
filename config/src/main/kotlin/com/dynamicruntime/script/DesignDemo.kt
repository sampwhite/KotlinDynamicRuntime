package com.dynamicruntime.script

import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.gedraConfigToEntries
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.layout

/**
 * The Design View demo client (issue #972): a small client defined **in data** -- written to a running node as
 * stored configuration, the way a customer's client is -- so that its forms are definitions Design View can show
 * as the client's own, rather than ones compiled into a component.
 *
 * Its one form carries each case the inspector has to tell apart: copy written inline in a layout (label,
 * description, a bounds hint), a closed choice list, a field only an administrator sees and one only a requester
 * sees (`g-visibleWhen` -- the second is a ghost to the administrator using Design View), a field asked only under
 * one answer (a conditional), a field whose fields come from a shared named type (`schemaDef`), and -- beside it in
 * the task -- the global `kdr:name` trait, which no client edits in place.
 *
 * What it does not carry is copy pulled from a fragment file of its own, because a client cannot yet declare one.
 * Its stored configuration only *overlays* fragment files: a key it adds to a shipped file is an orphan, which the
 * trial refuses, and an overlay of a file no component ships -- though its keys do resolve at render -- is reported
 * by the fragment check as a declared-but-absent file, with its own content never checked. The sample's `acme`,
 * declared in source, shows copy pulled from a shipped fragment file.
 */
@Suppress("ConstPropertyName")
object DesignDemo {
    const val client = "designdemo"
    const val configName = "designDemo"

    const val eventRequest = "eventRequest"
    const val contactType = "Contact"

    const val requestWorkflow = "requestEvent"
    const val reviewWorkflow = "reviewEvent"
    const val describeTask = "describe"
    const val detailsTask = "details"

    // The event request's fields.
    const val title = "title"
    const val attendees = "attendees"
    const val venue = "venue"
    const val budgetNote = "budgetNote"
    const val contact = "contact"
    const val backupPlan = "backupPlan"
    const val requesterNote = "requesterNote"
    const val contactName = "name"
    const val contactEmail = "email"
}

/**
 * The demo client's configuration. Built with the same DSL a component uses, then written as a stored bundle by
 * [designDemo]; [cxt] is only what the builder needs, so a [LiteCxt] serves.
 */
fun designDemoConfig(cxt: KdrCxtBase = LiteCxt()): GedraConfig =
    gedraConfig(cxt, DesignDemo.configName, clientNamespace(DesignDemo.client), DesignDemo.client) {
        defineClient(
            ClientDef(
                clientId = DesignDemo.client,
                name = "Design demo",
                usageType = ClientUsageType.demo,
                audience = ClientAudience.internal,
                enabledEnvironments = setOf(ENV.unit, ENV.local, ENV.dev),
                // A global trait is supported only where a client includes it; its own traits need no mention.
                includedTraits = listOf(GT.name),
            ),
        )

        // A shared type: declared once, referenced from the request -- so its fields belong to a `schemaDef` entry
        // rather than to the trait that uses it.
        type(DesignDemo.contactType) {
            type = SCT.kObject
            description = "Who to talk to about the event."
            property(DesignDemo.contactName, "The contact's name.")
            property(DesignDemo.contactEmail, "The contact's email address.")
            layout {
                field(DesignDemo.contactName, label = "Contact name")
                field(DesignDemo.contactEmail, label = "Contact email", hint = "We only use this about the event.")
            }
        }

        trait("EventRequestEntry", DesignDemo.eventRequest, setOf(GedraDataType.formDoc), "A request to hold an event.") {
            property(DesignDemo.title, "What the event is.", required = true)
            property(DesignDemo.attendees, "How many people are expected.") {
                type = SCT.integer
                minimum = 1
                maximum = 200
            }
            property(DesignDemo.venue, "Where the event is held.") {
                option("office", "At the office")
                option("hotel", "A hotel")
                option("outdoors", "Outdoors")
            }
            property(DesignDemo.budgetNote, "A note on budget, for administrators.") {
                visibleWhen = CFACTS.hasAdminLevel
            }
            property(DesignDemo.contact, "Who to talk to.") { ref(DesignDemo.contactType) }
            property(DesignDemo.backupPlan, "What happens if the weather turns.")
            property(DesignDemo.requesterNote, "Anything the requester wants the organizers to know.") {
                visibleWhen = "~${CFACTS.hasAdminLevel}"
            }
            // Asked only for an outdoor event -- so Design View shows it as a ghost until that venue is chosen.
            presentWhen(DesignDemo.backupPlan, on = DesignDemo.venue, value = "outdoors")
            layout(label = "Event request") {
                field(DesignDemo.title, label = "What is the event?", description = "A short name people will recognize.")
                field(DesignDemo.attendees, label = "Expected attendees", hint = $$"Between ${min} and ${max} people.")
                field(DesignDemo.venue, label = "Venue")
                field(DesignDemo.budgetNote, label = "Budget note", description = "Only administrators see this field.")
                field(DesignDemo.backupPlan, label = "Rain plan")
                field(DesignDemo.requesterNote, label = "Anything else?", description = "Requesters see this; administrators do not.")
            }
        }

        workflow(DesignDemo.requestWorkflow, WfEntry.creation) {
            label = "Request an event"
            task(DesignDemo.describeTask, "Describe the event") {
                trait(DesignDemo.eventRequest)
                trait(GT.name, required = false)
                save("submit", "Submit the request")
            }
        }

        workflow(DesignDemo.reviewWorkflow, WfEntry.survey) {
            label = "Event request"
            task(DesignDemo.detailsTask, "Event details") {
                trait(DesignDemo.eventRequest)
                trait(GT.name, required = false)
                save("saveDetails", "Save the details", WfSaveKind.edit)
            }
        }
    }

/** Name of the [designDemo] scenario. */
const val designDemoName = "design-demo"

/**
 * Writes the Design View demo client ([designDemoConfig]) to the instance and reloads it there (issue #972), as an
 * administrator with the full-scope capability -- the `admin` config surface is how a new client is created over
 * the API. Rerunning rewrites the same bundle, so it is safe to repeat; on an in-memory node it has to be rerun
 * after every restart, since nothing stored survives one.
 */
fun designDemo(cxt: ProbeContext) {
    val config = designDemoConfig()
    val admin = cxt.sessionAt(ROLE.admin)
    val write = admin.sendPostRequest(
        ACEP.bundleWrite,
        mapOf(
            CFEP.client to DesignDemo.client,
            CFEP.name to config.name,
            CFEP.namespaceField to config.namespace,
            CFEP.slots to gedraConfigToEntries(config),
        ),
    )
    println("Write '${DesignDemo.client}/${config.name}': HTTP ${write.statusCode} ${write.errorMessage ?: "ok"}")
    if (!write.isSuccess) return
    val reload = admin.sendPostRequest(ACEP.reload, mapOf(CFEP.client to DesignDemo.client))
    println("Reload '${DesignDemo.client}': HTTP ${reload.statusCode} ${reload.errorMessage ?: "ok"}")
    if (!reload.isSuccess) return
    println()
    println("Sign in as one of its administrators from the browser's console, then reload:")
    println("  fetch('/kda/fixture/becomeUser', {method: 'POST', headers: {'Content-Type': 'application/json'},")
    println("    body: JSON.stringify({email: 'designer@${DesignDemo.client}.example', level: 'admin', client: '${DesignDemo.client}'})})")
}
