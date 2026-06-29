package com.dynamicruntime.kdn

import com.dynamicruntime.common.Common

/** Dynamic-runtime core. Builds on the [Common] foundation. */
object Kdn {
    fun describe(): String = "${Common.greeting()} -> kdn runtime ready"
}
