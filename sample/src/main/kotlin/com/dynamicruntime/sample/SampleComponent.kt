package com.dynamicruntime.sample

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.sample.file.SampleFileService
import com.dynamicruntime.sample.todo.TodoService
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.ServiceInitializer

/**
 * The `sample` module's component. It contributes the Todo schema + endpoints (owned by [TodoService]) and the
 * file upload/download endpoints (owned by [SampleFileService]), and registers the services that back them.
 * Mirrors how each base module has one component (`CommonComponent`, `KdnComponent`).
 *
 * Discovered by the launcher via the ServiceLoader mechanism (issue #171) -- it is listed in
 * `src/main/resources/META-INF/services/com.dynamicruntime.common.startup.KdrProvider` -- rather than being
 * registered explicitly. Being a demo, it self-gates via [isLoaded]: its endpoints load only in developer
 * environments, so it never enters a real deployment's endpoint set.
 */
class SampleComponent : ComponentDefinition {
    override val providerName: String = "sample"

    /**
     * Loads the demo Todo/file endpoints only in developer environments (`local`/`dev`), never in
     * `prod`/`integration`; an explicit `KDR_LOAD_SAMPLE=true|false` overrides that (and lets tests force it on
     * regardless of environment). Formerly `shouldLoadSample` in the launcher's `Start.kt`.
     */
    override fun isLoaded(cxt: KdrCxt): Boolean {
        cxt.getEnvVar("KDR_LOAD_SAMPLE")?.let { return it.equals("true", ignoreCase = true) }
        val env = cxt.instanceConfig.env
        return env == ENV.local || env == ENV.dev
    }

    override fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {
        collector.addModule(TodoService.schema(cxt))
        collector.addModule(SampleFileService.schema(cxt))
    }

    override fun services(cxt: KdrCxt): List<() -> ServiceInitializer> = listOf(::TodoService, ::SampleFileService)
}
