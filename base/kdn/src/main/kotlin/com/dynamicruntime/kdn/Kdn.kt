package com.dynamicruntime.kdn

import com.dynamicruntime.common.Common

/** Dynamic-runtime core. Builds on the [Common] foundation. */
object Kdn {
    /**
     * Middle link of the module wiring chain. Chains onto [Common.wiringTag] -- so
     * this method existing and compiling is itself proof that `kdn` links against
     * `common`. See [Common.wiringTag].
     */
    fun wiringTag(): String = "${Common.wiringTag()} -> kdn wired on top"
}
