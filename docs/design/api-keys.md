# API keys

An **API key** lets a program call the API as a particular user, without anyone signing in. The program sends the key
in a header, and the request is answered as the key's user, with that user's roles as they are now. This document says
what a key is, how it is checked, what it may and may not do, how long it lasts, and what is planned on top of it.

**Status:** keys are built (#1402). Exchanging a key for a short-lived access token is planned (#1403). What is not
built is listed at the end.

## What a key is

A key looks like this:

```
kdr_<keyId>_<secret>
```

- **`kdr_`** marks it as one of ours, so a log line or a secret scanner can recognize a leaked key.
- **`keyId`** is 12 characters of base64url from 9 random bytes. It is public: it names the key wherever the key is
  shown (`kdr_<keyId>…`), and it is the row's primary key.
- **`secret`** is 43 characters of base64url from 32 random bytes (256 bits) from `SecureRandom`.

A program sends it as:

```
Authorization: Bearer kdr_<keyId>_<secret>
```

A key belongs to one **user**, its owner, and acts as that user and never above them. Whoever made it is recorded
separately (`createdBy`): the owner, or an administrator who made it for them.

## Only a hash is kept

The `AuthApiKeys` table (the `auth` topic, `forUsers()`) stores the SHA-256 of the secret, in hex, and never the secret
itself. So the whole key exists in exactly one place after it is made: the answer to the create, which the page shows
once in a dialog and then forgets. Nobody, the server included, can show it again.

SHA-256 rather than a slow password hash is deliberate. A slow hash protects a secret a person chose, which may be
guessable; a 256-bit random secret is not, and a slow hash on every request would cost time for no gain. The hashes are
compared in constant time (`constantTimeEquals`).

## How a request is authenticated

`RequestService.extractSessionAuth` looks for a bearer token beginning `kdr_`:

- **Another scheme is ignored**, so a site behind HTTP basic authentication, which sends `Basic` on every request,
  works as before. So is a bearer token that is not ours.
- **A key found replaces the cookie.** The request is authenticated by the key and its cookies are not read: a program
  sends one or the other.
- **The key must be valid.** It must exist, its hash must match, it must be neither revoked nor expired
  (`cxt.now() <= expiresAt`), and its owner must be enabled and in a client this node carries.
- **Anything else is one 401,** "The API key is not valid.", whatever was wrong, since saying which would tell a
  guesser how close they came. The hash is compared before the other checks, so a revoked key answers no faster than
  a wrong one.
- **Failures are rate-limited.** More than 30 from one address in 15 minutes are refused with a 429 until the window
  passes (`AuthRateLimiter`).

A valid key binds the owner's acting profile (`toActingProfile`) and records the key on the context (`KdrCxt.apiKey`).
Because the profile is row-backed, the section gate re-reads the owner's roles as it does for a session
(`refreshActingRoles`): a role taken from the owner is gone from their keys on the next request, and a disabled owner's
keys stop working.

Every keyed request looks its key up, so the table is held in memory (`kdr-table-cache`). It is small, at most 10 live
keys per user, and read one row at a time by id. A revoked key is disabled, which makes it a tombstone in the cache and
a disabled row to the SQL fallback, so a revocation bites on every node within the cache's state-row throttle.

The last use is recorded to within an hour (`lastUsedAt`): a use writes the stamp only when the stamp is an hour old,
and leaves `updatedAt` alone, since it is bookkeeping rather than a change to the key.

## What a key may do

- **A key acts as its owner.** It reaches what the owner may reach, and sees what the owner may see.
- **A read-only key** (`access: read`) may make GET requests only. Anything else is refused with a 403 before the
  section gate, so no section can forget to.
- **A full key** (`access: full`) may do what its owner may, except what follows.
- **No key reaches a session-only endpoint.** An endpoint declared `sessionOnly` is refused to any key with a 403.
  These are what a key must not do even when its owner could:
  - make, list, extend or revoke keys, so a leaked key cannot mint more or outlast what its maker gave it;
  - sign in, register, claim an account, or accept an invitation;
  - switch users or clients, open or leave a sandbox, join a demo, or create a self-serve client;
  - log out, or change a password.
- **A keyed request sets no cookie**: it has no session to start, renew or end, and records no device.
- **A key confers no env auth.** On a public test deployment, env auth comes from an administrator's sign-in, and a
  program holding their key is not them.

## Who makes keys

- **A person, for themselves**, on Profile, through the `user` section: `/user/self/apiKeys` and
  `/user/self/apiKey/{create,extend,revoke}`.
- **An administrator, for a user in their scope**, from the Users editor, through the `clientAdmin` section:
  `/clientAdmin/apiKeys` and `/clientAdmin/apiKey/{create,extend,revoke}`.
  - A user outside the administrator's scope reads as absent (404).
  - An organization-confined administrator sees and acts on the keys of their organization's users only. The table
    has no organization column, so the scope's organization is applied to each owner after the query, by the same
    per-row predicate a by-id read of a user asks (`ReadScope.admitsUserRow`).
  - **A key may not act above its maker.** Making a key for someone is acting as them, since the maker sees the secret.
    So every role the owner holds must be one the administrator holds too, as granting a role asks.
  - A `public` self-administrator is refused the administrator's surface, and manages their own keys on Profile.

A sandbox's users cannot hold keys: they take their standing from the person's users outside the sandbox, which a
program with nobody signed in could not ask about.

## How long a key lasts

- **A new key** lasts `KDR_API_KEY_DAYS` days (365 by default), or fewer if its maker asks.
- **No expiry may be more than** `KDR_API_KEY_MAX_DAYS` days from today (730 by default), when a key is made or
  extended. So no key is made to last indefinitely in one step.
- **An extension** is by days or by calendar months, exactly one. It is added to the key's expiry, or to today once
  that has passed, so an expired key extended by a month works for a month from today.
- **A revoked key** cannot be extended. An expired one can.
- **A user holds at most 10 keys** that are neither revoked nor expired.

The rules are the kernel's (`ApiKeyRules`, with `Instant.addMonths`, calendar months in UTC), so the page previewing an
extension and the server making it land on the same moment.

## In the webapp

- **Profile** has an "API keys" section with the person's own keys. It is not shown in a sandbox.
- **The Users editor** has the same section for the open user, managed as their administrator. It acts at once, unlike
  the editor's fields, which wait for OK.
- **The API keys page** (`/wa/api-keys`, "API keys" in the menu for a client's administrators) lists every key the
  caller administers, soonest to expire first, filtered by status, each owner linked to the Users editor.

Each listing shows a key's name, access, when it was made and by whom, when it expires and was last used, and its status
(works, expires soon, expired, revoked), with **Extend…** and **Revoke** on each key not revoked. A new key opens in a
dialog with a copy button and a `curl` command to try it, saying it is the only time it is shown. The key is held only in
the component's state, never in the address or browser storage.

## Next: access tokens (#1403)

A key is a long-lived secret. Another service that verifies callers, such as an identity service, should not hold it or
call back to check it. The plan follows the OAuth 2.0 **client-credentials** grant (RFC 6749 §4.4):

- **The exchange.** A program sends its key to `POST /auth/token` with `grant_type=client_credentials`. It gets back a
  signed, short-lived access token: `{access_token, token_type: "Bearer", expires_in, scope}`.
- **The token** is a JWT (RFC 9068) signed with ES256, using the JDK's `java.security`.
  - Its claims are `iss`, `sub` (the user id), `aud`, `client`, `scope` (`read` or `full`), `iat`, `exp`, `jti`, and the
    key's id.
  - It lasts `KDR_ACCESS_TOKEN_HOURS` hours (24 by default).
- **Refreshing** is asking again with the key. Refresh tokens belong to user-delegated flows, which are not planned.
- **Verifying.** The signing keys are published at `GET /auth/jwks`, so any service can verify a token with the public
  set alone. The same extractor accepts a token as a bearer, with the key's rules.
- **Revocation.** Revoking a key stops new tokens, but outstanding ones last until `exp`. That is why common practice
  keeps access tokens to an hour or less, and why the default is configurable.

## Not built

These are recorded in [`deferred-work.md`](../development/deferred-work.md#when-a-program-or-another-service-needs-more-than-an-api-key-gives-it),
each under the trigger that would make it worth doing:

- token introspection (RFC 7662) and revocation (RFC 7009) endpoints;
- refresh tokens with rotation, the authorization-code flow with PKCE for third-party apps, and token exchange
  (RFC 8693) for delegation between identity services;
- service accounts: keys that belong to no person's user;
- scopes finer than read and full;
- per-key rate limits, IP allowlists, a checksum inside the key, rotation with an overlap window, and a mail before a
  key expires.
