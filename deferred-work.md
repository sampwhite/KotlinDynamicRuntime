# Deferred work

Things we have deliberately decided **not** to do yet, each paired with the condition that should make us
revisit it. This is **not** a backlog. The GitHub issue tracker is the action queue — every open issue is
meant to be acted on soon — and deferred work lives here instead, so the tracker never fills with issues
everyone has silently agreed to ignore.

## How this file works

- **Each entry is a trigger, not a wish.** Phrase it *When `<observable condition>` → `<what to do>`*, so this
  file reads as a set of tripwires you notice in passing, not a guilt-list you scroll past. "When the app is
  mature" is not a trigger; "when a second deployment exists" is.
- **Promote and delete.** The moment a trigger fires — or an item simply becomes actionable — move it to a real
  issue and remove it here. This file only ever holds *not-yet-actionable* items; anything ready lives in the
  tracker.
- **Reachable from context.** Link into an entry by its heading anchor from the code or a closing issue
  comment, e.g. `// deferred: see deferred-work.md#5xx-wire-redaction`, so an entry is found while working on
  the relevant code, not only by reading top to bottom.
- **Keep entries short.** The trigger, a line or two of what and why, and links back to the source. Enough to
  reconstruct the decision — not a design doc.

## Entries

### 5xx wire redaction

*Deferred from #97 §6.* **When** a real deployment serves non-sensitive 5xx responses to untrusted clients →
redact internal 5xx message bodies server-side, not only at the `sensitive` flag. Today
`RequestHandler.handleException` obfuscates only errors explicitly flagged `sensitive`, and only when the
deployment obfuscates; a generic 500 (e.g. a database failure) still carries its cause chain in the response
body. The frontend hides it from the user (#111), but it is on the wire. Add a status/config-driven redaction
policy on top of the existing `obfuscateSensitiveErrors` resolver.

### Per-account error-display policy

*Deferred from #97 §6.* **When** the runtime gains per-account configuration (multi-tenant settings, an account
config surface) → let the error-obfuscation policy vary by client account, not only by deployment. Today
`obfuscateSensitiveErrors` is resolved from deployment config / env / environment alone; §6 wanted it
configurable per account as well.
