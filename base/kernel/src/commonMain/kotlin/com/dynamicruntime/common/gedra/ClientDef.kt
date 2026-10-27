package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.SchTypeBuilder
import com.dynamicruntime.common.schema.SchTypesBuilder
import com.dynamicruntime.common.schema.coerceAndValidate
import com.dynamicruntime.common.schema.offContractKeyFailures
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.schemaDefs
import com.dynamicruntime.common.user.normalizeUserLabels
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLongOrNull
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.home.HFRAG

/**
 * What a client is *for* -- the purpose it was created to serve (issue #343).
 *
 * Expected to grow: a new behavior that should be visible in some kinds of clients and not others conditions
 * itself on this. Only [production], in a production deployment, carries any strong promise that the client
 * will not be torn down and started over.
 *
 * An enum against the deliberate lack of them in `CxtConstants.kt`, and for the reason `GedraEditAction`
 * gives: the code exhausts it, and `SchTypeBuilder.options` builds a schema's choice list straight from the
 * entries, so the validator, the form's dropdown and any `when` over it cannot come to disagree. The argument
 * against enums is about values a *running deployment* may invent; a usage type is a thing this codebase has
 * to have an opinion about before it can ship the behavior conditioned on it.
 */
@Suppress("EnumEntryName")
enum class ClientUsageType {
    /** Actively used by people who care that the application stays where they left it. */
    production,

    /** Exists only to run tests against; may be allowed test fixture endpoints even in production. */
    test,

    /** Development work. Real people may try things; no external business depends on it. */
    dev,

    /** Exists to generate other client definitions. Its resources may be referenced by any client. */
    template,

    /** Exists to demonstrate the product, usually for sales; relaxed security and bulk-cleanup conveniences. */
    demo,
}

/**
 * Whose client this is -- ours or somebody else's (issue #343).
 *
 * Orthogonal to [ClientUsageType], which says what a client is *for*: an internal client can be `production`,
 * `dev` or `test`. Made a second axis rather than a sixth usage type deliberately, because the first internal
 * demo client would otherwise force the question "which is it?" -- the same conflation as letting the
 * `allClients` capability stand in for "is one of us".
 *
 * **It is an authority axis, not a label.** It decides what a caller is proactively shown, and it relaxes the
 * functional-group restriction (see [ClientDef.includedTraits]), so a client may not choose its own: it is set in
 * source code, or by a platform operator for a client defined in data -- never by the client's own administrator,
 * or a client could write `internal` and take a capability with it. See [ClientFieldPolicies] (issues #820, #1108).
 */
@Suppress("EnumEntryName")
enum class ClientAudience {
    /** Ours: staff, batch activity, the deployment's own work. */
    internal,

    /** Somebody else's. */
    customer,
}

/**
 * Where a client stands on this node (issue #828) -- a client summary row's [CLD.status]. The summary listing's
 * `allKnown` option lists every client this node knows of, so an administrator asking why a client is not working
 * can see one that is not, and this says which way it is not.
 */
@Suppress("EnumEntryName")
enum class ClientStatus {
    /** Carried here: declared, kept by the checks, and enabled in this environment. */
    present,

    /** Declared and kept by the checks, but not enabled in this environment. */
    notEnabled,

    /** Declared and kept by the checks, but retired by the platform ([ClientDef.retired], issue #1111): absent everywhere. */
    retired,

    /** Declared, but a check dropped its definition; its issues say why. */
    dropped,

    /**
     * Stored configuration exists for it, but no loaded config declares it: its configuration was refused whole,
     * or none of it defines the client.
     */
    storedOnly,
}

/**
 * What a client made **by another client** takes for a field of its definition (issue #1108) -- the delegated
 * create of #1112. The platform's own create sets any field it is given.
 */
@Suppress("EnumEntryName")
enum class ClientFieldAtCreate {
    /** Whoever makes the client chooses it. */
    creator,

    /** It is copied from the client that made it. */
    parent,

    /** It starts empty, or off. */
    cleared,
}

/** Who changes a field of a client's definition once the client exists (issue #1108). */
@Suppress("EnumEntryName")
enum class ClientFieldEdit {
    /** The client's own administrator, in the definition editor (issue #1026). */
    definitionEditor,

    /**
     * The client's own administrator, through a surface of its own rather than the definition editor: the traits it
     * includes through the traits editor (issue #1117), its sandbox through the sandbox's management.
     */
    ownSurface,

    /**
     * Only the platform (issue #820): source code, or for a client defined in data a platform operator -- an
     * `allClients` administrator, an import, a service write. The field carries authority over the client, or moves
     * what it holds or where it runs, so a write from the client's own administrator that changes it is refused.
     */
    platform,
}

/** One field's policy (issue #1108): what a client made by another takes for it, and who changes it after. */
class ClientFieldPolicy(val atCreate: ClientFieldAtCreate, val edit: ClientFieldEdit)

/**
 * The policy of every field of a [ClientDef] (issue #1108), declared once, in the order a form draws them. What a
 * client's own administrator may change, what the definition editor offers, what the platform's create takes, and
 * what a client made by another copies or clears are each read from here rather than restated, so a field's
 * editability is decided where the field is listed. Every field the definition's schema declares must have one:
 * declaring a field without a policy fails as the schema is built (`ClientDef.defineInfoType`).
 *
 * An `allClients` administrator -- the platform -- may set any field.
 */
object ClientFieldPolicies {
    private fun p(atCreate: ClientFieldAtCreate, edit: ClientFieldEdit) = ClientFieldPolicy(atCreate, edit)

    val byField: Map<String, ClientFieldPolicy> = linkedMapOf(
        CLD.clientId to p(ClientFieldAtCreate.creator, ClientFieldEdit.platform),
        CLD.name to p(ClientFieldAtCreate.creator, ClientFieldEdit.definitionEditor),
        CLD.description to p(ClientFieldAtCreate.creator, ClientFieldEdit.definitionEditor),
        // Authority over the client, and where it runs: the platform's, and a client's maker's to pass on.
        CLD.customerId to p(ClientFieldAtCreate.parent, ClientFieldEdit.platform),
        CLD.usageType to p(ClientFieldAtCreate.parent, ClientFieldEdit.platform),
        CLD.audience to p(ClientFieldAtCreate.parent, ClientFieldEdit.platform),
        CLD.enabledEnvironments to p(ClientFieldAtCreate.parent, ClientFieldEdit.platform),
        // The template a client is built on is chosen when it is made; changing it after moves everything it holds.
        CLD.extendsFromClientId to p(ClientFieldAtCreate.creator, ClientFieldEdit.platform),
        // A domain routes to one client, so a copy would refuse every create; where a client is reached is the
        // platform's (issue #1116).
        CLD.domainPrefix to p(ClientFieldAtCreate.cleared, ClientFieldEdit.platform),
        CLD.customDomain to p(ClientFieldAtCreate.cleared, ClientFieldEdit.platform),
        // What it presents itself with names a package a component ships, which only source code names (issue #1301):
        // a stored definition naming one is refused, so no create sets it and no editor offers it. A client built on a
        // template presents the template's.
        CLD.webResourcesId to p(ClientFieldAtCreate.cleared, ClientFieldEdit.platform),
        // Where the client's people are: a client made by another starts where its maker is (issue #1115).
        CLD.timeZone to p(ClientFieldAtCreate.parent, ClientFieldEdit.definitionEditor),
        CLD.userLabels to p(ClientFieldAtCreate.cleared, ClientFieldEdit.definitionEditor),
        CLD.sandbox to p(ClientFieldAtCreate.creator, ClientFieldEdit.ownSurface),
        CLD.includedTraits to p(ClientFieldAtCreate.cleared, ClientFieldEdit.ownSurface),
        CLD.preload to p(ClientFieldAtCreate.cleared, ClientFieldEdit.platform),
        // Only a definition in source code may set it; a stored one claiming it is refused outright.
        CLD.staticConfig to p(ClientFieldAtCreate.cleared, ClientFieldEdit.platform),
        CLD.retired to p(ClientFieldAtCreate.cleared, ClientFieldEdit.platform),
        CLD.testFeatures to p(ClientFieldAtCreate.cleared, ClientFieldEdit.platform),
        // Who may join the client from `public`, and as what (issue #1248): authority over who becomes its
        // administrator, so the platform's, and never handed on to a client another makes.
        CLD.joinRoles to p(ClientFieldAtCreate.cleared, ClientFieldEdit.platform),
        // How long its people stay signed in (issue #1308): the client's to shorten, and a made client starts as its maker.
        CLD.sessionHours to p(ClientFieldAtCreate.parent, ClientFieldEdit.definitionEditor),
        // How often its open tabs ask the deployment for more (issue #1308): a load on the deployment, so the platform's.
        CLD.refreshIntervalSeconds to p(ClientFieldAtCreate.parent, ClientFieldEdit.platform),
    )

