package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.fileref.FREF
import com.dynamicruntime.common.fileref.FileRefService
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.sql.cache.SqlTableCacheService
import com.dynamicruntime.common.sql.cache.TCH
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.sha256Hex
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A node with its table caches switched off (`KDR_TABLE_CACHE_DISABLED`) reads everything through SQL. A disabled
 * service registers no cache: one it handed out would never load, and its consumers trust a loaded cache as the whole
 * table -- so a node with caching off read every stored configuration, gedra, state and user as absent. **An instance
 * of its own**, since caching is instance-wide.
 */
class CachesDisabledTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("cachesDisabled", "cachesDisabledTest", mapOf(TCH.disabledEnv.name to "true"))
    val svc = GedraConfigService.get(cxt)
    val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

    fun writerOf(client: String) = cxt.mkSubContext("write", client).also { it.userId = 11911L }

    fun defineClient(client: String) {
        svc.writeConfig(
            writerOf(client),
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = "Client $client", usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                    ),
                )
            },
        )
        svc.publish(writerOf(client), GedraId.of(GedraConfigType.configDoc, client, "main"))
        GedraConfigReload.reloadClient(cxt, client)
    }

    "no cache is handed out, and what the caches served is read through SQL" {
        SqlTableCacheService.get(cxt).isDisabled shouldBe true
        svc.configCache.shouldBeNull()
        UserService.get(cxt).userCache.shouldBeNull()
        GedraDataService.get(cxt).dataCache.shouldBeNull()
        GedraDataService.get(cxt).statesCache.shouldBeNull()

        val client = "nocache"
        defineClient(client)
        // The stored configuration a listing and a trial read: there, as SQL holds it.
        svc.listConfigs(writerOf(client)).map { it.configId.baseId } shouldContain "main"
        svc.configsAt(writerOf(client), client, published = false).map { it.configId.baseId } shouldContain "main"
        // A person's users, as a listing reads them.
        val admin = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        val users = UserService.get(cxt)
        val row = users.queryByUserId(cxt, admin.userId).shouldNotBeNull()
        users.enabledUsersOf(cxt, row.identityId).map { it.userId } shouldContain admin.userId
    }

    // Issue #1171 on the SQL path, where a write's own transaction reads its revisions back: the stored set as it was
    // must leave a config the write created out, or that config's own problems would be forgiven it.
    "a draft write of a client whose drafts nothing runs is judged by what it brings, on the SQL path" {
        val client = "nocacheunrun"
        defineClient(client)
        val writer = writerOf(client)
        svc.setPublishedOnly(writer, client, true)
        val hash = png.sha256Hex()
        FileStoreService.get(cxt).put(writer, FileRefService.bucket, FileRefService.keyOf(client, hash), ContentData(png, "image/png"))
        svc.writeConfig(writer, gedraConfig(cxt, "art", clientNamespace(client), client) { fileRef("pic", hash) }, trial = true)
        FileStoreService.get(cxt).delete(writer, FileRefService.bucket, FileRefService.keyOf(client, hash)) shouldBe true
        FileRefService.get(cxt).forget(client, hash)

        // An unrelated draft write goes through, a whole write and a patch: the missing file is the draft's already.
        svc.writeConfig(writer, gedraConfig(cxt, "words", clientNamespace(client), client) { cfact("unrelated", "carry", "Unrelated.") }, trial = true)
        svc.patchConfig(writer, GedraId.of(GedraConfigType.configDoc, client, "words"), trial = true) { it }
        // A patch bringing a missing file of its own is refused: the set as it was is the patched config before.
        shouldThrow<KdrException> {
            svc.patchConfig(writer, GedraId.of(GedraConfigType.configDoc, client, "words"), trial = true) {
                it + (CCT.fileRefDef to listOf(mapOf(CCT.refId to "lost", CCT.source to FREF.sourceStore, CCT.hash to "cd".repeat(32))))
            }
        }.message.orEmpty() shouldContain "'lost'"
        // One creating a config with a missing file of its own is refused.
        shouldThrow<KdrException> {
            svc.writeConfig(writer, gedraConfig(cxt, "more", clientNamespace(client), client) { fileRef("ghost", "ab".repeat(32)) }, trial = true)
        }.message.orEmpty() shouldContain "'ghost'"
    }
})
