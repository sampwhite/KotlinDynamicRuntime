package com.dynamicruntime.kdn

import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.AuthUserRow
import com.dynamicruntime.common.user.TestUser
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * Deleting a user, in its two senses (issue #396). Driven through the in-process admin endpoint, so
 * the section gate and the whole write path run for real.
 *
 * A **recoverable** delete merely disables the account -- the same durable disable `setEnabled(false)` gives,
 * re-read from the list rather than trusted from the response. A **permanent** delete de-identifies it: the
 * email and username are obfuscated to their `deleted-<userId>` forms, the original email is freed for
 * re-registration, and the row survives only as a disabled tombstone.
 */
class UserDeleteTest : StringSpec({

    "a recoverable delete disables the user and is undone by re-enabling" {
        val cxt = Startup.mkTestBootCxt("userDelete", "userDeleteRecoverableTest")
        val admin = TestUser.createFullAdmin(cxt, "chief@del.com")
        val created = admin.postData(ADEP.userCreate, mapOf(ADF.primaryId to "recover@del.com", ADF.username to "recover"))
        val userId = created[ADF.userId] as Long

        // Delete recoverably (permanent defaults to false): disabled, identifiers untouched.
        val deleted = admin.postData(ADEP.userDelete, mapOf(ADF.userId to userId))
        deleted[ADF.enabled] shouldBe false
        deleted[ADF.primaryId] shouldBe "recover@del.com"

        // Durable: the stored row is disabled, and still carries its real email.
        val listed = admin.getItems(ADEP.users, mapOf(ADF.search to "recover@del.com")).single()
        listed[ADF.enabled] shouldBe false
        listed[ADF.primaryId] shouldBe "recover@del.com"

        // Recoverable means recoverable: re-enabling restores the account intact.
        admin.postData(ADEP.userSetEnabled, mapOf(ADF.userId to userId, ADF.enabled to true))
        admin.getItems(ADEP.users, mapOf(ADF.search to "recover@del.com")).single()[ADF.enabled] shouldBe true
    }

    "a permanent delete obfuscates the email, marks the former user, and frees the address" {
        val cxt = Startup.mkTestBootCxt("userDelete", "userDeletePermanentTest")
        val admin = TestUser.createFullAdmin(cxt, "chief@perm.com")
        val created = admin.postData(
            ADEP.userCreate,
            mapOf(ADF.primaryId to "gone@perm.com", ADF.username to "gone", ADF.name to "Gordon Gone"),
        )
        val userId = created[ADF.userId] as Long

        val deleted = admin.postData(ADEP.userDelete, mapOf(ADF.userId to userId, ADF.permanent to true))
        // The email and username are obfuscated to their deleted-<userId> forms -- the indication that this
        // was once a real user -- and the account is disabled with no password.
        deleted[ADF.enabled] shouldBe false
        deleted[ADF.primaryId] shouldBe AuthUserRow.deletedPrimaryId(userId)
        (deleted[ADF.primaryId] as String) shouldContain "@${AuthUserRow.deletedIdDomain}"
        deleted[ADF.username] shouldBe AuthUserRow.deletedUsername(userId)
        deleted[ADF.hasPassword] shouldBe false
        // It is marked as a deleted tombstone; the login identity is gone but the name is KEPT (a retirement,
        // not a privacy erasure -- the name helps a debugger recognize the account), and roles are cleared.
        deleted[ADF.deleted] shouldBe true
        deleted[ADF.name] shouldBe "Gordon Gone"
        @Suppress("UNCHECKED_CAST")
        (deleted[ADF.roles] as List<String>) shouldBe emptyList()

        // A tombstone cannot be re-enabled, renamed, re-roled, or re-deleted: every edit is refused, so the
        // irreversible delete stays irreversible.
        admin.expectError(EXC.badInput, ADEP.userSetEnabled, mapOf(ADF.userId to userId, ADF.enabled to true))
        admin.expectError(EXC.badInput, ADEP.userSetName, mapOf(ADF.userId to userId, ADF.name to "Back Again"))
        admin.expectError(
            EXC.badInput, ADEP.userSetRoles, mapOf(ADF.userId to userId, ADF.roles to listOf("user", "admin")),
        )
        admin.expectError(EXC.badInput, ADEP.userDelete, mapOf(ADF.userId to userId, ADF.permanent to true))

        // The original email no longer resolves to anyone...
        admin.getItems(ADEP.users, mapOf(ADF.search to "gone@perm.com")) shouldHaveSize 0
        // ...and it is free to be registered afresh -- a duplicate email would be refused, so success proves
        // the obfuscation released the unique identifier. The new account is a distinct, live user.
        val reused = admin.postData(ADEP.userCreate, mapOf(ADF.primaryId to "gone@perm.com", ADF.username to "gone2"))
        reused[ADF.primaryId] shouldBe "gone@perm.com"
        reused[ADF.enabled] shouldBe true
        (reused[ADF.userId] as Long) shouldNotBe userId

        // The tombstone is still there under its obfuscated id, disabled -- an audit record, not a hard delete.
        admin.getItems(ADEP.users, mapOf(ADF.search to "deleted-$userId")).single()[ADF.enabled] shouldBe false
    }

    "you cannot delete your own account, permanently or otherwise" {
        val cxt = Startup.mkTestBootCxt("userDelete", "userDeleteSelfTest")
        val admin = TestUser.createFullAdmin(cxt, "chief@self.com")
        admin.expectError(EXC.badInput, ADEP.userDelete, mapOf(ADF.userId to admin.userId))
        admin.expectError(EXC.badInput, ADEP.userDelete, mapOf(ADF.userId to admin.userId, ADF.permanent to true))
    }
})
