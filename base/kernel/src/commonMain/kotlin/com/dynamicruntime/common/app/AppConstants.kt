package com.dynamicruntime.common.app

/**
 * Shared wire vocabulary for the app-level UI config (issue #118): the endpoint path, and the feature keys the
 * frontend reads off the response. In the KMP kernel so the frontend uses exactly the strings the backend
 * writes. The app config is deployment-global -- the same for every caller -- and fetched once for the whole
 * frontend rather than per widget-group (part of the refresh foundation, issue #113).
 */
@Suppress("ConstPropertyName")
object APP {
    /** The app-level UI-config endpoint: deployment-wide config visible to the entire frontend. */
    const val uiConfig = "/app/ui/config"

    /**
     * Feature flag: whether this deployment obfuscates sensitive error messages (issue #108). The frontend
     * reads it to decide whether to show the content of a raw (non-fragment) error or suppress it (issue #111).
     */
    const val obfuscateSensitiveErrors = "obfuscateSensitiveErrors"
}
