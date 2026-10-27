package com.dynamicruntime.webapp

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.p
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName

private val formStatesScope = MainScope()

external interface FormStatesSectionProps : Props {
    var row: ClientOverview
}

/**
 * Recalculating a client's form states on its detail page (issue #1412): what [formStatesOffer] says, the
 * **Recalculate form states** button within the client's limit, and what the last run did ([formStatesRecalcText]).
 * Synchronous, so the button shows its work until the answer comes; nothing else on the page changes, since what a
 * recalculation changes is the forms' state, which this page does not show.
 *
 * Not asked about first: a recalculation changes no data anybody entered, only what is derived from it, and it is what
 * the nightly run would do anyway. An answer that comes back after the page has moved to another client is dropped,
 * and that client's button is not shown busy with it.
 */
val FormStatesSection = FC<FormStatesSectionProps> { props ->
    var busy by useState(false)
    var result by useState<FormStatesRecalc?>(null)
    var error by useState<DisplayError?>(null)
    val shownClient = useRef<String>(null)
    useEffect(props.row.clientId) {
        shownClient.current = props.row.clientId
        // Another client's run, still going, is not this client's: its button is free.
        busy = false
        result = null
        error = null
    }
    val offer = formStatesOffer(props.row)
    p {
        className = ClassName("subtitle")
        +offer.text
    }
    if (offer.recalculate) {
        Button {
            loading = busy
            onClick = {
                val forClient = props.row.clientId
                busy = true
                error = null
                result = null
                formStatesScope.launch {
                    try {
                        val answer = ClientsApi.recalculateFormStates(forClient)
                        if (shownClient.current == forClient) result = answer
                    } catch (e: Throwable) {
                        if (shownClient.current == forClient) error = userFacingError(e)
                    } finally {
                        if (shownClient.current == forClient) busy = false
                    }
                }
            }
            +"Recalculate form states"
        }
    }
    result?.let {
        p {
            className = ClassName("subtitle")
            +formStatesRecalcText(it)
        }
    }
    error?.let { errorText("Couldn't recalculate the form states.", it) }
}
