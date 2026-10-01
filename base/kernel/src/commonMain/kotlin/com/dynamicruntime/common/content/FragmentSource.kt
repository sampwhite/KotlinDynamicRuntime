package com.dynamicruntime.common.content

import com.dynamicruntime.common.exception.KdrException

/**
 * Who a fragment file is *for* (issue #514) -- and, through that, which template pass resolves it.
 *
 * A fragment file used to be one thing: content the frontend fetches and renders. The backend `@t` pass
 * (issue #505) added a second use -- a `%{@t("fileId.namespace.key")}` a handler resolves server-side before
 * content ships -- and with it a file whose *only* reason to exist is to be pulled that way, never delivered.
 * Audience is the file's own statement of which it is, so the two are not told apart by guesswork.
 *
 *  - [frontend] -- the ordinary case, and the default. The content server ships it, and the response is cached
 *    `immutable` under a content-hash URL, so its values must be **stable**: a `${...}` in it is a frontend
 *    block the delivered copy carries and the frontend finishes. It never gets a backend pass, so it must not
 *    hold a `%{...}` block -- one would ship raw.
 *  - [backend] -- a **private** file: never served, so a caller cannot fetch it however it names the URL. It
 *    exists to be pulled by a backend `%{@t(...)}`, which is why it may hold `%{...}` freely, and it may also
 *    carry `${...}` onward for the frontend to finish against whichever element the pulled text lands on.
 *
 * The distinction matters because a delivered file is cached forever against its content: a value resolved per
 * request could not be delivered without breaking that cache, so anything request-resolved has to live in a
 * file that is pulled rather than served. Audience draws that line where it can be declared and checked.
 */
@Suppress("EnumEntryName")
enum class FragmentAudience {
    /** Delivered to the frontend and cached against its content; the default. See [FragmentAudience]. */
    frontend,

    /** Never served; exists only to be pulled by a backend `%{@t(...)}`. See [FragmentAudience]. */
    backend,
}

/**
 * One contribution to a fragment file's content (issue #456) -- a **layer**.
 *
 * A fragment file used to be exactly one classpath resource, so "the file" and "its content" were the same
 * thing. They no longer are: several contributors may have something to say about one `fileId`, and what the
 * frontend receives is what they add up to. A source is one of those contributors.
 *
 * ### Base and overlay
 *
 * A **base** layer supplies the file's content. An **overlay** changes some of it, naming only the keys it
 * changes -- which is the whole point, since an overlay that had to restate the file would go stale against
 * the base the first time the base gained a key.
 *
 * ### Why layers merge rather than chaining
 *
 * A lookup could walk the layers, overlay first. It would give the same answers, and it cannot serve the
 * frontend: the content server ships a **whole file** as one map, and the frontend then does its own lookups
 * with no idea that overlays exist. So the layers are merged into one effective map -- and "an overlay is
 * consulted first" is written as "an overlay is applied last", which is the same statement about precedence.
 */
