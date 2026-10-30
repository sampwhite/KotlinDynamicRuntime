package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfEventScope
import com.dynamicruntime.common.gedra.workflow.WfFunctionCreation
import com.dynamicruntime.common.util.toJsonMap

/** The names the function unions are published under (issue #1501). Each name matches its value. */
@Suppress("ConstPropertyName")
object FNU {
    const val namespace = "kdr.fn"

    /** A function usage on a workflow definition: a function of a whole-workflow event, such as `cfactCalc`. */
    const val workflowFunction = "WorkflowFunction"

    /** A function usage on a workflow's task: a function of a per-task event, such as `prefillData`. */
    const val taskFunction = "WorkflowTaskFunction"

    /** A function usage in a type's `g-onWrite`. */
    const val writeFunction = "WriteFunction"
}

/**
 * The published schema of every registered function kind (issue #1501): each kind's settings type, as the kind
 * declares it, and three unions discriminated on `fn` over them -- the functions a workflow definition may list, those
 * a task may list ([FNU]), and those a type's `g-onWrite` may. A usage was always validated against its kind's type;
 * this is what lets the catalog, the bundle editor, and the meta-schema say so, where they used to show each function
 * as an untyped object.
 *
 * Documentation only: what *loads* stays forgiving -- a workflow is read without its functions' settings checked, and
 * each usage is held to its kind by the resolution pass, which drops only a usage that fails -- and a stored write is
 * refused by the trial reload, as before. Assembled at boot, since the kinds are registered by components.
 */
fun functionKindDefs(
    cxt: KdrCxtBase,
    workflowKinds: List<WfFunctionCreation>,
    schemaKinds: List<SchFunctionCreation>,
): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    workflowKinds.forEach { out.putAll(it.initDataDefs(cxt)) }
    out.putAll(
        schemaDefs(cxt, FNU.namespace) {
            functionUnion(
                FNU.workflowFunction,
                "A function a workflow definition lists, for an event of the whole workflow; 'fn' says which kind.",
                workflowKinds.filter { it.event.scope == WfEventScope.global }.map { it.initDataTypeName },
            )
            functionUnion(
                FNU.taskFunction,
                "A function a workflow's task lists, for an event of that task; 'fn' says which kind.",
                workflowKinds.filter { it.event.scope == WfEventScope.task }.map { it.initDataTypeName },
            )
        },
    )
    out.putAll(writeFunctionDefs(cxt, schemaKinds))
    return out
}

/** The schema function kinds' settings types and the [FNU.writeFunction] union over the write event's (issue #1501). */
private fun writeFunctionDefs(cxt: KdrCxtBase, schemaKinds: List<SchFunctionCreation>): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    schemaKinds.forEach { out.putAll(it.initDataDefs(cxt)) }
    out.putAll(
        schemaDefs(cxt, FNU.namespace) {
            functionUnion(
                FNU.writeFunction,
                "A function a type's '${SCH.onWrite}' lists, run on the object's data each time it is written; 'fn' " +
                    "says which kind.",
                schemaKinds.filter { it.event == SchEventType.write }.map { it.initDataTypeName },
            )
        },
    )
    return out
}

/**
 * A union discriminated on `fn` over [branches] (qualified type names). With none, an open object naming its kind:
 * a `oneOf` with no branches is a document no reader can make sense of, ours included.
 */
private fun SchTypesBuilder.functionUnion(name: String, description: String, branches: List<String>) {
    if (branches.isEmpty()) {
        type(name) {
            type = SCT.kObject
            this.description = "$description No kind of it is registered here."
            additionalProperties = true
            property(SFU.fn, "Which kind of function this is.", required = true)
        }
    } else {
        variantType(name, description, on = SFU.fn, branches = branches.sorted())
    }
}

/**
 * [defs] with the workflow definition's function lists typed (issue #1501): a definition's `functions` items as
 * [FNU.workflowFunction], a task's as [FNU.taskFunction]. Every other type is passed through as it is.
 */
fun withTypedWorkflowFunctions(defs: Map<String, Any?>): Map<String, Any?> {
    val out = LinkedHashMap(defs)
    for ((typeName, union) in listOf(WFD.defType to FNU.workflowFunction, WFD.taskType to FNU.taskFunction)) {
        val name = qualifyTypeName(typeName, WFD.namespace)
        out[name] = withItemsRef(defs[name], WFD.functions, union) ?: continue
    }
    return out
}

/**
 * The meta-schema's `$defs` ([SchMetaSchema.defs]) with `g-onWrite`'s items typed as [FNU.writeFunction] (issue
 * #1501), and the union and each kind it names beside it -- the document a schema author reads.
 */
fun withTypedWriteFunctions(cxt: KdrCxtBase, metaDefs: Map<String, Any?>, schemaKinds: List<SchFunctionCreation>): Map<String, Any?> {
    val out = LinkedHashMap(metaDefs)
    out[MSCH.nodeTypeName] = withItemsRef(metaDefs[MSCH.nodeTypeName], SCH.onWrite, FNU.writeFunction) ?: return metaDefs
    out.putAll(writeFunctionDefs(cxt, schemaKinds))
    return out
}

/** [type]'s body with its [property]'s `items` replaced by a reference to the union [union], or null when it has none. */
private fun withItemsRef(type: Any?, property: String, union: String): Map<String, Any?>? {
    val body = (type as? Map<*, *>)?.toJsonMap() ?: return null
    val properties = (body[SCH.properties] as? Map<*, *>)?.toJsonMap() ?: return null
    val prop = (properties[property] as? Map<*, *>)?.toJsonMap() ?: return null
    val typed = prop + (SCH.items to mapOf(SCH.dRef to typeRefPath(union, FNU.namespace)))
    return body + (SCH.properties to (properties + (property to typed)))
}
