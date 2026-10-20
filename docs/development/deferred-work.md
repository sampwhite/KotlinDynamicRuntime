# Deferred work

Things we have deliberately decided **not** to do yet, each grouped under the condition that should make us
revisit it. This is **not** a backlog. The GitHub issue tracker is the action queue — every open issue is
meant to be acted on soon — and deferred work lives here instead, so the tracker never fills with issues
everyone has silently agreed to ignore.

## How this file works

- **Grouped by trigger.** Each `##` section is an observable condition; the items under it are what to do once
  that condition holds. So when a trigger fires you read one section and see everything it unblocks.
- **Triggers, not wishes.** A trigger is something you will *notice* — "when a second deployment exists," "when
  we support clients." "When the app is mature" is not a trigger; it never arrives.
- **Promote and delete.** The moment a trigger fires — or an item simply becomes actionable — move it to a real
  issue and remove it here. This file only ever holds *not-yet-actionable* items; anything ready lives in the
  tracker.
- **Reachable from context.** Link into an item by its section anchor from the code or a closing issue comment,
  e.g. `// deferred: see docs/development/deferred-work.md#when-a-deployment-serves-real-clients`, so it is found while
  working on the relevant code, not only by reading top to bottom.
- **Keep items short.** The trigger, a line or two of what and why, and links back to the source — enough to
  reconstruct the decision, not a design doc.

## When a deployment serves real clients

The point at which untrusted callers and real accounts exist, so production-grade auth and information hygiene
start to matter.