    /** [field]'s policy; a field with none is a fault, since every field the schema declares has one. */
    fun of(field: String): ClientFieldPolicy =
        byField[field] ?: throw KdrException.mkConv("Client field '$field' has no policy (issue #1108); declare one.")

    /** The fields a client made by another client takes from whoever makes it (issue #1112): its creator's choice. */
    val creatorFields: List<String> = byField.filter { it.value.atCreate == ClientFieldAtCreate.creator }.keys.toList()

    /**
     * The definition a client made **by another client** starts from (issue #1112), as a `ClientInfo` map: [given]'s
     * [creatorFields], [parent]'s fields whose policy is [ClientFieldAtCreate.parent] -- its customer, where it runs,
     * what it is for and whose it is -- and none of those a create clears. [parent] is the making
     * client's definition ([ClientDef.toInfo]).
     */
    fun delegatedDefinition(parent: Map<String, Any?>, given: Map<String, Any?>): Map<String, Any?> = buildMap {
        for ((field, policy) in byField) {
            val value = when (policy.atCreate) {
                ClientFieldAtCreate.creator -> given[field]
                ClientFieldAtCreate.parent -> parent[field]
                ClientFieldAtCreate.cleared -> null
            }
            if (value != null) put(field, value)
        }
    }

    /** The fields only the platform changes. */
    val platformFields: List<String> = byField.filter { it.value.edit == ClientFieldEdit.platform }.keys.toList()

    /**
     * The [platformFields] the definition editor offers an administrator who sees every client (issue #1116), beside
     * the client administrator's [editorFields]: where the client is reached, what it is for and whose it is, where it
     * runs, and its preload. Not its customer or the template it extends, which move what the client is under or holds
     * and stay a deliberate configuration write; not its static lock or its web resources (source only, #1301),
     * its retirement (its own section, #1182), its test features (a test instance's), or its join roles (#1248, a
     * list of roles the editor's one-line fields cannot hold).
     */
    val platformEditorFields: List<String> = listOf(
        CLD.usageType, CLD.audience, CLD.enabledEnvironments, CLD.preload, CLD.domainPrefix, CLD.customDomain,
        CLD.refreshIntervalSeconds,
    )

    /** The fields the definition editor offers a client's own administrator, in the order it draws them. */
    val editorFields: List<String> = byField.filter { it.value.edit == ClientFieldEdit.definitionEditor }.keys.toList()

    /**
     * The fields the **platform's** create takes (issue #1054): every field but those a create clears and that are
     * then changed only through a surface of their own or by a configuration write -- the traits a client includes,
     * its static lock, its retirement, and its test features. A field a create clears but the definition editor
     * offers (a domain, the suggested labels, the preload) the operator may fill in at once.
     */
    val createFields: List<String> = byField.filter { (field, policy) ->
        policy.atCreate != ClientFieldAtCreate.cleared || policy.edit == ClientFieldEdit.definitionEditor ||
            field in platformEditorFields
    }.keys.toList()

    /**
     * The platform-only fields [proposed] sets differently from [current] -- both `ClientInfo` maps
     * ([ClientDef.toInfo]), and a stored entry may leave out what is off or empty -- or, when the client has no
     * definition, from the least authority ([leastAuthority]). What refuses a client administrator's write.
     */
    fun platformChangesBy(proposed: Map<String, Any?>, current: Map<String, Any?>?): List<String> =
        platformFields.filter { field -> comparable(field, proposed[field]) != comparable(field, (current ?: leastAuthority)[field]) }

    /**
     * What a client with no definition yet holds in the fields that carry authority: the least, which is all a
     * client's own write could ever start from. The rest read as absent.
     */
    val leastAuthority: Map<String, Any?> = mapOf(
        CLD.audience to ClientAudience.customer.name,
        CLD.usageType to ClientUsageType.production.name,
    )

    /** [value] of [field] in a form two definitions compare by: a set for a list, a flag for a boolean, else text. */
    private fun comparable(field: String, value: Any?): Any? = when (field) {
        CLD.enabledEnvironments, CLD.testFeatures, CLD.includedTraits, CLD.userLabels -> value.toJsonListOfStrings().toSet()
        CLD.preload, CLD.staticConfig, CLD.sandbox, CLD.retired -> value == true
        else -> value.toOptStr()
    }
}

/**
 * The [ClientDef] fields the definition editor on the Clients page edits (issues #1026, #1116), read from the
 * policies (issue #1108): a client's administrator edits those whose policy is [ClientFieldEdit.definitionEditor]
 * ([names]); an administrator who sees every client edits those and the platform's fields the editor offers
 * ([ClientFieldPolicies.platformEditorFields]) too ([namesFor]). Each with what the endpoint says of it, so the editor
 * and the endpoint's input are the same set.
 */
@Suppress("EnumEntryName")
object ClientPresentationFields {
    /** How the editor takes a field's value. */
    enum class Kind {
        /** One line of text; blank clears an optional one. */
        text,

        /** The suggested user labels: several strings, written as one comma-separated line. */
        labels,

        /** One of [choicesOf]. */
        choice,

        /** Any of [choicesOf]. */
        choices,

        /** On or off. */
        flag,

        /**
         * An IANA time zone, picked from a list rather than typed (issue #1257). Text on the wire, held as [text]
         * is -- trimmed, blank clearing it -- and checked by the client check, not here.
         */
        timeZone,

        /**
         * A whole number (issue #1308). Text on the wire, as [text] is -- blank clearing it, for the deployment's value
         * -- and stored as a number; its bounds are the definition's schema's.
         */
        number,
    }

    /** The headed groups the editor draws its fields in (issue #1308), in their order. */
    enum class Section(val heading: String) {
        /** What the client is called and noted as. */
        about("About"),

        /** How the client behaves for its people. */
        behavior("How it behaves"),

        /** What the platform sets: what it is for, whose it is, where it runs and is reached. */
        platform("Platform"),
    }

    /** The [Section] [name] is drawn in. */
    fun section(name: String): Section = when (name) {
        CLD.name, CLD.description -> Section.about
        CLD.timeZone, CLD.userLabels, CLD.sessionHours, CLD.refreshIntervalSeconds -> Section.behavior
        else -> Section.platform
    }

    /** [names] grouped by [section], in the sections' order and each group in the order given; an empty group is left out. */
    fun bySection(names: List<String>): List<Pair<Section, List<String>>> =
        Section.entries.map { section -> section to names.filter { section(it) == section } }.filter { it.second.isNotEmpty() }

