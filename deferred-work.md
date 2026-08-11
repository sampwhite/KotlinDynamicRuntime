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

- **Auto-admin should grant the level, not global scope** *(from #225).* `AdminRules.autoAdminRoles` grants
  `admin` **and** `allClients` to every address at `KDR_ADMIN_EMAIL_DOMAIN`. That is right for a single-client
  deployment and solves a real chicken-and-egg problem — nobody holds `allClients` to begin with, and
  anti-escalation stops an administrator granting reach they lack, so without it a fresh deployment could only
  reach its own admin surface through the `GrantRole` script. On a multi-client deployment it is wrong:
  everyone at the domain becomes a *global* administrator. The blocker was that a client-scoped administrator
  had nowhere to go; the `userAdmin` section (#231) removed it, so the change is now just a decision — grant
  the level, and make full scope a deliberate act. Note what has to come with it: something must still be able
  to mint the first `allClients` holder, which is what the script is for.

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

- **Trigger keywords in email addresses, to make a user fail on demand** *(cedar practice; discussed under
  #227).* On a backend that permits it (`isTestInstance` to begin with), encode keywords into a user's email
  address so that logging in as them injects a chosen failure. The point is **contrast**: run the same flow as
  a good user and as a bad one and diff the behavior, rather than reasoning about an error path in isolation.
  It earns its keep specifically on failures *partway through stored content*, which is why it waits for that
  content to exist — today the interesting per-user variation is the privilege rung, and `becomeUser`'s
  `level` already covers that.

  Two properties are why this form was preferred over a flag or a header, and both should survive into
  whatever gets built: an address is the **first thing you look at** when inspecting a login, and it is a
  **full-text search target** in logs (test instances log addresses where a real one would not). A persona
  that travels with the identity beats a per-request switch when the question spans a whole session.

  Note what it is *not* for. The frontend fault route in #227 needs none of it, because making the browser
  throw requires no identity — keep the two separate. This one is about the backend misbehaving for a
  particular user, and its natural injection points are the handlers that read and write that user's content.
