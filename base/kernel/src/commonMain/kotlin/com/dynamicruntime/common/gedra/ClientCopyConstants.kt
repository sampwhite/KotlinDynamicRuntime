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

    /** Sets one key's value for a client, and makes it live -- or a draft, for a client with a sandbox. */
    const val setPath = "/${SECT.clientAdmin}/client/copy/set"

    /** Removes a client's stored value for one key, and makes that live -- or a draft, for a client with a sandbox. */
    const val resetPath = "/${SECT.clientAdmin}/client/copy/reset"

    /**
     * Sets or resets several keys of one fragment file for a client in one save (issue #1062): one trial, one publish
     * and one reload, so the changes take effect together -- or a draft, for a client with a sandbox.
     */
    const val applyPath = "/${SECT.clientAdmin}/client/copy/apply"

    const val keyTypeName = "CopyKey"
    const val resultTypeName = "CopyEditResult"
    const val changeTypeName = "CopyChange"
    const val applyResultTypeName = "CopyApplyResult"
    const val appliedTypeName = "CopyApplied"

    /** The apply request's list of changes, and the result's list of what each key now reads. */
    const val changes = "changes"
    const val keys = "keys"

    /** A change's flag: remove the client's stored value for the key rather than set one. */
    const val reset = "reset"

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

    /** How the save took effect, an [EDM] value (issue #930) -- the copy and the menu editor's results alike. */
    const val mode = "mode"
}

/**
 * How an editor's save of a client's presentation took effect (issue #930): the result's [CPY.mode]. Chosen by
 * whether the client has a Shadow Sandbox, so the editor can say "Saved and live" or offer "Preview in sandbox" and
 * "Publish".
 */
@Suppress("ConstPropertyName")
object EDM {
    /** Published, and live for the client's people at once: a client with no sandbox. */
    const val live = "live"

    /** Written to the client's editable revision, which its sandbox runs; live in the client once published. */
    const val draft = "draft"
}
