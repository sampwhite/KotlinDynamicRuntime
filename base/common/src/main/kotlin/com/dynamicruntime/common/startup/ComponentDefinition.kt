package com.dynamicruntime.common.startup

import com.dynamicruntime.common.context.KdrCxt

/**
 * A component bundles a set of contributions -- schema and services -- that an
 * instance loads together at startup. The application is assembled from components;
 * configuration and which components are active determine what a given application
 * actually does.
 *
 * Unlike the prior-art dn split into `core` and `common` components (a distinction
 * that never earned its keep), kd2 has one component per module: [com.dynamicruntime.common.CommonComponent]
 * in `base/common`, `KdnComponent` in `base/kdn`.
 *
 * Extends [KdrProvider] so a component can be discovered at startup by the same ServiceLoader mechanism as a
 * config applier (issue #171). Its [KdrProvider.providerName] is the component's unique name, and
 * [KdrProvider.loadPriority] its load order; the schema-vs-services split below ([isLoaded]/[isActive]) is
 * component-specific and so stays here rather than on the shared base.
 */
interface ComponentDefinition : KdrProvider {
    /**
     * Whether this component contributes its schema to the application. Receives the startup [cxt] so the
     * decision can read instance config and environment (e.g., a demo component that loads only in developer
     * environments, as the `sample` module's `SampleComponent` does).
     */
    fun isLoaded(cxt: KdrCxt): Boolean = true

    /** Whether this component's services are active. Receives the startup [cxt] for the same config-driven reason. */
    fun isActive(cxt: KdrCxt): Boolean = true

    /** Contributes schema modules (types + endpoints) into the collector. */
    fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {}

    /**
     * Services that must be fully initialized before regular [services]. Returned as
     * factories (typically `::Ctor` references) so nothing is instantiated until the
     * registry binds it.
     */
    fun startupServices(cxt: KdrCxt): List<() -> ServiceInitializer> = emptyList()

    /** Regular services, initialized after all [startupServices]. */
    fun services(cxt: KdrCxt): List<() -> ServiceInitializer> = emptyList()
}
