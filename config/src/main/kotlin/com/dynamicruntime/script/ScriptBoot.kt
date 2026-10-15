package com.dynamicruntime.script

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.logging.LogSetup
import com.dynamicruntime.kdn.Startup

/**
 * Boots the runtime for a script, without a server, under an instance named [instanceName]: the deployment's own
 * configuration (`KDR_ENV`, the defaults file, every `KDR_*` variable), loaded as a node loads it, so a script
 * reaches the database and the file store the deployment does. Shared by the scripts that work on a deployment's
 * data ([GrantRole], [CheckFileStore]).
 */
fun bootScriptCxt(instanceName: String): KdrCxt {
    val preBoot = KdrInstanceConfig.preBootLoadConfig()
    val cxt = KdrCxt.mkSimpleCxt("preBoot", preBoot)
    val env = cxt.getEnvVar(KdrInstanceConfig.envName) ?: ENV.local
    // Init logging before mkBootCxt (which also calls ensureInit, then a no-op), passing the cxt so the
    // defaults file is honored for KDR_LOG_LEVEL (issue #524).
    LogSetup.ensureInit(env, cxt::getEnvVar)
    return Startup.mkBootCxt(instanceName, env, preBoot.entries().toMap())
}
