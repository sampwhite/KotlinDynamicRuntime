package com.dynamicruntime.common

/** Foundational helpers shared across the runtime. */
object Common {
    /**
     * Bottom link of the module wiring chain. Proves the `common` module is on the
     * classpath; [com.dynamicruntime.kdn.Kdn.wiringTag] and
     * [com.dynamicruntime.config.Config.wiringTag] chain onto it so a single call to
     * the topmost tag proves the whole dependency graph is wired together. Used only
     * by the WiringCheck launcher.
     */
    fun wiringTag(): String = "common module on classpath"
}
