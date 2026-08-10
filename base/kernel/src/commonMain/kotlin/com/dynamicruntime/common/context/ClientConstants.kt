package com.dynamicruntime.common.context

// Client constants, in the kernel (issue #78), so shared data classes like UserProfile can reference them
// from KMP code. Per the code guide: lowerCamelCase `const val`s in an upper-case acronym object, always
// referenced qualified (`CL.local`), never wildcard-imported.

/** Client-related constants. */
@Suppress("ConstPropertyName")
object CL {
    // Default client names.
    const val local = "local"
    const val public = "public"

    // Placeholder id for the implicit system user.
    const val systemUserId = 0
}
