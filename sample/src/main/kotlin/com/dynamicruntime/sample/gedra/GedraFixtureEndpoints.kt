package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GU
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.asStoredEntry
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/** Field names and debug tags for the Gedra entry fixture (issue #301). */
@Suppress("ConstPropertyName")
object GFX {
    /** The entries a caller sends. */
    const val entries = "entries"

    /** Stands in for the source a real endpoint would deduce from who is calling. */
    const val fixtureSource = "fixture"

    /** `_debug` tag: report what the manufactured union holds and which branch each entry reached. */
    const val explainEntries = "explainEntries"

    /** `_meta` key the tag writes under. */
    const val entriesExplained = "entriesExplained"

    /** Under [entriesExplained]: the trait ids the union knows, in the order it declares them. */
    const val knownTraits = "knownTraits"

    /** Under [entriesExplained]: the branch each entry reached, `default` where none matched. */
    const val branches = "branches"

    /** Value used in [branches] for an entry that fell to the union's default branch. */
    const val default = "default"
}

/**
 * A fixture that puts the whole config chain under load: entries in, the same entries back filled out
 * (issue #301).
 *
 * It stores nothing. What it proves is that everything between a component declaring a trait and a caller
 * sending an entry actually connects — the trait's schema, the entry type generated from it, the union
 * manufactured over every such type, and the envelope a stored entry carries. Any of those links could be
 * broken while each part passed its own tests, which is why the tests here are worth more than their size and
 * why several tests in #297, #298, and #300 could be deleted once this existed.
 *
 * Named for what it is. `fillOut` rather than `save`, because it fills entries out and returns them, and
 * `save` would be an untruth in the first place a reader looks.
 */
object GedraFixtureEndpoints {
    /**
     * The fixture's endpoints, in the `gedraFixture` namespace, referring to the manufactured
     * `globalconfig.FormDocEntry` by its qualified name — it does not exist when this module is built, and is
     * an ordinary type by the time anything resolves the reference.
     */
    fun schema(cxt: KdrCxt): SchModule = schemaModule(cxt, "gedraFixture") {
        val unionRef = "${GCFG.globalNamespace}.${GU.unionName(GedraDataType.formDoc)}"

        listEndpoint(
            "/fixture/gedra/formDocEntries/fillOut",
            "Validates a set of form-document entries against their traits and answers with them filled " +
                "out as storing them would leave them. Stores nothing.",
            outputRef = unionRef,
            method = HttpMethod.POST,
            // A limit would be nonsense: the answer is one entry per entry supplied, so there is nothing to
            // truncate.
            noLimit = true,
            forTestingOnly = true,
            inputFields = {
                field(GFX.entries, "The entries to validate and fill out.", required = true) {
                    type = SCT.array
                    items { ref(unionRef) }
                }
            },
        ) { c, request ->
            val now = c.instanceNow()
            val entries = request[GFX.entries].toJsonListOrEmpty().mapIndexed { index, raw ->
                filledOut(raw.toJsonMapOrEmpty()).asStoredEntry(
                    entryId = "e-${index + 1}",
                    // A real endpoint deduces this from who is calling; a fixture says what it is.
                    source = GFX.fixtureSource,
                    createdAt = now,
                )
            }
            explainEntries(c, entries)
            entries
        }
    }

    /**
     * Produces what the caller does not supply, which for a real trait is code bound to that trait.
     *
     * Only `expenseReport` has anything derived, and it is the smallest honest version of a pre-processor:
     * read the two supplied numbers, write the one that follows from them. A trait whose data does not need
     * it passes through untouched.
     */
    private fun filledOut(entry: Map<String, Any?>): Map<String, Any?> {
        if (entry[GE.traitId].toOptStr() != ST.expenseReport) {
            return entry
        }
        val data = entry[GE.data].toJsonMapOrEmpty()
        val perItem = (data[ST.perItemAmount] as? Number)?.toDouble() ?: return entry
        val count = (data[ST.itemCount] as? Number)?.toDouble() ?: return entry
        return entry + (GE.data to data + (ST.totalAmount to perItem * count))
    }

    /**
     * Reports, under `_meta`, what the union knows and which branch each entry reached — the two facts the
     * response itself cannot show, since a filled-out entry looks the same whichever branch accepted it.
     *
     * A `_debug` tag rather than a permanent unit test, for the reason the tag exists: the same observation is
     * then reachable by somebody debugging a live instance, not only by a test that already knew what to ask.
     */
    private fun explainEntries(cxt: KdrCxt, entries: List<Map<String, Any?>>) {
        if (!cxt.hasDebug(GFX.explainEntries)) {
            return
        }
        val union = cxt.getSchema().types["${GCFG.globalNamespace}.${GU.unionName(GedraDataType.formDoc)}"]
        val known = union?.variants?.values.orEmpty()
        cxt.request?.responseMeta?.put(
            GFX.entriesExplained,
            linkedMapOf<String, Any?>(
                GFX.knownTraits to known,
                GFX.branches to entries.map { entry ->
                    val traitId = entry[GE.traitId].toOptStr()
                    if (traitId != null && traitId in known) traitId else GFX.default
                },
            ),
        )
    }
}
