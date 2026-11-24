package com.dynamicruntime.common.home

import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.uiblock.UiBlockSource
import com.dynamicruntime.common.uiblock.uiBlockFaults
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.collections.shouldNotContain as shouldNotContainElement

/**
 * The menu and home cards guide (issue #1767) lists the core items a client's people may be shown, and leaves out the
 * deployment's own -- its operators', a test instance's, a debug session's -- naming them once instead.
 */
class MenuReferenceTest : StringSpec({
    val page = menuAndHomeCardsMarkdown()
    // The facts the shipped cards and the guide's example name.
    val cfacts = setOf(
        CFACTS.loggedIn, CFACTS.app, CFACTS.anonymous, CFACTS.signInOpenToEveryone, CFACTS.clientHasWaitingWorkflows,
        CFACTS.administersClient, CFACTS.clientHasSandbox, CFACTS.selfServeClient, CFACTS.inSandbox, CFACTS.isDeploymentAdmin,
        CFACTS.isEditor, CFACTS.isDeploymentOperator,
    )
    fun row(id: String) = "| `$id` |"

    "a client's items and cards are listed, the deployment's own are not" {
        // The lists alone: the table of pages names some of the same words, as pages.
        val lists = page.substringAfter("## The core menu")
        for (id in listOf(HMENU.home, HMENU.myClient, HMENU.clientForms, HMENU.workflows, HMENU.cfactReference)) {
            lists shouldContain row(id)
        }
        for (id in listOf(HMENU.operator, HMENU.operatorOverview, HMENU.clients, HMENU.becomeUser, HMENU.simulations, HMENU.debug)) {
            lists shouldNotContain row(id)
        }
        lists shouldContain row(HCARD.administer)
        lists shouldNotContain row(HCARD.operate)
        lists shouldNotContain row(HCARD.oversee)
    }

    "what is left out is named once, by its label, a header standing for what is under it" {
        page shouldContain "**Operator**, **Simulations**, and **Debug**."
        page shouldNotContain "**Boot checks**"
    }

    "a card whose body is copy is named, with its copy key, as reworded through copy" {
        page shouldContain "The platform's are\n**Register** (`${HCARD.registerCopy}`)."
    }

    "every page the listed menu items and cards open is one the guide names, with the New form" {
        val items = homeMenuBlock().content[HFLD.menu].toJsonListOfMaps() + homeCardsBlock().content[HCARD.cards].toJsonListOfMaps()
        val opened = items.filterNot { HomeMenuAudiences.deploymentOnly(it[UIB.cfactExpression].toOptStr()) }
            .flatMap { listOf(it) + it[HCARD.links].toJsonListOfMaps() }.mapNotNull { it[UIB.action] as? String }.toSet()
        ClientPages.all.map { it.page } shouldContainAll opened
        page shouldContain "| `${HMENU.pageNewForm}` | `/wa/forms/new` |"
    }

    "the whole card the guide shows is an overlay the checks accept, with a button that opens a page" {
        val example = Regex("```json\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(page).map { it.groupValues[1] }
            .single { "startForm" in it }.jsonMap()!!
        val overlay = UiBlockSource(
            example[CCT.blockId].toString(), isOverlay = true, client = "acme", origin = "guide",
            content = example[CCT.content].toJsonMapOrEmpty(),
        )
        uiBlockFaults(listOf(homeCardsBlock(), overlay), { cfacts }).shouldBeEmpty()
        val card = example[CCT.content].toJsonMapOrEmpty()[HCARD.cards].toJsonListOfMaps().single()
        ClientPages.forName(card[UIB.action].toString()).shouldNotBeNull()
        card[HCARD.actionLabel].shouldNotBeNull()
    }

    "the deployment's own is judged by its facts where the audience table does not name the condition" {
        HomeMenuAudiences.deploymentFacts shouldContainAll setOf(
            CFACTS.isDeploymentOperator, CFACTS.isDeploymentAdmin, CFACTS.hasTestAccess, CFACTS.isEnvDebug, CFACTS.canEnableDebug,
        )
        HomeMenuAudiences.deploymentFacts shouldNotContainElement CFACTS.app
        // Named by the table: its audience decides.
        HomeMenuAudiences.deploymentOnly("${CFACTS.isDeploymentOperator},${CFACTS.app}") shouldBe true
        HomeMenuAudiences.deploymentOnly("${CFACTS.administersClient},~${CFACTS.isDeploymentAdmin},${CFACTS.app}") shouldBe false
        // Not named: another order, or one more fact, still requires a deployment fact.
        HomeMenuAudiences.deploymentOnly("${CFACTS.app},${CFACTS.isDeploymentOperator}") shouldBe true
        HomeMenuAudiences.deploymentOnly("${CFACTS.isDeploymentOperator},${CFACTS.loggedIn}") shouldBe true
        // Negated, one side of an either, or inside a group: not required.
        HomeMenuAudiences.deploymentOnly("${CFACTS.loggedIn},~${CFACTS.isDeploymentAdmin}") shouldBe false
        HomeMenuAudiences.deploymentOnly("${CFACTS.isEditor}|${CFACTS.hasTestAccess}") shouldBe false
        HomeMenuAudiences.deploymentOnly("(${CFACTS.isEditor}|${CFACTS.hasTestAccess}),${CFACTS.app}") shouldBe false
    }
})