    /** Each editable field, a [CLD] key, in the order an editor draws them, with what the endpoint says of it. */
    val descriptions: Map<String, String> = linkedMapOf(
        CLD.name to "The name presented to users as the name of the client.",
        CLD.description to "An internal note about who, what, or why; blank clears it.",
        CLD.timeZone to "The client's IANA time zone (America/Chicago), which its dates are read and shown in; " +
            "blank clears it. Changing it moves no stored moment.",
        CLD.userLabels to "The user labels the client suggests, each once and trimmed; empty clears them.",
        CLD.sessionHours to "How long a sign-in lasts, in hours; blank for the deployment's default. At most the " +
            "deployment's limit. Shortening it ends longer sessions at once; lengthening it reaches sign-ins made after.",
        CLD.refreshIntervalSeconds to "How often, in seconds, an idle open tab re-reads what it shows " +
            "(${CLD.minRefreshIntervalSeconds} to ${CLD.maxRefreshIntervalSeconds}); blank for the deployment's. Set by the platform.",
        CLD.usageType to "What the client is for. Set by the platform.",
        CLD.audience to "Whose client this is: ours, or somebody else's. Set by the platform.",
        CLD.enabledEnvironments to "The environments the client is enabled in; naming any past unit and local needs " +
            "both of those. Set by the platform.",
        CLD.preload to "Whether the client's caches are computed before the node reports ready. Set by the platform.",
        CLD.domainPrefix to "A prefix on a core domain that routes to this client; blank clears it. No other client " +
            "may use it. Set by the platform.",
        CLD.customDomain to "A whole hostname that routes to this client; blank clears it. No other client may use it. " +
            "Set by the platform.",
    )

    /** The fields a client's own administrator edits, in the order an editor draws them. */
    val names: List<String> = descriptions.keys.filter { it in ClientFieldPolicies.editorFields }

    /** The fields the editor offers only an administrator who sees every client, in the order it draws them. */
    val platformNames: List<String> = descriptions.keys.filter { it in ClientFieldPolicies.platformEditorFields }

    /** The fields the editor offers: [names], and [platformNames] too for the [platform]. */
    fun namesFor(platform: Boolean): List<String> = if (platform) names + platformNames else names

    init {
        // The policy decides what is editable; this says it in the endpoint's words, so each has its sentence.
        val fields = ClientFieldPolicies.editorFields + ClientFieldPolicies.platformEditorFields
        check(descriptions.keys == fields.toSet()) {
            "The definition editor's descriptions ${descriptions.keys} are not its fields $fields."
        }
    }

    /** How the editor takes [name]'s value. */
    fun kind(name: String): Kind = when (name) {
        CLD.userLabels -> Kind.labels
        CLD.usageType, CLD.audience -> Kind.choice
        CLD.enabledEnvironments -> Kind.choices
        CLD.preload -> Kind.flag
        CLD.timeZone -> Kind.timeZone
        CLD.sessionHours, CLD.refreshIntervalSeconds -> Kind.number
        else -> Kind.text
    }

    /** The values a [Kind.choice] or [Kind.choices] field takes; empty for any other. */
    fun choicesOf(name: String): List<String> = when (name) {
        CLD.usageType -> ClientUsageType.entries.map { it.name }
        CLD.audience -> ClientAudience.entries.map { it.name }
        CLD.enabledEnvironments -> ENV.names
        else -> emptyList()
    }
}

/**
 * The fields a client is **created** with by the platform (issue #1054), as [CLD] keys -- read from the policies
 * ([ClientFieldPolicies.createFields], issue #1108), so the endpoint's input type and the code that reads a request
 * are the same set.
 */
object ClientCreateFields {
    val names: List<String> get() = ClientFieldPolicies.createFields
}

/** Names and field keys for a client definition (issue #343). */
@Suppress("ConstPropertyName")
object CLD {
    /**
     * On a create (issue #1346): whether the new client starts with workflows made from the starter templates
     * (`WFT.starters`), a creation workflow, and a survey -- on unless set false.
     */
    const val starterWorkflows = "starterWorkflows"

    /**
     * What marks an entry in [ClientDef.includedTraits] as a **group** rather than a trait id.
     *
     * A sigil no trait id can hold -- `GedraId`'s parts are `[A-Za-z0-9_]` -- so the two never have to be
     * told apart by looking them up.
     */
    const val groupSigil = '#'

    /**
     * The group of every trait owned by `global`, computed at load time rather than written down.
     *
     * A **functional** group: its membership is derived from what is global, so it cannot fall out of step
     * the way a hand-applied tag would. A client naming it is declaring that it *tracks a computed set*,
     * which is the only thing it could have meant.
     */
    const val allGlobal = "#allGlobal"

    // Field (JSON key) names, shared with the frontend: an admin console listing clients should build its
    // reads from the same strings the backend serves them under.
    const val clientId = "clientId"
    const val name = "name"
    const val description = "description"
    const val usageType = "usageType"
    const val audience = "audience"
    const val webResourcesId = "webResourcesId"

    /** The client's IANA time zone (issue #1115); see [ClientDef.timeZone]. */
    const val timeZone = "timeZone"
    const val enabledEnvironments = "enabledEnvironments"
    const val preload = "preload"
    const val staticConfig = "staticConfig"
    const val extendsFromClientId = "extendsFromClientId"
    const val domainPrefix = "domainPrefix"
    const val customDomain = "customDomain"
    const val includedTraits = "includedTraits"
    const val testFeatures = "testFeatures"
    const val userLabels = "userLabels"
    const val sandbox = "sandbox"

    /** The customer that owns the client (issue #1107); see [ClientDef.customerId]. */
    const val customerId = "customerId"

    /** On an overview row: the client's usage against its plan ([PlanUsage], issue #1109); absent for one on none. */
    const val planUsage = "planUsage"

    /** Whether the platform retired the client (issue #1111); see [ClientDef.retired]. */
    const val retired = "retired"

    /** The roles a visitor may join the client as (issue #1248); see [ClientDef.joinRoles]. */
    const val joinRoles = "joinRoles"

    /** How long a sign-in to the client lasts, in hours (issue #1308); see [ClientDef.sessionHours]. */
    const val sessionHours = "sessionHours"

    /** The shortest session a client may set, in hours. */
    const val minSessionHours = 1

    /** How often an idle open tab re-reads, in seconds (issue #1308); see [ClientDef.refreshIntervalSeconds]. */
    const val refreshIntervalSeconds = "refreshIntervalSeconds"

    /** The most often a client's idle tabs may re-read, in seconds. */
    const val minRefreshIntervalSeconds = 5

    /** The least often a client's idle tabs may re-read, in seconds: an hour. */
    const val maxRefreshIntervalSeconds = 3600

    /**
     * On the definition retrieve (issue #1308): the deployment's values of the fields a client may leave unset to take
     * them -- [sessionHours] and [refreshIntervalSeconds] -- so a page can say what an unset one means.
     */
    const val platformDefaults = "platformDefaults"

    /** In [platformDefaults]: the most a client's [sessionHours] may be, in hours (issue #1308). */
    const val maxSessionHours = "maxSessionHours"

    /** Schema type name for the input of a client's creation by a customer's root administrator (issue #1112). */
    const val delegatedCreateTypeName = "ClientDelegatedCreate"

    /** Schema type name for what a root administrator's create did (issue #1112). */
    const val delegatedCreateResultTypeName = "ClientDelegatedCreateResult"

    /** On a root administrator's create: the creator's own admin user in the new client (issue #1112). */
    const val userId = "userId"

    /** On a root administrator's create: why the creator's user could not be made, when it could not (issue #1112). */
    const val userFailure = "userFailure"

    /** Schema type name for what creating a person's self-serve client did (issue #1113). */
    const val selfServeCreateResultTypeName = "ClientSelfServeCreateResult"

    // On a self-serve create (issue #1336): the new client's home copy, each field the `home` key it sets.
    const val homeBrand = "homeBrand"
    const val homeTitle = "homeTitle"
    const val homeIntro = "homeIntro"
    const val homeNote = "homeNote"

    /** The longest wordmark a self-serve create takes: it sits beside the mark in the app bar. */
    const val homeBrandMaxLength = 40

    /** The longest home title a self-serve create takes. */
    const val homeTitleMaxLength = 120

    /** The longest home introduction or note a self-serve create takes, in characters of Markdown. */
    const val homeTextMaxLength = 4000

    /** The self-serve create's home-copy fields, each to the key of the home file's `home` namespace it sets. */
    val homeCopyFields: Map<String, String> = linkedMapOf(
        homeBrand to HFRAG.brand, homeTitle to HFRAG.title, homeIntro to HFRAG.intro, homeNote to HFRAG.note,
    )

    /** On a self-serve create: whether the session moved to the creator's user in the new client (issue #1113). */
    const val switched = "switched"

