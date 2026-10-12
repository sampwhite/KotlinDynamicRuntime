package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EndpointKind
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.InputFieldsBuilder
import com.dynamicruntime.common.endpoint.KdrEndpoint
import com.dynamicruntime.common.endpoint.SchModuleBuilder
import com.dynamicruntime.common.gedra.overseenClient
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.GeneratedSurface
import com.dynamicruntime.common.user.UADEP

/**
 * A client's **typed report endpoints** (issue #1088): for each report it may run, two endpoints whose output is that
 * report's own type, so the catalog says exactly what a run of it returns and every response is held to it.
 *
 * - `GET /clientAdmin/<client>/report/<reportId>/run` -- a row per form, of [RGEN.rowType].
 * - `GET /clientAdmin/<client>/report/<reportId>/aggregate` -- a row per group, of [RGEN.groupType], grouped as the
 *   report declares.
 *
 * The same run the shared endpoint makes ([runBoundReport]), with the three things that endpoint takes as inputs
 * fixed by the path instead: the client, the report, and the mode. The grouping is the report's own, which is what
 * the group type was generated from, so there is no `groupBy` to send; `excludeEmpty` may still be overridden, since
 * it changes which rows there are and not what a row holds. The input is closed, so a `groupBy` or a `client` is
 * refused rather than ignored.
 *
 * **Generated, not declared.** A report is configuration, and a client's may change while the node runs, so these are
 * made from the registry as it is bound -- at boot and again on a client's reload -- and published with their types
 * as that client's generated surface (`SchemaService.publishGenerated`). Each endpoint's handler holds the report it
 * was generated from: the endpoint, its handler and its types are made together and replaced together, so a response
 * is never of a newer definition than the type it is checked against.
 *
 * **Always checked** ([KdrEndpoint.validateOutput]). The type is generated from data, so no test written beside a
 * handler could have held the two together; the check on every response is what does. It is also why the run is one
 * with exact dates: the types are strict, and a day column holding a moment some form stored would fail them.
 *
 * Who may call one is the shared endpoint's rule ([overseenClient]): the client's own administrator, or one who sees
 * every client.
 */
fun typedReportSurface(cxt: KdrCxt, client: String, registry: ReportRegistry, shared: KdrEndpoint): GeneratedSurface? {
    if (registry.reports.isEmpty()) return null
    val defs = LinkedHashMap<String, Any?>()
    val endpoints = ArrayList<KdrEndpoint>()
    for (declared in registry.reports.values) {
        defs.putAll(reportRowDefs(cxt, client, declared.bound))
        endpoints.add(typedReportEndpoint(cxt, client, declared.bound, ReportMode.detail, shared))
        endpoints.add(typedReportEndpoint(cxt, client, declared.bound, ReportMode.aggregate, shared))
    }
    return GeneratedSurface(client, defs, endpoints)
}

/**
 * One typed endpoint of [report] for [client]. Built directly rather than through a builder -- there is no module
 * being declared, only an endpoint being made -- and taking from the [shared] run endpoint what the two must agree
 * on: the paging fields its envelope declares and the summary beside the rows.
 */
private fun typedReportEndpoint(cxt: KdrCxt, client: String, report: BoundReport, mode: ReportMode, shared: KdrEndpoint): KdrEndpoint {
    val aggregate = mode == ReportMode.aggregate
    val named = "'${report.report.label}' (${report.reportId})"
    return KdrEndpoint(
        path = UADEP.typedReportPath(client, report.reportId, if (aggregate) UADEP.typedReportAggregate else UADEP.typedReportRun),
        method = HttpMethod.GET,
        kind = EndpointKind.list,
        namespace = REP.namespace,
        description = if (aggregate) {
            "Runs the report $named over the forms of client '$client', a row per group of its grouped-by columns' " +
                "values -- one total row when it groups by nothing -- each of the report's own group type. Paged by " +
                "cursor, as '${UADEP.reportRun}' is."
        } else {
            "Runs the report $named over the forms of client '$client', a row per form in form-id order, each of the " +
                "report's own row type. Paged by cursor, as '${UADEP.reportRun}' is."
        },
        inputFields = InputFieldsBuilder(cxt, REP.namespace).apply {
            field(
                RRUN.excludeEmpty,
                "The columns a form must have a value for to be in the run; the report's own when absent, none when empty.",
            ) {
                type = SCT.array
                allowCoerce = true
                emptyIsAbsent = false
                items { type = SCT.string }
            }
        }.fields,
        inputTypeRef = null,
        includeLimit = true,
        outputSchema = SchModuleBuilder(cxt, REP.namespace).listOutput(
            if (aggregate) RGEN.groupType(client, report.reportId) else RGEN.rowType(client, report.reportId),
            shared.hasMore, shared.hasNumAvailable, shared.summaryRef, cursorPaged = true,
        ),
        forTestingOnly = shared.forTestingOnly,
        handler = { c, request ->
            // The path says which client, so the run is bound to it, as a client's copy of an endpoint is.
            val bound = c.mkSubContext("client:$client", client)
            bound.clientFromPath = client
            overseenClient(bound, client)
            val groupBy = if (aggregate) report.defaultGroupBy() else emptyList()
            runBoundReport(bound, client, report, mode, groupBy, excludeEmptyNamed(report, request), request, exactDates = true)
        },
        client = client,
        needsClientConfig = true,
        hasMore = shared.hasMore,
        hasNumAvailable = shared.hasNumAvailable,
        summaryRef = shared.summaryRef,
        cursorPaged = true,
        validateOutput = true,
    )
}
