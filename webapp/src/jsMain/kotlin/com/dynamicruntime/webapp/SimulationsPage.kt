package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlin.time.Clock
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.p
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

private val simulationsScope = MainScope()

/** Where the page keeps its recent runs (issue #1099), in this browser's `localStorage`. */
private const val recentRunsStorageKey = "kdrSimulationRecentRuns"
private const val recentRunsWhat = "recent simulation runs"

/**
 * The Simulations page (issue #997): the canned scenarios this test instance offers, each with its input form --
 * drawn from the simulation endpoint's schema by the same [SchemaForm] the endpoint catalog uses -- a Run, and, once
 * run, its report turned into "sign in as …" buttons that land on the report's start page. Offered only on a test
 * instance (the route resolves to Home elsewhere), where the simulations exist.
 *
 * **Recent runs** (issue #1099): signing in as one of a run's users swaps the session and leaves the page, so the
 * report would be gone on the way back. Each successful run is remembered in this browser ([SavedSimulationRun], at
 * most [recentRunsLimit], newest first) against the data id the app config carries, and listed above the
 * simulations with its sign-in buttons. A run from other data -- an in-memory node since restarted -- provisioned
 * users that are gone, so it is shown greyed, with no sign-in ([recentRunsFor]); on a persistent database the data id
 * holds across restarts, and so do the runs. Clear forgets them.
 */
val SimulationsPage = FC<Props> {
    var catalog by useState<Catalog?>(null)
    var error by useState<DisplayError?>(null)
    val (recent, setRecent) = useState<List<SavedSimulationRun>>(emptyList())

    useEffectOnce {
        setRecent(decodeSavedRuns(localStorageGet(recentRunsStorageKey, recentRunsWhat)))
    }

    // Remembers a finished run. Through the setter's transform rather than over `recent`, since a run finishes after
    // an await, and two runs finishing close together must both be kept.
    fun remember(simulation: String, run: SimulationRun) {
        val saved = SavedSimulationRun(simulation, appConfig().dataId, Clock.System.now().toString(), run)
        setRecent { prev ->
            rememberRun(prev, saved).also { localStorageSet(recentRunsStorageKey, encodeSavedRuns(it), recentRunsWhat) }
        }
    }

    useEffectOnce {
        simulationsScope.launch {
            try {
                catalog = SimulationsApi.fetchSimulations()
            } catch (e: Throwable) {
                error = userFacingError(e)
            }
        }
    }

    div {
        className = ClassName("card wide")
        h1 { +"Simulations" }
        p {
            className = ClassName("subtitle")
            +"Canned scenarios this test instance can set up. Run one, then sign in as one of its users to look at it."
        }
        if (recent.isNotEmpty()) {
            RecentRunsSection {
                runs = recent
                dataId = appConfig().dataId
                onClear = {
                    setRecent(emptyList())
                    localStorageRemove(recentRunsStorageKey, recentRunsWhat)
                }
            }
        }
        val cat = catalog
        when {
            error != null -> errorText("Couldn't load the simulations.", error!!)
            cat == null -> p {
                className = ClassName("subtitle")
                +"Loading…"
            }
            cat.endpoints.isEmpty() -> p {
                className = ClassName("subtitle")
                +"This instance offers no simulations."
            }
            else -> for (endpoint in cat.endpoints) {
                SimulationCard {
                    key = endpoint.path.unsafeCast<Key>()
                    this.catalog = cat
                    this.endpoint = endpoint
                    onRan = { run -> remember(simulationName(endpoint.path), run) }
                }
            }
        }
    }
}

private external interface SimulationCardProps : Props {
    var catalog: Catalog
    var endpoint: EndpointInfo
    /** Called with a successful run's report, which the page remembers. */
    var onRan: (SimulationRun) -> Unit
}

