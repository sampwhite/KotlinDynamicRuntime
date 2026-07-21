# Deferred work

Things we have deliberately decided **not** to do yet, each grouped under the condition that should make us
revisit it. This is **not** a backlog. The GitHub issue tracker is the action queue — every open issue is
meant to be acted on soon — and deferred work lives here instead, so the tracker never fills with issues
everyone has silently agreed to ignore.

## How this file works

- **Grouped by trigger.** Each `##` section is an observable condition; the items under it are what to do once
  that condition holds. So when a trigger fires you read one section and see everything it unblocks.
- **Triggers, not wishes.** A trigger is something you will *notice* — "when a second deployment exists," "when
  we support accounts." "When the app is mature" is not a trigger; it never arrives.
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

## When the runtime supports accounts

The point at which configuration can be scoped to a client account rather than only to the deployment.

- **Per-account configuration** *(from #97 §6 and #155).* Let values currently resolved per-deployment vary per
  client account. Known candidates: the error-display / obfuscation policy (`obfuscateSensitiveErrors`), the
  frontend idle-bump interval (`idleBumpIntervalMs`), and the login-cookie timeout period.
