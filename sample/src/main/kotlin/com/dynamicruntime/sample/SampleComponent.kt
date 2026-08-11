package com.dynamicruntime.sample

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.sample.file.SampleFileService
import com.dynamicruntime.sample.gedra.GedraSketchService
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.ServiceInitializer

/**
 * The `sample` module's component. It contributes the file upload/download endpoints (owned by
 * [SampleFileService]) and registers the service that backs them.
 * Mirrors how each base module has one component (`CommonComponent`, `KdnComponent`).
 *
 * It once also carried a Todo CRUD demo. That went when the runtime stopped needing a toy to point at; the
 * file endpoints stayed because they are the only exercise of the `file` endpoint kinds -- multipart upload
 * and a raw response body -- which nothing else in the codebase covers.
 *
 * Discovered by the launcher via the ServiceLoader mechanism (issue #171) -- it is listed in
 * `src/main/resources/META-INF/services/com.dynamicruntime.common.startup.KdrProvider` -- rather than being
 * registered explicitly. Being a demo, it self-gates via [isLoaded]: its endpoints load only in developer
 * environments, so it never enters a real deployment's endpoint set.
 */
class SampleComponent : ComponentDefinition {
    override val providerName: String = "sample"

    /**
     * Loads the demo file endpoints only in developer environments (`local`/`dev`), never in
     * `prod`/`integration`; an explicit `KDR_LOAD_SAMPLE=true|false` overrides that (and lets tests force it on
     * regardless of environment). Formerly `shouldLoadSample` in the launcher's `Start.kt`.
     */
    override fun isLoaded(cxt: KdrCxt): Boolean {
        cxt.getEnvBool("KDR_LOAD_SAMPLE")?.let { return it }
        val env = cxt.instanceConfig.env
        return env == ENV.local || env == ENV.dev
    }

    override fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {
        collector.addModule(SampleFileService.schema(cxt))
        collector.addModule(GedraSketchService.schema(cxt))
    }

    override fun services(cxt: KdrCxt): List<() -> ServiceInitializer> =
        listOf(::SampleFileService, ::GedraSketchService)
}
