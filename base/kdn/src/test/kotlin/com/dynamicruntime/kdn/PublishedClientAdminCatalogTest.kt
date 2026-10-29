package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.SECT
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * What of the client-scoped administration is published (issue #1483): what an AI configuring one client and its users
 * needs -- the configuration's reads, write, and patch; the users' listing and the calls that set them up; and a
 * client's file refs, whose bytes no bundle carries. A caller without env auth is shown these in the catalog, as an
 * outside developer is, and not the rest: publishing, which a client's own people do; the shortcuts the frontend's
 * pages use, which a write or a patch already covers; and the web assets, which a sandbox may not change and which
 * reach everyone in the client at once, as a publish does.
 *
 * The published set is pinned whole, so that adding to it or taking from it is a decision this test is changed for.
 */
class PublishedClientAdminCatalogTest : StringSpec({
    val cxt = TestInstances.default("publishedClientAdmin")

    val published = setOf(
        CFEP.bundles, CFEP.bundle, CFEP.slotEntries, CFEP.bundleWrite, CFEP.bundlePatch,
        UADEP.users, UADEP.userCreate, UADEP.userSetRoles, UADEP.userSetLabels, UADEP.userSetEnabled, UADEP.userSetName,
        UADEP.userSetOrg,
        UADEP.clientFiles, UADEP.clientFilesTakes, UADEP.clientFilesUpload, UADEP.clientFilesDownloadMeta,
    )

    "a client's administrator without env auth is shown exactly the published client administration" {
        // The premise: this node grants no env auth of its own accord, so the caller is the production one.
        cxt.instanceConfig.get(ACFG.assumeEnvAuth) shouldNotBe true
        val admin = TestUser.create(cxt, "pubca-admin@example.com", level = ROLE.admin, userClient = CL.public)

        val catalog = admin.catalog()
        catalog[EI.filtersAvailable] shouldBe false
        val shown = TestUser.endpointsIn(catalog).filter { it[EI.path].toString().startsWith("/${SECT.clientAdmin}/") }
        shown.map { it[EI.path] }.toSet() shouldBe published
        shown.forEach { it[EI.publicApi] shouldBe true }
    }

    "publishing, and the shortcuts a write or a patch covers, stay unpublished" {
        val admin = TestUser.create(cxt, "pubca-admin@example.com", level = ROLE.admin, userClient = CL.public)
        val paths = TestUser.pathsIn(admin.catalog())
        paths shouldContainAll listOf(CFEP.bundleWrite, CFEP.bundlePatch)
        paths shouldNotContainAnyOf listOf(
            CFEP.bundlePublish, CFEP.bundleImpact, CFEP.publishedOnly, CFEP.reload, CFEP.editor,
            UADEP.userSearch, UADEP.userDelete, UADEP.clientFilesRemove, UADEP.clientFilesDescribe,
            UADEP.clientWeb, UADEP.clientWebUpload,
        )
    }
})
