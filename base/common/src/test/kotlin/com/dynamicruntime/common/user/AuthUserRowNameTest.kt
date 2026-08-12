package com.dynamicruntime.common.user

import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The name/entity fields must survive the extract -> toMap round trip. AuthUserRow's own comment is explicit
 * that "every typed field this class exposes has to travel back out, or a caller that sets one sees it
 * silently dropped on write" -- so a new pair of fields is exactly the kind of thing that gets read but not
 * written.
 *
 * The pair is also deliberately *independent*: `name` holds a person's full name as readily as a business's,
 * so it has to outlive a change to `isEntity` rather than being cleared along with it.
 */
class AuthUserRowNameTest : StringSpec({

    fun storedRow(authData: Map<String, Any?>): Map<String, Any?> = mapOf(
        AU.userId to 5L,
        AU.primaryId to "biz@example.com",
        PF.client to "acme",
        AU.username to "acme_co",
        PF.enabled to true,
        AU.authUserData to buildMap<String, Any?> { put(AD.roles, listOf(ROLE.user)); putAll(authData) },
    )

    "an unnamed personal account carries neither field, in the row or on write" {
        val row = AuthUserRow.extract(storedRow(emptyMap()))
        row.isEntity shouldBe false
        row.name shouldBe null

        // Written back, the authUserData must not gain isEntity/name for an unnamed personal account.
        val out = row.toMap()[AU.authUserData]!!.toJsonMap()
        out.containsKey(AD.isEntity) shouldBe false
        out.containsKey(AD.name) shouldBe false
    }

    "an entity account round-trips its flag and name through extract and toMap" {
        val row = AuthUserRow.extract(storedRow(mapOf(AD.isEntity to true, AD.name to "Acme Co")))
        row.isEntity shouldBe true
        row.name shouldBe "Acme Co"

        // The write preserves both -- this is the assertion the class's own comment predicts a new field fails.
        val out = row.toMap()[AU.authUserData]!!.toJsonMap()
        out[AD.isEntity] shouldBe true
        out[AD.name] shouldBe "Acme Co"

        // And a re-extract of what was written recovers the same values.
        val reExtracted = AuthUserRow.extract(row.toMap())
        reExtracted.isEntity shouldBe true
        reExtracted.name shouldBe "Acme Co"
    }

    "a personal account carries a full name with no entity flag" {
        val row = AuthUserRow.extract(storedRow(mapOf(AD.name to "Ada Lovelace")))
        row.isEntity shouldBe false
        row.name shouldBe "Ada Lovelace"

        val out = row.toMap()[AU.authUserData]!!.toJsonMap()
        out.containsKey(AD.isEntity) shouldBe false
        out[AD.name] shouldBe "Ada Lovelace"
    }

    /**
     * The name is not the flag's dependent. Clearing `isEntity` reclassifies what the name means -- a business
     * becoming a person -- and dropping it there would be silent data loss on an ordinary edit.
     */
    "clearing entity status keeps the name" {
        val row = AuthUserRow.extract(storedRow(mapOf(AD.isEntity to true, AD.name to "Acme Co")))
        row.isEntity = false

        val out = row.toMap()[AU.authUserData]!!.toJsonMap()
        out.containsKey(AD.isEntity) shouldBe false
        out[AD.name] shouldBe "Acme Co"
    }

    "clearing the name removes just the name" {
        val row = AuthUserRow.extract(storedRow(mapOf(AD.isEntity to true, AD.name to "Acme Co")))
        row.name = null

        val out = row.toMap()[AU.authUserData]!!.toJsonMap()
        out[AD.isEntity] shouldBe true
        out.containsKey(AD.name) shouldBe false
    }
})
