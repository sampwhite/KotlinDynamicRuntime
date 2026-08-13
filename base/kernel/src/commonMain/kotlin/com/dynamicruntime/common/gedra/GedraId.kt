package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.intern.Internable

/**
 * The pieces of a [GedraId]'s text form that are fixed by the format rather than supplied (issue #287).
 */
@Suppress("ConstPropertyName")
object GID {
    /** Separates the four parsed segments: storage, kind, client, base id. */
    const val partSep = '.'

    /** Introduces the optional trailing suffix — a child's index, or a config revision. */
    const val suffixSep = '~'

    /**
     * The reserved client that owns application-level data: the parent of all clients.
     *
     * Not to be confused with `ROLE.allClients` / `AdminScope.allClients`, which sound identical and are a
     * different axis entirely. Those say who may *read* across clients; this says who *owns* a row. A caller
     * with the capability reaches every client's data; data in the global client belongs to no one client.
     */
    const val globalClient = "global"

    /**
     * Every kind abbreviation is exactly this long, which is why `userData` abbreviates to `ud` and not `u`.
     * It makes the first six characters of every id a fixed-width prefix (`gd.fd.`, `gd.ud.`, `gc.wf.`), so a
     * column of ids lines up when read by eye — which is most of how ids are read. The value evaporates the
     * first time an abbreviation is a different length, so [checkKindAbbrevs] enforces it.
     */
    const val kindAbbrevLength = 2
}

/**
 * Which family of tables a gedra lives in — the first segment of a [GedraId].
 *
 * Also the switch that decides which enum the *second* segment is drawn from, which is why the two kind
 * enums have to be reachable through one interface: see [GedraKind].
 */
@Suppress("EnumEntryName")
enum class GedraStorageType(val idAbbrev: String) {
    /** Ordinary stored entities: form documents, workflow data, users, file references. */
    dataStore("gd"),

    /**
     * Definitions — clients, workflows, traits — which are revisioned and pinned per environment.
     *
     * Reserved here so the abbreviation cannot be claimed by anything else and the intent is visible, but it
     * has no kinds yet: config storage is a later design, and parsing a `gc.` id says so rather than failing
     * in some way a reader would have to interpret.
     */
    configStore("gc"),
}

/**
 * What a gedra *is* — the second segment of a [GedraId], drawn from [GedraDataType] or (later) the config
 * equivalent depending on the first.
 *
 * One interface over both enums rather than a nullable field per enum on [GedraId]. Two nullable fields make
 * three illegal states representable — both set, neither set, and one that disagrees with the storage type —
 * each prevented only by a rule somebody has to remember. Here [storageType] is *derived* from the kind, so it
 * cannot contradict it, and there is no state to validate.
 */
interface GedraKind {
    /** This kind's two-character abbreviation in an id; see [GID.kindAbbrevLength]. */
    val idAbbrev: String

    /** The table family this kind is stored in — fixed per enum, not per entry. */
    val storageType: GedraStorageType
}

/**
 * The kinds of gedra stored as data. The names are the ones settled for the entity types themselves, so the
 * enum and the vocabulary cannot drift: `userData` rather than `user`, `formDoc` rather than "document",
 * `wfData` as the half of "workflow" that is captured data rather than rules.
 *
 * `wfDef` is deliberately absent. A workflow *definition* is config, so it belongs to the config enum — which
 * makes the `wfDef` / `wfData` split the codebase already settled land exactly on the `gc` / `gd` split. Both
 * will abbreviate to `wf`, which is safe because the first segment already says which is meant, and reads
 * well: `gd.wf.` is a workflow's data, `gc.wf.` is its rules.
 */
@Suppress("EnumEntryName")
enum class GedraDataType(override val idAbbrev: String) : GedraKind {
    /** User/account data. */
    userData("ud"),

    /** A single stored entity holding form-entry data. */
    formDoc("fd"),

    /** Data captured while a workflow was followed. */
    wfData("wf"),

    /** A pointer to (and claim on) file content held in a file store. */
    fileRef("fr");

    override val storageType: GedraStorageType get() = GedraStorageType.dataStore
}

/**
 * Where a randomly generated base id came from — the single letter it starts with.
 *
 * It is never parsed back out. Its whole job is that somebody looking at an id, or at a column of them, can
 * see how the objects got there: a run of `e`s is an import, a `u` is somebody working in the UI. Expect this
 * list to grow as more things create gedras.
 */
