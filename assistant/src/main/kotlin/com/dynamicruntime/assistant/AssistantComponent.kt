package com.dynamicruntime.assistant

import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.cfact.CFGRP
import com.dynamicruntime.common.cfact.CFactDef
import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.Presence
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.ServiceEntry
import com.dynamicruntime.common.startup.service

/**
 * The workflow assistant (issues #1524-#1527): a client administrator describes a workflow in words, and Claude, with
 * this application's own endpoints as tools, drafts it in the client's sandbox for a person to try and publish.
 * This issue (#1525) brings the model ([AssistantService.model]) and the operator's controls: which clients may use
 * it, and how much (`docs/design/workflow-assistant.md`).
 *
 * Found through ServiceLoader, like the edge, and on application nodes only. It always loads -- its tables, its
 * operator endpoints, and its cfact -- so an operator can see why it is unavailable and switch clients on ahead of a
 * key; **with no key nothing else changes**: [ASST.onCFact] stays false, so no menu offers it.
 */
class AssistantComponent : ComponentDefinition {
    override val providerName: String = "assistant"

    /** The component's own root: its schema namespace and its cfact live under it. */
    override val ownerRoot: String = ASST.root

    override fun presence(cxt: KdrCxt): Presence = Presence(roles = setOf(BOOT.app))

    override fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {
        collector.addTables(assistantTables(cxt))
        collector.addModule(assistantOperatorSchema(cxt))
        collector.addModule(assistantChatSchema(cxt))
        collector.addCFact(
            CFactDef(
                ASST.onCFact, CFGRP.caller,
                "True when the workflow assistant is available to the caller's client: this node can reach the model, " +
                    "and an operator has switched the client on (a sandbox goes by its parent). Not whether the " +
                    "month's budget is left.",
            ),
        ) { AssistantService.getOrNull(it)?.isOn(it, it.userProfile.client) == true }
    }

    override fun services(cxt: KdrCxt): List<ServiceEntry> = listOf(service(::AssistantService))
}
