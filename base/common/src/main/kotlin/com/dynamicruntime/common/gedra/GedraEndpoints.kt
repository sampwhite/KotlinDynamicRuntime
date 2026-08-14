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
import com.dynamicruntime.common.util.toOptStr

/** Endpoint paths for stored gedra data. */
@Suppress("ConstPropertyName")
object GEP {
    const val formDocCreate = "/gedra/formDoc/create"
    const val formDocDelete = "/gedra/formDoc/delete"
    const val formDoc = "/gedra/formDoc"
    const val formDocs = "/gedra/formDocs"

    /** The type naming what a "delete" removed. */
    const val deletedGedra = "DeletedGedra"
}

/**
 * The endpoints over stored gedra data (issue #310) -- create a form document, read one, list them.
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
}
