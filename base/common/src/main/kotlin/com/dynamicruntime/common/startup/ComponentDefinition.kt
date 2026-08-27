package com.dynamicruntime.common.startup

import com.dynamicruntime.common.content.FragmentSource
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.uiblock.UiBlockSource

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
     * Where this component belongs (issue #433): the boot roles and capability tags whose nodes carry it.
     *
     * The **declarative** half of the load decision, and the one worth preferring: it can be *read*. A
     * reviewer answering "does a consumer node carry the admin surface?" checks declarations rather than
     * executing [isLoaded] under each profile, and that auditability is the whole reason a declaration exists
     * where an `if` would also work.
     *
     * Defaults to [Presence.anywhere], so a component that says nothing loads everywhere, as every component
     * did before this existed.
     */
    fun presence(cxt: KdrCxt): Presence = Presence.anywhere

    /**
     * Whether this component contributes its schema to the application. Receives the startup [cxt] so the
     * decision can read instance config and environment (e.g., a demo component that loads only in developer
     * environments, as the `sample` module's `SampleComponent` does).
     *
     * **The escape hatch, not the main road.** [presence] covers role and tag gating declaratively; this is
     * for decisions that genuinely cannot be declared, such as the sample component's dependence on the
     * environment. It **narrows** rather than widens: a component loads only when its presence admits the node
     * *and* this returns true, so it cannot resurrect a component the declaration excluded.
     */
    fun isLoaded(cxt: KdrCxt): Boolean = true

    /** Whether this component's services are active. Receives the startup [cxt] for the same config-driven reason. */
    fun isActive(cxt: KdrCxt): Boolean = true

    /**
     * Contributes **instance configuration** this component needs in place before anything reads it (issue
     * #386) -- the code-is-config counterpart of the deployment's `AppConfigApplier`, for settings a component
     * owns rather than a deployment chooses.
     *
     * Runs after every [isLoaded] decision and **before** schema collection and any service, which is what
     * makes it usable for values the startup tier consumes: `NodeService` fixes the node's identity in its
     * `onCreate`, so a port set any later leaves the server bound correctly while `/health` reports the
     * default. (Observed, when this briefly lived in a regular service's `onCreate`.)
     *
     * **Contribute defaults, do not override.** A deployment's own config is already in place by now, so read
     * before writing (`get(key) ?: default`) -- otherwise a component silently overrules a choice somebody
     * made deliberately.
     *
     * Because it runs after the load decisions, a component cannot gate its own [isLoaded] on config another
     * component contributes here. That is deliberate: whether a component loads should follow from the
     * deployment, not from what its peers happened to set.
     */
    fun applyInstanceConfig(cxt: KdrCxt) {}

    /** Contributes schema modules (types + endpoints) into the collector. */
    fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {}

    /**
     * The fragment content this component contributes (issue #456) -- its own files, and any layer it puts
     * over somebody else's.
     *
     * Nothing enumerates the classpath, so a file no component declares is a file nothing validates;
     * declaring it is what puts it under the startup and `/operator/fragments/check` checks. Use
     * `fragmentFiles(...)` for the ordinary case of shipping files, `fragmentOverlayFile(...)` for a
     * `_overlay.md` beside one, and `fragmentInline(...)` for a layer written in code.
     *
     * One method rather than one per kind on purpose: a fragment file's content is what its layers add up to,
     * so a component that lists its files in one place and its overlays in another would be describing one
     * thing in two, and the merge order would depend on which list a reader happened to be in.
     */
    fun fragments(cxt: KdrCxt): List<FragmentSource> = emptyList()

    /**
     * The UiBlocks this component contributes (issue #457) -- the blocks it owns, and any layer it puts over
     * somebody else's.
     *
     * One method for both, as `fragments` is, and for the same reason: a block's content is what its layers
     * add up to, so listing bases in one place and overlays in another would describe one thing in two.
     */
    fun uiBlocks(cxt: KdrCxt): List<UiBlockSource> = emptyList()

    /**
     * The Gedra config bundles this component defines (issue #299) -- traits now, workflows later. Collected
     * beside schema, and for the same reason: nothing can enumerate them, so a bundle nobody declares is a
     * bundle nothing compiles.
     *
     * Contributed configs are checked against each other as they arrive (see `GedraConfigCollector`): a trait
     * id is unique across every namespace and kind, and a namespace has exactly one owner.
     */
    fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = emptyList()

    /**
     * Services that must be fully initialized before regular [services]. Returned as
     * entries wrapping factories (typically `::Ctor` references) so nothing is instantiated
     * until the registry binds it -- and so a service excluded by role or tag is never
     * constructed at all.
     */
    fun startupServices(cxt: KdrCxt): List<ServiceEntry> = emptyList()

    /**
     * Regular services, initialized after all [startupServices].
     *
     * Entries rather than bare factories (issue #433) so a service can be narrowed to some roles or tags
     * without the registry having to construct it to find out -- see [ServiceEntry]. Use [service] to build
     * one; `service(::Foo)` is a service that goes wherever its component does.
     */
    fun services(cxt: KdrCxt): List<ServiceEntry> = emptyList()
}
