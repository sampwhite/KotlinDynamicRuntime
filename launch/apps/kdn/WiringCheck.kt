package kdn

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.logging.LogSetup
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.config.Config

/**
 * Minimal launcher whose sole purpose is to prove the Gradle module graph wires
 * together end-to-end. It is intentionally *not* the real application entry point;
 * it exists to surface dependency/build wiring problems quickly.
 *
 * It logs the top of the module wiring chain, [Config.wiringTag], which chains
 * `common -> kdn -> config`. Because `launch` depends on `config`, a green run here
 * proves the whole `launch -> config -> kdn -> common` graph is linked.
 */
fun main() {
    // Install the logging configuration first, before anything logs in earnest.
    LogSetup.initFromEnv()

    val cxt = KdrCxt.mkSimpleCxt("wiringCheck")
    LogStartup.info(cxt, "Dependency wiring check:")
    LogStartup.info(cxt, Config.wiringTag())
}