    /** Schema type name for what retiring or bringing back a client did (issue #1111). */
    const val retirementResultTypeName = "ClientRetirementResult"

    /** On a create: a customer to make with the client, whose id is the client's and whose root it is (issue #1107). */
    const val newCustomer = "newCustomer"

    /** On a create's result: the customer made with the client, as stored (issue #1107). */
    const val customer = "customer"

    /** On a create's result: why the customer made with the client was not finished, when it was not (issue #1107). */
    const val customerFailure = "customerFailure"

    /** On a client overview row that is a sandbox (issue #932): the client whose sandbox it is. */
    const val sandboxOf = "sandboxOf"

    /** On a client overview row: whether the client has a live sandbox (issue #932). */
    const val hasSandbox = "hasSandbox"

    /**
     * On a client overview row: the customer whose root the client is (issue #1182), which may not be retired until
     * another root is named; absent for any other client.
     */
    const val rootOf = "rootOf"

    /**
     * Whether the client runs only published configuration: in the sandbox edit's result (issue #932), and on an
     * overview row (issue #1001), where it decides whether an unpublished revision is live.
     */
    const val publishedOnly = "publishedOnly"

    /**
     * On an overview row (issue #1001): whether the client takes **nothing** stored on this node -- static in
     * production (`staticConfig`) -- so publishing its stored configuration changes nothing it runs. For a
     * sandbox it is its parent's answer: a sandbox holds no configuration of its own, and every change made from
     * it is one to its parent's, which is refused there.
     */
    const val staticHere = "staticHere"

    /** Schema type name for the sandbox edit's result (issue #932). */
    const val sandboxResultTypeName = "ClientSandboxResult"
    /** Schema type name for the definition edit's result (issue #1026). */
    const val definitionEditResultTypeName = "ClientDefinitionEditResult"
    /** On the definition edit's result: the stored definition after the edit ([infoTypeName]); [client] there is the id. */
    const val definition = "definition"
    /**
     * On a client's definition (issue #1026): the client's **stored** definition as its latest revision holds it,
     * when it has one -- the editor's baseline, which differs from [client] (what the client runs) by an
     * unpublished draft and by what a template fills in.
     */
    const val storedDefinition = "storedDefinition"
    /** Beside [storedDefinition]: the name of the stored configuration holding it, so a page can say which to publish. */
    const val storedDefinitionConfig = "storedDefinitionConfig"

    /** Schema type name for the [ClientDef.toInfo] dump. */
    const val infoTypeName = "ClientInfo"

    /** The namespace `clientCatalogSchema` declares [infoTypeName] in; its value is that name. */
    const val catalogNamespace = "kdr.clientCatalog"

    /** [infoTypeName] qualified by [catalogNamespace] -- the canonical `ClientInfo`, for a cross-namespace `$ref`. */
    const val infoTypeQualified = "$catalogNamespace.$infoTypeName"

    /**
     * Schema type name for a client definition as a configuration **write** must give it (issue #1051): the fields
     * of [infoTypeName], with the list of environments closed. A type of its own rather than a second reading of
     * that name, so the stored slot can say which of the two it holds its entry to.
     */
    const val writtenInfoTypeName = "WrittenClientInfo"

    /** [writtenInfoTypeName] qualified by [catalogNamespace]: what the `kdr:clientDef` slot's data is declared as. */
    const val writtenInfoTypeQualified = "$catalogNamespace.$writtenInfoTypeName"

    /** Schema type name for the input of the create-client endpoint (issue #1054): [ClientCreateFields], written. */
    const val createTypeName = "ClientCreate"

    /** Schema type name for what creating a client answers (issue #1054). */
    const val createResultTypeName = "ClientCreateResult"

    /** On a create's result: why loading the created client failed on this node, when it did. */
    const val loadFailure = "loadFailure"

    /**
     * The id of the options source behind the create input's `extendsFromClientId` (issue #1054): the template
     * clients a client made from data may extend. Here for the reason [clientOptions] is.
     */
    const val templateOptions = "clientTemplateOptions"

    /**
     * The name of the stored configuration a created client's definition is written to (issue #1054): the client's
     * first, and the one its definition editors then find it in.
     */
    const val definitionConfigName = "main"

    /**
     * The id under which the clients-a-caller-may-name choice list is registered (issue #413).
     *
     * Here rather than beside the callback because both ends need it and only one of them can own it: the
     * attribute declaring `optionsSource(CLD.clientOptions)` lives wherever a client is asked for, while the
     * callback lives with the client endpoints. A literal at either end is a rename waiting to empty a list.
     */
    const val clientOptions = "clientOptions"

    // --- the client-definition retrieve + summary endpoints (issue #672) ------------------------------------

    /** Schema type name for one client's full definition (the `/admin/client/definition` retrieve, issue #672). */
    const val definitionTypeName = "ClientDefinition"

    /** Schema type name for one of a client's traits within [definitionTypeName] -- id, type, and data schema. */
    const val traitInfoTypeName = "ClientTraitInfo"

    /** Schema type name for one of a client's trait-usage rules within [definitionTypeName] (a listing column). */
    const val usageInfoTypeName = "ClientUsageInfo"

    /** Schema type name for a cross-client overview row (the `/admin/clients/summary` listing, issue #672). */
    const val summaryTypeName = "ClientSummary"

    // Definition-level field names on the retrieve/summary types (each matches its value). The per-trait and
    // per-usage keys are NOT re-declared here: a trait's field names live on `CCT` beside `GedraTrait` and a
    // usage's on `UF` beside `ClientTraitUsage`, so a renamed attribute is a single-file edit (CLAUDE.md).
    /** The client's attributes, as [ClientDef.toInfo] writes them ([infoTypeName]). */
    const val client = "client"
    const val traits = "traits"
    const val usages = "usages"
    const val workflows = "workflows"
    const val workflowIds = "workflowIds"
    const val traitIds = "traitIds"
    const val usageLabels = "usageLabels"

    /**
     * On a summary row (issue #695): whether the client declares a survey workflow -- what a cross-client
     * surface working in that client keys its survey-status filter on, as the shell's `hasSurvey` does for the
     * caller's own client.
     */
    const val hasSurvey = "hasSurvey"

    // --- a client's configuration issues (issue #840) -------------------------------------------------------

    /**
     * Schema type name for one configuration issue a check forgave -- what was wrong, what was dropped, and
     * where it came from. Declared in [catalogNamespace], which every node loads, so the config endpoints (an
     * app-only module) reference it as [configIssueTypeQualified].
     */
    const val configIssueTypeName = "ConfigIssue"

    /** [configIssueTypeName] qualified by [catalogNamespace], for a cross-namespace `$ref`. */
    const val configIssueTypeQualified = "$catalogNamespace.$configIssueTypeName"

    /** On a definition, summary, stored config or reload result: the configuration issues it carries. */
    const val issues = "issues"

    /**
     * On a definition: whether this node carries the client. False for one whose definition a check dropped --
     * returned anyway, with its [issues], so an administrator can see why it is not working.
     */
    const val present = "present"

    /** On a summary row (issue #828): where the client stands on this node, a [ClientStatus] name. */
    const val status = "status"

    /**
     * The summary listing's option (issue #828): also list every client this node knows of but does not carry --
     * not enabled here, dropped by a check, or known only from stored configuration -- each with its [status].
     */
    const val allKnown = "allKnown"

    // --- the administrators' clients overview (issue #904) --------------------------------------------------

    /** Schema type name for one row of the clients overview: a client, where it stands, and what it holds. */
    const val overviewTypeName = "ClientOverview"

    /** The namespace the overview module declares [overviewTypeName] in; its value is that name. */
    const val overviewNamespace = "kdr.clientOverview"

    /** [definitionTypeName] qualified by [catalogNamespace], for the scoped retrieve's cross-namespace `$ref`. */
    const val definitionTypeQualified = "$catalogNamespace.$definitionTypeName"

    /** Where the client's definition comes from: source code, or stored configuration (a `GedraConfigOrigin`). */
    const val origin = "origin"

