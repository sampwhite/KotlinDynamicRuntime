package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.user.TestUser
import io.kotest.matchers.shouldBe

/**
 * Publishes acme's stored configuration [name] as [admin] and reloads acme, so acme's people are served it (issue
 * #994). acme has a Shadow Sandbox, which makes it published-only: an editor's save is a draft that `acme:sandbox`
 * runs, and acme runs it once it is published. The calls are the client page's Publish (`ClientsApi.publishBundle`):
 * the deployment-wide routes, naming acme, for an administrator [acrossClients]; the caller's own client's otherwise.
 */
fun publishAcme(admin: TestUser, acrossClients: Boolean = true, name: String = CPY.copyConfigName) {
    val published = if (acrossClients) {
        admin.postData(ACEP.bundlePublish, mapOf(CFEP.client to SC.acme, CFEP.name to name))
            .also { admin.postData(ACEP.reload, mapOf(CFEP.client to SC.acme)) }
    } else {
        admin.postData(CFEP.bundlePublish, mapOf(CFEP.name to name)).also { admin.postData(CFEP.reload, emptyMap()) }
    }
    // A refused call comes back without results rather than throwing, so say so here rather than at a later read.
    published[CFEP.published] shouldBe true
}
