package com.dynamicruntime.common.user

import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The entity fields must survive the extract -> toMap round trip. AuthUserRow's own comment is explicit that
 * "every typed field this class exposes has to travel back out, or a caller that sets one sees it silently
 * dropped on write" -- so a new pair of fields is exactly the kind of thing that gets read but not written.
 */
class AuthUserRowEntityTest : StringSpec({

    fun storedRow(authData: Map<String, Any?>): Map<String, Any?> = mapOf(
        AU.userId to 5L,
        AU.primaryId to "biz@example.com",
        PF.client to "acme",
        AU.username to "acme_co",
        PF.enabled to true,
        AU.authUserData to buildMap<String, Any?> { put(AD.roles, listOf(ROLE.user)); putAll(authData) },
    )

    "a personal account carries no entity fields, in the row or on write" {
        val row = AuthUserRow.extract(storedRow(emptyMap()))
        row.isEntity shouldBe false
        row.entityName shouldBe null

        // Written back, the authUserData must not gain isEntity/entityName for a personal account.
        val out = row.toMap()[AU.authUserData]!!.toJsonMap()
        out.containsKey(AD.isEntity) shouldBe false
        out.containsKey(AD.entityName) shouldBe false
    }

    "an entity account round-trips its flag and name through extract and toMap" {
        val row = AuthUserRow.extract(storedRow(mapOf(AD.isEntity to true, AD.entityName to "Acme Co")))
        row.isEntity shouldBe true
        row.entityName shouldBe "Acme Co"

        // The write preserves both -- this is the assertion the class's own comment predicts a new field fails.
        val out = row.toMap()[AU.authUserData]!!.toJsonMap()
        out[AD.isEntity] shouldBe true
        out[AD.entityName] shouldBe "Acme Co"

        // And a re-extract of what was written recovers the same values.
        val reExtracted = AuthUserRow.extract(row.toMap())
        reExtracted.isEntity shouldBe true
        reExtracted.entityName shouldBe "Acme Co"
    }

    "clearing entity status on the row removes both fields on write" {
        val row = AuthUserRow.extract(storedRow(mapOf(AD.isEntity to true, AD.entityName to "Acme Co")))
        row.isEntity = false
        row.entityName = null

        val out = row.toMap()[AU.authUserData]!!.toJsonMap()
        out.containsKey(AD.isEntity) shouldBe false
        out.containsKey(AD.entityName) shouldBe false
    }
})