    /** How many stored configurations this node loaded for the client -- its own definition, or overlays on one from source. */
    const val storedConfigs = "storedConfigs"

    /** How many live form documents the client holds. */
    const val forms = "forms"

    /** How many active (enabled) users the client has; disabled and deleted users are not counted. */
    const val users = "users"

    /** Of [users], how many have not yet claimed their account (invited, not registered). */
    const val unclaimedUsers = "unclaimedUsers"

    /** How many workflows the client sees, its own and the inherited global ones. */
    const val workflowCount = "workflowCount"

    /** How many fragment keys the client's own configuration overrides (issue #916). */
    const val copyOverrides = "copyOverrides"

    /** How many UiBlock items and objects -- menu entries among them -- the client's own configuration changes (issue #916). */
    const val blockOverrides = "blockOverrides"
}

/**
 * How a client reads in a choice list: its name with its id in brackets, or the id alone when it has no name.
 *
 * Both halves, because neither alone is enough. The **id** is what gets stored and what appears inside every
 * one of that client's gedra ids, so it is what an administrator will later recognize in a log or a URL, while
 * the **name** is what a person actually calls the client.
 *
 * In the kernel because both sides render this list: the backend when it answers a sourced choice list
 * (issue #413), and the admin console when it builds a selector from the clients endpoint. The same client
 * reading two ways in two dropdowns is the sort of difference nobody reports and everybody notices.
 */
fun clientLabel(clientId: String, name: String): String =
    if (name.isEmpty()) clientId else "$name ($clientId)"

/**
 * Marks an attribute as **naming a client**: it offers the clients this caller may name (issue #413).
 *
 * The shared half of a client field, and only the shared half. The **name** stays at each declaration --
 * `EI.client`, `ADF.client` and the rest are the key sets of different surfaces that happen to agree on a
 * spelling, and folding them into one constant would couple a SQL column to a query parameter. The
 * **description** stays too, and more deliberately: "whose surface am I looking at" and "where does this new
 * user go" are different sentences, and the mandatory-description rule exists so that nobody writes one
 * generic one for both.
 *
 * What is left is the thing that must not drift -- which list a client field draws on -- and it is declared
 * once, next to the class that defines what a client is. That placement is the point, and generalizes: an
 * attribute owned by a Kotlin class exports its schema fragment from that class's file, the way `toInfo`'s
 * shape is defined by `defineInfoType` a few lines below rather than by whoever serves it.
 *
 * It composes -- a site may make the field required, or add anything else, after calling this.
 */
fun SchTypeBuilder.clientAttribute() {
    optionsSource(CLD.clientOptions)
}

/**
 * A client: who it is, what it is for, where it runs, and which traits it supports (issue #343).
 *
 * Held as a typed field on the [GedraConfig] that declares it, whose [GedraId] already carries the client --
 * so the id *is* the binding and nothing says which client a definition belongs to twice. One bundle therefore
 * defines at most one client; every bundle sharing a client is read as one whole. See
 * `docs/design/client-definition.md` for the reasoning behind each attribute.
 *
 * **Nothing here decides how a request is served.** This class declares, validates, and finds a client; what
 * its definition drives is built elsewhere -- the per-client schema variant (`SchemaService.storeFor`, issue
 * #356) and its per-client endpoints (#387). An absent-client gate on reading a `GedraId`, and domain routing,
 * are later work.
 */
