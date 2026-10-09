package com.dynamicruntime.kdn

import com.dynamicruntime.common.cfact.CFACT
import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.home.HomeMenuAudiences
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.uiblock.UiBlockService
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * The names of the home menu's audiences (issue #1094), held in step with the menu they name. A client's
 * administrator chooses who an item is shown to by these names, so a condition the menu writes and this table does
 * not hold would be an audience nobody could read -- and the first test here is what stops a new menu item from
 * adding one quietly.
 */
class HomeMenuAudiencesTest : StringSpec({
    val cxt = TestInstances.default("homeMenuAudiences")

    /** The conditions the shipped menu draws for: each item's, an item with none being drawn for everyone. */
    fun shippedConditions(): Set<String> =
        UiBlockService.get(cxt).merged(cxt, HMENU.block, null).content[HFLD.menu].toJsonListOfMaps()
            .map { CFACT.orAlways(it[UIB.cfactExpression].toOptStr()) }.filter { it != CFACT.neverName }.toSet()

    "every condition the shipped menu draws for has a name, and every name is of one it draws for" {
        val named = HomeMenuAudiences.all.map { it.condition }.toSet()
        // A new menu item with a new condition lands here: name it in HomeMenuAudiences and decide whether to offer it.
        (shippedConditions() - named).shouldBeEmpty()
        // And a name left behind by an item that went away is removed with it.
        (named - shippedConditions()).shouldBeEmpty()
    }

    "an audience is one condition and one name" {
        HomeMenuAudiences.all.groupBy { it.condition }.filterValues { it.size > 1 }.keys.shouldBeEmpty()
        HomeMenuAudiences.all.groupBy { it.name }.filterValues { it.size > 1 }.keys.shouldBeEmpty()
    }

    "what any item may be shown to is the audiences of a client's own people, and no more" {
        // Pinned on purpose: offering another audience to every item of every client is a decision, not a side effect.
        HomeMenuAudiences.offered.map { it.condition } shouldBe listOf(
            CFACTS.app,
            "${CFACTS.loggedIn},${CFACTS.app}",
            "${CFACTS.anonymous},${CFACTS.app}",
            "${CFACTS.administersClient},${CFACTS.app}",
            "${CFACTS.loggedIn},~${CFACTS.administersClient},${CFACTS.app}",
            "${CFACTS.isClientOperator},${CFACTS.app}",
        )
    }

    "an item may be shown to the offered audiences, and put back to its own" {
        val offered = HomeMenuAudiences.offered.map { it.condition }
        fun choices(shipped: String?) = HomeMenuAudiences.choicesFor(shipped).map { it.condition }
        // An item shipped for an offered audience, for nobody, or under a condition with no name: the offered ones.
        choices("${CFACTS.loggedIn},${CFACTS.app}") shouldBe offered
        choices(CFACT.neverName) shouldBe offered
        choices("isChief") shouldBe offered
        // One shipped for an audience that is not offered may still go back to it -- and only that one may.
        choices(CFACTS.isDeploymentOperator) shouldBe offered + CFACTS.isDeploymentOperator
        // No condition at all is everyone, on every node: named, and the item's own.
        choices(null) shouldBe offered + CFACT.alwaysName
        HomeMenuAudiences.of(null)?.name shouldBe HomeMenuAudiences.of(CFACT.alwaysName)?.name
        // A withdrawn item has no audience, and neither does a condition nobody named.
        HomeMenuAudiences.of(CFACT.neverName) shouldBe null
        HomeMenuAudiences.of("isChief") shouldBe null
    }
})
