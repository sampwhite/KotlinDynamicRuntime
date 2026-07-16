package com.dynamicruntime.sample

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.sample.file.SampleFileService
import com.dynamicruntime.sample.todo.TodoService
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.ServiceInitializer

/**
 * The `sample` module's component. It contributes the Todo schema + endpoints (owned by [TodoService]) and the
 * file upload/download endpoints (owned by [SampleFileService]), and registers the services that back them.
 * Mirrors how each base module has one component (`CommonComponent`, `KdnComponent`); the standalone launcher
 * ([Start]) registers this alongside the base components so the sample app is the full runtime plus these
 * endpoints.
 */
class SampleComponent : ComponentDefinition {
    override val componentName: String = "sample"

    override fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {
        collector.addModule(TodoService.schema(cxt))
        collector.addModule(SampleFileService.schema(cxt))
    }

    override fun services(cxt: KdrCxt): List<() -> ServiceInitializer> = listOf(::TodoService, ::SampleFileService)
}
