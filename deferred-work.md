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
  e.g. `// deferred: see deferred-work.md#when-a-deployment-serves-real-clients`, so it is found while working
  on the relevant code, not only by reading top to bottom.
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

- **Google sign-in: throttle JWKS refetches** *(#157 review).* `GoogleJwksKeySource` refetches Google's signing
  keys on every `kid` cache miss, under a global lock — so a burst of tokens carrying bogus key ids (bounded
  per-IP by the login rate limit, but not across IPs) can keep it refetching and serialize real Google logins.
  Add a minimum refetch interval so a miss shortly after a fetch fails fast instead of re-fetching.

## When the runtime supports clients

The point at which configuration can be scoped to a client rather than only to the deployment.

- **Per-client configuration** *(from #97 §6 and #155).* Let values currently resolved per-deployment vary per
  client. Known candidates: the error-display / obfuscation policy (`obfuscateSensitiveErrors`), the
  frontend idle-bump interval (`idleBumpIntervalMs`), and the login-cookie timeout period.

## When a deployment has a second client

The point at which "every client" stops meaning "the only client", so a grant that reads as harmless today
starts handing out reach over somebody else's data.

- **Auto-admin's scope should narrow before production** *(from #225, revised by #352).* The rule grants
  `admin`, `operator` and `allClients` to a no-`+` address on a controlled domain — the configured
  `KDR_ADMIN_EMAIL_DOMAIN`, or `example.com` outside production. #352 settled two thirds of the original item
  and left this third. It **no longer re-applies on every login**, so the grant is a statement about how an
  account was provisioned rather than a standing property of an address; and it keeps `allClients`, because
  dropping it would leave a fresh deployment unable to reach its own admin surface by any route but the
  `GrantRole` script. What remains is production: the design has `example.com` not working there at all, and
  only a subset of admin-domain addresses holding `allClients`. Neither is built, and neither can be tested
  against anything real until there is a production deployment to narrow — which is the trigger.

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

## When the logging integration is built out (structured / OpenSearch sinks)

The point at which logs become searchable fields — the moment a per-browser tracing id starts to look worth
logging.

- **A `deviceId` in logs is tempting here, but gated on cookie consent** *(cedar lesson).* Logging a `deviceId`
  that traces one browser across anonymous browsing and repeated login/logout was a high-value search key in
  cedar. It requires minting the `deviceId` cookie on the first (anonymous) request — and cedar found the
  cookie-privacy/consent question a big enough deal to reshape how cookies were set. Today `deviceId` is
  deliberately **login-gated** (minted only at login, in `RequestService.checkAddAuthCookies`); keep it that way
  until the cookie-consent story exists (see *When a deployment serves real clients*). If revived: split minting
  (unconditional, early) from `recordDevice`/auth-cookie writing (login-gated), give the cookie its own
  lifetime, and never `Set-Cookie` on the immutable-cached static assets (#137).

## When user-scoped content is stored

The point at which a user owns something with structure — a document, workflow state, a part-written form — so
that "this user's data is broken" becomes a thing that can be true.

**This trigger has fired** (#310: a user now owns form documents). The item below is therefore actionable and
wants promoting to an issue rather than sitting here; it is left in place only until somebody decides whether
it is worth doing now, since the file's own rule is that anything ready lives in the tracker.

- **Trigger keywords in email addresses, to make a user fail on demand** *(cedar practice; discussed under
  #227).* On a backend that permits it (`isTestInstance` to begin with), encode keywords into a user's email
  address so that logging in as them injects a chosen failure. The point is **contrast**: run the same flow as
  a good user and as a bad one and diff the behavior, rather than reasoning about an error path in isolation.
  It earns its keep specifically on failures *partway through stored content*, which is why it waits for that
  content to exist — today the interesting per-user variation is the privilege rung, and `becomeUser`'s
  `level` already covers that.

  Two properties are why this form was preferred over a flag or a header, and both should survive into
  whatever gets built: an address is the **first thing you look at** when inspecting a login, and it is a
  **full-text search target** in logs (test instances log addresses where a real one would not). A **fault**
  that travels with the identity beats a per-request switch when the question spans a whole session.

  This said *persona* until `client-definition.md` claimed that word for a formal concept — a named thing
  mapping to roles and capabilities, carried after a `%` in the same part of an email address this item wants
  to write into. Two meanings in one place is the collision; the formal one keeps the word, and *fault* is not
  a coinage but the vocabulary #227 already uses for deliberate failure.

  Note what it is *not* for. The frontend fault route in #227 needs none of it, because making the browser
  throw requires no identity — keep the two separate. This one is about the backend misbehaving for a
  particular user, and its natural injection points are the handlers that read and write that user's content.

## When gedra data is held in a memory cache

The point at which a node keeps the gedras it serves in memory rather than querying for each one — anticipated
in #310 as arriving "not too long from now", and noticeable because somebody builds it.

- **Let a gedra id cache miss mean "no such gedra"** *(from #280, #310).* `InternCache`'s second property is
  that where a cache holds *every* extant value, a miss answers an existence question without touching the
  database. `GedraService.gedraIds` cannot claim it: ids are interned as gedras are created and read, so the
  cache is a subset and a miss means only "not seen here yet". Everything therefore goes through `readId`,
  which parses on a miss and asserts nothing about existence. A memory cache of the data is what would make
  the population exhaustive per client, and whoever builds it should say so on that field — the code carries
  the note at the point where somebody would otherwise assume the stronger reading.

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
  surface honors it, which is why the form draws no control for such a field.

- **Strip the default branch from a strictly-read union** *(from #301).* A manufactured entry union always
  declares a default branch, so an unrecognized `traitId` can pass through where that is the right answer.
  Whether a *reader* uses it is a policy the reader applies — `SchOpts.allowUnknownVariant` — and a strictly
  read endpoint therefore honors less than the document it publishes says. Its exported schema has to have
  the `discriminator.defaultMapping` and the default branch removed, or we publish a schema we do not honor
  and a client's own tooling calls a payload valid that we return a 400 for. It is a second projection at the
  boundary that already has to project `g-derived` out of input shapes, not a new mechanism.

- **The rest of the export contract is already written down** in
  [`gedra-entry.md`](gedra-entry.md) — `g-` keywords stripped by default with a small transformer table,
  `discriminator.mapping` synthesized from the branches' `const` values, and the governing rule that an
  inexact conversion must be *stricter* than us rather than looser. Read it before starting: the decisions are
  made, only the code is missing. Note one known gap recorded there — `g-primaryKey` has no standard
  equivalent, so stripping it makes the export looser, which is accepted because the constraint governs our
  own stored entries rather than anything a third party validates.

## When fragment copy computes with its data

Today a fragment *substitutes* values. The trigger fires the first time one **compares or calculates** with
them — `${minutes > 5}`, `${count * price}` — because that is when a value of the wrong type becomes possible.
Until then every substitution is "print this", which no type can get wrong.

- **Type-aware fragment checking** *(from #305; the half #314 left undone).* `/operator/fragments/check`
  reports the paths an entry requires and which ones a supplied map lacks, but it is a **presence** check: it
  never evaluates, so `${minutes > 5}` against a `"15"` passes it and still fails at render, since a string is
  never a number (#293). Catching that means evaluating against realistic values, which means first deciding
  where those come from — supplied by the caller at the endpoint, or declared beside the fragment so a boot
  check can run with nobody present. Deferred because the shipped copy reads five paths in total and none of
  them compute, so a check with nothing to find would be tested against invented cases only.

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

`onHashChange` (in `HashRoute.kt`) registers with `window.addEventListener` and nothing ever removes it. Three
pages now do this — `Home`, `EndpointCatalog` and, since #324, `Users` — and every one of them mounts and
unmounts on each navigation to and from it, so what accumulates over a session is one live closure per visit,
each still running on every later hash change.

- **Cleanup for the `hashchange` listeners.** It is currently a leak rather than a defect: the handlers call
  state setters of unmounted components, which React ignores, so nothing observable happens. The trigger fires
  when something does — a handler with an effect beyond `setState`, a page whose re-derivation is expensive
  enough to feel N times over, or a heap that grows across a long session. Deferred because the fix is not the
  usual one-liner: this wrapper version's effect body is a **cancellable coroutine**
  (`useEffectOnce { … }` takes `suspend CoroutineScope.() -> Unit`), so cleanup means `try`/`finally` around
  `awaitCancellation()` rather than a `cleanup { }` block, and nothing in `webapp` uses that idiom yet.
  Introducing it for a leak with no symptom would make three working effects the place it gets learned.
  `App`'s listener is deliberately exempt either way — the root never unmounts.

## When an edge fronts a backend that allows anonymous access

Today an edge challenges **every** proxied request (#419), which is right while it fronts internal
deployments: everything behind it is for operators, and a caller who is not signed in has no business there.
That stops being right the moment a public-facing application sits behind one — a visitor who is *supposed* to
be anonymous is redirected to a Google sign-in they cannot complete and will not want.

The trigger is observable: the first route added for a backend whose application serves the public.

- **Per-route anonymous access.** `EdgeProxyHandler` decides to challenge from the context root alone, so the
  answer is the same for every backend. The route entry of `kd2-design/thoughts-edge-server.md` §7 already
  anticipates this with an `allowNoEnvAuth` flag — a root not carrying it is challenged, one carrying it is
  forwarded as-is. Needs the route table, so it lands with that rather than before it.

- **Static assets should not be challenged at all** *(Sam, during the #419 discussion).* A page's CSS, JS and
  icons are not a place to make a login decision: an unauthenticated asset request either belongs to a page the
  caller was already allowed to load, or to one they were not, and the page itself is where that was decided.
  Challenging them means a signed-out visitor gets a sign-in page where a stylesheet belonged. This is
  *survivable* today rather than fixed — since #419 a background request is refused with a 401 the frontend
  acts on, instead of a redirect it tried to parse as JSON — so the symptom is a clean refusal rather than a
  broken page, and that is the whole of why it can wait.