- **5xx wire redaction** *(from #97 §6).* Redact internal 5xx message bodies server-side, not only at the
  `sensitive` flag. Today `RequestHandler.handleException` obfuscates only errors explicitly flagged
  `sensitive`, and only when the deployment obfuscates; a generic 500 (e.g. a database failure) still carries
  its cause chain in the response body. The frontend hides it from the user (#111), but it is on the wire. Add
  a status/config-driven redaction policy on top of the existing `obfuscateSensitiveErrors` resolver.

- **Single-use auth-form / verification tokens** *(from #155).* Persist generated auth-form tokens in the
  database, recording whether each may be used to set a password when its verification token is generated. Add
  logic so a verification token can be consumed only once.

- **Server-side session store + invalidation** *(from #155).* Persist a hash of the auth cookie plus the
  relevant session information into a data store at the time the cookie is generated — and the same for any
  other mechanism that grants temporary login state. Every potentially-authenticated request loads this session
  information. On logout the session is marked invalid, and every session sharing the same auth credentials can
  no longer act as that user. Add an admin endpoint that lists all active sessions, including their creation and
  expiration dates.

- **Google sign-in: bind the ID token with a `nonce`** *(#157 review).* The ID token is verified by
  signature / audience / issuer / expiry, but is not bound to a single sign-in, so a captured token can be
  replayed within its (~1h) validity to log in as that user. Generate a nonce in the frontend, pass it to
  Google (which embeds it in the token), and check it in `GoogleIdTokenVerifier.verify` against what the browser
  sent. Defense-in-depth on top of the audience binding, and Google's own recommendation for the ID-token flow.

- **Auto-admin's scope should narrow before production** *(from #225, revised by #352).* The rule grants `admin`
  (which ranks above `operator` on the role ladder) and `allClients` to a no-`+` address on a controlled domain —
  the configured `KDR_ADMIN_EMAIL_DOMAIN`, or `example.com` outside production. #352 settled two thirds of the
  original item and left this third. It **no longer re-applies on every login**, so the grant is a statement about
  how an account was provisioned rather than a standing property of an address; and it keeps `allClients`, because
  dropping it would leave a fresh deployment unable to reach its own admin surface by any route but the `GrantRole`
  script. What remains is production: the design has `example.com` not working there at all — which holds, since it
  is a controlled domain only outside production — and only a subset of admin-domain addresses holding
  `allClients`. That is not built, and cannot be tested against anything real until there is a production
  deployment to narrow. Every deployment already has more than one client (`hub`, `public`, and the sample's), so
  what this waits on is production, not a second client.

- **Cookie consent, and a `deviceId` minted before login** *(cedar lesson).* Logging a `deviceId` that traces one
  browser across anonymous browsing and repeated login/logout was a high-value search key in cedar. It requires
  minting the `deviceId` cookie on the first (anonymous) request, and cedar found the cookie-privacy/consent question
  a big enough deal to reshape how cookies were set. Today `deviceId` is deliberately **login-gated** (minted only at
  login, in `RequestService.checkAddAuthCookies`), and no consent is asked for. Settle how consent is asked and
  recorded first; only then consider the early `deviceId`. If revived: split minting (unconditional, early) from
  `recordDevice`/auth-cookie writing (login-gated), give the cookie its own lifetime, and never `Set-Cookie` on the
  immutable-cached static assets (#137).

## When an organization has to hide content, not just narrow it

The point at which someone expects an organization to be a confidentiality boundary rather than a convenience
filter — two orgs in one client whose content must not cross.

- **The organization filter is lenient by design, and so cannot hide anything** *(from #225).*
  `ReadScope.admitsOrg` admits a row whose own org is null, so content belonging to the client as a whole stays
  visible to every organization inside it. That is deliberate and worth keeping by default: strict matching
  would make everything a client wrote before adopting organizations vanish the moment somebody was given one
  — an adoption cliff indistinguishable from data loss. The cost is that an org narrows a view without ever
  sealing it, and nothing today says so to whoever assigns one. Revisit as a per-client choice (lenient while
  adopting, strict once migrated) rather than a global flip, and expect a backfill: making it strict is only
  safe once every row that should belong to an organization actually carries one.

## When frontend errors are shipped to a third-party logger

The point at which a render failure in production is *recorded somewhere* rather than only sitting in one
user's console — which is what makes it worth interrupting them about.

- **Show the user a designed apology, not the current panel** *(from #223).* The error boundary added in #223
  keeps the app alive and shows a deliberately plain "this section could not be displayed" card, with the
  detail withheld unless `showErrorDetail` (the backend's `isTestInstance`) says otherwise. That is the right
  behavior while a failure goes unreported: there is nothing to promise the user, so promising nothing is
  honest. Once errors reach a logger, the trade changes — the failure is now known to us and being acted on,
  so the page can be taken over with a proper "We are sorry…" treatment that says so. The seam already exists:
  `ErrorFallback` in `webapp/.../ErrorBoundary.kt` is a plain component the boundary swaps in, and
  `installGlobalErrorHandlers` is where the non-render failures already arrive and currently only report.

## When schema is exported for third-party tooling

The point at which somebody outside this codebase needs the API in a form their own tools read — most likely
a YAML file to point Swagger at, which is what penetration testers and clients building integrations ask for
first. Noticeable because someone asks for it by name.

- **Project a derived field out of a `$ref`'d type, not just the top level** *(from #254).* An endpoint's
  published input schema drops its `g-derived` fields, but only the flat top-level ones. A derived field
  *inside* a referenced type stays visible, because the catalog ships one `$defs` bag that the input and the
  output both resolve against — removing it there would take it out of the response schema too. Fixing that
  means the input and the output carry separately projected defs, which is a change to the shape of the
  catalog and is only worth making once something consumes an export. Until then the keyword travels and each
  surface honors it, which is why the form draws no control for such a field. The same projection would let
  the input schema state resolved defaults, such as the input trim, that the catalog leaves out today (#772).

- **Strip the default branch from a strictly-read union** *(from #301).* A manufactured entry union always
  declares a default branch, so an unrecognized `traitId` can pass through where that is the right answer.
  Whether a *reader* uses it is a policy the reader applies — `SchOpts.allowUnknownVariant` — and a strictly
  read endpoint therefore honors less than the document it publishes says. Its exported schema has to have
  the `discriminator.defaultMapping` and the default branch removed, or we publish a schema we do not honor
  and a client's own tooling calls a payload valid that we return a 400 for. It is a second projection at the
  boundary that already has to project `g-derived` out of input shapes, not a new mechanism.

- **The rest of the export contract is already written down**, in two places. The `SCH.gPrefix` KDoc in
  `SchemaConstants.kt` holds the rule itself: `g-` keywords stripped by default, a small transformer table for the
  few with a standard equivalent, and the governing rule that an inexact conversion must be *stricter* than us
  rather than looser. [`docs/design/gedra/entry.md`](../design/gedra/entry.md) adds `discriminator.mapping`,
  synthesized at export from the branches' `const` values, and the one known gap: `g-primaryKey` has no standard
  equivalent, so stripping it makes the export looser, which is accepted because the constraint governs our own
  stored entries rather than anything a third party validates. Read both before starting: the decisions are made,
  only the code is missing.

## When public API documentation is written

The point at which we publish documentation for people calling the API from outside — a page, not only the
catalog. Noticeable because somebody sits down to write it.

- **A general page on how the API works** *(from #772).* The catalog's per-field schema does not tell the
  whole story: it is JSON Schema (draft 2020-12) plus our `g-` keywords, and some of its behavior comes from
  defaults that never appear on a field — plain string input is trimmed, a blank scalar counts as absent,
  and numeric, boolean and date fields coerce from strings. The page states those as global rules, so a
  field's schema only has to show where it departs from them (`g-outerWhitespace: "keep"`,
  `g-allowCoerce: false`). The rules are in `docs/development/code-guide.md`'s "Relaxed validation and coercion is the
  default" section.

## When fragment copy computes with its data

Today a fragment *substitutes* values. The trigger fires the first time one **compares or calculates** with
them — `${minutes > 5}`, `${count * price}` — because that is when a value of the wrong type becomes possible.
Until then every substitution is "print this", which no type can get wrong.

- **Type-aware fragment checking** *(from #305; the half #314 left undone).* `/operator/fragments/check`
  reports the paths an entry requires and which ones a supplied map lacks, but it is a **presence** check: it
  never evaluates, so `${minutes > 5}` against a `"lots"` passes it and still fails at render, since that is not
  a number (a cleanly-numeric string like `"15"` now coerces, #608; a non-numeric one still throws, #293).
  Catching that means evaluating against realistic values, which means first deciding
  where those come from — supplied by the caller at the endpoint, or declared beside the fragment so a boot
  check can run with nobody present. Deferred because none of the paths the shipped copy reads compute, so a
  check with nothing to find would be tested against invented cases only.

## When a customer wants detailed provenance, including "where used", in the configuration UI

Today the copy editor (#1062) says *who set* a key -- the config, source or stored, or the template it came from --
and, per file, where the application shows it (`shownOn`). It does not say what *uses* a key. The trigger is a
customer asking for that: which workflow, layout or mail a piece of copy appears in, before they change it.

- **"Used by" rows for backend keys** *(from #1062's follow-ups).* The boot checks already find every layout and
  workflow-label `%{@t("file.ns.key")}` pull (`layoutPullProblems`, `LayoutCheckService`, `WorkflowRegistry`) and
  the pulls between fragment files (`MarkdownFragmentService.checkFragments`, kept as edges for cycle detection).
  Inverted into an index, they would give a key a "used by: workflow X's label for field Y" row. Covers backend
  files only (mail, the workflow help files); which page reads a *frontend* key is a string literal in the webapp
  (`copy.t("home", "brand", …)`) and would need usage tracking, which this deliberately does not add.
- **Author notes as key documentation** *(from #1062's follow-ups).* The `/- … -/` comment written just above a
  key in a `.md` file often says what it is for, and the parser strips it (`stripFragmentComments`). Kept as the
  key's note and shown in the copy editor, it is authored documentation rather than tracking. Sparse today
  (auth.md: 7 comments for 62 keys), so it would come with a pass writing the missing ones.

## When a frontend change breaks a page its author did not open

Today the practice is that whoever changes the front end drives it in a browser and looks. That holds while
one person can see the whole surface. The trigger fires the first time a change breaks a page its author had
no reason to open — which is also the first time the practice has demonstrably failed rather than merely
looked fragile.

- **A DOM/React test suite** *(the half of #161 that was not built).* `jsTest` covers pure logic — plain
  `Map`/`String` in, typed value out — and `jsBrowserTest` is **disabled** in `webapp/build.gradle.kts` so
  `check` never needs a headless Chrome; that disable line is the re-enable point. Nothing automated renders a
  component, drives a form, or exercises `fetch`. The coupling that makes this matter is already here: the app
  bar and the profile page both render `UserProfile.displayName`, so a change to one *is* a change to the
  other, and no existing test would notice. Deferred because it needs a harness decision first — an in-browser
  DOM suite through the disabled task, or Playwright driving a booted instance — and picking one is most of the
  work. Note #161 itself is closed: it delivered the pure-logic layer, so nothing currently tracks this.


## When a listener that outlives its page starts costing something

`onHashChange` (in `HashRoute.kt`) adds a page's handler to the list the one app-wide `hashchange` listener calls
(#700), and nothing ever removes it. Five pages now do this — `EndpointCatalog`, `Users` (since #324),
`FormsPage`, `EditFormPage` and `SurveyEditPage` — and every one of them mounts and
unmounts on each navigation to and from it, so what accumulates over a session is one live closure per visit,
each still running on every later hash change.

- **Cleanup for the `hashchange` listeners.** It is currently a leak rather than a defect: the handlers call
  state setters of unmounted components, which React ignores, so nothing observable happens. The trigger fires
  when something does — a handler with an effect beyond `setState`, a page whose re-derivation is expensive
  enough to feel N times over, or a heap that grows across a long session. Deferred because the fix is not the
  usual one-liner: this wrapper version's effect body is a **cancellable coroutine**
  (`useEffectOnce { … }` takes `suspend CoroutineScope.() -> Unit`), so cleanup means `try`/`finally` around
  `awaitCancellation()` rather than a `cleanup { }` block. The idiom is no longer new to `webapp` —
  `useIdleBump` uses it — but applying it still means changing five working effects for a leak with no symptom.
  `App`'s listener is deliberately exempt either way — the root never unmounts.

## When listings get long enough that losing your place in one costs somebody

A listing page is unmounted when it is left and mounted with no rows when it is come back to, so returning to it
(Back, or the menu) lands at the top: the browser's own scroll restoration runs on `popstate` before the rows have
arrived, and the page it left was shorter than the one it returns to. Measured on the Users page with 62 rows: a
scroll of 2000 came back as 0 (#1313). A refresh while on the page keeps its place, since a re-read replaces the
rows in place. The trigger fires when somebody works down a long list a form at a time -- opening a form from the
forms listing, then going back to carry on from where they were -- and has to find their place again on every
return.

- **Restore a listing's scroll position on return.** Keep the position per history entry (`history.state`, or a
  map keyed by the hash), and restore it once the page's rows have rendered, not on navigation. The forms listing
  is the first case; the Users page, the Clients listing, and a report's run are the others. Deferred because it
  touches every listing page, and because "once the rows have rendered" is per page: each page has to say when
  its content is in, which a shared hook can ask for but not decide.

## When an edge fronts a backend that allows anonymous access

Today an edge challenges **every** proxied request (#419), which is right while it fronts internal
deployments: everything behind it is for operators, and a caller who is not signed in has no business there.
That stops being right the moment a public-facing application sits behind one — a visitor who is *supposed* to
be anonymous is redirected to a Google sign-in they cannot complete and will not want.

The trigger is observable: the first route added for a backend whose application serves the public.

- **Per-route anonymous access.** `EdgeProxyHandler` decides to challenge from the context root alone, so the
  answer is the same for every backend. The route entry of `kdr-deployment/thoughts-edge-server.md` §7 already
  anticipates this with an `allowNoEnvAuth` flag — a root not carrying it is challenged, one carrying it is
  forwarded as-is. Needs the route table, so it lands with that rather than before it.

- **Static assets should not be challenged at all** *(Sam, during the #419 discussion).* A page's CSS, JS and
  icons are not a place to make a login decision: an unauthenticated asset request either belongs to a page the
  caller was already allowed to load, or to one they were not, and the page itself is where that was decided.
  Challenging them means a signed-out visitor gets a sign-in page where a stylesheet belonged. This is
  *survivable* today rather than fixed — since #419 a background request is refused with a 401 the frontend
  acts on, instead of a redirect it tried to parse as JSON — so the symptom is a clean refusal rather than a
  broken page, and that is the whole of why it can wait.

- **A caller without env auth sees only the published API** *(Sam, during the #433 review; since #1128 with one
  exception, the endpoints generated for a client the caller oversees -- `SchemaService.shownWithoutEnvAuth`).* The endpoint
  catalog is curated by the section model today: an anonymous caller sees every anonymous section, which is 30
  endpoints and correct, since a login page needs `/auth/...` and a shell needs `/app/ui/config`. The intended
  end state is narrower — no env auth means the catalog shows only endpoints marked `publicApi`, so an
  outsider is shown the surface we document and support rather than everything they happen to be allowed to
  reach.

  Env auth is the right discriminator because it already means "arrived through our perimeter", so it
  separates the people who operate a deployment from the people who use it, without either becoming a role.
  The mechanism is in place after #433: the tag exists, the catalog carries it, and it already filters on it.

  **The precondition is what makes this deferred rather than a small change.** Only a first set is marked
  `publicApi` (#489: the gedra endpoints and `selfInfo`), so switching the default over today would show an
  outsider a catalog without the endpoints that let them log in — worse than the current behavior, and precisely
  for the people it is meant to serve. It needs the published set curated first, which is a product decision about
  what we will support rather than a code task. Note also that the anonymous sections are anonymous for a reason:
  whatever the rule becomes, an unauthenticated client still has to be able to find the endpoints that let it log
  in.

  The acceptance test already exists: the env-auth toggle from #360 was built partly so this filter could be
  exercised by hand.

## When a client wants to publish part of a bundle separately

A bundle -- one row of the stored-config table -- is the unit of write, publish, revert and the publish impact check.
So two workflows in one bundle are published together, always, and a client that wants to publish one without the
other has to have put them in separate bundles when it was written. Nothing moves an entry between bundles afterwards:
by hand it is a delete from one and a write to the other, two writes with no safe order on the latest tier (the trial
refuses the client holding a workflow id twice, and deleting first leaves the client without it). The conference demo
(#1134) keeps one workflow per bundle for this reason, and its README says so.

The trigger is a client asking to split a bundle, or to publish one workflow of several. When one does:

- **The atomic half is already there.** Every config write runs under the client's lock on `GedraConfigClientTran`
  (`underClientLock`, #843), and `writeConfigs` writes several bundles of one client in one transaction, judged by
  one trial that runs inside it. A "move these entries to that bundle" operation is one such block: read the source,
  drop the entries, write both bundles, trial the pair. The import is the one caller of `writeConfigs` today.
- **Publishing wants the same treatment.** `publish` is per bundle and per call. A split on a published-only client
  needs "publish these together", with the impact report (#935) run over the pair.
- **Stored references are the real question.** A form's state names a workflow by `WfRef`, bundle id plus workflow id
  (`gc.cd.acme.base~2#review`), pinning the revision on purpose. After a move, every engaged form's
  `computedAgainstRef` and its eligibility-failure records point at the old bundle. Either rewrite them in the same
  transaction, or let resolution follow a moved workflow by id within the client when the named bundle no longer
  holds it -- the forgiving choice, which keeps history readable. Decide this before building the operation.

## When a client wants a field whose choices depend on another field

A schema's `if`/`then`/`else` is deliberately one shape (`SchCondition`, issue #253): a property is **required or
forbidden** depending on what another property holds. It cannot change a field's `g-options`, and the form rules say
so (`offeredChoices`: "the schema's choices are fixed per field today"). So "the session format offers *workshop*
only when the speaker has spoken before" is not expressible, and the conference demo (#1139) shows the required-when
conditional instead of it.

The trigger is a client asking for it; none has. When one does:

- **Let a `then`/`else` carry a property's `g-options`**, merged over the base list by the validator for the data's
  current state, and by `offeredChoices` for the form -- the one place the form's list meets the schema's, which
  its KDoc already names as where this would land.
- **Keep the closed-list promise at both ends** (see the section below): a value valid under the branch that held
  when it was written must still read back after the deciding field changes, or a stored answer becomes unreadable.
- **Design View's ghost for a conditional field** (#972) shows a field that is absent; a choice that is absent needs a
  marking of its own.

## When a closed choice list has to change

A closed `g-options` list is a promise that nothing else is valid, and the promise is made at **both** ends:
a value is refused on the way in, and refused again on the way out. The second half is the problem. Change a
list — globally, or by a client narrowing one (`SchNarrowing` rule 2, and since #418 by closing an open list
too) — and rows written under the old list stop validating. What that costs is not a rejected write, which
would be fair, but a **read that fails**: data already stored becomes unreadable, and a schema edit turns into
an outage over content nobody is touching.

The trigger is observable and has not happened yet: the first time a deployment carrying real data changes a
closed list's values. Nothing is deployed, so nothing is stranded; that is the whole of why this can wait.

- **Drop option validation on the response side** *(Sam, reviewing #418)*. Ingest strictly, emit leniently. The
  option list exists to stop a bad value *entering*; once it is stored, refusing to hand it back helps nobody.
  Cedar built request/response variance into its schema for a family of problems like this one.

- **The direction already exists**, which is what makes this a small change rather than a design. `SchOpts`
  carries `forInput` (#254), and `g-derived` is the precedent: a property meaningful only in a **response**,
  neither asked for nor accepted on the way in. This is its mirror — a *constraint* meaningful only in a
  **request**. Same flag, opposite side, and the symmetry is worth preserving in whatever keyword or default
  it lands as.

- **Decide whether it is a default or a declaration.** Making it unconditional says every closed list is
  lenient outbound, which is probably right and is a behavior change to make deliberately. A per-property
  keyword says which lists expect to shift, which is more honest and is another keyword to carry. Note that
  `validateResponseSchema` is on in tests, so whichever is chosen shows up there first rather than in
  production.

- **This is not the whole of the stranded-data question**, only the half that keeps a node serving. A value
  that no longer validates is still *wrong* by the current schema, and something eventually has to reconcile
  it — a migration, a report, or a refusal at config-load time when stored data would fail the definition
  being loaded. Reading it back is what buys the time to do that.

## When the people who can reach an edge stop being a small trusted group

A diverse employee and consulting base, with active customers behind the deployment. Today everyone who can
clear an edge's Google gate is staff, and the gate is the whole of the decision.

- **Restrict what env auth grants** *(Sam, during the #434 review).* `UserProfile.envAuthed` grants
  `ROLE.admin`, so anyone who signs in at the perimeter on a permitted domain is an admin of it, with
  `operator` and `user` implied by the ladder. That is right while the permitted domain means "us": the Google
  gate already established who they are, and withholding the top rung after it buys nothing.

  It stops being right when the domain admits contractors, or when customer data sits behind the deployment
  and "reached the perimeter" no longer implies "may do anything at it". The likely shape is that env auth
  establishes *identity and channel* while the level comes from somewhere that can differ per person -- which
  is a user store, and an edge deliberately has none, so this is a real design question rather than a
  narrowing of one constant.

  Deliberately not pre-solved. Guessing at it now would build a mechanism against an imagined threat, and the
  cost of waiting is one line plus whatever the answer turns out to need. Note that `allClients` is already
  withheld — it is a capability rather than a rung, so the full-scope admin sections stay closed to an
  env-authed caller, which is the natural first thing to revisit rather than the last.

## When the workflow designer is greatly expanded in scope and functionality

The point at which the workflow designer is grown to let a client author workflows well beyond rewording shipped
copy. Two things a client cannot do today would then matter: **declare copy files of its own** (new file ids, not
overlays of a component's), and have a workflow **select among** that copy **by reference** (choosing which fragment
to show by a key rather than inlining the text). The first makes a client-owned fragment file worth building, the
second a declarative selector. Until then a client overlays existing component copy, and that is enough. Design and
full rationale live in the fragment design doc, `kdr-design/thoughts-on-fragments.md` (private `sampwhite/Actions`).

- **Client-defined template (fragment) files** *(from the "client-declared fragments" iteration in the design
  doc).* Let a client declare its own **base** fragment files -- new fileIds, not just overlays of
  component-shipped ones -- via a `GedraConfigBuilder.fragmentFile(...)` beside the existing `fragmentOverlay`.
  New fileIds are clientId-namespaced so they cannot collide with component ids (separator sub-decision: only
  `-`/`_` pass `isSafeFileId` today and both already occur in ids, while `.`/`:`/`/` are each reserved
  elsewhere -- so this needs either a widened `isSafeFileId` with one dedicated char or a structural clientId
  field). The audience boot checks already resolve a pull's target audience in the variant's client
  (`declaredAudience` takes the client, #505), so a client's own backend pulls are checked as they should be. No
  cross-client references; reuse across clients is an in-source **clone-and-own** "include", never a live lookup.

  *What #974 added (deferred here 2026-10-08).* Where it stands: a client's stored `fragmentDef` entry is always
  read as an overlay. A new key in a shipped file is an orphan (refused at write). An overlay of a file **no
  component ships** is accepted and its keys resolve when pulled, but by accident -- the fragment check reports the
  file as declared-but-absent, its content is never checked (`checkFragments` builds a client row only over a
  base), its audience defaults to frontend, and, read from the code but not run, the startup fragment check is strict
  outside `prod`, so a non-prod node loading such a config at boot would likely refuse to start. The proposal: a
  `fragmentDef` entry for a file no component declares **is that client's base** (`mergeFragmentLayers` already
  admits a client-scoped base), declaring its audience -- backend the safer default for copy pulled by layouts and
  workflow labels -- and checked like any base, so a malformed template is refused at write and dropped at load; an
  entry for a shipped file stays an overlay under the orphan rule. Open, besides the file-id namespacing above: whether
  `fragmentDef` says base-or-overlay explicitly or leaves it implied, and widening the copy editor's
  `requireShippedKey` (#918) to "a key some base declares, shipped or the client's" -- which would also give Design
  View's shared wording (#1010, #1070) copy of the client's own to edit.

- **Structured descriptor content form** *(Phase 5 of #505).* A `{template, by, default}` alternative to a
  bare string in a content field -- a schema `String | descriptor` union that normalizes to the *same* resolved
  outcome as the string form. It buys two things the string form cannot: a **statically knowable pull-set**
  (`by` enumerates the choices, so they can be validated and preloaded, where a computed `${@t(chosenKey)}` is
  opaque), and a **declarative shape** friendlier to content authored or generated as data than embedded
  `@t`/`?:` syntax -- which is exactly the select-by-reference case this trigger describes. Deferred because it
  duplicates the proven string/`@t` form (Phases 1-4) and is **purely additive** -- widening a field to accept
  a second shape breaks no existing string content and needs no migration -- so it costs nothing to add when a
  consumer exists, and building it now would be a second way to do a solved thing. Ripples into `SchType`, the
  UiBlock merge, and both render paths when built. Revisit sooner if component content (not just a client's) is
  ever authored as data, or a preload/bundling path needs the static pull-set.

## When a client declared in source needs its definitions changed as data

A real client, not a sample, whose configuration is declared in **source** and which needs a workflow's copy,
requirements or choices changed -- or another part of a source-declared definition overridden -- as stored data,
without a code change. A client defined in data already gets all of it: workflow variants from Design View
(#984), with form requirements and restated choices in the layout (#1022, #1048). Today the sample's `acme` is the
only client this would serve.

- **Targeted overlays of source-declared definitions** *(#1011, parked 2026-10-05, deferred here 2026-10-07).*
  Let a client's stored configuration alter what its own source declares, without cloning it:
  - **A workflow variant for a source-declared workflow.** A stored config declaring the same workflow id is a
    collision today and is dropped, so Design View refuses to edit such a form. The first step would admit only
    a workflow's `types` with layout, which since #1022 and #1048 carries requirements and restated choices as
    well as copy.
  - **A stored alteration of a type the client's source declares**, merged by #985's merger rather than
    replacing it whole. #990's `g-extends` partly eases this: an extension follows its source base without
    drifting, but under a new name that traits must point to.

  Deferred on complexity: it adds a new concept for authors, an overlay entry in stored configuration, and today
  it would mainly serve a sample. The design constraints in #1011 stand for when it resumes:
  - an overlay is explicit, names its target, and is never a redeclaration;
  - what may be overlaid is declared per part by a `MergeSpec`;
  - addressing tasks, saves, eligibility and labels stays deferred, in a shape that does not foreclose a keyed
    merge on task ids.

## When a client's branding has to reach somebody who has not signed in

A client's own favicon and brand mark (issue #1130) are served to the client of whoever is asking, and somebody
who has not signed in is in the default client. So a client's login page, and the first paint of every page,
show the deployment's.

- **Resolve the client from the request's domain** *(#1130, deferred here 2026-10-12).* `ClientDef.domainPrefix`
  and `customDomain` are declared, checked for clashes between clients, and shown on the Clients page, but not yet
  used to resolve a request's client. Once a request's host names a client, an anonymous visitor to
  `acme.example.com` has one, and both the static address (`ClientWebAssetService.serve`) and the fragment server,
  which has had this limit since #456, follow without a change of their own: each already asks the context whose
  client it is. It is a new trust level rather than a new lookup -- the host is the caller's to choose -- which is
  why it was not folded into either.
- **Write the client's icon into the shell's `<head>`** *(#1130, deferred here 2026-10-12).* `AppUiPage` renders
  the deployment's icon links for everybody, and the frontend swaps them once the shell config is read, so a tab
  shows the deployment's icon for a moment on each load. With the client known at render -- from the session
  today, from the domain later -- the shell could link the client's own. Left out because the shell is rendered
  before anything about the caller is consulted, and that is a property worth a decision of its own.

## When a configuration holding file refs is imported for another client, or into another deployment

A file ref (issue #1153) is an entry in a client's stored configuration that names bytes in the file store by their
hash, kept under that client. (One that names a local file, issue #1154, names nothing in the store: it travels
whole, and resolves wherever the same root holds the same file -- or, for a client's own ref, issue #1164,
wherever the root holds that file in the *target* client's directory.) An export carries the entry and not the bytes, so an import is refused, naming the refs
whose files are not there, wherever the bytes are not under the *target* client: another deployment's store, or a
clone to another client id in this one.

- **Carry a configuration's files with it** *(#1153, deferred here 2026-10-13).* An export that lists, or bundles,
  the objects its file refs name, and an import that stores them before it writes the entries -- under the target
  client's prefix. Until then the files are copied by hand, key for key (`clients/<client>/refs/<hash>`), before the
  import. Left out because no client holds file refs yet, so no import meets the refusal; the first clone or restore
  of one that does is the trigger, and the refusal says exactly what is missing.

## When a deployment keeps its files on S3

The `s3` file store (issue #1129) has been run against Linode's S3-compatible Object Storage (issue #1271), and never
against AWS itself. Do this **before** the first start of an AWS deployment whose credentials come from a web identity
or an assumed role, not after: the boot check is where the store first signs in, and there it can come up as an
identity nobody chose without saying so.

- **Add the SDK's `sts` module, for web-identity and assumed-role credentials** *(#1145, deferred here
  2026-10-19).* Only `s3` and its URL-connection client are on the classpath, so EKS's IAM roles for service
  accounts, and a profile that assumes a role, cannot be read. Worse than a refusal: the default chain moves on,
  and on an EKS node can end at the *node's* role, so the store comes up as an identity nobody chose. Add `sts`
  with the same HTTP-client exclusions, a test that the web-identity provider can be built, and a startup line
  naming the kind of source that supplied the credentials. Static keys, an instance profile, ECS task roles and EKS
  Pod Identity are unaffected. Trigger: the deployment's credentials come from a web identity or an assumed role.

## When a client must keep its files somewhere other than its deployment's store

A deployment keeps every file in one store (issue #1129): its directory, its S3 buckets, or memory. A client whose
files must be elsewhere -- data residency, a customer's own cloud account -- is today given a deployment of its own
(`docs/deploy/lightweight-node.md`), which keeps the store per deployment and simple to run. What would let one
deployment keep different clients' files in different stores is designed and part-built, and was set aside as more
than anybody needs yet (2026-10-20).

- **What is in place** *(#1282).* Every client's file is reached through `FileStoreService.forOwner(cxt, owner)`, a
  view that takes only that client's folder, `clients/<owner>/`, while the service's own calls refuse a key in any
  client's folder. So which client a file is for is said, and checked, wherever it is stored or read, and *routing* a
  client's files to another store is decided in `forOwner` alone -- the stores, the assignment and its checks below
  are the rest of the work.
- **Several named stores in a deployment** *(#1282, removed again 2026-10-20).* A `KDR_FILE_STORES` variable
  (`aws.type=s3,aws.region=…,aws.profile=…,disk.type=local,disk.dir=…`) parsed without a service
  (`FileStoreSpecs`), every store held to every bucket at boot, a credentials `profile` per S3 store, and
  `CheckFileStore --store`. Built, tested and merged in fd42265d, then taken out in the change that wrote this, because
  without a client assigned to a store it was a setting that looked like it did something and did not. Bring it
  back from there.
- **A client assigned to one of them** *(#1283, deferred here 2026-10-20).* A platform-only `ClientDef.fileStore`
  that `forOwner` routes by (a sandbox by its parent), checked at load (`ClientCheck`: a store the node lacks is a
  configuration problem, never a fallback to the default), and a change of store refused in the config trial while
  the old store holds a file the new one lacks. Its costs are why it waits: that check reads the client's files
  inside a configuration write, and every node must declare the same stores.
- **The Clients page** *(#1284, deferred here 2026-10-20).* A "File store" row in the definition summary, and a
  choice of the node's stores in the definition editor for an administrator of every client.
- **Moving a client's files to another store** *(#1285, deferred here 2026-10-20).* A `kdr-run` script copying a
  client's folder between stores, keeping each file's metadata and checking each copy's hash.

Trigger: a client that must share a deployment with others **and** keep its files apart from theirs. One that can have
a deployment of its own needs none of this.

## When a bucket holds tens of thousands of objects

The operator's file-store browser (issue #1297) derives a folder's contents from every object name beneath it,
however deep, since no provider keeps folders: the top of a bucket names the whole bucket to show its first folders.

- **A delimiter listing for the browser** *(#1297, deferred here 2026-10-20).* S3's `delimiter` answers the folders
  under a prefix as `CommonPrefixes` in one request; a directory store reads one directory. A `FileStore.folders(cxt,
  bucket, prefix)` each provider answers its own way would let `FileStoreBrowse` name only what is directly under
  the prefix. Trigger: a bucket past tens of thousands of objects, where the top of it costs more than a few list
  requests.

## When a deployment keeps local files for its clients

Local file refs (issues #1154, #1164) are built and tested, but no deployment declares a root of local files yet,
and none has opened one to clients. These are what a deployment that does will want.

- **Remember what a served file is, by its hash** *(#1172, deferred here 2026-10-19).* Serving a local ref reads,
  hashes and sniffs the file on every request -- for an SVG, a new `SAXParserFactory` and a full parse
  (`WebAssetCheck.svgProblem`). The right address is cached by the browser; a wrong or bare one is `no-store` and
  parsed each time, so a signed-in user of a client with local SVG refs can make a node do that on demand. A
  bounded `hash -> kind` memo in `LocalFileSource`, consulted after hashing and before sniffing (so the hash must be
  taken first), is exact, since the same bytes always sniff the same; and one factory configured once, if it is
  safe to share. The listing's half was done in #1175. Must not move: a file rewritten in place within the same size
  and date is served as its new bytes (`LocalFileRootsTest`). Trigger: a client with local SVG refs on a node with
  real traffic.
- **Let a client's administrator list their own directory of an open root** *(#1177, deferred here 2026-10-19).*
  Today they must be told a path and type it, and a wrong one is answered with one deliberately vague sentence.
  `GET /clientAdmin/client/files/roots/list {client?, root}` would list `<root>/<clientId>/` and below, held to every
  rule a read is (no links, no hidden names, regular files, the size limit), bounded in depth and count, each entry
  saying whether a ref already names it -- and become a picker in the Files section's two kept-file editors. To
  decide: whether a listed file is sniffed (the `lookedAt` memo makes a second listing cheap), and whether an
  administrator of every client may list a root that is not open. Trigger: a deployment opens a root to clients
  (`KDR_LOCAL_FILE_CLIENT_ROOTS`).
- **Show a node's roots on the operator's report, and flag an opened name that is no root** *(#1178, deferred here
  2026-10-19).* What a node resolved is one startup line and nowhere else, so `KDR_LOCAL_FILE_CLIENT_ROOTS=aproved`
  opens nothing without a sign, and a node started without the variable refuses its clients' local refs, which
  looks like an intermittent fault. An `operator` endpoint beside `/operator/cache/state` (`LocalFileRoots.describe()`
  is most of it), with how many refs name each root, and a boot-check warning -- never a refusal -- for an opened
  name that matches no root and a placed root that is absent. Trigger: the first deployment that declares roots.
- **Fence the deployment's data where its configuration puts it** *(#1167, deferred here 2026-10-19).* A root may
  not be inside, or contain, the places holding the deployment's own data -- but only the file store's directory
  follows configuration; `private/`, `h2Database/` and `logs/` are fenced at their defaults, so an H2 file placed by
  `fileH2(filePath = …)` or logs written elsewhere are not. (The image sniff still stands between a path and a
  file, so this is the fence saying less than it might, not a way to read a database.) Ask each owner where its data
  is -- the SQL layer for every file-backed database, the logging setup for its directory -- in
  `LocalFileRoots.reservedPlaces`. Trigger: a deployment that declares roots and moves its database or its logs.

## When a client wants to try its branding before it goes live

A client's favicon and brand mark (issue #1130) are web assets: not configuration, so live for everybody the moment
they are uploaded, with no draft and nothing a sandbox can show.

- **Name a file ref as a slot** *(#1187, deferred here 2026-10-19).* The client's definition names which of its
  file refs is its favicon and which its brand mark, so branding is drafted in the sandbox and published with the
  copy beside it, and a template's can be inherited. First decide whether it is wanted at all -- two slots that take
  effect at once are simple -- and then whether the slots stay as a fallback or are migrated and the Web assets
  section goes; two mechanisms for one thing is the worse end state. A slot's tighter limit (a favicon is 256 KB, a
  ref 2 MB) is held when a ref is named as one. Trigger: a client asks to preview a new logo, or to change its
  branding with its copy.

## When a client needs to show its people a file that is not an image

A file ref (issues #1153, #1154) is an image, told by its bytes, and that rule is what makes a ref safe to serve
from the app's own origin under a root that asks for no sign-in -- and, for a local file, what keeps a path from
being pointed at a key or a database.

- **File types other than images, PDF first** *(#1166, deferred here 2026-10-19).* A declared list of kinds, an
  extension of `WebImageKind`: how each is recognised, the type it is served as, inline or download, its own size
  limit. A PDF served only as a download under a policy that lets it run nothing. Decided per source: the store's
  list may grow while local files stay images only, since there the question is what a path may be made to
  disclose. Tests against files dressed as each kind that are not. Not HTML or anything scripted. Trigger: a client
  wants its copy to link to a document (a form, a policy, a brochure).

## When a client's images have to move into the file store in bulk

An image becomes a client's ref one upload at a time, or by configuration naming it where it is on a node's disk,
which every node must then hold.

- **Import a directory of images as a client's refs** *(#1165, deferred here 2026-10-19).* A `kdr-run` script in
  `com.dynamicruntime.script` taking a client, a directory and a naming rule: each image goes the upload's way
  (`ClientFileRefEdit.upload`, the image check, the size limit), with a dry run by default, and a mode that
  **promotes** a client's local refs into the store, rewriting `{source: local, root, path}` to `{source: store,
  hash}` (a stored override, for a ref in source configuration). To decide: one revision through one trial or
  resumable; refusing a batch that would take the client past its 200 refs before a byte is stored; live or draft
  for a client with a sandbox. Trigger: a client moved off local files, or a batch of pictures prepared outside the
  app.

## When an env-authed administrator on a public test deployment needs an invitation to reach the person invited

On a public test deployment (issue #1150) mail follows the **caller**: an env-authed caller's mail is captured for
read-back, so an administrator's invitation to an outside address is never delivered, and they pass the link on.

- **Capture by recipient domain** *(#1150, deferred here 2026-10-13).* Cedar's answer: designated domains are
  internal capture, so an env-authed caller's mail to anyone else is delivered for real while test users can still
  be made quickly at a test domain. The domains would be the reserved names only -- `example.com`, `example.org`,
  `example.net`, and anything under `.test` or `.invalid` (RFC 2606) -- never `test.com`, which Cedar used and
  is a real, registered domain. The decision is `MailService.deliveryFor`, which is where it would change.

## When a deployment wants a flavor on because of its environment rather than by name

Deployment flavors (#1218) are active only when `KDR_DEPLOY_FLAVORS` names them.

- **Default rules on a flavor's declaration** *(from #1218).* `FlavorDef` gains a rule over the instance config
  (environment, instance name, another setting), asked once while flavors resolve and only when
  `KDR_DEPLOY_FLAVORS` is unset, with each flavor it turns on logged with its reason. The rule belongs on the
  declaration rather than in each contributing component, so every component agrees on whether the flavor is on.
  Design: `kdr-design/thoughts-on-flavors.md` (private `sampwhite/Actions`), "Default Variations of Config".

## When the product is internationalized

The deployment's official day start, `serverTimeZone` (the kernel's `DateUtil.kt`), is a hardwired UTC-8. It is a
fixed offset on purpose: it never moves for daylight saving, so a day is always 24 hours and a contract written
against it is unambiguous. Since #1115 a client may name its own IANA zone, and `KDR_DISPLAY_TIME_ZONE` sets where a
client naming none is shown; neither moves the day start.

- **Make the day start configurable** *(from #1115).* An environment variable naming the offset (still a fixed
  offset, never an IANA zone), defaulting to `-08:00`. The kernel's date helpers read it as a top-level value with
  no context, so it would be set once at boot. The webapp works out its fallback zone from the same kernel value
  (`deploymentZone`), and would be told it by the shell config instead -- as would the report history, whose days
  are the day start's (`ReportHistory.dayOf`, `ReportSnapshot.day` in the webapp).

## When mail volume from a public sign-in becomes a cost or a reputation problem

A public test deployment with `KDR_PUBLIC_SIGN_IN=realMail` (#1150), such as the reference instance (#1242), mails a
code to any address typed into its sign-in form. `AuthRateLimiter` bounds it per source address (20 per 15 minutes)
and per mail address (5 per hour), but nothing bounds the total.

- **A ceiling on sends across all callers** *(from #1242).* A per-node cap on codes mailed per hour or day, refusing
  further sign-ins with a plain "try again later" once reached, so a distributed flood costs a bounded amount and
  cannot damage the sending domain's reputation. In memory like the existing limiter, since a lightweight node is
  one node.

## When the deploy commands need a dependency the server should not carry

`kdr-deploy` (#1243), and `kdr-node` beside it (#1244), live in the `config` module's `com.dynamicruntime.script.deploy`
package, beside the other workspace commands, because they shell out to the system `ssh` and `rsync` and so add
nothing to the server's classpath.

- **A module of their own** *(from #1243).* Split them out the day they need a library the application has no use for
  (an SSH client, a cloud provider's SDK), so it stays out of every release. That module also needs its own way onto
  `kdr-run`'s classpath, since `kdr-run` launches from the `launch` project's pathing jar.

## When a client wants to start its sandbox over

A client's sandbox ([`docs/design/sandboxes.md`](../design/sandboxes.md)) keeps users and data of its own, under the
sandbox's client id. Removing the sandbox keeps them, and they come back when it is added again
(`ClientSandboxEdit`); nothing clears them.

- **Clear a sandbox's data** *(from #925).* An operator or client administrator action that removes a sandbox's
  users and forms, or resets the sandbox to empty, so a client that has finished one round of trying a change can
  begin the next with nothing left over. #925 deferred it until somebody wants it.

## When batch editing of form documents becomes common

`POST /gedra/patch` admits every target before it writes, then applies one transaction per target. A refusal in the
apply phase (a trait lock, a visibility gate, a stale `entryId`, a failed `underLock` check, or validation) leaves the
earlier targets committed; #1307 makes such a refusal say which target it was and what landed. Today only direct API
callers send more than one target: the webapp's form editor (`GedraForms.kt`) and workflow save (`WorkflowSave.kt`)
each send one.

- **Refuse a multi-target patch whole, in the common case** *(beside #1307, deferred here 2026-10-20).* Run the
  apply-phase checks, everything that can fail, once before the first transaction as well as again inside each
  target's transaction, so a patch that would be refused is refused with nothing written. It is Cedar's practice:
  there, atomicity was to avoid confusing the user, not to prevent bad data. One form's traits being locked and
  another's not was common, and failing the whole edit was cleaner than a partial one. The guarantee is best-effort,
  since data can change between the pre-check and the transaction, which is acceptable because a batch edit is a
  convenience and the user can retry.
