package com.dynamicruntime.kdn

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.node.IC
import com.dynamicruntime.common.node.InstanceConfigService
import com.dynamicruntime.common.node.NodeService
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
        NodeService.get(Startup.mkTestBootCxt(cxtName, "nodeEncryptionTest")).shouldNotBeNull()

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
        val node = NodeService.get(cxt).shouldNotBeNull()
        val service = InstanceConfigService.get(cxt).shouldNotBeNull()
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
})
