package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.defaultListLimit
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.ReadScopeRules
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/** Endpoint paths for stored gedra data. */
@Suppress("ConstPropertyName")
object GEP {
    const val formDocCreate = "/gedra/formDoc/create"
    const val formDocDelete = "/gedra/formDoc/delete"
    const val formDoc = "/gedra/formDoc"
    const val formDocs = "/gedra/formDocs"

    const val patch = "/gedra/patch"

    /** The type naming what a "delete" removed. */
    const val deletedGedra = "DeletedGedra"

    /** The type of one target in a patch: a gedra, and what is asked of it. */
    const val patchTarget = "PatchTarget"

    /** The type of a patch's targets, grouped by gedra kind. */
    const val patchTargets = "PatchTargets"

    /** The type of what a patch did to one gedra. */
    const val patchedGedra = "PatchedGedra"
}

/**
 * The endpoints over stored gedra data -- create a form document, read one, list them (issue #310), delete
 * one (#326), and patch several at once (#337).
 *
 * They sit in the **`gedra`** section, which is login-gated (`RequestService.userSections`). That is the whole
 * of the level check, and it is deliberately not more: how far a caller reaches is a *scope* question rather
 * than a privilege one, and `ReadScopeRules.forCaller` answers it -- an ordinary user reaches their own
 * documents, an administrator their client's (narrowed to their organization if they have one), and an
 * administrator holding `allClients` reaches everything. One surface serves all of them, which is why there is
 * no second listing endpoint behind an admin section.
 *
 * This is the endpoint `ReadScopeRules` has been waiting for. Its own note says the own-user width had no
 * surface reaching it and would arrive with "the first ordinary endpoint over an owned table". This is that
 * endpoint, and the width is now exercised by a caller rather than only by a test.
 */
