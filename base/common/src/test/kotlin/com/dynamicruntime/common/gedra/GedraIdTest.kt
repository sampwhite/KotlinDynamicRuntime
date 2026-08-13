package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.intern.InternCache
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith

/**
 * The gedra id (issue #287).
 *
 * The format is the thing being pinned. Ids are written into a database and travel in URLs, so every rule
 * here is one we have to live with — which makes the round trip, the rejections, and the exact text form the
 * assertions worth having, rather than the class's plumbing.
 */
class GedraIdTest : StringSpec({

    val cxt = KdrCxt.mkSimpleCxt("gedraId")

    "an id spells itself the documented way" {
        val id = GedraId.of(GedraDataType.formDoc, "acme", "e20260812130405123AbCd", "7")
        id.fullId shouldBe "gd.fd.acme.e20260812130405123AbCd~7"
        id.toString() shouldBe id.fullId
        id.toInternString() shouldBe id.fullId
    }

    "a suffix is optional" {
        GedraId.of(GedraDataType.userData, "acme", "u12").fullId shouldBe "gd.ud.acme.u12"
    }

    "every segment survives the round trip" {
        val id = GedraId.parse("gd.fd.acme.e20260812130405123AbCd~7")
        id.storageType shouldBe GedraStorageType.dataStore
        id.kind shouldBe GedraDataType.formDoc
        id.dataType shouldBe GedraDataType.formDoc
        id.client shouldBe "acme"
        id.baseId shouldBe "e20260812130405123AbCd"
        id.suffix shouldBe "7"
        // Reused rather than rebuilt -- and identical either way, which is what makes reusing it safe.
        id.fullId shouldBe "gd.fd.acme.e20260812130405123AbCd~7"
        GedraId.of(id.kind, id.client, id.baseId, id.suffix).fullId shouldBe id.fullId
    }

    "a parsed id with no suffix has none, rather than an empty one" {
        val id = GedraId.parse("gd.ud.global.u1")
        id.suffix.shouldBeNull()
        id.client shouldBe GID.globalClient
    }

    // The storage type is derived from the kind rather than stored beside it, so the pair cannot drift. This
    // is the whole reason for the GedraKind interface -- with a nullable field per enum, a caller could build
    // an id whose storage type said one thing and whose kind said another.
    "storage type follows the kind and cannot be set independently" {
        GedraDataType.entries.forAll { kind ->
            kind.storageType shouldBe GedraStorageType.dataStore
            GedraId.of(kind, "acme", "x").fullId shouldStartWith "gd.${kind.idAbbrev}."
        }
    }

    // Stated as a rule in GID.kindAbbrevLength and worth nothing unless something enforces it: the fixed-width
    // prefix is the payoff, and it is lost the moment one abbreviation is a different length.
    "every kind abbreviation is exactly two characters" {
        GedraId.checkKindAbbrevs().shouldBeEmpty()
    }

    // --- what is refused -----------------------------------------------------

    "a malformed id is refused, and the message says which part is wrong" {
        shouldThrow<KdrException> { GedraId.parse("gd.fd.acme") }
            .message.shouldNotBeNull() shouldContain "four"
        shouldThrow<KdrException> { GedraId.parse("xx.fd.acme.a1") }
            .message.shouldNotBeNull() shouldContain "not a storage type"
        shouldThrow<KdrException> { GedraId.parse("gd.zz.acme.a1") }
            .message.shouldNotBeNull() shouldContain "not a 'gd' kind"
    }

    // Reserved in the storage enum so nothing else claims `gc`, but with no kinds yet. A reader meeting one
    // should be told that plainly rather than left with "'wf' is not a config kind", which reads as a typo.
    "a config id is refused as unsupported rather than as unrecognized" {
        shouldThrow<KdrException> { GedraId.parse("gc.wf.acme.acmePaymentWf~7") }
            .message.shouldNotBeNull() shouldContain "not supported yet"
    }

    // The base id is held to `[A-Za-z0-9_]` for two reasons at once: it keeps the separators out, which is
    // what makes the format parseable, and it keeps a base id a single token to a log tokenizer, which is the
    // property `mkUniqueId` was built to have and the piece anyone hunting one object would paste.
    "an id part may hold only letters, digits and underscores" {
        shouldThrow<KdrException> { GedraId.of(GedraDataType.formDoc, "acme", "has space") }
            .message.shouldNotBeNull() shouldContain "base id"
        shouldThrow<KdrException> { GedraId.of(GedraDataType.formDoc, "acme", "has-dash") }
        shouldThrow<KdrException> { GedraId.of(GedraDataType.formDoc, "acme", "") }
        shouldThrow<KdrException> { GedraId.of(GedraDataType.formDoc, "acme", "ok", "") }
        // A tilde inside the tail would make the id ambiguous about where the suffix starts.
        shouldThrow<KdrException> { GedraId.parse("gd.fd.acme.a~1~2") }
    }

    // Ids are ASCII by construction. Unicode letters would admit lookalikes -- two ids a person reads as the
    // same and the cache treats as different -- which for an identifier is a security-shaped problem, not a
    // tidiness one.
    "a lookalike in another alphabet is not a letter here" {
        // U+0430 CYRILLIC SMALL LETTER A, which renders identically to 'a'.
        shouldThrow<KdrException> { GedraId.of(GedraDataType.formDoc, "acme", "аcme1") }
    }

    "a client must be usable as a code identifier" {
        shouldThrow<KdrException> { GedraId.of(GedraDataType.formDoc, "9acme", "a1") }
            .message.shouldNotBeNull() shouldContain "start with a letter"
        shouldThrow<KdrException> { GedraId.of(GedraDataType.formDoc, "", "a1") }
        GedraId.of(GedraDataType.formDoc, "_internal", "a1").client shouldBe "_internal"
    }

    // --- minting -------------------------------------------------------------

    "a minted id carries its origin letter and is unique" {
        val a = cxt.mkGedraId(GedraDataType.formDoc, "acme", GedraIdContext.excel)
        val b = cxt.mkGedraId(GedraDataType.formDoc, "acme", GedraIdContext.excel)
        a.fullId shouldStartWith "gd.fd.acme.e"
        (a.fullId == b.fullId) shouldBe false
        // It parses back -- the minting path and the format cannot drift apart.
        GedraId.parse(a.fullId).baseId shouldBe a.baseId
    }

    "a user's id is fixed by the user rather than chosen" {
        mkUserGedraId("acme", 12).fullId shouldBe "gd.ud.acme.u12"
        // Invariant: building it twice is the point, since it is how you find it without storing it.
        mkUserGedraId("acme", 12) shouldBe mkUserGedraId("acme", 12)
    }

    "a user-scoped object combines the user and the object's own name" {
        val id = mkUserScopedGedraId(GedraDataType.wfData, "acme", 12, "acmePaymentWf")
        id.fullId shouldBe "gd.wf.acme.u12_acmePaymentWf"
        // Two clients may use one workflow name; the id already carries the client, so nothing else must.
        mkUserScopedGedraId(GedraDataType.wfData, "other", 12, "acmePaymentWf").fullId shouldBe
            "gd.wf.other.u12_acmePaymentWf"
    }

    "children of one import share their parent's base id" {
        val parent = cxt.mkGedraId(GedraDataType.fileRef, "acme", GedraIdContext.excel)
        val rows = (1..3).map { mkChildGedraId(GedraDataType.formDoc, parent, it) }
        rows.map { it.baseId }.toSet() shouldBe setOf(parent.baseId)
        rows.map { it.suffix } shouldBe listOf("1", "2", "3")
        rows.forAll { it.client shouldBe parent.client }
        // A child is a form document even though its parent was the file -- the shared base id is the link,
        // not a shared kind.
        rows[0].kind shouldBe GedraDataType.formDoc
        rows[0].fullId shouldBe "gd.fd.acme.${parent.baseId}~1"
    }

    // --- equality and interning ---------------------------------------------

    // Interning makes `===` usable; it does not make it the contract. Parse hands back uninterned instances
    // on purpose, and one of those has to equal its interned twin -- otherwise a caller that parsed an id
    // could not find the gedra it names in a map keyed by interned ones.
    "two instances of one id are equal, interned or not" {
        val a = GedraId.parse("gd.fd.acme.a1")
        val b = GedraId.of(GedraDataType.formDoc, "acme", "a1")
        (a === b) shouldBe false
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
        mapOf(a to "found")[b] shouldBe "found"
    }

    // The reason parse does not intern: a well-formed id for a gedra nobody created is still well-formed, and
    // interning those would fill the cache with things that do not exist -- which is exactly the property
    // that lets a miss answer "no such gedra".
    "parsing an id does not make it exist" {
        val cache = InternCache<GedraId>("gedraIds")
        GedraId.parse("gd.fd.acme.neverCreated")
        cache.gedraId("gd.fd.acme.neverCreated").shouldBeNull()
        cache.size shouldBe 0
    }

    "a known id is answered from the cache without parsing, and an unknown one is not invented" {
        val cache = InternCache<GedraId>("gedraIds")
        val created = cxt.mkGedraId(GedraDataType.formDoc, "acme", GedraIdContext.ui)
        cache.intern(created)

        (cache.gedraId(created.fullId) === created) shouldBe true
        cache.gedraId("gd.fd.acme.other").shouldBeNull()
        // The read path yields the interned instance when there is one, and a fresh equal one otherwise.
        (cache.readGedraId(created.fullId) === created) shouldBe true
        val unknown = cache.readGedraId("gd.fd.acme.other")
        unknown.baseId shouldBe "other"
        cache.size shouldBe 1
    }
})

/** Applies [check] to every element, reporting which one failed. */
private inline fun <T> Iterable<T>.forAll(check: (T) -> Unit) {
    for (item in this) {
        try {
            check(item)
        } catch (e: AssertionError) {
            throw AssertionError("failed for $item: ${e.message}", e)
        }
    }
}