@Suppress("EnumEntryName")
enum class GedraIdContext(val letter: String) {
    /** Somebody working in the application's own UI. */
    ui("u"),

    /** A spreadsheet import. */
    excel("e"),

    /** An API caller. */
    api("a"),

    /** The runtime itself — bootstrapping, migrations, scheduled work. */
    system("s"),

    /** Created by a test. */
    test("t"),
}

/**
 * The identity of a gedra: `<storage>.<kind>.<client>.<baseId>` with an optional `~<suffix>` (issue #287).
 *
 * ```
 * gd.fd.acme.e20260812130405123AbCd~7
 * ~~ ~~ ~~~~ ~~~~~~~~~~~~~~~~~~~~~~ ~
 * |  |  |    |                      the 7th row of the import that produced it
 * |  |  |    an opaque base id -- `e` says an Excel import made it
 * |  |  the owning client
 * |  a form document
 * stored as data
 * ```
 *
 * ### What is parsed and what is not
 *
 * The first three segments are structure and are read back out. **The base id is opaque** — it is built to be
 * invariant given its inputs, not to be taken apart. A random one is a context letter followed by the
 * project's standard time-sortable unique id; a deterministic one spells out what makes it unique (`u12` for a
 * user's gedra, `u12_acmePaymentWf` for the one workflow a user may have). Nothing reads either back.
 *
 * The [suffix] is not parsed either, but it *is* held separately, because for config it is the revision and
 * "same object, which revision" is a question worth being able to ask without string surgery.
 *
 * ### Interning, and why parsing does not do it
 *
 * A `GedraId` is [Internable], and ids for gedras that exist are interned so that `===` is a legitimate
 * identity test and a cache miss answers "no such gedra" without a database round trip (issue #280).
 *
 * [parse] deliberately does **not** intern. It is fed whatever a caller sends, and a well-formed id for an
 * object that was never created is still well-formed — interning those would fill the cache with ids for
 * things that do not exist and destroy the property that makes the cache worth having. Interning is for ids
 * being created or loaded. The consequence, and it matters: [equals] is real value equality on [fullId], not
 * identity, so an id that came from [parse] still behaves correctly as a map key and still equals its
 * interned twin.
 *
 * ### Text form
 *
 * `.` and `~` are both *unreserved* in RFC 3986, so an id needs no escaping in a URL. The cost, accepted
 * knowingly: both are split points for a full-text log tokenizer, so a whole id pasted into a log search does
 * not stay one token the way `mkUniqueId`'s output was carefully built to. The [baseId] does, though — it is
 * held to `[A-Za-z0-9_]` for exactly that reason — and it is the piece anyone hunting a single object would
 * paste.
 */
