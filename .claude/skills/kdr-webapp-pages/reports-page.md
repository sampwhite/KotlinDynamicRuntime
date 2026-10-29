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