class FragmentSource(
    /** The fragment file this contributes to -- what a UI-config names and the frontend fetches. */
    val fileId: String,
    /**
     * Whether this **changes** content rather than supplying it.
     *
     * It decides precedence (every overlay is applied after every base), and it is what makes an orphan
     * checkable: an overlay key that no base declares is almost always a base key that was renamed, and
     * `/operator/fragments/check` reports it. A base key nothing overlays is just an ordinary key.
     */
    val isOverlay: Boolean,
    /**
     * The client whose content this changes, or null for every client.
     *
     * Only a client's own configuration sets it. A component contributes to everybody, because a component
     * that wanted to say something about one client would be a component that knows its customers.
     */
    val client: String?,
    /**
     * Where this came from, for diagnostics -- `md-fragments/home_overlay.md`, or a component's own name for
     * an inline map. Carried because the interesting failures are about *which* layer said something, and a
     * report naming only the fileId cannot answer that.
     */
    val origin: String,
    /**
     * Who this file is for -- [FragmentAudience.frontend] (delivered, the default) or
     * [FragmentAudience.backend] (private, pulled not served).
     *
     * It is a fact about the **file**, not the layer, so it is the **base** that decides: an overlay changes
     * some values of a file whose audience is already settled, and its own value here is ignored (it defaults
     * [FragmentAudience.frontend][FragmentAudience] and never has to be set). A file marked
     * [backend][FragmentAudience] by any base is treated as backend -- the fail-safe direction, since the cost
     * of getting it wrong is a private file served rather than a public one withheld.
     */
    val audience: FragmentAudience = FragmentAudience.frontend,
    /**
     * Where the application shows this file's copy (issue #933), as the declaration says it -- "the home page and
     * app bar" -- so an editor can tell a key somebody reads from one nothing displays. A fact about the **file**,
     * like [audience], so it is the **base** that declares it; an overlay's is ignored. Null means the file is not
     * shown by this application: a fixture, or one only a deployment's own frontend reads by URL. Kept with the
     * declaration rather than derived from the code that reads the file, so it cannot drift from it.
     */
    val shownOn: String? = null,
    /**
     * The one client [shownOn] holds for (issue #933), when the file is read by that client's configuration alone
     * -- a backend file its workflows pull, say. Null, the ordinary case, means every client. Base-declared like
     * [shownOn]; to any other client the file is then not shown.
     */
    val shownFor: String? = null,
    /**
     * The name of the client config this layer came from, or null for a component's (issue #916). Kept beside
     * the free-text [origin] because a report of what a client customized has to name the config that set a
     * value -- to show it, and later to write to it -- rather than a diagnostic phrase about it.
     */
    val configName: String? = null,
    /**
     * Whether this layer came from a **stored** client config rather than one in source (issue #916). It is
     * what orders a client's own overlays -- see [overlayPrecedence].
     */
    val stored: Boolean = false,
    /**
     * The template this layer was cloned from (issue #945), or null. A client extending a template gets the
     * template's overlays as layers of its own, applied **below** the client's own -- see [overlayPrecedence].
     */
    val inheritedFrom: String? = null,
    /**
     * Reads this layer's content, or null when its resource is absent.
     *
     * A function rather than a map so a classpath read stays lazy and an inline map needs no wrapper: both
     * kinds of layer are the same kind of thing to everything downstream, which is what lets a fragment file
     * be part file and part code without anybody choosing between two mechanisms.
     */
    val load: () -> Map<String, Map<String, String>>?,
) {
    /**
     * This layer filed again: the same content under a different [client], [configName], [stored] or [inheritedFrom].
     * The one place every field is copied, so a field added to this class cannot be dropped by one of the paths that
     * re-file a layer -- a config stamping its name (issue #916), a sandbox rebinding its parent's (#928), an
     * extending client cloning its template's (#945).
     */
    fun refiled(
        client: String? = this.client,
        configName: String? = this.configName,
        stored: Boolean = this.stored,
        inheritedFrom: String? = this.inheritedFrom,
    ): FragmentSource = FragmentSource(
        fileId = fileId, isOverlay = isOverlay, client = client, origin = origin, audience = audience,
        shownOn = shownOn, shownFor = shownFor, configName = configName, stored = stored,
        inheritedFrom = inheritedFrom, load = load,
    )

    override fun toString(): String = "$fileId <- $origin" + (client?.let { " ($it)" } ?: "")
}

/**
 * Where an overlay is applied among the overlays of one merge (issue #916): a component's first, then what the
 * client **inherited** from the template it extends (issue #945), then the client's from **source**, then the
 * client's from **stored** configuration -- later wins, so a stored overlay has the last word. Shared by the
 * fragment and the UiBlock merges, which order their layers alike.
 *
 * The stored-after-source half is a rule rather than an accident of load order (stored configs happen to load
 * after source ones) because editing a client's copy writes to stored configuration: an administrator's change
 * to a key the client's source config also sets has to win, and has to go on winning however the registry is
 * next assembled -- at boot, or by a reload appending a client's revised layers. The inherited rank is the same
 * kind of rule: a template's copy is what the client starts from, so anything the client says itself wins, and
 * since the template's copies are added after the client's own configs, load order would say the opposite.
 */
