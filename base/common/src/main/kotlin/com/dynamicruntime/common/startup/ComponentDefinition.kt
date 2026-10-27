package com.dynamicruntime.common.startup

import com.dynamicruntime.common.content.ComponentImageDef
import com.dynamicruntime.common.content.FragmentSource
import com.dynamicruntime.common.content.MarkdownDocDef
import com.dynamicruntime.common.context.DebugTagDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.flavor.FlavorContribution
import com.dynamicruntime.common.flavor.FlavorDef
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.uiblock.UiBlockSource
import com.dynamicruntime.common.webasset.WebResourcesDef

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
     * The owner root this component's global names live under (issue #950): every namespace its schema modules and
     * global configs declare types in is `<ownerRoot>` or `<ownerRoot>.<more>`. Core's components declare `kdr`, and
     * nothing else does by accident; any other owner chooses a root of its own.
     *
     * A component may still extend another owner's root -- `kdr` included -- one contribution at a time: a schema
     * module or config whose namespace is under a different root says so with `contributesTo`, so one component can
     * both add behavior of its own and extend an existing owner's without being split in two. What the boot refuses
     * is the accident: a namespace under neither, the reserved `client` root, or a declared type outside its
     * contribution's root.
     *
     * Null for a component that contributes no global names; one that does, without declaring a root, fails the
     * boot outside production. Core cannot see who is downstream, so this keeps an owner from colliding with
     * another by mistake, not one owner from deliberately claiming another's root.
     */
    val ownerRoot: String? get() = null

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

    /**
     * The deployment flavors this component **owns** (issue #1218): each flavor's name and what it is for. One
     * component declares a flavor; any may contribute to it ([flavors]).
     *
     * Asked of every component, loaded or not, while the instance is built and before any load decision -- a
     * flavor's values may decide what loads -- so the answer must not depend on anything that is not yet in place.
     */
    fun flavorDefs(cxt: KdrCxt): List<FlavorDef> = emptyList()

    /**
     * What this component contributes to deployment flavors (issue #1218), built with `flavor(name) { ... }`:
     * instance-config keys to set and existing environment variables to default while a flavor is active. Asked
     * when [flavorDefs] is, for the same reason.
     *
     * Code never tests a flavor. Where this component behaves differently under one, it reads the key or variable
     * the flavor sets, as it reads any other -- in [presence]/[isLoaded] to decide whether it loads, in
     * [addSchema] for its endpoints, and in [services] for its services.
     */
    fun flavors(cxt: KdrCxt): List<FlavorContribution> = emptyList()

    /** Contributes schema modules (types + endpoints) into the collector. */
    fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {}

    /**
     * The fragment content this component contributes (issue #456) -- its own files, and any layer it puts
     * over somebody else's.
     *
     * Nothing enumerates the classpath, so a file no component declares is a file nothing validates;
     * declaring it is what puts it under the startup and `/operator/fragments/check` checks. Use
     * `fragmentFiles(...)` for the ordinary case of shipping files, `fragmentOverlayFile(...)` for a
     * `_overlay.md` beside one, `omnibusOverlay(...)` for one `_omnibus.md` overlaying several files, and
     * `fragmentInline(...)` for a layer written in code. A shipped file says
     * **where the application shows it** (`shownOn`, issue #933), so the copy editor can tell a key somebody
     * reads from one nothing displays; leave it out only for a file the application does not show.
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
     * The Markdown documents this component ships and serves in-app (issue #1136): each one's resource id, label
     * and source path, and whether the home page's Documents list offers it. Declared for the same reason as
     * [fragments] -- the classpath is not enumerable, so a document nobody declares is one nothing links to -- and
     * collected in component order, which is the order the Documents list shows them in.
     */
    fun docs(cxt: KdrCxt): List<MarkdownDocDef> = emptyList()

    /**
     * The images this component ships in its resources (issue #1339), each a rooted name (under [ownerRoot]) and the
     * file it is read from under `component-images/` (`componentImage`). Copy names one as `![alt](img:<name>)`, and
     * `ComponentImageService` checks and serves them. Declared for the reason [docs] are: the classpath is not
     * enumerable, so what is declared is what is read, checked, and served.
     */
    fun images(cxt: KdrCxt): List<ComponentImageDef> = emptyList()

    /**
     * The web resources packages this component ships (issue #1301), each a rooted id (under [ownerRoot]) and a file
     * per slot under `web-resources/` (`webResources`): the favicon and brand mark a client defined in source code
     * presents itself with when its definition names the package. `ComponentImageService` checks and holds them,
     * beside the [images]. Declared for the same reason.
     */
    fun webResources(cxt: KdrCxt): List<WebResourcesDef> = emptyList()

    /**
     * The `_debug` words this component reads (issue #1334), each declared once as a [DebugTagDef] beside the code
     * that checks it. Gathered from the loaded components at boot, into the list the app bar's debug box offers; a
     * word two components declare refuses the boot, and one checked but declared by no loaded component is refused
     * when checked.
     */
    fun debugTags(cxt: KdrCxt): List<DebugTagDef> = emptyList()

    /**
     * The Gedra config bundles this component defines (issue #299) -- traits, workflows, clients. Collected
     * beside schema, and for the same reason: nothing can enumerate them, so a bundle nobody declares is a
     * bundle nothing compiles.
     *
     * Contributed configs are checked against each other as they arrive (see `GedraConfigCollector`): a global
     * trait's id is unique across every client and kind, a client's own trait's id within that client (issue
     * #807), and a namespace has exactly one owner.
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
