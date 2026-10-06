package com.dynamicruntime.common.simulation

import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchLayoutMode
import com.dynamicruntime.common.schema.layout

/*
 * The `simulation` package holds what a test sets up that is worth setting up the same way outside one: clients and
 * data a scenario provisions for a demo or for UAT, kept in main source so the tests, the `kdr-probe` scenarios and
 * (to come) a provisioning endpoint all build from one definition rather than from copies that drift. Nothing here
 * runs on its own; whatever provisions it is a test-instance affordance.
 */

/**
 * The Design View demo client (issue #972): a small client defined **in data** -- written to a running node as
 * stored configuration, the way a customer's client is -- so that its forms are definitions Design View can show
 * as the client's own, rather than ones compiled into a component.
 *
 * Its one form carries each case the inspector has to tell apart: copy written inline in a layout (label,
 * description, a bounds hint), a closed choice list, a field only an administrator sees and one only a requester
 * sees (`g-visibleWhen` -- the second is a ghost to the administrator using Design View), a field asked only under
 * one answer (a conditional), a field whose fields come from a shared named type (`schemaDef`), a field with no layout
 * entry -- whose form copy is the field's own -- and one its type's layout leaves out of a list that decides the order
 * (issue #1039), and -- beside it in the task -- the global `kdr:name` trait, which no client edits in place. Its two workflows, a creation and a
 * survey, collect the same trait, which is what makes a workflow's own copy (issue #984) visible as its own.
 *
 * The `design-demo` probe scenario provisions it on a running node, and the Design View tests provision it in
 * theirs. A test asserts the demo's copy through the constants here (`titleLabel`, ...), not as literals, so the
 * demo's wording can be reworked for how it looks without breaking them.
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

    /** The simulation that provisions it (issue #997): `/fixture/simulate/design-demo`. */
    const val simulationName = "design-demo"
    const val configName = "designDemo"

    const val eventRequest = "eventRequest"
    const val contactType = "Contact"

    const val requestWorkflow = "requestEvent"
    const val reviewWorkflow = "reviewEvent"
    const val describeTask = "describe"
    const val detailsTask = "details"
    const val submitSave = "submit"

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
    const val contactPhone = "phone"
    const val catering = "catering"

    // Copy a test asserts, so the demo's wording can change under it.
    const val titleLabel = "What is the event?"
    const val contactEmailLabel = "Contact email"
}

/**
 * The demo client's configuration, built with the same DSL a component uses and written as a stored bundle by
 * whatever provisions it. [cxt] is only what the builder needs, so a [LiteCxt] serves. [client] is the client id it is
 * built for -- [DesignDemo.client], or that plus a suffix for a fresh copy of the same shape (issue #997); its types
 * take the client's namespace, so copies never share a name.
 */
fun designDemoConfig(cxt: KdrCxtBase = LiteCxt(), client: String = DesignDemo.client): GedraConfig =
    gedraConfig(cxt, DesignDemo.configName, clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client,
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
            property(DesignDemo.contactPhone, "A number to reach the contact on the day.") { title = "Phone number" }
            // A list that decides the order: the phone, which it leaves out, follows the fields it names -- and the
            // shared editor cannot give the phone copy, since that would add it to the list.
            layout(mode = SchLayoutMode.reorder) {
                field(DesignDemo.contactName, label = "Contact name")
                field(DesignDemo.contactEmail, label = DesignDemo.contactEmailLabel, hint = "We only use this about the event.")
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
            // No layout entry: the form shows a label made from the name, and this description.
            property(DesignDemo.catering, "Whether the event needs food and drink.") { type = SCT.boolean }
            property(DesignDemo.requesterNote, "Anything the requester wants the organizers to know.") {
                visibleWhen = "~${CFACTS.hasAdminLevel}"
            }
            // Asked only for an outdoor event -- so Design View shows it as a ghost until that venue is chosen.
            presentWhen(DesignDemo.backupPlan, on = DesignDemo.venue, value = "outdoors")
            layout(label = "Event request") {
                field(DesignDemo.title, label = DesignDemo.titleLabel, description = "A short name people will recognize.")
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
                save(DesignDemo.submitSave, "Submit the request")
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
