package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind

/** The sample's template client and the names it declares. */
@Suppress("ConstPropertyName")
object STPL {
    /** A code-defined client of the template kind, which a self-serve client may start from. */
    const val client = "starterkit"
    const val namespace = "client.starterkit"
    const val expenseReview = "expenseReview"
    const val review = "review"
    const val saveReview = "saveReview"
}

/**
 * A **template client** (`ClientUsageType.template`): never used directly, but a configured client may extend it
 * (`extendsFromClientId`), and a person creating a self-serve client is offered it to start from. It is modeled on
 * acme from the sample's global traits: a New form asking for the form's name and a questionnaire, and a workflow
 * that collects an expense report once a form exists. A client extending it inherits both and may replace either
 * whole with its own -- the creation workflow included, which is what lets a client change what its New form asks.
 */
fun templateClient(cxt: KdrCxt): GedraConfig =
    gedraConfig(cxt, "${STPL.client}Client", STPL.namespace, STPL.client) {
        defineClient(
            ClientDef(
                clientId = STPL.client,
                name = "Starter kit (sample)",
                description = "A template: a New form with a name and a questionnaire, and an expense review.",
                usageType = ClientUsageType.template,
                audience = ClientAudience.customer,
                enabledEnvironments = setOf(ENV.unit, ENV.local, ENV.dev),
                includedTraits = listOf(GT.name, ST.questionnaire, ST.expenseReport),
            ),
        )

        workflow(SW.createForm, WfEntry.creation) {
            label = "New form"
            task(SW.identify, "Name the form") {
                trait(GT.name)
                trait(ST.questionnaire, required = false)
                save(SW.create, "Create form")
            }
        }

        workflow(STPL.expenseReview, WfEntry.normal) {
            label = "Expense review"
            task(STPL.review, "The expenses") {
                trait(ST.expenseReport)
                save(STPL.saveReview, "Save the expenses", WfSaveKind.edit)
            }
        }
    }
