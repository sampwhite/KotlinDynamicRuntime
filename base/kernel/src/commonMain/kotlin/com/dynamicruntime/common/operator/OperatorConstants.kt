package com.dynamicruntime.common.operator

import com.dynamicruntime.common.http.request.SECT

/**
 * Wire vocabulary for the operator surface that the **frontend** also names (issue #371), so it lives in the
 * kernel both sides compile. The env-var reference is served by the JVM backend and rendered by the webapp, and
 * neither re-hardcodes the other's path or result key: the endpoint is defined against these and the page
 * fetches against these.
 */
@Suppress("ConstPropertyName")
object OENV {
    /** The operator endpoint that returns the environment-variable reference as assembled Markdown. */
    const val envReferencePath = "/${SECT.operator}/env/reference"

    /** The result field carrying the assembled Markdown document. */
    const val markdown = "markdown"

    /** Schema type name for the endpoint's output object. */
    const val referenceType = "EnvVarReference"
}