data class ClientDef(
    /**
     * The unique key identifying this client, embedded in every [GedraId] it owns.
     *
     * Declared rather than derived from the config's id, which makes "declares one client and is filed under
     * another" a state that can be written down -- and refused. Through the builder that is a typo guard;
     * it earns its keep when a definition arrives as data, where the two halves travel separately.
     */
    val clientId: String,
    /** What to present to users as the name of this client. */
    val name: String,
    /** An internal note for humans: who, what, or why. Never shown to a client's own users. */
    val description: String? = null,
    /** What this client is for; see [ClientUsageType]. */
    val usageType: ClientUsageType,
    /** Whose client this is; see [ClientAudience]. Source-code only. */
    val audience: ClientAudience,
    /**
     * The web resources package the client presents itself with (issue #1301): the id of one a component ships
     * (`webResources`, a rooted name such as `sample:acme`), whose favicon and brand mark the client's people see in a
     * slot its administrator has uploaded nothing to. Two clients may share one, and a client built on a template takes
     * the template's when it names none. **Source code only**: a stored definition naming one is refused at the write
     * and loses it at a load, and one naming a package no component ships loses it at the check (refusing the boot
     * outside production). Packages from elsewhere -- the file store, a CDN -- are for later.
     */
    val webResourcesId: String? = null,
    /**
     * The client's time zone (issue #1115): an IANA id (`America/Chicago`), never an offset. Where the client's
     * people are, so the zone a day means something in -- a date entered as a day, a day a filter or a window names,
     * the day a moment falls on -- and the zone its moments are shown in. Null for the deployment's
     * (`serverTimeZone`), which operational work keeps whatever a client says. Changing it moves no stored moment:
     * what was stored as an instant stays that instant, and is shown in the new zone.
     */
    val timeZone: String? = null,
    /**
     * Where this client is enabled. Elsewhere, it may still be *referred to* and is otherwise not present.
     *
     * The **only per-node** axis deciding which clients a deployment carries -- there is no separate node-level
     * notion -- so two deployments in one environment necessarily carry the same clients, and a deployment
     * focused on a subset would have to be its own environment. Presence also needs the client not to be
     * [retired] ([isEnabledIn]), which holds on every node alike.
     *
     * Naming any environment beyond `unit` and `local` requires naming both of those as well: a client in
     * active use must never be one that local development and the unit tests are locked out of.
     *
     * **Naming nothing leaves the client absent everywhere**, which is legal, but it is not how a client is
     * retired: [retired] is (issue #1111), so that bringing a client back finds its environments untouched. Either
     * way the definition stays, the content stays, and only access stops -- the same decision #326 made for a gedra
     * one level up: keep the row, flip a flag, let reads treat it as absent. Two things follow. Absence **reclaims
     * nothing**, so a client brought back brings all of its content back, including data somebody may have assumed
     * was gone; deleting a client for real is a different question with the shape of a purge. And an absent
     * client is still *known* -- it can be referred to, and extended from -- which is why the registry keeps
     * "known" and "present" apart rather than collapsing them.
     */
    val enabledEnvironments: Set<String> = setOf(ENV.unit, ENV.local, ENV.dev),
    /** Whether to do this client's cache computations before the node reports itself ready. Not yet testable. */
    val preload: Boolean = false,
    /**
     * Whether this client's definition is locked to source **in production** (issue #824): there it takes nothing
     * from the database -- not its definition, traits, workflows, cfacts, or any other stored config. A write of
     * stored config for it is refused, stored config that exists is ignored at boot and on a reload, and
     * "published" has no meaning: its definition is its source, implicitly published. Outside production it is an
     * ordinary client -- which is where its configuration is edited in the database, before the changes are brought
     * into its source component by PR and shipped. Only a source definition may set it; a stored one is refused.
     */
    val staticConfig: Boolean = false,
    /**
     * Another client whose definitions are cloned in first, with this client's own configuration applied over them
     * (issue #945). Each of the named client's **source** configs is copied under this client's id; whatever this
     * client defines itself replaces the copy's definition of the same id whole -- a trait, a type, a cfact, a
     * workflow, its creation or survey workflow, its listing columns -- while copy and interface overlays merge key
     * by key, the template's below this client's. Its own definition takes the template's `webResourcesId` when it
     * sets none, and the template's `includedTraits` and `userLabels` ahead of its own. See `ClientExtension`.
     *
     * **One level, no chains**: the named client may not itself extend one. Usually a template (to deduplicate
     * clients), a preview variant of a client, or a test variant of a production one. From data, it may name
     * only a template; in either case only the *source-code* definition is pulled in, and any database overlay
     * on the extended client is ignored. A sandbox is never a base.
     */
    val extendsFromClientId: String? = null,
    /**
     * A prefix on a core domain that routes to this client. Decides anonymous presentation and
     * self-registration only.
     */
    val domainPrefix: String? = null,
    /** A whole hostname the client configured for itself; as [domainPrefix], and for the same two things. */
    val customDomain: String? = null,
    /**
     * The traits this client takes exactly as they stand: trait ids, and **group** names carrying
     * [CLD.groupSigil].
     *
     * A **minimum, not a total.** A trait this client alters, extends, or defines is supported without a second
     * mention, so the computed supported set is this list with its groups expanded plus everything the client
     * customized. Two names rather than one because an attribute claiming to list what a client supports,
     * which does not list all of it, is the kind of near-truth somebody eventually relies on.
     *
     * A functional group ([CLD.allGlobal]) is refused when [audience] is `customer` **and** [usageType] is
     * `production`, and both conditions do real work. What makes it dangerous is that *we* ship a global trait,
     * and it becomes editable in a client that never reviewed it -- a statement about two parties. A
     * customer's dev client tracking new global traits is how they preview what is coming; an internal
     * production client has no second party. Stated as the principle: **a client's supported set must be fully
     * determined by its own definition when somebody other than us depends on it.**
     */
    val includedTraits: List<String> = emptyList(),
    /**
     * A free-form set of enabled **test/demo** feature names (issue #599) -- unvalidated, so adding a feature
     * needs no enum change: a feature just asks "is my name in this client's set?" It gates *behavior*, not
     * schema, so it does not breach the global-state invariant (decision 3 of the gedra-states design) -- e.g.,
     * whether this client runs the demo `traitPresenceByYear` state derivation, while the trait's schema stays
     * global. **Honored only on a test instance** (`isTestInstance`), so a name listed here can never switch a
     * demo behavior on in production; it is the client-level counterpart of the endpoint `forTestingOnly` fence.
     *
     * It round-trips through [toInfo] / [fromInfo] so it can be authored as stored data (issue #696), but the
     * honored-only-on-a-test-instance guarantee is enforced structurally, not by every consumer: `ClientService`
     * strips it from the *present* definition on a non-test instance (`checkClientDefs`), so a stored value that
     * is cloned onto a real node is simply not there. A consumer therefore reads this field directly and trusts
     * it; it is empty off a test instance whatever the stored row held.
     */
    val testFeatures: Set<String> = emptySet(),
    /**
     * The user labels this client **suggests** (issue #786) -- what an administrator's label editor offers, and
     * what a workflow function naming a label literally is checked against at boot. Suggestions, not a bound: a
     * label outside the list may still be applied to a user, since labels are free-form by design; the list is
     * what makes a typo in a *workflow* visible, where nothing else would notice it.
     */
    val userLabels: List<String> = emptyList(),
    /**
     * Whether this client has a **sandbox** beside it (issue #928, the Shadow Sandbox #925): a linked client,
     * [sandboxOf] this one's id, that runs this client's *latest* configuration with users and data of its own,
     * where unpublished changes are seen before they are published. Read from the definition the client runs, so
     * on a published-only client turning it on takes effect when it is published.
     *
     * A sandbox's own definition is never authored: it is derived from this one (see `SandboxConfigs`), and a
     * definition whose id is a sandbox's is refused wherever one is declared (issue #927).
     */
    val sandbox: Boolean = false,
    /**
     * The **customer** that owns this client (issue #1107), a [CustomerDef.customerId] -- the link that says whose
     * client it is, set only by the platform (its policy is [ClientFieldEdit.platform]). A source client names a customer
     * declared in source; a stored one may name either. The link is judged when the client loads, and read through
     * `ClientService.customerOf`, which answers only for a customer that is there.
     *
     * Null for a client no customer owns: a self-serve one, and for now any client nobody has filed under one.
     */
    val customerId: String? = null,
    /**
     * Whether the platform has **retired** the client (issue #1111): its data is kept, and it is inert -- absent in
     * every environment, so nothing scoped by it is reached, its users cannot sign in, and a session already open is
     * no longer honored. Set and cleared only by the platform (`ClientRetirement`), and a client retired is brought
     * back as it was: its environments are untouched, which is why this is a flag of its own rather than an empty
     * [enabledEnvironments].
     */
    val retired: Boolean = false,
    /**
     * The roles a visitor signed in to `public` may **join** this client as (issue #1248): each gives the visitor a
     * user of their own identity here, of the role's persona -- so its default roles, never `allClients` or
     * `operator` -- with the role's labels. Empty for a client nobody joins, which is every client but a demo one.
     * Joining is offered only where the deployment says so (`JOIN.offeredKey`), so declaring roles opens nothing on a
     * node that does not. Set only by the platform; a sandbox and a client made by another never take it.
     */
    val joinRoles: List<ClientJoinRole> = emptyList(),
    /**
     * How long a sign-in to this client lasts, in hours (issue #1308); null for the deployment's default. Shorter or
     * longer than that, but never past the deployment's limit (`KDR_SESSION_MAX_HOURS`). Held at use as well as at
     * write, and checked against when the person signed in rather than only when the cookie was issued, so shortening
     * it ends longer sessions at once; lengthening it reaches only sign-ins made after, since a cookie's expiry is set
     * when it is issued. Set by the client's own administrator.
     */
    val sessionHours: Int? = null,
    /**
     * How often, in seconds, the webapp re-reads what it shows while a tab of this client's is open and idle (issue
     * #1308; the refresh generation's idle bump, #146); null for the deployment's. Every open tab asks that often, so
     * it is a load on the deployment: set by the platform only, within [CLD.minRefreshIntervalSeconds] and
     * [CLD.maxRefreshIntervalSeconds].
     */
    val refreshIntervalSeconds: Int? = null,
) {
    init {
        // Held to the one label rule ([normalizeUserLabels]) that a label on a *user* is held to, and refused here,
        // where the definition is written: a stray space or a repeat in the client's own list would otherwise
        // surface later as a workflow's literal `reviewer` failing the suggestion check -- blaming the workflow
        // for a typo in the client.
        userLabelsFault(clientId, userLabels)?.let { throw KdrException.mkConv(it) }
    }

    /**
     * Whether this client is present on a node of [env] -- the whole of it: [enabledEnvironments] holds [env], and the
     * client is not [retired].
     */
    fun isEnabledIn(env: String): Boolean = !retired && env in enabledEnvironments

    /** The [includedTraits] entries that are groups rather than trait ids. */
    val includedGroups: List<String> get() = includedTraits.filter { it.startsWith(CLD.groupSigil) }

    /** The [includedTraits] entries that name a trait directly. */
    val includedTraitIds: List<String> get() = includedTraits.filterNot { it.startsWith(CLD.groupSigil) }

    /**
     * Whether this client may include a functional group ([CLD.allGlobal]): anything but a `customer` client in
     * `production`, whose supported set must be fully determined by its own definition (see [includedTraits]).
     */
    val mayIncludeGroups: Boolean
        get() = !(audience == ClientAudience.customer && usageType == ClientUsageType.production)

    override fun toString(): String = "$clientId ($usageType/$audience)"

    /**
     * A JSON-friendly dump of this client's attributes, for the administrative listing. Absent optionals are
     * omitted rather than sent as null; the shape is described by [defineInfoType].
     */
    fun toInfo(): Map<String, Any?> = buildMap {
        put(CLD.clientId, clientId)
        put(CLD.name, name)
        if (description != null) put(CLD.description, description)
        put(CLD.usageType, usageType.name)
        put(CLD.audience, audience.name)
        if (webResourcesId != null) put(CLD.webResourcesId, webResourcesId)
        if (timeZone != null) put(CLD.timeZone, timeZone)
        put(CLD.enabledEnvironments, enabledEnvironments.toList())
        put(CLD.preload, preload)
        put(CLD.staticConfig, staticConfig)
        if (extendsFromClientId != null) put(CLD.extendsFromClientId, extendsFromClientId)
        if (domainPrefix != null) put(CLD.domainPrefix, domainPrefix)
        if (customDomain != null) put(CLD.customDomain, customDomain)
        put(CLD.includedTraits, includedTraits)
        // Round-trips so it can be authored as data; a non-test instance strips it on the way back in
        // (checkClientDefs), so emitting it here is safe -- the present definition it reads from has none.
        if (testFeatures.isNotEmpty()) put(CLD.testFeatures, testFeatures.toList())
        if (userLabels.isNotEmpty()) put(CLD.userLabels, userLabels)
        if (sandbox) put(CLD.sandbox, true)
        if (customerId != null) put(CLD.customerId, customerId)
        if (retired) put(CLD.retired, true)
        if (joinRoles.isNotEmpty()) put(CLD.joinRoles, joinRoles.map { it.toJsonMap() })
        if (sessionHours != null) put(CLD.sessionHours, sessionHours)
        if (refreshIntervalSeconds != null) put(CLD.refreshIntervalSeconds, refreshIntervalSeconds)
    }

    companion object {
        /**
         * What is wrong with [userLabels] as client [clientId]'s suggestions, or null when nothing is: each label is
         * written once and trimmed. The rule the constructor keeps, asked as a question so that a definition
         * arriving as data ([readClientDef]) can report it under the field rather than catch it as a fault.
         */
        fun userLabelsFault(clientId: String, userLabels: List<String>): String? {
            if (userLabels == normalizeUserLabels(userLabels)) return null
            val bad = userLabels.filterIndexed { i, l -> l.isBlank() || l != l.trim() || userLabels.indexOf(l) != i }
            return "Client '$clientId' suggests user labels ${bad.map { "'$it'" }} that are blank, padded with " +
                "spaces, or repeated. Write each label once, trimmed -- a label is matched exactly as written."
        }

        /**
         * A [ClientDef] from a stored [toInfo] dump (issue #613) -- the inverse of [toInfo], for reassembling a
         * client definition off a config row. Reads what [toInfo] writes, `testFeatures` included (issue #696):
         * the raw stored value is reassembled here, and `ClientService` neutralizes it on a non-test instance
         * (`checkClientDefs`), so this stays context-free.
         *
         * **The lenient reader, for what is already stored.** It checks nothing but that the id, the name, and the
         * two choices are there: an unknown key is read past, a flag that is not a boolean reads as false, and an
         * absent list as empty. That is right for a row a write once accepted -- a load reports and degrades, it
         * does not refuse -- and wrong for a definition arriving in a write, which goes through
         * [readClientDef] first (issue #1051): validated against [ClientDefSchema], every failure named.
         */
        fun fromInfo(m: Map<String, Any?>): ClientDef = ClientDef(
            clientId = m[CLD.clientId].toOptStr() ?: throw KdrException.mkConv("A stored client has no '${CLD.clientId}'."),
            name = m[CLD.name].toOptStr() ?: throw KdrException.mkConv("A stored client has no '${CLD.name}'."),
            description = m[CLD.description].toOptStr(),
            usageType = enumOf(ClientUsageType.entries, m[CLD.usageType].toOptStr(), CLD.usageType),
            audience = enumOf(ClientAudience.entries, m[CLD.audience].toOptStr(), CLD.audience),
            webResourcesId = m[CLD.webResourcesId].toOptStr(),
            timeZone = m[CLD.timeZone].toOptStr(),
            enabledEnvironments = m[CLD.enabledEnvironments].toJsonListOfStrings().toSet(),
            preload = m[CLD.preload] as? Boolean ?: false,
            staticConfig = m[CLD.staticConfig] as? Boolean ?: false,
            extendsFromClientId = m[CLD.extendsFromClientId].toOptStr(),
            domainPrefix = m[CLD.domainPrefix].toOptStr(),
            customDomain = m[CLD.customDomain].toOptStr(),
            includedTraits = m[CLD.includedTraits].toJsonListOfStrings(),
            testFeatures = m[CLD.testFeatures].toJsonListOfStrings().toSet(),
            userLabels = m[CLD.userLabels].toJsonListOfStrings(),
            sandbox = m[CLD.sandbox] as? Boolean ?: false,
            customerId = m[CLD.customerId].toOptStr(),
            retired = m[CLD.retired] as? Boolean ?: false,
            joinRoles = ClientJoinRole.listFrom(m[CLD.joinRoles]),
            sessionHours = m[CLD.sessionHours].toOptLongOrNull()?.toInt(),
            refreshIntervalSeconds = m[CLD.refreshIntervalSeconds].toOptLongOrNull()?.toInt(),
        )

        private fun <E : Enum<E>> enumOf(values: List<E>, name: String?, field: String): E =
            values.firstOrNull { it.name == name }
                ?: throw KdrException.mkConv("A stored client's '$field' is '$name', not one of ${values.map { it.name }}.")

        /**
         * The shape of the [toInfo] dump.
         *
         * One declaration making two types (issue #1051), so the fields are stated once. `ClientInfo`
         * ([CLD.infoTypeName]) is what the client endpoints **answer** with, and there the environments are an open
         * list: a stored client naming an environment that does not exist is dropped by the load and still answers
         * its definition read, with the issue that says why, so the answer has to be able to carry the name.
         * [written] declares `WrittenClientInfo` ([CLD.writtenInfoTypeName]) instead, with the list closed -- what a
         * definition arriving in a write is held to ([ClientDefSchema]), where a name that is no environment is a
         * typo to refuse. Two names rather than one name read two ways, so a `$ref` says which it means.
         */
        fun defineInfoType(builder: SchTypesBuilder, written: Boolean = false) {
            builder.type(if (written) CLD.writtenInfoTypeName else CLD.infoTypeName) {
                type = SCT.kObject
                description = if (written) {
                    "A client's definition as a configuration write must give it."
                } else {
                    "A client this deployment carries, as it was declared."
                }
                infoProperties(written)
            }
        }

        /**
         * The input of the create-client endpoint (issue #1054): the written form's properties, as they are
         * declared for it, for [ClientCreateFields] only -- so what a create is held to is what any write of a
         * definition is held to, for the fields a create takes. `extendsFromClientId` offers the templates there
         * are ([CLD.templateOptions]); what a client may extend is still judged where it always was.
         */
        fun defineCreateType(builder: SchTypesBuilder) {
            builder.type(CLD.createTypeName) {
                type = SCT.kObject
                description = "A client to create: what its definition is to hold."
                infoProperties(written = true, only = ClientCreateFields.names.toSet(), offerChoices = true)
                starterWorkflowsProperty()
                // Not a field of the definition: a customer made with the client (issue #1107).
                property(
                    CLD.newCustomer,
                    "A customer to create with the client: its id is the client's, and the client is its root. " +
                        "Instead of naming an existing customer.",
                ) {
                    type = SCT.kObject
                    property(CUST.name, "The name presented for the customer.", required = true)
                    property(CUST.description, "An internal note about who, what or why.")
                }
            }
        }

        /**
         * The input of a client's creation by a customer's root administrator (issue #1112): the written form's
         * properties for the fields a creator chooses ([ClientFieldPolicies.creatorFields]). The rest is the root's to
         * pass on or the create's to clear.
         */
        fun defineDelegatedCreateType(builder: SchTypesBuilder) {
            builder.type(CLD.delegatedCreateTypeName) {
                type = SCT.kObject
                description = "A client to create under the caller's customer: what its creator chooses."
                infoProperties(written = true, only = ClientFieldPolicies.creatorFields.toSet(), offerChoices = true)
                starterWorkflowsProperty()
            }
        }

        /**
         * Not a field of the definition: whether a new client starts with workflows made from the starter templates
         * (issue #1346), on unless the create says otherwise.
         */
        private fun SchTypeBuilder.starterWorkflowsProperty() {
            property(
                CLD.starterWorkflows,
                "Start the client with a creation workflow and a survey, made from the deployment's templates around the " +
                    "form's name (which the client then includes). On unless set false.",
            ) {
                type = SCT.boolean
                default = true
            }
        }

        /**
         * The definition's properties, declared once for every type that holds them: all of them, or [only] the
         * ones named. [written] closes the list of environments; [offerChoices] sources the choices of
         * `extendsFromClientId` and `customerId`, for a form.
         */
        private fun SchTypeBuilder.infoProperties(written: Boolean, only: Set<String>? = null, offerChoices: Boolean = false) {
            fun prop(name: String, description: String, required: Boolean = false, build: SchTypeBuilder.() -> Unit = {}) {
                // Every field has a policy (issue #1108): a field added here without one fails as the schema is built.
                ClientFieldPolicies.of(name)
                if (only == null || name in only) property(name, description, required, build)
            }
            prop(CLD.clientId, "The client's unique key, embedded in every gedra id it owns.", required = true)
            // `emptyIsAbsent = false`: an empty name is a real, handled state (`clientLabel` falls back to the
            // id), so it must not read as a missing required field and fail validation (issue #672 review).
            prop(CLD.name, "The name presented to users as the name of the client.", required = true) {
                emptyIsAbsent = false
            }
            prop(CLD.description, "An internal note about who, what or why.")
            // Set only by the platform (issue #1107); a create under a customer gets an id in its namespace.
            prop(CLD.customerId, "The customer that owns the client; its id is the prefix of the client's.") {
                if (offerChoices) optionsSource(CUST.customerOptions)
            }
            prop(CLD.usageType, "What the client is for.", required = true) {
                options(ClientUsageType.entries)
            }
            prop(CLD.audience, "Whose client this is: ours, or somebody else's.", required = true) {
                options(ClientAudience.entries)
            }
            prop(CLD.webResourcesId, "The web resources package a component ships that the client presents its images from; named in source code only.")
            prop(CLD.timeZone, "The client's IANA time zone (America/Chicago), which its dates are read and shown in.")
            // The environments there are ([ENV.names]), as choices (issue #1051): a form drawn from this type
            // offers the real ones, and a written definition is held to them.
            prop(CLD.enabledEnvironments, "The environments the client is enabled in.", required = true) {
                type = SCT.array
                items {
                    ENV.names.forEach { option(it) }
                    if (!written) openOptions()
                }
            }
            // The flags are booleans and nothing else (`allowCoerce = false`, issue #1051): this is a definition,
            // not a query string, and "yes" for a flag is a mistake to name rather than a value to guess at.
            prop(CLD.preload, "Whether the client's caches are computed before the node reports ready.") {
                type = SCT.boolean
                allowCoerce = false
            }
            prop(CLD.staticConfig, "Whether, in production, the client takes nothing from the database.") {
                type = SCT.boolean
                allowCoerce = false
            }
            prop(CLD.extendsFromClientId, "The client whose definitions this one is built on top of.") {
                if (offerChoices) optionsSource(CLD.templateOptions)
            }
            prop(CLD.domainPrefix, "A prefix on a core domain that routes to this client.")
            prop(CLD.customDomain, "A whole hostname the client configured for itself.")
            prop(CLD.includedTraits, "Trait ids and group names the client takes as they stand.") {
                type = SCT.array
                items { type = SCT.string }
            }
            prop(
                CLD.testFeatures,
                "Test/demo feature names, honored only on a test instance (stripped elsewhere).",
            ) {
                type = SCT.array
                items { type = SCT.string }
            }
            prop(CLD.userLabels, "User labels the client suggests; a label editor offers them, without binding to them.") {
                type = SCT.array
                items { type = SCT.string }
            }
            prop(CLD.sandbox, "Whether the client has a sandbox beside it, running its latest configuration.") {
                type = SCT.boolean
                allowCoerce = false
            }
            prop(CLD.retired, "Whether the platform retired the client: its data kept, and the client absent everywhere.") {
                type = SCT.boolean
                allowCoerce = false
            }
            prop(CLD.joinRoles, "The roles a visitor signed in to public may join the client as, on a deployment that offers it.") {
                type = SCT.array
                items { ClientJoinRole.declareProperties(this) }
            }
            prop(CLD.sessionHours, "How long a sign-in lasts, in hours; absent for the deployment's default, and never past its limit.") {
                type = SCT.integer
                minimum = CLD.minSessionHours
            }
            prop(CLD.refreshIntervalSeconds, "How often, in seconds, an idle open tab re-reads what it shows; absent for the deployment's.") {
                type = SCT.integer
                minimum = CLD.minRefreshIntervalSeconds
                maximum = CLD.maxRefreshIntervalSeconds
            }
        }
    }
}

