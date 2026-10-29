## The Simulations page (issue #997)

`/wa/test/simulations`, offered to a caller with **test access** only -- a test instance, with env auth effective (issue
#1149; the menu item is gated on `kdr:hasTestAccess`, and the route is Home without the app config's `hasTestAccess`):
the simulations the node offers, from `/fixture/simulations` -- the catalog's shape, parsed with `parseCatalog`. Like
every fixture, the simulations exist for nobody else, so a session that suppressed its env auth loses the page, and
its sign-in buttons stop working, until it turns env auth back on.
Each gets its input form from its schema (the same `SchemaForm` and `checkInput` the endpoint catalog uses) and a
**Run**; the run's report becomes **Sign in as …** buttons, which post `/fixture/becomeUser` and `afterSessionChange`
to the report's start page -- no reload. Each successful run is remembered in this browser's `localStorage`
(`SavedSimulationRun`, the ten most recent; issue #1099) and listed under **Recent runs** with its sign-in buttons, so
after signing in as one of a run's users the others are a click away on the way back. A run is kept against the
app config's `dataId` -- written once into the database by `InstanceConfigService`, so it changes when an in-memory
node restarts and holds across restarts on a persistent database -- and a run from other data is shown greyed,
with no sign-in, since its users are gone (`recentRunsFor`). The pure half (`SimulationsApi.kt`: the report, the
sign-in body, the start page, the saved runs) is covered by `SimulationsTest`. What a simulation is, and how to add one, is in the `kdr-testing` skill.