class GedraId private constructor(
    /** What this gedra is; [storageType] follows from it. */
    val kind: GedraKind,
    /** The owning client, or [GID.globalClient] for application-level data. */
    val client: String,
    /** The opaque unique part. Never parsed; see the class note. */
    val baseId: String,
    /** A child's index within its imported parent, or a config revision. Absent for most ids. */
    val suffix: String?,
    /** The canonical text form — the intern key, the `toString`, and what is stored and transmitted. */
    val fullId: String,
) : Internable {

    /** Which table family this gedra lives in. Derived, so it can never disagree with [kind]. */
    val storageType: GedraStorageType get() = kind.storageType

    /** [kind] as a data kind, or null when this is a config id. */
    val dataType: GedraDataType? get() = kind as? GedraDataType

    override fun toInternString(): String = fullId

    override fun toString(): String = fullId

    /**
     * Value equality on [fullId], not identity.
     *
     * Interning makes `===` *usable* for ids known to exist; it does not make it the contract. [parse] hands
     * back uninterned instances by design, and one of those has to equal its interned twin or a caller that
     * parsed an id could not find the gedra it names in a map keyed by interned ones.
     */
    override fun equals(other: Any?): Boolean = this === other || (other is GedraId && other.fullId == fullId)

    override fun hashCode(): Int = fullId.hashCode()

    companion object {
        /**
         * Builds an id from its parts, validating each. The text form is built here, so a caller that already
         * holds the parts never pays to parse a string it just assembled.
         */
        fun of(kind: GedraKind, client: String, baseId: String, suffix: String? = null): GedraId {
            checkClient(client)
            checkPart(baseId, "base id")
            suffix?.let { checkPart(it, "suffix") }
            val fullId = buildString {
                append(kind.storageType.idAbbrev).append(GID.partSep)
                append(kind.idAbbrev).append(GID.partSep)
                append(client).append(GID.partSep)
                append(baseId)
                if (suffix != null) append(GID.suffixSep).append(suffix)
            }
            return GedraId(kind, client, baseId, suffix, fullId)
        }

        /**
         * Reads an id from its text form. Structural only — a well-formed id says nothing about whether the
         * gedra exists, and this does not intern (see the class note).
         *
         * The supplied string becomes [fullId] unchanged rather than being rebuilt: every segment has been
         * validated, so re-assembling them could only produce the same characters back.
         */
        fun parse(fullId: String): GedraId {
            // limit = 4: the base id is opaque and the suffix is split off afterwards, so anything past the
            // third separator belongs to the caller's own construction and is not ours to divide further.
            val parts = fullId.split(GID.partSep, limit = 4)
            if (parts.size != 4) {
                throw mkBad(fullId, "it needs four '${GID.partSep}'-separated parts")
            }
            val storageType = GedraStorageType.entries.firstOrNull { it.idAbbrev == parts[0] }
                ?: throw mkBad(fullId, "'${parts[0]}' is not a storage type (${abbrevList()})")
            val kinds = kindsOf(storageType)
            if (kinds.isEmpty()) {
                throw mkBad(fullId, "'${parts[0]}' ids are not supported yet")
            }
            val kind = kinds.firstOrNull { it.idAbbrev == parts[1] }
                ?: throw mkBad(
                    fullId,
                    "'${parts[1]}' is not a '${parts[0]}' kind (${kinds.joinToString(", ") { it.idAbbrev }})",
                )
            val client = parts[2]
            val tail = parts[3]
            val at = tail.indexOf(GID.suffixSep)
            val baseId = if (at < 0) tail else tail.substring(0, at)
            val suffix = if (at < 0) null else tail.substring(at + 1)
            checkClient(client)
            checkPart(baseId, "base id")
            suffix?.let { checkPart(it, "suffix") }
            return GedraId(kind, client, baseId, suffix, fullId)
        }

        /** The kinds belonging to [storageType]; empty for a storage type with no kinds defined yet. */
        fun kindsOf(storageType: GedraStorageType): List<GedraKind> = when (storageType) {
            GedraStorageType.dataStore -> GedraDataType.entries
            GedraStorageType.configStore -> emptyList()
        }

        /**
         * Verifies the fixed-width rule [GID.kindAbbrevLength] states. Called by the tests rather than at
         * every parse: it is a property of the enums, so it can only be broken by editing one, and a check
         * that runs on every id would be paying at runtime for a mistake that cannot survive a build.
         */
        fun checkKindAbbrevs(): List<String> = GedraStorageType.entries
            .flatMap { kindsOf(it) }
            .map { it.idAbbrev }
            .filter { it.length != GID.kindAbbrevLength }

        private fun abbrevList() = GedraStorageType.entries.joinToString(", ") { it.idAbbrev }

        /**
         * A client name is a Java-variable-friendly identifier. Config objects are named by people and
         * addressed by their names, so the names have to survive being used as code identifiers.
         */
        private fun checkClient(client: String) {
            if (client.isEmpty() || !(client[0].isAsciiLetter() || client[0] == '_')) {
                throw KdrException.mkInput(
                    "A gedra client must start with a letter or underscore; '$client' does not.",
                )
            }
            checkPart(client, "client")
        }

        /**
         * Held to `[A-Za-z0-9_]`, which is what every id-building path here produces and what keeps a base id
         * a single token to a log tokenizer (see the class note). Rejecting anything else also keeps the two
         * separators out, which is what makes the format unambiguous to parse.
         */
        private fun checkPart(part: String, what: String) {
            if (part.isEmpty()) {
                throw KdrException.mkInput("A gedra $what may not be empty.")
            }
            val bad = part.firstOrNull { !(it.isAsciiLetter() || it.isAsciiDigit() || it == '_') }
            if (bad != null) {
                throw KdrException.mkInput(
                    "A gedra $what may hold only letters, digits and underscores; '$part' holds '$bad'.",
                )
            }
        }

        private fun mkBad(fullId: String, why: String): KdrException =
            KdrException.mkInput("'$fullId' is not a gedra id: $why.")

        // Spelled out rather than using isLetterOrDigit(), which is Unicode-aware: an id is ASCII by
        // construction, and a base id that admitted Cyrillic lookalikes would be a way to mint two ids that
        // read identically to a person and differ to the cache.
        private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

        private fun Char.isAsciiDigit() = this in '0'..'9'
    }
}
