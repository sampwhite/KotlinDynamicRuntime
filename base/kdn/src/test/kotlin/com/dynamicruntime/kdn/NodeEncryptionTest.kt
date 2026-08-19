package com.dynamicruntime.kdn

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.node.IC
import com.dynamicruntime.common.node.InstanceConfigService
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.util.mkEncryptionKey
import com.dynamicruntime.common.util.stdHashToBytes
import com.dynamicruntime.common.util.toReadableChars
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Verifies (issue #46) that a booted instance has a working, persistent encryption key on [NodeService].
 * That `encryptString`/`decryptString` round-trip proves two things at once: the database was functional at
 * startup (`InstanceConfigService.onCreate` connected to it and created the `InstanceConfig` table during
 * boot) and a persistent encryption key was loaded into `NodeService` from it.
 *
 * These read-only tests share one booted instance (cached by name); only the inexpensive context name varies.
 */
class NodeEncryptionTest : StringSpec({

    fun node(cxtName: String): NodeService =
        NodeService.get(Startup.mkTestBootCxt(cxtName, "nodeEncryptionTest"))

    "a booted instance can encrypt and decrypt strings through NodeService" {
        val node = node("enc")
        val secret = "swordfish|hunter2 — the actual secret" // a pipe in the plaintext must survive the round-trip
        val encrypted = node.encryptString(secret)
        encrypted shouldNotBe secret
        // Ciphertext is stamped with the active key's lookup name, so a rotated key can still be selected.
        encrypted.startsWith(node.instanceAuthConfigKey + "|") shouldBe true
        node.decryptString(encrypted) shouldBe secret
    }

    "the encryption key was persisted to the InstanceConfig table at startup" {
        val cxt = Startup.mkTestBootCxt("encPersisted", "nodeEncryptionTest")
        val node = NodeService.get(cxt)
        val service = InstanceConfigService.get(cxt)
        // The auth-config row is in the database, under the active key's name, holding the encryption key.
        val row = service.getConfig(cxt, node.instanceAuthConfigKey).shouldNotBeNull()
        row[IC.configType] shouldBe InstanceConfigService.authConfigType
        val data = row[IC.configData]!!.toJsonMap()
        (data[InstanceConfigService.encryptionKeyField] as? String).shouldNotBeNull()
    }

    "decrypting text whose key name is unknown fails" {
        val node = node("encBadKey")
        shouldThrow<KdrException> { node.decryptString("zz|c29tZQ") }
    }

    "decrypting text without the keyName| prefix fails" {
        val node = node("encMalformed")
        shouldThrow<KdrException> { node.decryptString("no-prefix-here") }
    }

    // --- the verification code must not be derivable from public inputs -------
    //
    // The code was once `hash(address + formAuthToken)` with an *unkeyed* digest -- and both inputs are
    // public (the address is chosen by the attacker; the form token is handed to any anonymous caller by
    // /auth/form/createToken). So anyone could compute any account's code offline and log in as them. The
    // fix keys it under the node's secret, and these are the properties that keep it fixed.

    "a verification code is stable for the same inputs, so the server can recompute it to verify" {
        val node = node("vcStable")
        val a = node.computeVerifyCode("token-abc", "victim@example.com")
        val b = node.computeVerifyCode("token-abc", "victim@example.com")
        a shouldBe b
        a.isNotEmpty() shouldBe true
    }

    "the code changes with the address and with the token, so it binds both" {
        val node = node("vcBinds")
        val base = node.computeVerifyCode("token-abc", "victim@example.com")
        node.computeVerifyCode("token-abc", "other@example.com") shouldNotBe base // different address
        node.computeVerifyCode("token-xyz", "victim@example.com") shouldNotBe base // different token
    }

    /**
     * The takeover, reproduced and refused. An attacker holds the address and the form token and computes the
     * *unkeyed* digest of them -- exactly what the old code was. It must not match the code the node issues,
     * because the node's is keyed and the attacker has no key.
     */
    "the code an attacker can compute from public inputs does not match the real one" {
        val node = node("vcTakeover")
        val token = "token-abc"
        val address = "victim@example.com"

        // What the attacker can build with only public values (the pre-fix algorithm, verbatim).
        val attackerGuess = (address + token).stdHashToBytes().toReadableChars(4)

        node.computeVerifyCode(token, address) shouldNotBe attackerGuess
    }

    /**
     * The same code, computed under two different node keys, differs -- so the secret is load-bearing, not
     * decoration. If HMAC were dropped for a plain hash again, this fails.
     */
    "the code depends on the node key, so a different key yields a different code" {
        val node = node("vcKeyed")
        val real = node.computeVerifyCode("token-abc", "victim@example.com")

        // A throwaway node carrying a *different* secret key under the same lookup name.
        val other = NodeService()
        other.instanceAuthConfigKey = node.instanceAuthConfigKey
        other.registerEncryptionKey(node.instanceAuthConfigKey, mkEncryptionKey())

        other.computeVerifyCode("token-abc", "victim@example.com") shouldNotBe real
    }
})