/**
 * The schema a client definition arriving as data is validated against (issue #1051): `WrittenClientInfo`, the
 * **written** form of the type [ClientDef.defineInfoType] declares, compiled on its own as `ReportDefSchema` and
 * the workflow definition schema are. One declaration makes it and the `ClientInfo` the client endpoints answer
 * with, so a definition is judged by the shape it is read back in; the written type differs only in closing the
 * list of environments, which an answer must leave open. The client catalog declares it too, beside `ClientInfo`,
 * which is where the `kdr:clientDef` slot's declaration finds it.
 *
 * What it states is the definition's **shape**: its keys and no others, each one's type, the choices of
 * `usageType`, `audience` and each environment. What a definition means beside the deployment's other clients --
 * that its id is well-formed and its own, that the template it extends exists, that its environments make a legal
 * set, that no other client holds its domain -- is `checkClientDefs`' and the write's, which see those clients.
 * The id's characters are among those rather than a `pattern` here: this type also describes a sandbox's derived
 * `<parent>:sandbox` id on the way out.
 */
object ClientDefSchema {
    /** The `$defs` of the definition schema, under [CLD.catalogNamespace]. */
    fun defs(cxt: KdrCxtBase): Map<String, Any?> =
        schemaDefs(cxt, CLD.catalogNamespace) { ClientDef.defineInfoType(this, written = true) }

