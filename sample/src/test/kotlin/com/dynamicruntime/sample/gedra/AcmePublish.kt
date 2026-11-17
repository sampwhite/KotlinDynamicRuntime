package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.user.TestUser
import io.kotest.matchers.shouldBe

/**
 * Publishes acme's stored configuration [name] as [admin] and reloads acme, so acme's people are served it (issue
 * #994). acme has a Shadow Sandbox, which makes it published-only: an editor's save, made in `acme:sandbox` (issue
 * #1824), is a draft the sandbox runs, and acme runs it once it is published. The calls are the client page's Publish
 * (`ClientsApi.publishBundles`): the deployment-wide routes, naming acme, for an administrator [acrossClients]; the
 * caller's own client's otherwise, which for a client-scoped [admin] is the sandbox -- it has opened `acme:sandbox`
 * to make the change -- so the publish and the reload act on acme from there.
 */
fun publishAcme(admin: TestUser, acrossClients: Boolean = true, name: String = CPY.copyConfigName) {
    val published = if (acrossClients) {
        admin.postData(ACEP.bundlePublish, mapOf(CFEP.client to SC.acme, CFEP.names to listOf(name)))
            .also { admin.postData(ACEP.reload, mapOf(CFEP.client to SC.acme)) }
    } else {
        admin.postData(CFEP.bundlePublish, mapOf(CFEP.names to listOf(name))).also { admin.postData(CFEP.reload, emptyMap()) }
    }
    // A refused call comes back without results rather than throwing, so say so here rather than at a later read.
    published[CFEP.configs].toJsonListOfMaps().single()[CFEP.published] shouldBe true
}
