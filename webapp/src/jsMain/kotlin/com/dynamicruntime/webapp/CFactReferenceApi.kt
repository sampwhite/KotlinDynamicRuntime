package com.dynamicruntime.webapp

import com.dynamicruntime.common.cfact.CFD
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.util.getOptStr
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/**
 * The cfact reference (issue #488): one call fetching the whole document, which the backend assembled as
 * Markdown from the caller's client's cfacts and which of them are present for this caller now. The page renders
 * it exactly as it renders the environment-variable reference and the README -- the frontend composes none of
 * it, and names the path and result key from the shared kernel constants ([CFD]) rather than re-hardcoding the
 * endpoint. An `itemEndpoint`, so the document arrives under [EP.item], not `results`.
 */
object CFactReferenceApi {
    // Throw rather than fall back to "" when the field is absent: the endpoint declares `markdown` required, so a
    // response without it is a server-side defect, and a blank document reads as "this client knows no cfacts"
    // -- a plausible-looking wrong answer. Throwing lets the page's error state say something failed instead.
    suspend fun fetch(): String =
        Http.getApi(CFD.cfactsPath)[EP.item].toJsonMapOrEmpty().getOptStr(CFD.markdown)
            ?: error("The cfact reference response carried no '${CFD.markdown}' field.")
}
