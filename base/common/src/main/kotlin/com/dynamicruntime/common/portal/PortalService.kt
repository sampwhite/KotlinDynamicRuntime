package com.dynamicruntime.common.portal

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.resolveEndpointInputType
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ContentServer
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.toJsonStr

/**
 * Portal paths and JSON keys. Kept short and referenced qualified, per the code guide. The
 * field-descriptor keys are the contract between [PortalService.buildFieldsCatalog] (server) and the
 * portal page's JavaScript (client), so the two must agree on their spelling.
 */
@Suppress("ConstPropertyName")
object PTL {
    /** Anonymous context root that serves the portal (already permitted in [RequestService.anonRoots]). */
    const val root = "portal"

    /** Path of the HTML portal page. */
    const val page = "/portal"

    /**
     * The platform's endpoint-introspection endpoint (owned by SchemaService), which the portal page calls
     * to list the instance's endpoints. Kept as a portal-side constant to document the dependency.
     */
    const val endpointsApi = "/schema/endpoints"

    /**
     * Portal-served feed the page uses to render forms: resolved input fields per endpoint, keyed by the
     * endpoint's `collationKey` (`path:method`). Served as content (not an endpoint), so it neither appears
     * in [endpointsApi]'s listing nor needs its own input schema. It supplies what [endpointsApi] cannot: the
     * `$ref`s in an endpoint's raw input schema resolved into concrete fields, which the browser can't do.
     */
    const val fieldsFeed = "/portal/fields"

    // Field-descriptor JSON keys.
    const val name = "name"
    const val type = "type"
    const val description = "description"
    const val required = "required"
    const val default = "default"
    const val format = "format"
    const val options = "options"
    const val value = "value"
    const val label = "label"
    const val itemType = "itemType"
    const val fields = "fields"
}

/**
 * Serves a self-contained, form-based UI for exploring and calling the instance's endpoints, entirely from
 * within the running server (no separate webapp/build step). It is purely a [ContentServer] (registered with
 * the [RequestService] during init) -- it contributes no endpoints of its own. It serves:
 *
 *  - the HTML page at [PTL.page] (and redirects `/` to it), and
 *  - the [PTL.fieldsFeed] JSON: each endpoint's input schema resolved into concrete form fields.
 *
 * The page lists endpoints by calling the platform's [PTL.endpointsApi] (SchemaService's `/schema/endpoints`),
 * then renders a form per endpoint using the resolved fields from [PTL.fieldsFeed]. Both feeds derive from the
 * compiled schema store, so they reflect exactly the endpoints the dispatcher will route to.
 */
class PortalService : ServiceInitializer, ContentServer {
    override val serviceName: String = PortalService.serviceName

    /** Registers this content server with the dispatcher (idempotent). */
    override fun checkInit(cxt: KdrCxt) {
        val requestService = RequestService.get(cxt) ?: return
        requestService.checkInit(cxt)
        requestService.addContentServer(this)
    }

    /** Serves the portal page and its fields feed, or redirects the site root; passes on anything else. */
    override fun serve(cxt: KdrCxt, handler: RequestHandler): Boolean {
        return when (handler.target) {
            "", "/" -> {
                handler.sendRedirect(PTL.page)
                true
            }
            PTL.page, "${PTL.page}/" -> {
                handler.sendStringResponse(PortalPage.html, EXC.ok, "text/html; charset=utf-8")
                true
            }
            PTL.fieldsFeed -> {
                handler.sendStringResponse(buildFieldsCatalog(cxt).toJsonStr(), EXC.ok, "application/json")
                true
            }
            else -> false
        }
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "PortalService"

        fun get(cxt: KdrCxt): PortalService? = cxt.instanceConfig.get(serviceName) as? PortalService

        /**
         * Resolved input fields for every registered endpoint, keyed by `collationKey` (`path:method`) so the
         * page can look them up against the `path`/`method` the platform's [PTL.endpointsApi] reports. Each
         * value is the endpoint's input type flattened by [describeFields] (empty when it takes no input).
         */
        fun buildFieldsCatalog(cxt: KdrCxt): Map<String, Any?> {
            val schema = cxt.getSchema()
            val out = LinkedHashMap<String, Any?>()
            for (endpoint in schema.endpoints.values) {
                // The same resolved input type the dispatcher validates against, so the form matches.
                val inputType = resolveEndpointInputType(endpoint, schema.types)
                out[endpoint.collationKey] = inputType?.let { describeFields(it, 0) } ?: emptyList<Any?>()
            }
            return out
        }

        /**
         * Flattens an object [SchType]'s properties into form-field descriptors. Object fields
         * recurse (nested `fields`); array fields report their element type. Carries a [depth]
         * guard against a self-referential schema, per the code guide.
         */
        fun describeFields(type: SchType, depth: Int): List<Map<String, Any?>> {
            if (depth > 20) {
                throw KdrException("Input schema nests too deeply (over 20 levels) to render as a form.")
            }
            return type.properties.map { (fieldName, prop) ->
                val valueType = prop.valueType
                val field = LinkedHashMap<String, Any?>()
                field[PTL.name] = fieldName
                field[PTL.type] = valueType.jsonType ?: SCT.string
                field[PTL.description] = prop.description ?: valueType.description
                field[PTL.required] = fieldName in type.required
                valueType.default?.let { field[PTL.default] = it }
                valueType.format?.let { field[PTL.format] = it }
                valueType.options?.let { opts ->
                    field[PTL.options] = opts.map { linkedMapOf(PTL.value to it.value, PTL.label to it.label) }
                }
                if (valueType.jsonType == SCT.kObject && valueType.properties.isNotEmpty()) {
                    field[PTL.fields] = describeFields(valueType, depth + 1)
                }
                if (valueType.jsonType == SCT.array) {
                    field[PTL.itemType] = valueType.itemType?.jsonType ?: SCT.kObject
                }
                field
            }
        }
    }
}
