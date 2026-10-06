package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.p
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

private val simulationsScope = MainScope()

/**
 * The Simulations page (issue #997): the canned scenarios this test instance offers, each with its input form --
 * drawn from the simulation endpoint's schema by the same [SchemaForm] the endpoint catalog uses -- a Run, and, once
 * run, its report turned into "sign in as …" buttons that land on the report's start page. Offered only on a test
 * instance (the route resolves to Home elsewhere), where the simulations exist.
 */
val SimulationsPage = FC<Props> {
    var catalog by useState<Catalog?>(null)
    var error by useState<DisplayError?>(null)

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
                }
            }
        }
    }
}

private external interface SimulationCardProps : Props {
    var catalog: Catalog
    var endpoint: EndpointInfo
}

/** One simulation: what it does, its form, Run, and what the run reported. */
private val SimulationCard = FC<SimulationCardProps> { props ->
    val endpoint = props.endpoint
    val inputType = props.catalog.inputType(endpoint)
    val bump = useRefreshBump()
    var values by useState<Map<String, Any?>>(emptyMap())
    var failures by useState<List<SchFailure>?>(null)
    var running by useState(false)
    var run by useState<SimulationRun?>(null)
    var runError by useState<DisplayError?>(null)
    var signInError by useState<DisplayError?>(null)

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
                        signInError = null
                        simulationsScope.launch {
                            try {
                                val response = SchemaCatalogApi.invoke(endpoint, payload)
                                run = parseSimulationReport(response[EP.results].toJsonMapOrEmpty())
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
            div {
                className = ClassName("sim-users")
                for (user in r.users) {
                    div {
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
    }
}
