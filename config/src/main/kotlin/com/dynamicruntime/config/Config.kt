package com.dynamicruntime.config

import com.dynamicruntime.kdn.Kdn

/**
 * Module facade for the `config` project, named after its module to match the
 * `Common` / `Kdn` convention.
 */
object Config {
    /**
     * Top link of the module wiring chain. Chains onto [Kdn.wiringTag] (config
     * depends on both `kdn` and `common`), so a single call from the launcher
     * produces the full `common -> kdn -> config` chain and proves the whole
     * dependency graph the deployment relies on is wired together. See
     * [com.dynamicruntime.common.Common.wiringTag]. Used only by the WiringCheck
     * launcher.
     */
    fun wiringTag(): String = "${Kdn.wiringTag()} -> config wired on top"
}
