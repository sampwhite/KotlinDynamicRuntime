package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.gedraConfig

/**
 * The workflow templates every deployment has (issue #1120): one of each kind a client's administrator can make a
 * workflow from on the client page. Each collects the form's name, which every client can store, so it runs as declared
 * until a client's own choices replace it. No client runs these as they are ([WorkflowTemplates]).
 */
fun coreWorkflowTemplates(cxt: KdrCxt): GedraConfig = gedraConfig(cxt, WFT.configName, GCFG.globalNamespace) {
    workflow(WFT.createForm, WfEntry.creation) {
        label = "New form"
        comment = "A form's first step: what it is called, and the button that creates it."
        task("identify", "Name the form") {
            trait(GT.name)
            save("create", "Create form", WfSaveKind.create)
        }
    }
    workflow(WFT.survey, WfEntry.survey) {
        label = "Form details"
        comment = "What an owner fills in about the form, and saves."
        task("details", "Details") {
            trait(GT.name)
            save("saveDetails", "Save", WfSaveKind.edit)
        }
    }
    workflow(WFT.collect, WfEntry.normal) {
        label = "Collect information"
        comment = "One step that asks for information, which whoever can see the form fills in and saves."
        task("collect", "Information") {
            trait(GT.name)
            save("saveInformation", "Save", WfSaveKind.edit)
        }
    }
}