    // Parsed once and kept, as the report and workflow definition schemas are: it is a constant of the runtime.
    private var parsed: Map<String, SchType>? = null

    /** The compiled types, keyed by qualified name. */
    fun types(cxt: KdrCxtBase): Map<String, SchType> = parsed ?: parseSchemaTypes(defs(cxt)).also { parsed = it }

    /** The compiled definition type. */
    fun defType(cxt: KdrCxtBase): SchType = types(cxt).getValue(CLD.writtenInfoTypeQualified)
}

/** A client definition read from data (issue #1051): the [def], or every [failures] entry that kept it from being one. */
class ClientDefRead(val def: ClientDef?, val failures: List<SchFailure>)

/**
 * A client definition from its JSON form, **strictly** (issue #1051): [raw] validated against [ClientDefSchema]
 * -- an unknown key, a missing field, a value of the wrong type and a choice that is not one are each a failure,
 * by path -- and only then built. What a write of a definition goes through; a load of one already stored reads
 * it with [ClientDef.fromInfo].
 *
 * Two things are judged here beside the schema, each reported as a failure of its own field:
 * - **A key the validator lets through as off-contract** -- one starting with `_` or `$`, which on a request is a
 *   caller's own annotation. A stored definition has no such thing: it is stored as [ClientDef.toInfo] writes it,
 *   so the key would be accepted and then gone, which is the silent drop this reading exists to end.
 * - **The one rule the class itself keeps**, that the suggested user labels are each written once and trimmed
 *   ([ClientDef.userLabelsFault]) -- asked, rather than caught from the constructor, so that nothing else the
 *   constructor may come to refuse is blamed on that field.
 */
fun readClientDef(cxt: KdrCxtBase, raw: Map<String, Any?>): ClientDefRead {
    val result = coerceAndValidate(ClientDefSchema.defType(cxt), raw)
    val failures = result.failures + offContractKeyFailures(raw)
    if (failures.isNotEmpty()) return ClientDefRead(null, failures)
    val m = result.value.toJsonMapOrEmpty()
    ClientDef.userLabelsFault(m[CLD.clientId].toOptStr().orEmpty(), m[CLD.userLabels].toJsonListOfStrings())?.let {
        return ClientDefRead(null, listOf(SchFailure(CLD.userLabels, SchFailCode.badValue, it)))
    }
    return ClientDefRead(ClientDef.fromInfo(m), emptyList())
}

