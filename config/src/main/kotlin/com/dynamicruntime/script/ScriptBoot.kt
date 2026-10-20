package com.dynamicruntime.script

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.logging.LogSetup
import com.dynamicruntime.common.startup.BootStage
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.KdrProvider
import com.dynamicruntime.kdn.Startup
import java.util.ServiceLoader

/**
 * Boots the runtime for a script, without a server, under an instance named [instanceName]: the deployment's own
 * configuration (`KDR_ENV`, the defaults file, every `KDR_*` variable), loaded as a node loads it, so a script
 * reaches the database and the file store the deployment does. Shared by the scripts that work on a deployment's
 * data ([GrantRole], [CheckFileStore]).
 *
 * **Only as far as the script needs** (issue #1289). [BootStage.initialized], the default, runs every service through
 * `checkInit` and none through `checkReady`: the database is usable, and no job scheduler starts, no demo is
 * provisioned, and nothing is announced to the deployment's other nodes. [BootStage.collected] runs only [services],
 * and reaches no database -- so a script on the file store runs while the database is unreachable, or locked by the
 * node's own server.
 */
fun bootScriptCxt(
    instanceName: String,
    stage: BootStage = BootStage.initialized,
    services: Set<String> = emptySet(),
): KdrCxt {
    val preBoot = KdrInstanceConfig.preBootLoadConfig()
    val cxt = KdrCxt.mkSimpleCxt("preBoot", preBoot)
    val env = cxt.getEnvVar(KdrInstanceConfig.envName) ?: ENV.local
    // Init logging before mkBootCxt (which also calls ensureInit, then a no-op), passing the cxt so the
    // defaults file is honored for KDR_LOG_LEVEL (issue #524).
    LogSetup.ensureInit(env, cxt::getEnvVar)
    return Startup.mkBootCxt(
        instanceName, env, preBoot.entries().toMap(), additionalComponents = discoveredComponents(), stage = stage, services = services,
    )
}

/**
 * The components the deployment put on the runtime classpath -- the sample, an injected component -- discovered as the
 * launcher discovers them (issue #171, `Boot.kt`), so a script boots the deployment's components and not just the two
 * base ones (issue #1244). Each still gates itself at boot (`presence`, `isLoaded`), as on the node: the edge's stays
 * out of an application node's script, as it does out of its server. Without this a script saw neither the sample's
 * clients nor its bucket.
 */
fun discoveredComponents(): List<ComponentDefinition> =
    ServiceLoader.load(KdrProvider::class.java, KdrProvider::class.java.classLoader).filterIsInstance<ComponentDefinition>()
