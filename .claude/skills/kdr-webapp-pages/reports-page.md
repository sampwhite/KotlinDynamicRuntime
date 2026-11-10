## The Reports page (issue #1007)

`/wa/reports` lists a client's named reports (`GET /clientAdmin/reports`) down the left and runs the open one
(`GET /clientAdmin/report/run`) as a table beside them; `docs/design/reporting.md` at the repo root says what a report
is. The pure half -- parsers, the run's query, the table's columns, a cell's text, the counts line, the links -- is
`ReportsApi.kt` (covered by `ReportsPageTest`); `ReportsPage.kt` holds the components.

- **Where the page is rides the address**: `c` (the client, for an `allClients` administrator only -- a client's own
  administrator sends no client and the endpoint answers for theirs), `rpt` (the open report) and `view`
  (`grouped` / `detail`). Report and client are ordinary links, so Back steps through them; the mode is written
  with `replaceRoute` and **read back from the address** (`reportSetupInForce`), never kept beside it, so the page
  shows what its address says. Rows are drawn only while they belong to the walk on screen (`reportWalkKey`). **Not `m`** for the mode: that is the catalog's, and any address carrying it routes there.
- **The setup is "the report's own" until touched.** `ReportRunSetup`'s lists are null until the user changes one,
  and a null list is not sent, so the endpoint applies the report's defaults. A list the user **emptied** is sent
  as an empty list -- absent and empty mean different things to the endpoint (`reportRunQuery`).
