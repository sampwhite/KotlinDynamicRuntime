package com.dynamicruntime.assistant

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.cfact.CFGRP
import com.dynamicruntime.common.cfact.CFactDef
import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.home.HCARD
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.home.HSEC
import com.dynamicruntime.common.home.homeCard
import com.dynamicruntime.common.home.homeMenuBlock
import com.dynamicruntime.common.home.menuItem
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.Presence
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.ServiceEntry
import com.dynamicruntime.common.startup.service
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.uiblock.UiBlockSource
import com.dynamicruntime.common.uiblock.UiRoute
import com.dynamicruntime.common.uiblock.uiBlockOverlay
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toOptLong

/**
 * The workflow assistant (issues #1524-#1527): a client administrator describes a workflow in words, and Claude, with
 * this application's own endpoints as tools, drafts it in the client's sandbox for a person to try and publish.
 * This issue (#1525) brings the model ([AssistantService.model]) and the operator's controls: which clients may use
 * it, and how much (`docs/design/workflow-assistant.md`).
 *
 * Found through ServiceLoader, like the edge, and on application nodes only. It always loads -- its tables, its
 * endpoints, its cfact, and its menu entries -- so an operator can see why it is unavailable and switch clients on ahead
 * of a key; **with no key nothing else changes**: [ASST.onCFact] stays false, so no menu offers the page.
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
        collector.addCFact(
            CFactDef(
                ASST.usableCFact, CFGRP.caller,
                "True when the caller can talk to the workflow assistant, or switch it on: it is on for their client, or " +
                    "offered to it (a self-serve client, issue #1568) -- and, signed in to the client's sandbox, they are " +
                    "there as a user opened from one of the client's own. Not whether they administer the client, nor " +
                    "whether the month's budget is left.",
            ),
        ) {
            // Read only once the switch says yes, so a node with the assistant off asks nothing more of a sandbox caller.
            val service = AssistantService.getOrNull(it)
            val client = it.userProfile.client
            service != null && (service.isOn(it, client) || service.offeredTo(it, client)) && AssistantTurns.personOrNull(it) != null
        }
        collector.addCFact(
            CFactDef(
                ASST.selfServeOfferedCFact, CFGRP.node,
                "True when this deployment offers the workflow assistant to the clients people create for themselves " +
                    "(issue #1636): this node can reach the model, and self-serve clients may switch it on. A fact about " +
                    "the node, whoever is asking.",
            ),
        ) { AssistantService.getOrNull(it)?.offersSelfServe() == true }
    }

    override fun services(cxt: KdrCxt): List<ServiceEntry> = listOf(service(::AssistantService))

    /**
     * The module's menu entries (issue #1527), added by an overlay so core never names a cfact that exists only while
     * this module is loaded: the page beside Reports, for a client's administrator while [ASST.usableCFact] holds -- in
     * the client, and in its sandbox when the assistant answers for them there, as the page's endpoints do -- and the
     * operator's page last in the Operator group.
     */
    override fun uiBlocks(cxt: KdrCxt): List<UiBlockSource> = listOf(
        uiBlockOverlay(HMENU.block, origin = providerName) {
            items(HFLD.menu) {
                menuItem(
                    ASST.menuItem, "Workflow assistant", UiRoute(ASST.page),
                    cfactExpression = "${CFACTS.administersClient},${ASST.usableCFact},${CFACTS.app}",
                    displayOrder = orderAfter(HMENU.reports),
                )
                menuItem(
                    ASST.operatorMenuItem, "Workflow assistant", UiRoute(ASST.operatorPage),
                    cfactExpression = "${CFACTS.isDeploymentOperator},${CFACTS.app}",
                    displayOrder = orderAfter(HMENU.fileStore), parentId = HMENU.operator,
                )
            }
        },
        // Its home cards (issue #1636), ahead of core's: the assistant offered to whoever its menu entry is, and -- to a
        // public administrator, where a client they create comes with it -- the reason to create one.
        uiBlockOverlay(HCARD.block, origin = providerName) {
            items(HCARD.cards) {
                homeCard(
                    ASST.homeCard, "Workflow assistant",
                    "Describe the forms and workflows you want in your own words, and the assistant drafts them in your " +
                        "client's sandbox, for you to try there and publish when they are right.",
                    action = UiRoute(ASST.page), actionLabel = "Open the workflow assistant",
                    // While it is off and the person may switch it on: the page opens on that panel.
                    offerLabel = "Switch on the assistant",
                    kind = ASST.cardKind,
                    cfactExpression = "${CFACTS.administersClient},${ASST.usableCFact},${CFACTS.app}",
                    displayOrder = UIB.orderStep / 2,
                )
                homeCard(
                    ASST.createHomeCard, "Design your own forms",
                    "[Create a client of your own](#${HSEC.ownClient}) and the workflow assistant helps you build it: " +
                        "describe the form you want people to fill in and the steps it goes through, and it drafts them " +
                        "for you to try.",
                    cfactExpression = "${CFACTS.publicMember},${ASST.selfServeOfferedCFact},${CFACTS.app}",
                    displayOrder = UIB.orderStep / 2,
                )
            }
        },
    )

    /**
     * A place in the menu right after the base's item [itemId]: halfway to the item after it. Read from the base, so the
     * entry stays beside its neighbor however the base's items are numbered.
     */
    @KdrPrivate
    fun orderAfter(itemId: String): Int {
        val orders = homeMenuBlock().content[HFLD.menu].toJsonListOfMaps()
            .map { it[HFLD.id].toString() to (it[UIB.displayOrder].toOptLong()?.toInt() ?: 0) }
        val at = orders.firstOrNull { it.first == itemId }?.second
            ?: throw KdrException("The menu has no item '$itemId' to place the assistant's entry after.")
        val next = orders.map { it.second }.filter { it > at }.minOrNull() ?: (at + UIB.orderStep)
        return at + (next - at) / 2
    }
}
