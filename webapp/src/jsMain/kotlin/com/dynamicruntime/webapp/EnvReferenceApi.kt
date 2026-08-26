package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.operator.OENV
import com.dynamicruntime.common.util.getOptStr
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/**
 * The operator environment-variable reference (issue #371): one call fetching the whole document, which the
 * backend assembled as Markdown from what THIS node resolved each variable to. The page renders it exactly as
 * it renders the README -- the frontend composes none of it, and names the path and result key from the shared
 * kernel constants ([OENV]) rather than re-hardcoding the endpoint.
 */
object EnvReferenceApi {
    suspend fun fetch(): String =
        Http.getApi(OENV.envReferencePath)[EP.results].toJsonMapOrEmpty().getOptStr(OENV.markdown) ?: ""
}