- **The table grows, as every listing does** (issue #1315, *Growing listings* in `webapp/CLAUDE.md`): more rows are the same run
  asked again from the start with a larger limit, not the next cursor page, so a refresh keeps the rows shown and
  they are one run's answer. How many are shown is component state keyed on the client, report and setup
  (`reportWalkKey`, `ReportRunSetup.signature`), so anything that changes the query starts at the first batch. It
  does not ride the address: a row's "Open" leaves by Back, which re-runs the page anyway. The cursor is the CSV's.
- **A detail row's "Open" link goes to the forms page**, which owns its address and its ways home, so its back link
  reads "← Forms" (the listing's name for an administrator, issue #1091); the way back to the report is the
  browser's Back. Do not add a `from=reports`: the forms
  page drops any `from` that is not a forms listing.
- **A grouped run's table** is what it grouped by, a **Forms** count, then each rolled-up column headed with its
  rollup, "Total (sum)" (`reportTableColumns`). A group with no value reads "(none)"; any other empty cell a dash.
- **A choice reads as its label** (issue #1438): a text column whose field offers choices carries them (`choices`,
  from the binding's walk to the field), and a cell, a group, and a history row show a stored value by its label
  (`reportCellText`), with the stored value as the cell's tooltip (`reportCellTip`). The run's values and the CSV keep
  the stored ones, since a spreadsheet wants the value. A path ending in one of the vocabulary's closed sets -- a
  form's status, a workflow's category -- takes its labels from the page (`vocabularyChoices`): the listing chip's
  words and the Workflows page's, which are the app's, not a schema's. A search keyword (`workflow.<id>.ctaKeyword`,
  `meta.ctaKeywords`, issue #1600) is a choice the backend supplies: each keyword's report label, in the client's copy.
- **A cell stays on one line** (`nowrap`), so a report's many narrow columns keep one line per row, except one whose
  text runs over several lines, as long text may (`g-longText`, issue #1591): it keeps its lines, wrapping within a
  bounded width (`reportTextHasLines`, `.report-lines`). A list holding any multi-line text is split by blank lines
  rather than commas (`reportCellText`), so where one answer ends stays plain. The CSV is untouched.
- **Download CSV** (issue #1008) is the browser walking the cursor: `ReportsApi.runAll` → `walkReportRun` fetches
  the run as set up from its first page at `reportDownloadPageSize` (500), following `next` to the end, and
  `reportCsv` writes it -- there is no export endpoint. The walk asks `stillWanted` around every page, so changing
  the report or a control abandons it; **a failure part-way saves nothing**, since part of a run would pass for
  the whole, and so does leaving the page (a cleanup bumps the token, since the walk's scope outlives the
  component). In the file a detail run leads with the form id, a number is in full, a UTC timestamp is written
  `yyyy-MM-dd HH:mm:ss` (`csvTimestamp`; the ISO form is one spreadsheets leave as text), nothing is an empty
  field, and a cell beginning `= + - @` gets a leading apostrophe (`csvSafeText`, applied once per cell): a report's text is
  whatever somebody typed into a form, and a spreadsheet would run it as a formula. `walkReportRun` is `suspend`,
  and its tests return `GlobalScope.promise { … }`, which the Node runner awaits -- the way to test suspend logic
  here without a coroutine test library.
- **Data to look at**: the `report-demo` simulation creates acme and globex forms -- run it from the **Simulations**
  page, which then signs you in as an acme administrator in one click, or `kdr-probe --url <your server> report-demo`.
  `report-history-demo` does the same and adds five dated days of snapshots, landing on the History view.

**New report** (issue #1492; `view=new`, no report open): a draft panel that makes a report from simple choices, one
way -- afterwards it is changed in the bundle editor, and removed there or under the open report. The pure half is
`ReportCreateModel.kt` (covered by `ReportCreateTest`); `ReportCreatePanel.kt` draws it. `docs/design/reporting.md`
(*Making a report*) says what it makes.

- **Where it is offered is the listing's to say** (`ReportListing.offer`, from the summary's `canCreate`, `createsDraft`,
  `createFrom`): the button under the reports where the client's configuration is edited here, a draft's
  `saveEffectText` in a sandbox, and for a client with a sandbox a line pointing there instead -- a link to the
  sandbox's reports for an administrator who sees every client, the app bar's Open sandbox for the client's own -- as the
  bundle editor leads such a client to its sandbox. The address names `view=new` only; the page draws the panel only
  when the offer says it may, so a stale link shows the listing.
- **The page never builds a path.** The panel reads `GET /clientAdmin/report/paths` (grouped by source: the form, each
  trait, each workflow) and a column keeps the path it picked. A trait's group is named as every page names the trait
  (`formTraitName`, issue #1520): its heading's first line, its data type's title, or its id in words. A path that may hold several values offers **Which
  value** -- all of them, how many, the first (`ValuePick`, each a combine) -- and for a keyed trait **One, by its
  key**, which sends the key's values (`RCR.key`) for the server to write into the path.
- **A totals report** groups by one column: the first that has one value per form until chosen, and none once that
  column shows all of several values (`withColumn`, `withColumnChanged`). Each other column's **Combined** offers only
  the rollups its value allows (`rollupChoices`, the kernel's `ReportCombine.allows`), a number starting as a total and a
  date as the latest; "Not shown when grouped" sends none. Only a totals report sends a grouping, rollups, and history.
- **The Add-a-column choice searches by label** (`showSearch` with `optionFilterProp: 'label'`): antd filters on an
  option's value by default, which here is the path. Its list is virtual, so to drive it from script, open it and type
  the label's text before picking the option by its title.
- **A create lands on the new report** (`navigate` to it, which re-reads the listing); Cancel goes back to the listing.
- **Remove this report** is the open report's last panel (`PanelKind.danger`, behind `DangerConfirm`, page-structure
  rule 6), offered only for a report the client stores where its configuration is edited here (`reportRemovable`: the
  offer allows it, the report is `stored`, and not a template's copy). The question names where it acts
  (`reportRemoveQuestion`, `deleteTargetText` over the listing's `client`, so a sandbox reads as the sandbox), and says
  the snapshots are kept. Done, the page goes back to the listing.

**The History view** (issue #1037; `view=history`, the third button beside Per form and Grouped): a report's stored
snapshots (`GET /clientAdmin/report/history`, #1034) as a bar chart over days, with **Snapshot now**
(`POST /clientAdmin/report/snapshot`). No run is fetched behind it, and it has neither Group by nor Must have: a
snapshot is always the report's own setup. The pure half is `ReportHistoryApi.kt` (covered by `ReportHistoryTest`);
`ReportHistoryPanel.kt` draws it. It is the webapp's first chart, hand-drawn SVG (`react.dom.svg`, no chart
library), and these are the rules it was built to:

- **What can be charted** (`historyMetrics`): the count of forms, then each rolled-up column whose result is a
  number and that the report does not group by. A rolled-up date is a moment, not a height, so it is not offered.
- **One snapshot a day** (`latestPerDay`) -- the deployment's official day (UTC-8, `ReportSnapshot.day`, issue #1115),
  as the backend's pruning counts it, never the viewer's zone -- and **only the current definition's**: each snapshot says whether it was
  taken under the report as bound now (`sameDefinition`), and one that was not is counted in a note, not charted.
- **A group's colour is its place, never its rank.** `historySeries` orders groups as first seen, oldest day first,
  so switching metric or one group overtaking another repaints nothing. Past eight groups, the eight with the most
  forms on the latest day are kept; the rest fold into a grey **Other** bar when the metric adds up (a count, a
  sum) and are left to a note when it does not (an average). Only there can a new snapshot repaint -- a group
  entering the kept eight takes its first-seen place among them -- since eight colours cannot be a fixed place for
  a ninth group; switching metric still repaints nothing.
- **The palette is validated, not picked**: `--chart-1..8` in `app.css` are a fixed categorical order checked as a
  set against the card surface for colour-vision separation and contrast. Do not re-order them or add a ninth hue
  by eye. Text never wears a series colour; a swatch beside it does.
- **Laid out in real pixels** (`historyChartLayout`, at the panel's measured width via a `ResizeObserver`), not a
  `viewBox` scaled to fit, so axis text keeps its size in a narrow pane. Bars are at most 24 wide with 2 between
  neighbours, rounded at the data end and square at the baseline (`barPath`); ticks are clean numbers
  (`niceTickStep`); when the days are too many for a readable bar, only the latest that fit are drawn and a line
  says so.
- **Nothing is hover-only.** Each bar's hit target is its whole column of the plot, is focusable, and shows the
  same readout on focus as on hover; the table under the chart holds every day and every number the readout shows.
  To the keyboard the chart is **one Tab stop** (the latest day's first bar) with the arrows, Home and End moving
  among the bars (`historyBarStep`, a roving `tabindex`) -- sixty bars are not sixty stops on the way to the table.
  The plot's `svg` is a `group`, never an `img`: an image's children do not exist for a screen reader, and the
  bars' named hit targets are its children.
- **A Kotlin trap this view hit**: inside an antd `Button { }` builder, `loading = x` assigns a *local* named
  `loading` if the component has one (a `var loading by useState(...)`), not the button's property -- which sets
  state during render and crashes with React's "too many re-renders". Name such state something else, or write
  `this.loading`. A function *parameter* does the same (`onFocus: (...) -> Unit` beside a `rect { onFocus = ... }`),
  though that one the compiler catches: a parameter cannot be assigned.

**The Grouped view's chart** (issue #1772): a grouped run drawn above its table as horizontal bars or a pie, measured by
the count of forms or a rolled-up number. The pure half is `ReportChartApi.kt` (covered by `ReportChartTest`), the
component `ReportChartPanel.kt`; what every chart shares -- the marks' measures, clean ticks (`chartAxis`), a bar's and a
slice's outline, the palette's class, the keyboard's steps -- is `ChartKit.kt`, the History view's too.

- **What is in force rides the address** (`chart`: `bar`, `pie` or `table`; `measure`: a column id or `count`), read
  back by `reportChartInForce`, never kept beside it. Where the address is silent the report's **declared** chart applies
  (`ReportInfo.chart`, issue #1771), but only while the run is grouped as the report groups: a measure named for one
  grouping need not fit another. A choice writes both keys (`reportChartParams`), so choosing Forms overrides a declared
  measure; a change of mode or Group by keeps them, and they are held to what the new setup offers when read back.
- **Held to what can be drawn**: the measures are the History view's (`reportMeasures`: the count, then each column
  rolled up to a number and not grouped by); one the run no longer offers falls back to the count, and a pie of a measure
  that does not add up (`ReportCombine.addsUp`) is drawn as bars. The control offers Pie only when the measure adds up.
- **Every group, not the loaded batch.** The table grows by a hundred; a chart of those would keep and fold the wrong
  groups. When the run has more groups than are loaded, the panel walks it (`ReportsApi.chartGroups`, pages of 500, up to
  `chartMaxGroups` -- 2,000, a snapshot's cut) and a note says when there are more. Once per run and refresh, not per growth.
- **What is drawn** (`reportChartData`): the groups in the **run's order**, colour by place. A pie leaves out a group with
  no value, zero, or less (a share cannot be negative), keeps the seven largest of more than eight, and folds the rest into
  a grey **Other**. A bar chart is one series in the first colour, keeps twenty, and folds the rest into Other when the
  measure adds up, else leaves them to the table and says so (`reportChartNotes`).
- **Bars run down the page** (`barChartLayout`): each group's name on a line above its bar -- cut with an ellipsis to the
  plot's width (`chartFitText`, an estimate; the readout has it whole) -- and every value in a column at the right edge,
  so nothing is hover-only. Laid out in real pixels at the panel's measured width, from zero, on clean ticks.
- **A pie** (`pieChartLayout`, at most 240 across) runs clockwise from twelve o'clock beside its legend, a row a slice
  with swatch, name, value and share (`chartShareText`). One group is the whole circle, drawn in two halves since an arc
  from a point to itself draws nothing. A hairline of the card between slices keeps close colours apart.
- **The History chart's rules hold**: one Tab stop with the arrows moving among the marks (Up and Down too, since bars and
  the legend run down the page: `chartStep(vertical = true)`), the readout on focus as on hover, the `svg` a `group`, and
  no text in a series colour. It reuses the History view's `rh-*` classes and palette; `rc-*` are its own.

**Its copy file** (`reports.md`, `PFRAG.reports`, issue #1337) holds the page's "What is this?" explanations: its views
(`views`) and **New report** (`newReport`). The New report one links [the report paths
reference](../../../docs/reference/report-paths.md) (issue #1633), as `/docs/reference/report-paths.md`, which the
copy's link resolver opens in the app. `HomeUiConfigEndpointTest` holds the link to a served document.
