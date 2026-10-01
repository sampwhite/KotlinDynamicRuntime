package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.http.request.SECT

/**
 * The wire vocabulary of editing a client's **copy** (issue #918): the fragment keys an administrator may override
 * for a client, and the set/reset of one. The addresses and origins reuse `COV`'s names, so a row of the overrides
 * report and a request here spell a key the same way. Each name matches its value.
 */
@Suppress("ConstPropertyName")
object CPY {
    const val namespace = "kdr.clientCopy"

    /** Every key an administrator may override for a client, with its value for that client. */
    const val keysPath = "/${SECT.clientAdmin}/client/copy/keys"

    /** Sets one key's value for a client, and makes it live. */
    const val setPath = "/${SECT.clientAdmin}/client/copy/set"

    /** Removes a client's stored value for one key, and makes that live. */
    const val resetPath = "/${SECT.clientAdmin}/client/copy/reset"

    const val keyTypeName = "CopyKey"
    const val resultTypeName = "CopyEditResult"

    /**
     * The stored configuration an administrator's copy edits land in when no stored config of the client's already
     * overlays the file being edited -- created on the first such edit. One name for every client, so the editors
     * and a reader of the bundles listing know where to look.
     */
    const val copyConfigName = "copy"

    /** The result's fields beyond `COV`'s: which config took the edit, and what the client's people now read. */
    const val buildId = "buildId"
    const val issues = "issues"
    const val stored = "stored"
}