fun gedraSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, "gedra") {
    val formDoc = GedraDataType.formDoc
    val docType = GU.gedraName(formDoc)
    GedraDataRow.defineType(this, formDoc)

    // Creation takes the same type it returns. Everything but `entries` is `g-derived`, so the input
    // projection leaves a caller supplying exactly the part that is theirs -- and a client that echoes a whole
    // document back (which is how every form works) has its derived fields dropped rather than refused.
    itemEndpoint(
        GEP.formDocCreate,
        "Creates a form document carrying the supplied entries, and answers with it as stored.",
        HttpMethod.POST,
        outputRef = docType,
        inputRef = docType,
    ) { c, request ->
        val entries = request[GDF.entries].toJsonListOfMaps()
        GedraDataService.require(c).createGedra(c, formDoc, entries).toJsonMap()
    }

    itemEndpoint(
        GEP.formDoc,
        "Fetches one form document by its gedra id.",
        HttpMethod.GET,
        outputRef = docType,
        inputFields = {
            field(GDF.gedraId, "Id of the form document to fetch.", required = true)
        },
    ) { c, request ->
        val fullId = request[GDF.gedraId].toOptStr()
            ?: throw KdrException.mkInput("A ${GDF.gedraId} is required.")
        val row = GedraDataService.require(c).queryGedra(c, fullId, formDoc, ReadScopeRules.forCaller(c))
        // Absent, disabled, the wrong kind and out of scope all arrive here as null, and all leave as 404 --
        // see `GedraDataService.queryGedra` for why the last of those must not be distinguishable.
            ?: throw KdrException("No form document '$fullId'.", code = EXC.notFound)
        row.toJsonMap()
    }

    // What a delete answers with. Not the document: a caller who has just deleted something does not want it
    // handed back looking exactly like a live one, since the type carries no `enabled` to tell them apart.
    type(GEP.deletedGedra) {
        type = SCT.kObject
        description = "Confirmation that a gedra was deleted."
        property(GDF.gedraId, "Id of the gedra that was deleted.", required = true)
    }

    // A POST rather than an HTTP DELETE, because `HttpMethod` has no DELETE -- adding one reaches the
    // dispatcher, the catalog and the form engine, which is a change worth making on its own account rather
    // than in passing here. The path says the verb in the meantime, as `create` does.
    generalEndpoint(
        GEP.formDocDelete,
        "Deletes a form document, so that it is no longer readable or listed.",
        HttpMethod.POST,
        outputRef = GEP.deletedGedra,
        inputFields = {
            field(GDF.gedraId, "Id of the form document to delete.", required = true)
        },
    ) { c, request ->
        val fullId = request[GDF.gedraId].toOptStr()
            ?: throw KdrException.mkInput("A ${GDF.gedraId} is required.")
        // Absent, already deleted, the wrong kind and out of scope all answer false and all leave as 404 --
        // the same four-into-one the read makes, so trying to delete something reveals no more than trying to
        // read it. A second delete is therefore a 404 rather than a quiet success, which says plainly that
        // there was nothing there rather than implying this call is what removed it.
        if (!GedraDataService.require(c).deleteGedra(c, fullId, formDoc, ReadScopeRules.forCaller(c))) {
            throw KdrException("No form document '$fullId'.", code = EXC.notFound)
        }
        mapOf(GDF.gedraId to fullId)
    }

    listEndpoint(
        GEP.formDocs,
        "Lists the form documents the caller may see, newest first.",
        outputRef = docType,
    ) { c, request ->
        val limit = (request[EP.limit] as? Number)?.toInt() ?: defaultListLimit
        GedraDataService.require(c)
            .listGedras(c, formDoc, ReadScopeRules.forCaller(c), limit)
            .map { it.toJsonMap() }
    }
    // --- the patch (issue #337) ---------------------------------------------------------------------------

    // One target: a gedra, and the edits asked of it. Its `edits` are the manufactured edit union for this
    // kind, so a trait that cannot be carried by a form document is refused at the path where it was written
    // rather than by service code that has to remember.
    type(GEP.patchTarget) {
        type = SCT.kObject
        description = "One gedra a patch touches, and everything it asks of that gedra."
        property(GDF.gedraId, "Id of the gedra to change.", required = true)
        property(GPF.edits, "What to do with this gedra's entries.", required = true) {
            type = SCT.array
            items { ref("${GCFG.globalNamespace}.${GU.editUnionName(formDoc)}") }
        }
    }

    // Targets are grouped by kind, and the kind is therefore stated twice -- once here and once inside every
    // id. That redundancy is the price of typing: a schema cannot read a prefix out of an id string to choose
    // a branch, so for `edits` to be typed per kind at all the kind has to be a token the schema can see. The
    // service refuses a row whose id disagrees with its group.
    //
    // Only `formDoc` today, because it is the only kind with an edit union -- which is the only kind anything
    // can store. A kind appears here when it appears in the union assembly, which is the right amount of
    // friction for a decision about what may exist.
    type(GEP.patchTargets) {
        type = SCT.kObject
        description = "The gedras a patch touches, grouped by kind."
        property(formDoc.name, "Form documents to change.") {
            type = SCT.array
            items { ref(GEP.patchTarget) }
        }
    }

    type(GEP.patchedGedra) {
        type = SCT.kObject
        description = "What a patch did to one gedra."
        property(GDF.gedraId, "Id of the gedra that was patched.", required = true)
        property(GPF.outcomes, "What became of each edit, named by the trait it addressed.", required = true) {
            type = SCT.array
            items {
                type = SCT.kObject
                property(GE.traitId, "The trait the edit addressed.", required = true)
                property(GPF.applied, "Whether the edit changed anything.", required = true) {
                    type = SCT.boolean
                }
            }
        }
    }

    // A list endpoint because the answer is one result per target, and a POST because a patch is neither a
    // read nor an HTTP PATCH -- it targets an arbitrary set of rows rather than the resource at the URI, and
    // the PATCH method advertises body formats (RFC 6902's op arrays, RFC 7386's merge-patch where `null`
    // deletes) that this design specifically does not use. See `gedra-patch.md`.
    listEndpoint(
        GEP.patch,
        "Changes entries on one or more gedras, and answers with what became of each edit.",
        outputRef = GEP.patchedGedra,
        method = HttpMethod.POST,
        // Nothing to truncate: the answer is one result per target supplied.
        noLimit = true,
        inputFields = {
            field(GPF.targets, "The gedras to change, grouped by kind.", required = true) {
                ref(GEP.patchTargets)
            }
        },
    ) { c, request ->
        val gedraService = GedraService.require(c)
        val byKind = LinkedHashMap<GedraDataType, List<GedraPatchTarget>>()
        for ((kindName, raw) in request[GPF.targets].toJsonMapOrEmpty()) {
            // A property the schema does not declare cannot arrive, so an unknown name here would mean the
            // type and this loop had drifted -- worth a fault rather than a silent skip.
            val kind = GedraDataType.entries.firstOrNull { it.name == kindName }
                ?: throw KdrException.mkInput("'$kindName' is not a kind of gedra a patch can target.")
            byKind[kind] = raw.toJsonListOfMaps().map { GedraPatchTarget.extract(gedraService, it) }
        }
        if (byKind.values.all { it.isEmpty() }) {
            throw KdrException.mkInput("A patch has to name at least one gedra to change.")
        }
        GedraDataService.require(c)
            .patchGedras(c, byKind, ReadScopeRules.forCaller(c))
            .map { it.toJsonMap() }
    }
}
