package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import web.cssom.ClassName

/** One search control the client's usage rules declared (issue #538): the parameter [name] to send, and the
 *  [label] to show. Derived from the listing endpoint's own input schema, so it is exactly what the client's
 *  variant advertises -- a field appears here only when the backend will accept it. */
class SearchField(val name: String, val label: String)

/** The fields on the reserved side of the listing query -- not search, and never shown as a search control. */
private val reservedQueryFields = setOf(EP.offset, EP.limit, EI.user)

/**
 * The search fields declared on a listing endpoint's [inputSchema] (issue #538): every input property that is
 * not one of the reserved paging/scope fields, in the schema's own order. A client with no usage rules has
 * none, so the caller shows no search box at all. The label is the property's `title` (the column label, with
 * the variant said in words), falling back to the parameter name.
 */
fun searchFields(inputSchema: Map<String, Any?>): List<SearchField> {
    val properties = inputSchema[SCH.properties].toJsonMapOrEmpty()
    return properties.entries
        .filter { (name, _) -> name !in reservedQueryFields }
        .map { (name, body) ->
            val title = body.toJsonMapOrEmpty()[SCH.title] as? String
            SearchField(name, title ?: name)
        }
}

/**
 * The forms-list search box: one text control per declared [SearchField], a Search that applies them and a
 * Clear that drops them. Presentational -- the parent owns the values and both actions -- so it knows nothing
 * about usages or endpoints, only the fields it was handed. Every control is a plain text input (a number or
 * date bound is coerced by the backend), which is also the one antd control a browser test can drive.
 */
external interface FormsSearchProps : Props {
    /** The declared search fields, in display order. */
    var fields: List<SearchField>

    /** The current value of each field, keyed by parameter name; a missing key is an empty control. */
    var values: Map<String, String>

    /** Records a keystroke in one field. */
    var onChange: (String, String) -> Unit

    /** Applies the current values as the list's filter. */
    var onSearch: () -> Unit

    /** Clears every field and drops the filter. */
    var onClear: () -> Unit
}

val FormsSearch = FC<FormsSearchProps> { props ->
    div {
        className = ClassName("row forms-search")
        props.fields.forEach { field ->
            Input {
                placeholder = field.label
                value = props.values[field.name] ?: ""
                allowClear = true
                onChange = { event -> props.onChange(field.name, event.target.value as? String ?: "") }
                onPressEnter = { props.onSearch() }
            }
        }
        Button {
            type = "primary"
            onClick = { props.onSearch() }
            +"Search"
        }
        Button {
            type = "link"
            onClick = { props.onClear() }
            +"Clear"
        }
    }
}
