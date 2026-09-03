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

    /** Reports this node's in-memory table caches and the change dates every node shares. */
    const val cacheStatePath = "/${SECT.operator}/cache/state"

    /** Forces this node's table cache(s) to reload now -- one table, or all. */
    const val cacheReloadPath = "/${SECT.operator}/cache/reload"
}


/**
 * Wire vocabulary for the cache-state operator surface (issue #540) -- shared with the frontend, since the
 * cache-state page is a hand-written composite (a per-node comparison plus a reload action) that reads these
 * fields by name, the way the env reference reads [OENV.markdown]. Both sides compile the same names.
 */
@Suppress("ConstPropertyName")
object TCS {
    const val isDisabled = "isDisabled"
    const val minRecheckMs = "minRecheckMs"
    const val caches = "caches"
    const val sharedState = "sharedState"

    /** The node this report is about -- cache state is per node, so the page says which one answered. */
    const val nodeId = "nodeId"

    // The reload endpoint: its input table name and its output list of reloaded tables.
    const val table = "table"
    const val reloaded = "reloaded"
    const val reloadTypeName = "TableCacheReload"
}

/** Field names of one cache's entry in the cache-state report (issue #540); shared with the frontend page. */
@Suppress("ConstPropertyName")
object TCI {
    const val tableName = "tableName"
    const val topic = "topic"
    const val isLoaded = "isLoaded"
    const val isDetached = "isDetached"
    const val pendingReload = "pendingReload"
    const val numRows = "numRows"
    const val numEntries = "numEntries"
    const val highCounter = "highCounter"
    const val queryFromDate = "queryFromDate"
    const val lastSeen = "lastSeen"
    const val indexes = "indexes"
}