/** One simulation: what it does, its form, Run, and what the run reported. */
private val SimulationCard = FC<SimulationCardProps> { props ->
    val endpoint = props.endpoint
    val inputType = props.catalog.inputType(endpoint)
    var values by useState<Map<String, Any?>>(emptyMap())
    var failures by useState<List<SchFailure>?>(null)
    var running by useState(false)
    var run by useState<SimulationRun?>(null)
    var runError by useState<DisplayError?>(null)

    div {
        className = ClassName("sim-card")
        h2 { +simulationName(endpoint.path) }
        endpoint.description?.let {
            p {
                className = ClassName("subtitle")
                +it
            }
        }
        if (inputType.properties.isNotEmpty()) {
            SchemaForm {
                type = inputType
                this.values = values
                editable = true
                cfacts = props.catalog.cfacts
                this.failures = failures
                onChange = { values = it }
            }
        }
        div {
            className = ClassName("row")
            Button {
                type = "primary"
                loading = running
                onClick = {
                    val check = checkInput(inputType, values)
                    failures = check.failures.ifEmpty { null }
                    check.payload?.let { payload ->
                        running = true
                        run = null
                        runError = null
                        simulationsScope.launch {
                            try {
                                val response = SchemaCatalogApi.invoke(endpoint, payload)
                                val report = parseSimulationReport(response[EP.results].toJsonMapOrEmpty())
                                run = report
                                props.onRan(report)
                            } catch (e: Throwable) {
                                runError = userFacingError(e)
                            } finally {
                                running = false
                            }
                        }
                    }
                }
                +"Run"
            }
        }
        runError?.let { errorText("The simulation failed.", it) }
        run?.let { r ->
            p {
                className = ClassName("form-ok")
                +r.summary
            }
            SimulationSignIns { this.run = r }
        }
    }
}

private external interface SimulationSignInsProps : Props {
    var run: SimulationRun
}

/** A run's users as "sign in as …" buttons, each landing on the run's start page; a refused sign-in says why. */
private val SimulationSignIns = FC<SimulationSignInsProps> { props ->
    val bump = useRefreshBump()
    var signInError by useState<DisplayError?>(null)
    val r = props.run
    div {
        className = ClassName("sim-users")
        for (user in r.users) {
            div {
                key = "${user.client}/${user.email}/${user.persona.orEmpty()}".unsafeCast<Key>()
                className = ClassName("row")
                Button {
                    onClick = {
                        signInError = null
                        simulationsScope.launch {
                            when (val result = SimulationsApi.signInAs(user)) {
                                is ApiResult.Ok -> afterSessionChange(bump, startPageHash(r.startPage))
                                else -> signInError = result.failureOrNull()?.let { userFacingError(it) }
                            }
                        }
                    }
                    +"Sign in as ${user.email}"
                }
                p {
                    className = ClassName("type-hint")
                    +user.purpose
                }
            }
        }
    }
    signInError?.let { errorText("Couldn't sign in.", it) }
}

private external interface RecentRunsSectionProps : Props {
    var runs: List<SavedSimulationRun>
    /** The data id the node serves now; null when it serves none, and every run counts as current. */
    var dataId: String?
    var onClear: () -> Unit
}

/**
 * The runs this browser remembers (issue #1099): each current one with what it reported and its sign-in buttons, so a
 * run's other users are a click away after signing in as one; the ones from data since reset greyed, with no sign-in.
 */
private val RecentRunsSection = FC<RecentRunsSectionProps> { props ->
    val split = recentRunsFor(props.runs, props.dataId)
    div {
        className = ClassName("sim-recent")
        div {
            className = ClassName("row")
            h2 { +"Recent runs" }
            Button {
                type = "link"
                onClick = { props.onClear() }
                +"Clear"
            }
        }
        for (saved in split.current) {
            div {
                key = saved.ranAt.unsafeCast<Key>()
                className = ClassName("sim-card")
                h3 { +recentRunHeading(saved) }
                p {
                    className = ClassName("subtitle")
                    +saved.run.summary
                }
                SimulationSignIns { this.run = saved.run }
            }
        }
        if (split.stale.isNotEmpty()) {
            div {
                className = ClassName("sim-recent-stale")
                p {
                    className = ClassName("subtitle")
                    +"This server's data has been reset since these ran, so their users are gone:"
                }
                for (saved in split.stale) {
                    p {
                        key = saved.ranAt.unsafeCast<Key>()
                        className = ClassName("type-hint")
                        +recentRunHeading(saved)
                    }
                }
            }
        }
    }
}