fun overlayPrecedence(client: String?, stored: Boolean, inherited: Boolean = false): Int = when {
    client == null -> 0
    inherited -> 1
    !stored -> 2
    else -> 3
}

/** The suffix marking a classpath fragment file as an overlay: `<fileId>_overlay.md`. */
const val fragmentOverlaySuffix = "_overlay"

/**
 * A layer built **in code** rather than read from a file (issue #456), through the [FragmentMapBuilder] DSL.
 *
 * Not every fragment wants to be a file. A component with three strings to override should not have to ship a
 * Markdown resource to say so, and a configuration that arrives as data has no classpath to live on at all. The
 * merge does not care which a layer is, so the two compose: a file base with an inline overlay over it is the
 * ordinary shape of a small change.
 *
 * [origin] is the contributor's own name, and it is required rather than derived because an inline map has no
 * file path to be identified by -- and "which layer set this?" is the question a fragment report exists to
 * answer.
 */
fun fragmentInline(
    fileId: String,
    origin: String,
    isOverlay: Boolean = true,
    client: String? = null,
    audience: FragmentAudience = FragmentAudience.frontend,
    shownOn: String? = null,
    shownFor: String? = null,
    build: FragmentMapBuilder.() -> Unit,
): FragmentSource {
    val content = FragmentMapBuilder().apply(build).build()
    return FragmentSource(fileId, isOverlay, client, origin, audience, shownOn, shownFor, load = { content })
}

/**
 * Builds a three-tier fragment map in code: `namespace -> key -> value` (issue #456).
 *
 * A builder rather than a raw nested map literal, for the reason every other kd2 config is built through one:
 * a raw literal has no place to put a check, so a duplicate key silently wins and a namespace typo silently
 * creates a second namespace. Here both are refused where they are written.
 */
class FragmentMapBuilder {
    private val namespaces = LinkedHashMap<String, LinkedHashMap<String, String>>()

    /**
     * Declares [name]'s keys. Re-opening a namespace already declared in this builder is refused: two blocks
     * for one namespace read as two independent statements and are not, so the second's keys would silently
     * join the first's -- and a duplicate key across them would silently win.
     */
    fun namespace(name: String, build: FragmentNamespaceBuilder.() -> Unit) {
        if (name.isEmpty()) {
            throw KdrException.mkConv("A fragment namespace needs a name.")
        }
        if (namespaces.containsKey(name)) {
            throw KdrException.mkConv(
                "The fragment namespace '$name' is declared twice in one map. Put its keys in one block: two " +
                    "blocks read as two statements, and the second's keys would silently join the first's.",
            )
        }
        namespaces[name] = FragmentNamespaceBuilder(name).apply(build).build()
    }

    fun build(): Map<String, Map<String, String>> = namespaces.mapValues { it.value.toMap() }
}

/** The keys of one namespace; see [FragmentMapBuilder.namespace]. */
class FragmentNamespaceBuilder(private val name: String) {
    private val keys = LinkedHashMap<String, String>()

    /** Declares [key]'s Markdown [value]. A key declared twice is refused rather than overwritten. */
    fun key(key: String, value: String) {
        if (key.isEmpty()) {
            throw KdrException.mkConv("A fragment key in namespace '$name' needs a name.")
        }
        if (keys.containsKey(key)) {
            throw KdrException.mkConv(
                "The fragment key '$name.$key' is declared twice in one map. One of the two would win " +
                    "silently, and which one is not something a reader should have to work out.",
            )
        }
        keys[key] = value
    }

    fun build(): LinkedHashMap<String, String> = keys
}
