package com.dynamicruntime.sample.uiblock

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.uiblock.UiBlockService
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import com.dynamicruntime.sample.gedra.SB
import com.dynamicruntime.sample.gedra.SC
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Resolving a UiBlock on a real node (issue #457): the layers merged for a client, and the items the caller's
 * cfacts do not satisfy taken out.
 *
 * Boot-level because the **wiring** is what is under test -- that a component's registration and a client's
 * Gedra config reach one registry, that the cfact registry the expressions parse against is the caller's, and
 * that resolution happens per caller. The merge rules themselves are `UiBlockMergeTest`, where they are maps
 * in and a map out.
 */
class UiBlockResolveTest : StringSpec({

    InstanceRegistry.register(listOf(SampleComponent()))
    val cxt = Startup.mkTestBootCxt("uiBlock", "uiBlockTest", mapOf("KDR_LOAD_SAMPLE" to "true"))
    val service = UiBlockService.get(cxt)

    fun asUser(name: String, client: String? = null, vararg roles: String): KdrCxt {
        val sub = if (client == null) cxt.mkSubContext(name) else cxt.mkSubContext(name, client)
        sub.userProfile = UserProfile(authId = "someone", client = client ?: sub.userProfile.client,
            roles = roles.toSet())
        return sub
    }

    fun itemIds(scope: KdrCxt): List<String?> =
        (service.resolve(scope, SB.nav)?.get(SB.items) as? List<*>)
            ?.map { (it as Map<*, *>)[SB.id] as String? } ?: emptyList()

    fun labelOf(scope: KdrCxt, id: String): String? =
        (service.resolve(scope, SB.nav)?.get(SB.items) as? List<*>)
            ?.map { it as Map<*, *> }?.firstOrNull { it[SB.id] == id }?.get(SB.label) as String?

    "an ordinary caller sees only what their cfacts satisfy" {
        // `users` needs `isAdmin`; `perimeter` needs the `edge` boot role, which an application never matches;
        // `retired` says `#never`. All three are in the base, and none of them is here.
        itemIds(asUser("plain", null, ROLE.user)) shouldBe listOf(SB.overview)
    }

    "an administrator sees the item behind isAdmin, and still not the others" {
        itemIds(asUser("admin", null, ROLE.user, ROLE.admin)) shouldBe listOf(SB.overview, SB.users)
    }

    "a retired item is in the block and never shown" {
        // `#never` is how something is taken away without merging learning to delete: still in the base, so
        // "why is this gone?" is answered by reading the item.
        val base = service.resolve(asUser("admin2", null, ROLE.user, ROLE.admin), SB.nav)
        (base?.get(SB.items) as List<*>).none { (it as Map<*, *>)[SB.id] == SB.retired } shouldBe true
    }

    "the condition itself does not travel to the frontend" {
        // It decided the item; it is not something the frontend needs, and shipping it would put the caller's
        // vocabulary on the wire.
        val items = service.resolve(asUser("admin3", null, ROLE.user, ROLE.admin), SB.nav)
            ?.get(SB.items) as List<*>
        items.none { (it as Map<*, *>).containsKey(UIB.cfactExpression) } shouldBe true
    }

    "a client's overlay renames one item and adds another, in order" {
        // Matched by the base's primary key, so `overview` is changed rather than duplicated; the added item
        // states its own displayOrder and lands between the base's two.
        val acme = asUser("acme", SC.acme, ROLE.user, ROLE.admin)
        itemIds(acme) shouldBe listOf(SB.overview, SB.siteAudits, SB.users)
        labelOf(acme, SB.overview) shouldBe "Acme overview"
    }

    "another client sees none of it" {
        val globex = asUser("globex", SC.globex, ROLE.user, ROLE.admin)
        itemIds(globex) shouldBe listOf(SB.overview, SB.users)
        labelOf(globex, SB.overview) shouldBe "Overview"
    }

    "a block nothing registers resolves to nothing" {
        service.resolve(asUser("plain2", null, ROLE.user), "noSuchBlock") shouldBe null
    }
})
