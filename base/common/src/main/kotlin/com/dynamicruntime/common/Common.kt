package com.dynamicruntime.common

/** Foundational helpers shared across the runtime. */
object Common {
    val NAME: String = Common::class.simpleName ?: "no-name"

    fun greeting(): String = "Hello from $NAME common"
}
