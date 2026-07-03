package com.dynamicruntime.common.startup

import com.dynamicruntime.common.context.KdrCxt

/**
 * A component bundles a set of contributions -- schema and services -- that an
 * instance loads together at startup. The application is assembled from components;
 * configuration and which components are active determine what a given application
 * actually does.
 *
 * Unlike the prior-art dn split into `core` and `common` components (a distinction
 * that never earned its keep), kd2 has one component per module: [CommonComponent]
 * in `base/common`, `KdnComponent` in `base/kdn`.
 */
interface ComponentDefinition {
    /** Unique name of this component. */
    val componentName: String

    /** Whether this component contributes its schema. May become config-driven later. */
    fun isLoaded(): Boolean = true

    /** Whether this component's services are active. May become config-driven later. */
    fun isActive(): Boolean = true

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

    /** Load order; lower loads earlier. Relative expressions like [PRI.standard] - 1 are expected. */
    fun loadPriority(): Int = PRI.standard
}
