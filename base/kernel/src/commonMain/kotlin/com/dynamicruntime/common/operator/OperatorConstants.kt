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

/**
 * Operator endpoint **paths** the frontend routes to (issue #540). Only the path is shared: each page is
 * rendered from the endpoint's own output schema and its `g-presentation` hints, so no field-name constants
 * cross the boundary -- a renamed field changes the schema the page already follows, not code here.
 */
@Suppress("ConstPropertyName")
object OPS {
    /** Reports every boot check this node ran, its mode, verdict, and findings. */
    const val bootChecksPath = "/${SECT.operator}/boot/checks"

    /** Reports this node's identity, uptime and JVM statistics (a free-form diagnostic map). */
    const val systemInfoPath = "/${SECT.operator}/system/info"

    /** Lists the database tables registered for this instance. */
    const val dbTablesPath = "/${SECT.operator}/db/tables"

    /** Syntax-checks this instance's Markdown fragment files, reporting problems and per-entry data reads. */
    const val fragmentsCheckPath = "/${SECT.operator}/fragments/check"
}
