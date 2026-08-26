package com.dynamicruntime.common.content

import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef

/**
 * Names for the Markdown fragment files `base/common` ships that no UI-config declares, plus the wiring for
 * checking fragments at startup. The widget-group files are named by their own component's constants
 * (`AFRAG`, `HFRAG` in the kernel); these two have no such home.
 */
@Suppress("ConstPropertyName")
object FRAG {
    /** Error-message copy, reached through `KdrMsg` rather than a UI-config, so nothing else names it. */
    const val errors = "errors"

    /** The reference fragment file, kept checked so the documented syntax cannot rot. */
    const val sample = "sample"

    /** Instance-config key under which the boot collects every component's declared fragment files. */
    const val registryKey = "fragmentFiles"

    /**
     * Env var choosing what a fragment problem does at startup: [strict], [warn] or [off]. Unset means
     * **strict everywhere except `prod`**, which is the split that matters -- a developer or a test should be
     * stopped by a broken fragment, and a production node should not refuse to serve everything else over a
     * defect in one piece of copy. Naming it explicitly overrides that either way, so a cautious deployment
     * can demand [strict] and a developer chasing something else can drop to [warn].
     */
    val checkEnvVar = EnvVarDef(
        "KDR_FRAGMENT_CHECK", group = ENVGRP.content, defaultDoc = "strict (`warn` in `prod`)",
        description = "What a fragment problem does at startup: `strict`, `warn` or `off`. Unset means strict " +
            "everywhere except `prod` -- a developer or test should be stopped by a broken fragment; a " +
            "production node should not refuse to serve everything else over a defect in one piece of copy.",
    )

    /** Refuse to boot when a fragment has a syntax problem. */
    const val strict = "strict"

    /** Log the problems and serve anyway. */
    const val warn = "warn"

    /** Do not check at all. */
    const val off = "off"
}

/** Field names for a fragment check result; each name matches its value. */
@Suppress("ConstPropertyName")
object FCHK {
    const val fileId = "fileId"
    const val found = "found"
    const val issueCount = "issueCount"
    const val issues = "issues"

    /** Per-entry data requirements, present on every result. */
    const val entries = "entries"
    const val entry = "entry"
    const val required = "required"
    const val optional = "optional"

    /** Required paths the supplied [data] does not provide; empty unless `data` was given. */
    const val missing = "missing"

    /** Optional JSON object to check the required paths against. */
    const val data = "data"
}
