package com.dynamicruntime.common.filestore

/**
 * File Store NaMes (issue #1282): what a deployment's file stores are called. A deployment keeps files in its
 * **default** store and may declare more, by name; a client is assigned one by name (issue #1283), which is why the
 * rule is in the kernel, beside the client definition that names one.
 */
@Suppress("ConstPropertyName")
object FSNM {
    /** The store `KDR_FILE_STORE_TYPE` and its variables describe, which every deployment has. No other may be called so. */
    const val default = "default"

    const val maxNameLength = 30

    /**
     * What is wrong with [name] as a store's name, or null when it is one: a lowercase letter, then lowercase letters,
     * digits and hyphens, at most [maxNameLength] characters -- a word that reads the same in an environment
     * variable, a log line and a choice list.
     */
    fun nameProblem(name: String): String? = when {
        name.isEmpty() -> "it is empty"
        name.length > maxNameLength -> "it is longer than $maxNameLength characters"
        name.first() !in 'a'..'z' -> "it must begin with a lowercase letter"
        !name.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } -> "it may hold only lowercase letters, digits and hyphens"
        else -> null
    }
}
