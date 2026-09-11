package com.dynamicruntime.kdn

import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The transaction-scoped context capability (issue #687): `mkTransactionSubContext` marks a context whose life
 * is bounded to a transaction, and only such a context may have its **owner** rebound mid-life
 * ([com.dynamicruntime.common.context.KdrCxt.bindTransactionOwner]) -- who is *acting* stays put, so a rebind
 * on a request's own context (which would bleed the owner out) is refused.
 */
class KdrCxtTransactionTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("kdrCxtTx", "kdrCxtTx")

    "a plain context is not transaction-scoped; mkTransactionSubContext marks one that is" {
        cxt.transactionScoped shouldBe false
        cxt.mkTransactionSubContext("tx").transactionScoped shouldBe true
    }

    "bindTransactionOwner rebinds the owner on a transaction-scoped context, leaving the actor" {
        val tx = cxt.mkTransactionSubContext("tx")
        val actor = tx.userProfile.userId
        tx.bindTransactionOwner(userId = 4242L, client = "otherClient", org = "orgA")
        tx.userId shouldBe 4242L        // who owns the rows this context writes...
        tx.client shouldBe "otherClient"
        tx.org shouldBe "orgA"
        tx.userProfile.userId shouldBe actor // ...but who is acting is unchanged, so audit still names the actor
    }

    "bindTransactionOwner refuses a context that is not transaction-scoped" {
        // Rebinding a request's own (or a plain sub) context would leak the owner past the write, so it throws.
        shouldThrow<KdrException> { cxt.mkSubContext("plain").bindTransactionOwner(1L, "c", null) }
        shouldThrow<KdrException> { cxt.bindTransactionOwner(1L, "c", null) }
    }
})
