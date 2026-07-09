package com.dynamicruntime.common.sql

import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC
import java.util.Properties

/**
 * Reads deployment secrets (currently just database passwords) from a Java properties file at
 * `private/secrets.properties`, resolved against the workspace directory (see [AppPaths]). Keeping
 * secrets in a file outside application config means a password is never stored in — or logged from — the
 * config; config only carries the *name* of the secret to read (see [DBC.passwordSecretKey]).
 */
object SecretsUtil {
    const val secretsPath = "private/secrets.properties"

    /** The value for [key] from the secrets file, or null if the file or key is absent. */
    fun getSecret(key: String): String? {
        val file = AppPaths.resolve(secretsPath)
        if (!file.isFile) {
            return null
        }
        val props = Properties()
        file.inputStream().use { props.load(it) }
        return props.getProperty(key)
    }

    /** The value for [key], or a config error (which fails startup) if the secret is missing. */
    fun getReqSecret(key: String): String =
        getSecret(key) ?: throw KdrException(
            "Required secret '$key' not found in ${AppPaths.resolve(secretsPath).path}.",
            null, EXC.internalError, SRC.config, ACT.code,
        )
}
