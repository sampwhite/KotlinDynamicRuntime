package com.dynamicruntime.common.app

/**
 * Instance-config keys for the webapp host — *deployment* inputs, as opposed to `AUI` (in `appui`), which holds the app's
 * own fixed paths and types. Defaults live at the read sites in `AppUiService` (module `appui`). Here, in core, so a
 * component that does not depend on `appui` -- kdn's `reference` flavor, issue #1769 -- can set one by name.
 */
@Suppress("ConstPropertyName")
object AUIC {
    /**
     * The classpath directory the app's **branding** assets (`AUI.brandingAssets`) are read from, letting a
     * deployment ship its own artwork instead of forking `:webapp`. Absent (the default) means the built-in
     * set. A deployment sets this from its `:customConfig` project and puts the files on the runtime classpath
     * — the same project, being on that classpath already, is the natural place to carry them (see
     * `examples/custom-config.md`; note its example build script disables resources, which such a deployment
     * would re-enable).
     *
     * Resolution is **per asset**: a file the configured directory does not supply falls back to the built-in
     * one, so a deployment can override just its logo and inherit the rest. This swaps the *bytes* only — the
     * URLs are fixed, so both shells' `<link>` tags stay identical and static.
     */
    const val appUiBrandingDir: String = "appUiBrandingDir"
}
