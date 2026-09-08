package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.schema.SCT

/** The core config traits (issue #316): the config's name, the trait ids, and their data fields. */
@Suppress("ConstPropertyName")
object CCT {
    const val configName = "coreConfigTraits"

    /** The client definition itself; single-instance, since a bundle defines at most one client. */
    const val clientDef = "clientDef"

    /** One workflow definition, keyed by its workflow id. */
    const val workflowDef = "workflowDef"

    /** One schema type definition, keyed by the type's name. */
    const val schemaDef = "schemaDef"

    const val workflowId = "workflowId"
    const val definition = "definition"
    const val typeName = "typeName"
    const val schema = "schema"
}

/**
 * The config traits a **stored** client configuration is made of (issues #316, #611): the pieces of a
 * [GedraConfig] that a database row keeps as separate entries so each has its own accounting -- *when* and *by
 * whom* it changed -- while an editor still sees the one JSON structure a component declares.
 *
 * Three, deliberately, where #611 names four. Tasks are not a trait of their own: a task is declared *inside*
 * its workflow ([com.dynamicruntime.common.gedra.workflow.WfTask], reached by `WfDef.task(id)`), and there is no
 * standalone task definition to key an entry by. The workflow trait carries its tasks. If per-task accounting
 * turns out to matter under the diff-before-stamp rule (#613), splitting them out is additive.
 *
 * Two of the three bind to shapes that already exist as schema: the client definition to `ClientInfo`
 * ([ClientDef.defineInfoType]) and the workflow to the definition schema under [WFD.namespace] -- which is
 * *referenced*, not redeclared, so this config's types resolve only beside that schema, exactly as they will at
 * boot where every component's `$defs` are compiled together. The third, the schema definition, is the one that
 * has no schema of its own to bind to: its body is validated by **parsing** it (`schemaDocument()`), the reason
 * #316 exists.
 *
 * **Declared, not yet contributed.** Nothing registers this config with a component: the entries it describes
 * are stored by #613 and loaded by #614, and until a row can hold one, contributing these types would put three
 * entry types into every catalog that nothing writes or reads. Keyed as #611 says -- workflows by id, schema
 * definitions by type name, the client single-instance.
 */
fun coreConfigTraits(cxt: KdrCxtBase): GedraConfig = gedraConfig(cxt, CCT.configName, GCFG.globalNamespace) {
    ClientDef.defineInfoType(this)
    configTrait(
        "ClientDefEntry", CCT.clientDef, setOf(GedraConfigType.configDoc),
        dataType = CLD.infoTypeName,
        description = "The client this configuration defines, as a stored entry.",
    )
    configTrait(
        "WorkflowDefEntry", CCT.workflowDef, setOf(GedraConfigType.configDoc),
        "One workflow definition of this configuration, keyed by its workflow id.",
        primaryKey = listOf(CCT.workflowId),
    ) {
        property(CCT.workflowId, "The workflow's id within this configuration.", required = true)
        property(CCT.definition, "The workflow definition, as the definition schema describes it.", required = true) {
            ref("${WFD.namespace}.${WFD.defType}")
        }
    }
    configTrait(
        "SchemaDefEntry", CCT.schemaDef, setOf(GedraConfigType.configDoc),
        "One schema type this configuration declares, keyed by the type's name.",
        primaryKey = listOf(CCT.typeName),
    ) {
        property(CCT.typeName, "The qualified name of the type being defined.", required = true)
        property(CCT.schema, "The type's schema body, validated by parsing it.", required = true) { schemaDocument() }
    }
}
