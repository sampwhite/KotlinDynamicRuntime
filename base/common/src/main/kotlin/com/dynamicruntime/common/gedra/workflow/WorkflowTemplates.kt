package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.startup.SchemaCollector

/**
 * The deployment's workflow templates (issue #1120): every **global** workflow, declared in source by a component. No
 * client runs one as it is; a client's workflow names one ([WFD.fromTemplate]) and is read over it
 * ([expandWorkflowTemplate]), so this is what that reading looks a template up in.
 */
object WorkflowTemplates {
    /** The templates [configs] declare, by rooted id: the global configs' workflows. */
    fun of(configs: List<GedraConfig>): Map<String, WfDef> =
        configs.filter { it.gedraId.client == GID.globalClient }.flatMap { it.workflows.values }.associateBy { it.workflowId }

    /**
     * The templates of [cxt]'s instance, looked up when a workflow asks for one: the global configs its components
     * declared, which are in place before any stored configuration is read. None where the instance has no collector --
     * a store built by hand -- and a workflow made from a template then does not read.
     */
    fun lookup(cxt: KdrCxtBase): WfTemplateLookup = WfTemplateLookup { templateId ->
        val collector = (cxt as? KdrCxt)?.let { SchemaCollector.get(it) } ?: return@WfTemplateLookup null
        collector.gedraConfigs.configs.firstNotNullOfOrNull { config ->
            config.workflows[templateId]?.takeIf { config.gedraId.client == GID.globalClient }
        }
    }

    /** The template [templateId] of [cxt]'s instance; refused (404) when the deployment declares none. */
    fun template(cxt: KdrCxt, templateId: String): WfDef = lookup(cxt).template(templateId)
        ?: throw KdrException("There is no workflow template '$templateId'.", code = EXC.notFound)

    /**
     * A workflow's [stored] definition read as the workflow it is (issue #1120): one made from a template is its
     * overlay expanded over the template; any other is read as written.
     */
    fun readStored(cxt: KdrCxt, stored: Map<String, Any?>): WfDef {
        val templateId = stored[WFD.fromTemplate] as? String ?: return parseWfDef(cxt, stored)
        return expandWorkflowTemplate(cxt, template(cxt, templateId), stored)
    }

    /**
     * What a store writes for [edited] (issue #1120): for a workflow made from a template, the overlay that makes the
     * template into it ([workflowTemplateOverlay]) -- refused when it changes what an overlay cannot state -- and for any
     * other, the definition whole.
     */
    fun storedForm(cxt: KdrCxt, edited: WfDef): Map<String, Any?> =
        edited.fromTemplate?.let { workflowTemplateOverlay(template(cxt, it), edited) } ?: edited.toJsonMap()
}
