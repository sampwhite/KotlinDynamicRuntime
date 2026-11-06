package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * The client's unpublished drafts (issue #1638), as Home's "Unpublished drafts" card reads them: a draft written is
 * counted, publishing it clears it, and from inside the sandbox the answer is the parent's. Over a person's own client,
 * which has a sandbox, on the shared instance.
 */
class UnpublishedDraftsTest : StringSpec({
    val cxt = TestInstances.default("drafts1638")

    /** A configuration [name] of [client] declaring one cfact of its own: something to leave as a draft. */
    fun bundle(client: String, name: String): Map<String, Any?> = mapOf(
        CFEP.name to name,
        CFEP.namespaceField to clientNamespace(client),
        CFEP.slots to mapOf(
            CCT.cfactDef to listOf(
                mapOf(CCT.name to "${name}Ready", CCT.group to "grp", CCT.description to "Ready.", CCT.toFrontend to false),
            ),
        ),
    )

    "a draft is counted until it is published, and the sandbox answers for its parent" {
        val person = TestUser.create(cxt, "maker@drafts1638.test")
        val client = person.postData(AEP.createOwnClient, mapOf(CLD.name to "Drafts"))[CLD.client].toOptStr().shouldNotBeNull()
        val before = person.getItem(CFEP.drafts)
        before[CFEP.client] shouldBe client
        before[CFEP.inSandbox] shouldBe false
        val baseline = before[CFEP.names].toJsonListOfStrings()

        person.postData(CFEP.bundleWrite, bundle(client, "draft1638"))
        val drafted = person.getItem(CFEP.drafts)
        drafted[CFEP.names].toJsonListOfStrings() shouldContain "draft1638"
        (drafted[CFEP.count] as Number).toInt() shouldBe baseline.size + 1

        person.postData(CFEP.bundlePublish, mapOf(CFEP.names to listOf("draft1638")))
        val published = person.getItem(CFEP.drafts)
        published[CFEP.names].toJsonListOfStrings() shouldNotContain "draft1638"
        (published[CFEP.count] as Number).toInt() shouldBe baseline.size

        // From inside the sandbox: the parent's drafts, which the sandbox runs, said to be asked from there.
        person.postData(CFEP.bundleWrite, bundle(client, "sandboxed1638"))
        person.postData(AEP.openSandbox, emptyMap())
        val inside = person.getItem(CFEP.drafts)
        inside[CFEP.client] shouldBe client
        inside[CFEP.inSandbox] shouldBe true
        inside[CFEP.names].toJsonListOfStrings() shouldContain "sandboxed1638"
    }

    "only a client's administrator may ask" {
        TestUser.create(cxt, "member@drafts1638.test").expectError(EXC.notAuthorized, CFEP.drafts)
        // A public administrator administers only their own users, not the client everyone in public shares.
        TestUser.create(cxt, "public@drafts1638.test", level = ROLE.admin, userClient = CL.public).expectError(EXC.notAuthorized, CFEP.drafts)
    }
})
