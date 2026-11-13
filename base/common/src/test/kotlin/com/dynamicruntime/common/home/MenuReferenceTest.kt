package com.dynamicruntime.common.home

import com.dynamicruntime.common.cfact.CFACTS
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
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
    fun row(id: String) = "| `$id` |"

    "a client's items and cards are listed, the deployment's own are not" {
        for (id in listOf(HMENU.home, HMENU.myClient, HMENU.clientForms, HMENU.workflows, HMENU.cfactReference)) {
            page shouldContain row(id)
        }
        for (id in listOf(HMENU.operator, HMENU.operatorOverview, HMENU.clients, HMENU.becomeUser, HMENU.simulations, HMENU.debug)) {
            page shouldNotContain row(id)
        }
        page shouldContain row(HCARD.administer)
        page shouldNotContain row(HCARD.operate)
        page shouldNotContain row(HCARD.oversee)
    }

    "what is left out is named once, by its label, a header standing for what is under it" {
        page shouldContain "**Operator**, **Simulations**, and **Debug**."
        page shouldNotContain "**Boot checks**"
    }

    "a card whose body is copy is named, with its copy key, as reworded through copy" {
        page shouldContain "The platform's are\n**Register** (`${HCARD.registerCopy}`)."
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
