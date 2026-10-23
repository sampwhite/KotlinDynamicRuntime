# Client definition

How a client is described, what it owns, and what a deployment does with one. Written before any of it existed,
as something to design against and argue with.

It is a companion to [`docs/design/gedra/config-and-data.md`](gedra/config-and-data.md), which describes how a gedra is
identified and how a deployment can be split by client — the mechanism this document gives a definition to —
and to the patch, in [`docs/design/gedra/entry.md`](gedra/entry.md#changing-a-gedra-the-patch), whose endpoints were
the first to need a client's own view of a schema.

**Much of it is now built.** Issue #340 was the planning issue. Clients are declared, validated and found
(`ClientDef`, `ClientService`, issue #343); each present client gets its own schema variant (#356) and its own
copies of the client-shaped endpoints (#387); and a client -- its definition included -- can be stored in the
database, loaded, reloaded and protected at runtime (#611 and its slices). *What it means, and what it takes* is
the plan as it was written before any of that, kept for its reasoning.

## How to read it

**The specification** states what a client definition holds. It began as a statement written to drive
decisions rather than as a complete declaration, and is grown as those decisions settle: **anything settled is
folded back into it**, so it stays the single answer to "what does a client have". *Decisions* keeps the
reasoning rather than repeating the conclusion.

**What it means, and what it takes** works through the consequences: what already exists to build on, what can
be built now, and what has to wait.

**Decisions** records what has been settled, with the reasoning that settled it. Several were reversals, and
where a decision changed the reason it changed is kept — a later reader who does not know it will otherwise
re-derive the discarded answer.

**Open questions** is the only place to look for status. It says which answers are still wanted and which of
them block the first slice of work.

---

# The specification

A client definition is defined in a GedraConfig object with a GedraId that has the clientId embedded in it.
One immediate implication is that a GedraConfig cannot define more than one client, however, it can
define all the normal things that a client can define so a client can be a complete package if it so wishes.
But, all `GedraConfig` that share the same client are treated as part of the whole, but duplication, such
as two client definitions will cause a load time error.

For the client configuration itself, the client has the following attributes.

* `name` - The name to be presented to users as the name of the client.
* `clientId` - A unique key identifying the client. It must use a restricted character set
  so that it would be a legal JavaScript variable name and only have ASCII characters. It is embedded in
  the GedraId. It is unique **without regard to case** (issue #1106): `Acme` beside `acme` would be two clients
  that read as one to a person, so a create is refused (409) for an id another client holds up to case, and the
  load drops the later of two such definitions -- a reload's own client being the later -- as it does any other
  refused definition. How strictly follows where the definition came from (issue #839): one in source code is
  strict outside production, and one in stored configuration is forgiven, dropped with a warning, everywhere but
  the unit tests.
* `description` - An optional internal description for humans to understand who, what, or why for the client.
* `usageType` - An enum list of usage purposes for the client which may grow over time. Some new behaviors
  or features may condition themselves on the usage type to control visibility of changes. 
  nly usage type of `production` in the prod deployment environment will have
  any strong guarantee that we will not potentially kill the entire client and start over.
  The current list is
  * `production` - Client is being actively used by users who care about the stability of the application.
  * `test` - The client is a client that exists only to run tests against. A test client was to allow
    certain test fixture endpoints to be available, even in prod. *Not built that way:* a test endpoint is fenced
    by the deployment instead, and exists only on a test instance (`forTestingOnly`, issues #125 and #1149), so no
    client's type opens one in prod. See "Usage types, and what a caller is shown" below.
  * `dev` - The client exists for development work, some real users may try out functionaly, but there is
    no active usage by external users in support of their business. However, the dev environment, when 
    deployed in prod, will attempt to keep to prod style behaviors.
  * `template` - The client exists to help generate client definitions. Such clients can have their resources
    referenced by any client in their own definition.
  * `demo` - The client exists to demo functionality, usually for sales. It will usually allow
    access to variant behaviors, such as relaxed security, and convenience endpoints for bulk-load delete
    of recently added content.
* `audience` - Whose client this is: `internal` (ours -- staff, batch activity, the deployment's own work) or
  `customer` (somebody else's). Orthogonal to `usageType`, which says what a client is *for*: an internal client
  can be `production`, `dev` or `test`. It is not merely a label: it relaxes the functional-group restriction
  below, which is the only thing that reads it today. (It was also to decide what a caller is proactively shown,
  which is not built; see "Usage types, and what a caller is shown" below.) Because it gates a capability, **only
  the platform sets it** -- source code, or an `allClients` administrator -- and never a client's own
  administrator (issue #1108). The same reasoning limits a database-defined client to extending template clients.
* `webResourcesId` - An ID that identifies a package of resources that can have things like a 
  custom favicon (plus other related images), general look and feel images, and CSS file. Note, two different
  clients can share a `webResourceId`. The mechanics of where these packages of resources are in source
  code, or in the file store, or in the database will be designed over time. At some future point,
  we will integrate with a CDN, and the `webResourceId` can potentially define where to find the resources in the CDN.
  *Not built.* What is built (issue #1130) is narrower and keyed by the client's own id: its administrator
  uploads a **favicon** and a **brand mark** from the Clients page, kept in the file store and shown to the
  client's signed-in people in place of the deployment's. They are not configuration -- no draft, no publish --
  and a sandbox shows its parent's. Beside them (issue #1153) a client's stored configuration holds **file refs**:
  named images of its own, kept in the file store by the hash of their bytes, and -- unlike the two above --
  drafted, previewed in the sandbox, and published with the rest of its configuration. A ref may also name an
  image already on the machine, under a root the deployment declares (issue #1154) -- how a client is shipped
  with artwork that an upload then overrides. The client's copy shows one as an image by naming it,
  `![Our logo](ref:logo)` (issue #1186), so a replaced image needs no edit to the copy. File refs, and how a
  template passes them on, are described in [`file-refs.md`](file-refs.md). A package two clients share is still
  this field's to become.
* `enabledEnvironments` - A list of environments where the client is enabled. Even if not enabled, it
  can still be referred to, but the client will otherwise be considered not to be present. 
  It defaults to the list {'unit', 'local', 'dev'}. It is an error if any environment besides `unit` and `local`
  is present and **both** `unit` and `local` are not also present. A client in active use must never be one that
  local development and the unit tests are locked out of.
  We do not need thorough test coverage for this attribute until we actually have real deployments. The
  environments that exist are `unit`, `local`, `dev`, `integration` and `prod`; `staging` is anticipated and
  does not exist yet.
  This is the **only per-node** axis deciding which clients a deployment carries. A deployment focused on a
  subset of clients would therefore have to be its own environment. Two other conditions decide whether a client
  is present, and neither varies by node: a client the platform has `retired` is absent everywhere, and a sandbox
  is present only where its parent is and still asks for one.
* `preload` - Whether to practively load all computations done on freshly loaded cache for the client before the
  node says it is ready to receive requests. Not yet testable.
* `staticConfig` - If true, **in production** the client takes nothing from the database (issue #824): its
  definition is its source alone, implicitly published. A write of stored config for it is refused there, and
  stored config that exists is ignored at boot and on reload. Outside production it behaves like any client,
  which is where its configuration is edited before the changes are brought into its source component by PR
  and shipped. Only a source definition may set it.
* `extendsFromClientId` - A reference to another client. When done in source code, it can be any reference.
 When done as data in the database, it can only reference template clients. When this value is present
 when the client is loaded, it first clones in all the definitions from the other client and then applies
 any configuration in the current GedraConfig as extensions or overlay. This reference is usually done
 for three reasons. One, to build on a template to deduplicate the definitions of the clients. The other is
 to create a slightly variant client that previews new feature behavior that will eventually be put into
 the base client and formally released. The third, to create a test variant of a prod client for focused testing.
 If this value is present, it will also default the `webResourceId` to the `webResourceId` of the pulled in client,
 and the client includes the pulled-in client's `includedTraits` and `userLabels` ahead of its own. It will also
 pull in the same `organizations` once a client can declare them (it cannot yet; see below). Note, this key can only
 cross-reference to a client that is defined in source code and will pull in only the source code definition. Any
 database data overlays that may be present will be ignored. How the clone and the overlay combine is described
 under "Inheritance between clients" below.
* `domainPrefix` - If the client is given its own domain by prefixing a core domain by a prefix string, then
 this is that prefix. If a user acccesses the application through that domain, then the web resources
 identified by `webResourceId` should be applied to the anonymous view of the application and any
 registered users should be assigned to this client.
* `customDomain` - Similar to `domainPrefix` but applies to the entire hostname. Used when the client
 has configured their own domain name to use for entry into the application.
* `includedTraits` - The traits this client supports and takes exactly as they stand. A list holding trait ids
 and **group names**, where a group name carries a `#` (no trait id can). `#allGlobal` is the first group and is
 *functional* -- its membership is computed at load time from what is global rather than written down. A
 functional group is refused when `audience` is `customer` **and** `usageType` is `production` -- see the
 decision below for why both conditions.
 This list is a **minimum**, not a total: see the traits section below.
* `userLabels` - The user labels this client suggests (issue #786): what the admin console's label editor
 offers, and what a workflow function naming a label literally (`userHasLabel`) is checked against when the
 configuration loads -- so a misspelled label in a workflow is caught there rather than silently never matching.
 A suggestion, not a bound: any label may be applied to a user. Each label is written once and trimmed; a blank,
 padded or repeated one is refused.
* `organizations` - A complex definition of organizations associated with this client. *Not built.* What exists is
 narrower: a user may carry an organization (`org`), which narrows an administrator's reads to it
 (`ReadScope.ofOrg`), and which reports, sandbox access, and a customer root's client create all respect. A client
 declaring its organizations, and a client inheriting them, are what is still to come.
* `customerId` - The **customer** that owns this client (issue #1107), set only by the platform, like `audience`.
 A source client names a customer declared in source; a stored client may name either. Optional, since some clients
 belong to no customer; see "Customers" below.
* `testFeatures` - A free-form set of test and demo feature names (issue #599): a behavior asks whether its name is
 in the set, so adding one needs no enum change. It gates behavior, never schema, and is **honored only on a test
 instance** -- elsewhere it is stripped from the client the node carries -- so it can never switch a demo behavior
 on in production. It may be stored as data (issue #696); only the platform sets it.
* `sandbox` - Whether the client has a **sandbox** beside it (issues #925 and #928): a second client,
 `<parent>:sandbox`, that runs this client's latest configuration, drafts included, with users and data of its own.
 Its definition is derived from the parent's and never authored. What a sandbox runs, who may enter it, and how it
 changes editing and publishing are described in [`sandboxes.md`](sandboxes.md).
* `retired` - Whether the platform has retired the client (issue #1111): its data is kept, and the client is absent
 in every environment. Only the platform sets or clears it; see "Environments, availability and retirement" below.

**Customers** (issue #1107). A customer owns a set of clients, and its id is the **prefix** of the ids of the
clients created for it: customer `acme` owns the id `acme` itself and every `acme_<name>`, so two customers creating
clients can never compete for an id. A customer's id is ASCII letters and digits, starting with a letter -- it holds
no `_`, since the first `_` in a client id is where its prefix ends -- and is unique without regard to case. It also
names a **root** client (`rootClientId`), one of its own, whose administrators will act for the customer as a whole.

Only the platform declares a customer: in source code, with `defineCustomer` beside a client's definition, or in the
stored configuration of `hub` (the `kdr:customerDef` slot), which only an `allClients` administrator may change. A
stored configuration of any other client declaring one is refused at write and dropped at load. Core declares the
platform's own customer, `kdr`, whose root is `hub`, and `hub` and `public` belong to it. Some ids no customer may
take, case aside: the platform's own (`kdr`, `hub`, `public`, `house`, and the global client's `global`) and the
self-serve prefix, `free`. Core's `kdr` is the one declaration allowed its id.

**Clients with no customer** are a supported kind, and of two sorts:

- **Platform and test clients** -- the simulations' demonstration clients, and the clients tests and fixtures make.
  Their ids hold no `_`. Only cross-client privilege brings one into being: a test fixture (a simulation), code
  writing through the configuration service, or an `allClients` administrator's create or bundle write naming no
  customer.
- **Self-serve clients** (#1113), whose generated ids are `free_...`.

What a client is, is told by its link, never by its id: a customer's first client usually takes the customer's own
id (`hub`, `acme`), with no `_` in it either. Whatever is decided by a client having no customer must hold for both
sorts -- that it creates no clients, say -- while the free plan's quotas are keyed on being **self-serve**, not on
the missing link, so a demonstration or test client is never held to them.

- **The prefix is judged when a client comes into being**, and only then -- by whichever write brings it: the
  operator's create, a bundle write or an import defining a client this deployment does not have yet. Under a
  customer the id is the customer's own or `<customer>_<name>`; under none it may not fall under any customer's
  prefix, and an id holding `_` is otherwise a self-serve client's (`free_...`). After that the link is the
  authority: a client moved to another customer keeps its id.
- **A link is read when it is used** (`ClientService.customerOf`), and answers only for a customer that is there --
  and, for a source client, declared in source. The load reports a link naming no customer, or an id holding `_`
  under no customer's prefix; the client stands. A write's trial refuses whichever of these it would introduce.
- **Customers arrive and leave with `hub`'s configuration.** A customer new to a reload may not take the prefix of
  ids another customer's clients hold (a client linked to none may sit there, which is how one is adopted), and one
  a client still uses may not leave -- a prefix is permanent once a client uses it. Both are refused by the trial of
  the `hub` write that would do it.
- **A source customer is never redefined by stored configuration**: a stored customer of the same id, case aside,
  is the second definition -- strict outside production, and in production dropped, the source one standing.

- **A customer's root administrators make its other clients** (issue #1112), at `POST /clientAdmin/client/create`:
  an id `<customer>_<name>`, holding the creator's choices (its id, name, note, template, and sandbox) and what the
  root passes on (its customer, environments, usage type, audience, and web resources), with no configuration. The
  creator gets their own admin user there, returned rather than switched to -- `/user/self/switchClient` moves the
  session; a creator's user that could not be made is said in the result (`userFailure`), the client made all the
  same. Only the root's client-wide administrators make clients -- not one confined to an organization -- and not on
  the free plan, which makes none (nor on a plan name that is no plan, read as free). An id outside the customer's
  prefix is refused before anything looks it up (issue #1288), so the refusal is the same whether another customer's
  client holds it or nobody does: the create never says which of other customers' clients exist.
- **A person makes their own self-serve client** (issue #1113), at `POST /user/self/createClient`, from `public`:
  one per person, counted per identity rather than per user, since a `public` administrator makes more users of
  their own. The person names the client, and the system names its id, `free_<name><random>`. It has no customer, so
  it is on the free plan and makes no clients, which is why the limit is enforced on this one route. Its audience is
  `customer`; its usage type is `production` where the deployment runs in `prod`, and `dev` elsewhere; it is enabled
  where the deployment runs (and in `unit` and `local`); and it has a sandbox, so it runs only what is published.
  The creator gets their own admin user there, and the session moves to it. The one is recorded on the identity
  (`selfServeClient`) by a guarded write before the client is made, so two creates at once cannot both pass; a
  refused create gives it back, and once made it stays used, whatever becomes of the client. Moving the client to a
  customer later is the platform's to do: it keeps its id, and the link makes it the customer's.
- **A customer's first client can make the customer.** The operator's create takes `newCustomer` (a name, and
  optionally a description) instead of naming a customer: the customer's id is the client's, and the client is its
  root. It is three writes, each judged as any is -- the customer into `hub`'s `customers` configuration
  (`CustomerStore`), the client under it, then the customer's root -- across two clients' locks, so not one
  transaction: a client the write refuses takes its customer back with it, and a root that cannot be named is said
  in the result (`customerFailure`) rather than thrown, since the client was made.

**Plans** (issue #1109). A plan limits **content, never features**: what a client may hold, never what it may do.
A customer names its plan (`plan`, set by the platform; `unlimited` when it names none), and its clients are on it.
A self-serve `free_` client is on `free`. A platform or test client, which no customer owns, is on none and holds
what it likes. A sandbox is on its parent's plan and holds its own forms and users, counted apart from the parent's.

| The free plan holds | |
| --- | --- |
| Forms | 100 |
| Users | 20 |
| Workflows | 4, of at most 2 tasks each |
| Schemas | 10, of at most 10 attributes each |

- **What is counted.** A **schema** is a type the client declares as its own: a trait's data type, or one declared
  directly. A trait's generated entry type is not one, and neither is a narrowing of a global type, which takes
  away and holds nothing new. A type's **attributes** are its own properties, inline sub-objects included at any
  depth. A property that is only a `$ref` counts as one, and what the referenced type declares is that type's. A
  `g-extends` type counts only what it adds. A configuration a template's copy carries is the template's.
- **Where it is held to.** Workflows, tasks, schemas, and attributes are judged by a configuration write's trial,
  against the candidate configuration as a whole. Forms and users are counted when one is made, every form create
  and every user provisioning (a re-enable too). Two creates at once may both pass, a soft limit.
- **Going over refuses only what adds to it.** Nothing is deleted. A client already over its plan, because its
  customer moved to a smaller one, may still change and shrink what it has; a change is refused only where it takes
  a measure over the plan **and** past what the client holds.
- **Per client.** The limits are each client's own, not pooled across a customer's clients. How many clients a
  customer may have is the plan's to say instead: a future paid plan will cap it (a few), and a person starting
  from `public` makes one (#1113). The free plan makes no clients, so it needs no such cap yet.
- **Shown.** The Clients overview carries a client's plan and usage (`planUsage`), and its page says it on one
  row: "Free: 12 of 100 forms, 3 of 20 users, 1 of 4 workflows, 2 of 10 schemas".

**A customer has a page** (issue #1114). An administrator who sees every client lists the deployment's customers
(`GET /clientAdmin/customers`: each one's plan, root client, and client count) and opens any one; an administrator
of a customer's root client, client-wide, is shown their own (`GET /clientAdmin/customer/overview`, the caller's own
when no `customer` is named). The page lists the customer's clients, sandboxes aside, as the Clients overview's rows:
each one's facts, plan usage, publish and sandbox state, and issues. It is read-only and facts only, and each
administrator still edits only their own client. The rule is `CustomerAccess`, which is also the `administersCustomer`
cfact that offers "My customer" in the menu. No read width goes with it yet: the overview computes its facts itself,
and a customer-wide `ReadScope` arrives with the first customer-wide data read that needs SQL to honor it.

**Who sets each field** (issue #1108). Every field has a policy, declared once beside the definition's schema
(`ClientFieldPolicies`; a field the schema declares without one fails as it is built). A policy has two parts: what
a client made **by another client** (#1112) takes for the field, and who changes it once the client exists. The
platform -- source code, or an `allClients` administrator -- sets any field. What a client's own administrator may
change (the write check of #820), what the definition editor offers, and what the platform's create takes are all
read from it.

| Field | Made by another client | Changed after by |
| --- | --- | --- |
| `clientId` | the creator | nobody (it is the client) |
| `name`, `description` | the creator | the definition editor |
| `timeZone` | copied from the maker | the definition editor |
| `sessionHours` | copied from the maker | the definition editor |
| `refreshIntervalSeconds` | copied from the maker | the platform, in the definition editor |
| `userLabels` | cleared | the definition editor |
| `usageType`, `audience`, `enabledEnvironments` | copied from the maker | the platform, in the definition editor |
| `webResourcesId` | copied from the maker | the platform, in the definition editor |
| `domainPrefix`, `customDomain` | cleared (a domain routes to one client) | the platform, in the definition editor |
| `preload` | cleared | the platform, in the definition editor |
| `customerId` | copied from the maker | the platform, by a configuration write |
| `extendsFromClientId` | the creator | the platform, by a configuration write |
| `sandbox` | the creator | its own surface (the sandbox's management) |
| `includedTraits` | cleared | its own surface (the traits editor, #1117) |
| `retired` | cleared | the platform, in its own surface (#1182) |
| `staticConfig`, `testFeatures` | cleared | the platform (`staticConfig` only in source) |

**The definition editor offers each caller what its policy lets them edit** (issue #1116). A client's own
administrator edits its name, note, time zone, suggested labels, and session lifetime. An administrator who sees every client edits
those and the platform's fields marked "in the definition editor" above (`ClientFieldPolicies.platformEditorFields`);
the endpoint refuses one of those from a client's own administrator (403). The customer and the template stay a
deliberate configuration write: changing either moves what the client is under or holds. Every field the caller
does not edit there is shown read-only, saying who sets it. The editor draws its fields in sections -- About, How it
behaves, Platform -- declared beside each field (`ClientPresentationFields.section`, issue #1308).

The platform's create takes every field but those a create clears and that are then changed only through their own
surface or by a configuration write: the traits a client includes, its static lock, its retirement, and its test
features.

**How long its people stay signed in, and how often their open tabs refresh** (issue #1308). Two values that were
the deployment's alone, each now a field a client may set, and absent for the deployment's:

- **`sessionHours`**, how long a sign-in lasts. The deployment's default is `KDR_SESSION_HOURS` (two days unset), and
  its limit `KDR_SESSION_MAX_HOURS` (30 days unset): a client's administrator may make its people's sessions shorter
  or longer than the default, never past the limit. A value past it is refused at the write, and dropped with an issue
  at a load should the limit later fall below it. The session cookie carries when the person signed in, and every request measures from that against the client's
  lifetime now (`SessionLifetime`), so shortening it ends longer sessions at once. Lengthening it reaches only
  sign-ins made after, since a cookie's expiry is set when it is issued.
- **`refreshIntervalSeconds`**, how often an idle open tab re-reads what it shows (the webapp's idle bump, #146).
  Every tab asks that often, so it is a load on the deployment, and only the platform sets it, within 5 seconds to an
  hour. The app UI-config serves the caller's client's, else the deployment's (`IdleRefresh`).

The client's page shows each as the value in force: the client's own, or the platform's, said as such. Error
obfuscation (`KDR_OBFUSCATE_ERRORS`) stays the deployment's: the errors it hides say something across client lines,
and most arise before any client is known.

**A client has a time zone** (issue #1115): `timeZone`, an IANA zone of a place (`America/Chicago`), or `UTC`. It
says where the client's people are, and so the zone its days mean something in. Absent, the client is in the
deployment's zone (`serverTimeZone`, a fixed UTC-8), as every client was before.

- **Never an offset.** An offset (`-05:00`, `Etc/GMT+5`) or an abbreviation (`EST`) says where the clock is today,
  not where the people are, and is wrong half the year wherever clocks change. A write naming one is refused; a
  stored one the load finds is dropped with an issue, and the client stands in the deployment's zone.
- **Where a day crosses to a moment, it does so in the client's zone.** A day given for a timestamp field of a
  request (the endpoint's input is validated with the zone), a day bound of a forms search, and a report run bringing
  a date column to its declared form all read the day in the client's zone. Day-only values never shift: a day stays
  the day it was written. Operational work -- jobs, logs, a client with no zone -- keeps the deployment's zone.
- **Moments are shown in it.** The shell config carries the zone (`state.timeZone`), and the webapp shows every
  timestamp in it, labeled (`Oct 17, 2026, 5:00 PM CT`).
- **The deployment's day start and its display default are two things.** The day start (`serverTimeZone`) is the
  official day boundary: a fixed offset that never moves for daylight saving, so a day is always 24 hours and a
  contract written against it is unambiguous. A client naming no zone reads its days there. `KDR_DISPLAY_TIME_ZONE`
  (an IANA zone) is only where such a client's moments are **shown**; unset, they are shown at the day start too.
  When the two differ, a moment shown near midnight can fall on the neighboring official day -- the least bad of
  the choices, and gone for a client that names its own zone, which reads and shows its days in that one zone.
- **Changing it moves no stored moment.** What was stored as an instant stays that instant and is shown in the new
  zone; the definition editor says so.
- **Chosen when the client is made.** The platform's create and a person's own self-serve create take it (the
  webapp sends the creator's browser's zone); a client a customer's root makes starts in the root's.
- **Templates read it** (#1321). A template's date functions read the evaluation's environment, its zone and now,
  which the caller hands in: a listing column and a mail the client's zone, named as the webapp names it, and the
  webapp the zone it shows moments in. `formatDay` is the day there, and `formatDate(x, style)` takes `"relative"`,
  `"moment"` (`Oct 17, 2026, 5:00 PM CT`) or `"day"`. An evaluation handed none reads the deployment's zone.
- **Not yet in its zone:** a workflow's time window written as a day is read when the definition is, in the
  deployment's zone (the window editor, #1121, writes moments).

**The definition has a schema, and a written one is held to it** (issue #1051). The attributes above are declared
once (`ClientDef.defineInfoType`) and make two types. `ClientInfo` is what the client endpoints answer with.
`WrittenClientInfo` -- compiled on its own as `ClientDefSchema`, and what the `kdr:clientDef` slot is declared as --
is what a definition arriving as data in a configuration write is validated against before anything reads it. The
schema states the definition's **shape**: its keys and no others (not even the `_` and `$` keys a request may carry
off-contract: a definition is stored in its canonical form, so one would be accepted and then gone), each one's type
(a flag is a boolean, not text that reads like one), and the choices of `usageType`, `audience` and each entry of
`enabledEnvironments`. A fault is a 400 naming each path. The two types differ in one thing: an *answer* leaves the
environments an open list, since a stored client naming one that does not exist is dropped by the load and still
answers its definition read, with the issue that says why. It does not state what a definition means beside the
deployment's other clients, which only code that sees them can judge and which stays where it was: the id's
characters and its being the config's own, the environment set being a legal one, the extended client existing
and being a template, a domain not being another client's (`checkClientDefs`, the write's own guards, and the
trial reload). A definition declared in source is built by its constructor and never passes the schema; a
definition already stored is read leniently, as it always was, so a tightened rule refuses a write and never a
client that is already there.

**Creating a client from data is one call** (issue #1054): `POST /admin/client/create` (`ADEP.clientCreate`,
`ClientCreate`), the platform operator's, since there is no client to administer until it exists. Its input is a
third type made by the same declaration, `ClientCreate`: the written form's properties for the fields somebody
decides when a client is made (`ClientCreateFields`): every field but the traits it includes, its static lock, its
retirement, and its test features, as "Who sets each field" above says. So the endpoint
catalog draws a form from it, with the environments and the templates there are as choices. The definition is
written as the client's first stored configuration (`main`), published, and the client loaded; it is present at
once on a node whose environment it is enabled in, and stored but not present on one it is not (the result says
which). The create adds one rule of its own -- an id that is taken, in source or in stored configuration, is a 409
-- and is otherwise refused for exactly what a bundle write of the same definition would be, in the same words,
because it goes through that write. The rule is kept by the write itself, under the client's lock
(`ConfigWrite.mustBeNew`), so two creates of one id cannot both succeed. An id differing from a taken one only by
case is refused too, before the write; two such ids take two clients' locks, so for that race the load's check is
what holds. Once the definition is published the
client exists whatever becomes of loading it: a load that fails is reported in the result (`loadFailure`), not
thrown, since an error would send the caller to create it again.

As part of the client definition, the client declares the traits it supports. `includedTraits` names the ones it
takes as they stand; a trait the client alters, extends or defines is supported **without a second mention**, so
that list is a minimum rather than a total. The computed result -- the included list with its groups expanded,
plus everything the client customized -- is what forms and data entry are driven from.

A client's administrator chooses the global traits it includes on its Clients page (issue #1117): every global
trait as the platform declares it, with its description and fields, and one switch for `#allGlobal`, which a
`customer` client in `production` may not take. A save is the definition editor's write -- live, or a draft for a
client with a sandbox -- and a live one that takes away a trait the client's stored forms hold is refused with the
impact report until acknowledged. An entry the save adds must name a global trait; one already in the list is kept
as it is, so a stale entry the load drops does not block a save of something else.

It also designs the client's own traits (issue #1118), from a fixed set of field kinds: text, long text, number, whole
number, yes/no, date, and single or multiple choice. A trait's id, a field's key and a choice's value are made from
their first labels and never change; labels change freely, and the labels are written into the trait's layout so
Design View starts from them. A field may be required in the data, off by default; a form's own requirements stay in
its layout. A new trait is stored in a configuration of its own; an edit is written where the trait is stored and
keeps what the editor does not own, such as Design View's hints. The plan's limits on schemas and attributes hold, and
an edit that would invalidate stored data goes through the impact report.

And it narrows and extends global traits (issue #1119). A narrowing offers only moves that narrow: hiding a field,
which is the layout's -- the trait's default field list for every workflow, with the data unchanged, so a field the
data requires cannot be hidden -- and requiring a field or keeping fewer of its choices, which are the data's. It is
written as the client's alteration of the trait's data type, which the narrowing rules judge as they judge any; those
rules judge an array's `items` the same way, so a field of several choices narrows as one of a single choice does. A
narrowing does not include the trait. An extension is a trait of the client's own: a global trait plus the fields it
adds (`g-extends`), the base's own fields kept as the base has them. Changes that affect stored data go through the
impact report, and a sandbox's draft reports what publishing it would do to the live client.

The client can choose also to alter an existing trait by changing UI options and can
only narrow, or can create a new trait by extension, or can define a completely new trait. However,
all schemas not defined in other clients are visible to the client. **Visible and supported are different
sets**: everything global stays visible so that `$ref`s resolve, while a trait the client never mentions is not
supported by it for forms or data entry. 

The client can define new schemas using the same process of altering, extending, or new as is done for traits.
(Extending -- a new type declared as a base plus a delta -- is `g-extends`, built in issue #990. A new type may
also pull another in by `$ref` as a property.)
It will be typical for complex traits that they will pull in schemas using "$ref" and it is quite
possible that a client will target the schema that was pulled in by the "$ref". This would leave the parent
part of the schema alone.

The client has its own customized version of the schema, a variant maintained by the SchemaService
(`SchemaService.storeFor`, issue #356). Anonymous callers get the default global schema, and a client that
varies nothing computes a schema equal to it -- which is why `public` never becomes a variant. This customized
version is rebuilt when the client's stored configuration is reloaded (issue #616, and on the other nodes by the
sync that follows, #618), unless `staticConfig` is true and the node is in production, where the client takes
nothing from the database and changes only with a deployment.

The test fixture that allows creation of users takes a parameter to specify the client, and a similar
option exists for creating users in internal unit tests. A self-registered user lands in `public`, the
placeholder client for a person who has not yet been invited anywhere; the request's host will later be able
to name a client instead (the "default client" rule the Google sign-in and registration share). An address on
the env value of `KDR_ADMIN_EMAIL_DOMAIN` (or, outside production, `example.com`) with **no** `+` at all grants
`admin` and `allClients`, and a user provisioned with them and no client named lands in `hub` rather than
`public` (issue #799) -- how a deployment's first administrator comes to exist. *(The `+clientId%persona`
address tags that once named a client and a persona on those domains were retired in issue #750 -- see "Users,
clients and personas" below.)*

Two things are not addressed here, and both have since been built as issues of their own: how clients introduce
their variants to Markdown fragments (a client's config carries fragment overlays, `GedraConfig.fragments`), and
the automatic creation of endpoints that are restricted down to the `traits` defined by the client (each client
that varies something gets its own copies of the client-shaped endpoints, issue #387).


---

# What it means, and what it takes

## The model

A **client is a config object**, not a table row. It is a typed field beside `traits` on a `GedraConfig` whose
`GedraId` already carries the client, so the id *is* the binding — nothing has to say which client a definition
belongs to twice.
One bundle therefore defines at most one client, and every bundle sharing a client is read as one whole. Two
bundles both declaring the *client itself* is a load-time error; two bundles adding traits to the same client is
the ordinary case.

A client owns three sorts of thing: **its own attributes** (identity, purpose, environments, presentation,
inheritance), **its view of traits and schemas**, and later **its organizations**.

That middle one carries a distinction worth stating before anything else, because it is easy to read straight
past. **Visible and supported are not the same set.** All of the global schema stays *visible* to a client — it
has to be, for `$ref`s to resolve. But a trait the client does not **mention** is **not supported** by that
client for forms or data entry. Support is an opt-in allowlist, not "global minus what you exclude". A client
that mentions nothing supports nothing, however much it can see.

The consequence running through everything else: a client is not a filter applied at read time. It is a *set of
definitions* that produce a client-specific compiled schema.

## What already exists to build on

More than the length of this document suggests, because several pieces were shaped for it in advance:

- **`GedraConfig` already carries a client** in its id, and `gedraConfig(...)` already takes one, defaulting to
  `GID.globalClient`. Its KDoc already calls the client segment the activation scope, and says it bounds
  visibility.
- **`GedraConfigCollector` already refuses incoherent config at boot**, with the environment split from #296 and
  #299: strict everywhere, degrading in production. A duplicate client definition is the same class of problem
  and has an obvious home.
- **Namespace ownership is already enforced** — one owner per namespace, and a client's is `client.<clientId>`
  (#949) — which is the mechanism behind a client seeing global's definitions and its own and nobody else's.
- **`entryUnionDefs` / `entryEditUnionDefs`** are already **functions of (client, kind)**, called once today with
  the global scope. Per-client views are those functions called again with a different set.
- **`traitsFor(client)`** answers "this client's traits, plus global's" — which is the **visibility** question.
  The set the unions should be built from is the client's *supported* traits, which nothing computed yet. (Since
  built: `supportedTraits`.)
- **`ReadScope` / `ReadScopeRules`** already confine reads by client, and the SQL layer already carries a
  `client` column on the tables that need one.

So the gap is narrower than it looks. What is missing is the *definition*: there is no object saying what a
client is, and no registry that can be asked whether one exists or is enabled.

## What can be built now

1. **The client definition itself** — a `ClientDef` carrying the attributes the specification lists, declared in
   a `GedraConfig`, with its validation: the identifier charset, the `enabledEnvironments` rule, the usage-type
   enum.
2. **Collection and refusal** — the collector gains a client per bundle, refuses a second definition of one
   client, and refuses a bundle whose declared client contradicts its own id.
3. **A registry** — which clients exist, which are enabled here, and what a client's attributes are. Everything
   later consults it.
4. **User creation by client** — the fixture's explicit client and role parameters, the same for unit tests, and
   the `+client%persona` email convention (since retired, issue #750).
5. Probably **`extendsFromClientId` for source-code clients** — clone then overlay. The largest single piece
   here, and reasonably its own step. (Since built, issue #945.)

## What has to wait

- **Organizations**, said in the specification and big enough to be several issues. (A user's own organization,
  which narrows an administrator's reads, has since been built; a client declaring its organizations has not.)
- **The per-client compiled schema.** The assembly functions are ready; the *store* is not. (Since built,
  issue #356.)
- **Domain routing** (`domainPrefix`, `customDomain`), which needs a client resolved from the request rather
  than from the caller — a new trust level rather than a new lookup.
- **`webResourcesId` packaging** and `preload`; the specification already calls the last untestable today.
  (A client's own favicon and brand mark are built, issue #1130, keyed by the client rather than by a package.)
  (`staticConfig` is built, issue #824.)
- **Markdown fragment variants** and **auto-generated per-client endpoints**, both named as future issues.
  (Both since built; the endpoints are issue #387.)

## The cut for the first slice

Steps 1–4 are all *declaration and refusal*: they add a definition, validate it, and make it findable. None of
them changes how a request is served. Step 5 and everything under *what has to wait* changes behavior.

That line is the natural boundary for the first issue. It can land with nothing observable changing except that
a client can be declared — which is the safest way to introduce a concept this load-bearing.

---

# Decisions

## Clients that exist: `hub`, `public`, and the name

**`hub` is the client that `local` becomes** — a real client, defined in the `common` component, and one of the
first written in this structure. **`public` stays as it is**: existing behavior untouched, with the
application's default anonymous web resources serving as its resources. It is for demoing rather than real use.
Its shape has since been settled: a `demo`, `customer` client under the platform's customer `kdr`, including
`#allGlobal`. It is where a self-registered user lands (issue #805), and the only place a person makes their own
self-serve client from (issue #1113); see "`public` is not a client in the ordinary sense" below.

**`house` is a third, and owns nothing** (issue #386). It is the client of an env-authed caller: somebody who
reached a node through an authenticating edge and acts for the business itself rather than for any customer. No row
is ever scoped to it; it says who is acting, and is never a key to store anything under.

**Why `local` had to go.** `CL.local` and `ENV.local` are the same string meaning unrelated things, and they
would sit beside each other constantly once a client declares `enabledEnvironments`. It had already caused one
misreading: `SqlTopicServiceTest` asserts `PF.client shouldBe "local"`, which scans as an assertion about the
environment. Of the two the client is the newcomer — "local" for a development environment is universal
vocabulary — so the client is what moved.

**Why `hub`.** The rule that decided it: **name the position, not the purpose.** What this client is *for* is
exactly what is still being learned, so any name describing the job — `internal`, `staff`, `ops`, `system` —
could turn out wrong, while a name describing where it sits stays true whatever it comes to hold. That was also
the fairest defence of `local`, whose only real defect was the collision.

`hub` names a central point everything connects through, which is what this client is whatever it ends up
doing, and it survives the future where several internal clients exist — one of them is still the hub. It is
three characters, so `gd.fd.hub.u2026…` reads as cleanly as a two-letter token would, and it is a word rather
than an abbreviation, which matters for something said aloud.

**What was considered and rejected.** `hq` was the first candidate and carries a military and large-corporation
register that this is not. Two attempts to soften it by prefixing the family's `g` both landed on words that
already exist: `ghub` reads as a near-miss of *github*, and `ghq` is *General Headquarters* — more specifically
military than `hq`, not less. Short letter-strings are dense with prior meaning, so prefixing a common word
tends to hit another word rather than escape into open space.

**The classification stays an attribute.** Whether a client is internal or customer-facing belongs in a field,
not in its name, so that several internal clients can exist and all be marked. That is what frees the name to
be arbitrary, and it is why `hub` need not carry any of that meaning itself.

**The window, and why it is now shut cheaply.** No persisted row carries `local` today: all five client-scoped
tables — `AuthUsers`, `LinkedUsers`, `AuthUserDevices`, `GedraDataTran`, `GedraData` — receive `public`, since
auth writes it explicitly and gedras take it from a request profile, and `InstanceConfig` is not client-scoped.
(`LinkedUsers` and `AuthUserDevices` have since lost their client column altogether: they key on the identity,
which spans clients -- issue #748.)
The first row carrying it would appear when something writes a client-scoped table **from a context whose client
was defaulted** — internal batch activity, or users created in this client once it is defined. Settling the name
before that happens means `CL.hub` arrives with the first slice and no row ever carries the old value, so there
is nothing to migrate.

## Environments, availability and retirement

**`enabledEnvironments` is the only per-node axis.** There is no separate node-level notion of which clients a
deployment carries. A consequence worth knowing: two deployments in the same environment necessarily carry the same
clients, so a deployment focused on a subset would have to be its own environment, and `ENV` is a closed set of
five. Two other conditions decide presence, and neither varies by node: a retired client is absent everywhere
(below), and a sandbox is present only where its parent is and still asks for one.

**Both `unit` and `local` must be present whenever any other environment is named.** The reason belongs with the
rule: a client in active use must never be one that local development and the unit tests are locked out of.
`integration` is a real environment; `staging` is anticipated and does not exist.

**Retirement is by a flag, not deleting** — reversibly, on purpose (issue #1111). The platform sets `retired` on a
client's stored definition (`POST /admin/client/retirement/set`), and the client is absent in every environment:
nothing scoped by it is reached, its users cannot sign in, a session already open is no longer honored, and its
sandbox goes with it. Clearing the flag brings it back as it was -- its `enabledEnvironments` were never touched,
which is why retiring is a flag of its own rather than emptying them (a client enabled nowhere is still legal,
and absent everywhere). A client defined in source code is retired there; a customer's root is retired only once
another root is named. The definition stays, the content stays, only access stops. That is the same decision #326
made for a gedra one level up: keep the row, flip a flag, let reads treat it as absent. Two things follow from the
resemblance being real. **Retiring reclaims nothing** — a retired client's content is still stored and bringing it
back brings all of it back, including data somebody may have assumed was gone; deleting a client for real is a
different question with the shape of a purge. And **the client list only grows**, which is fine for a per-request
set lookup though every definition still loads at boot.

## A client that is not there

**If a client definition is not present, everything scoped by it behaves as though it were not there** — its
users cannot get in, its content cannot be read. Not an error and not a permission failure: absent.

`allClients` holders are the exception, on *some* calls only: they may address a resource in a client that no
longer exists, which is what makes support and recovery possible. Being a widening exception, it wants naming
call by call rather than granted as a class.

**What is built** gates a client's people (issue #1111). A session whose client this node does not carry is no
longer honored (`RequestService`), a sign-in to such a client is refused and says whether it was retired
(`AuthFormHandler`), the user listings an identity sees leave its users out (`UserService`), and an administrator
cannot create a user in it (`AdminEndpoints`).

**What is intended and not built** gates its content. `GedraService` does not consult the client registry, so a
`GedraId` of an absent client is not refused yet. Three things follow for whoever builds it.

**A client check is a new gate on a `GedraId`, and the cheapest one.** An id carries its client, so "is this
client present here?" is a set lookup answerable **before any database access**, exactly as the kind check
already is. Its answer is *absent*, joining the ones the read path already collapses into a single 404.

**The intern cache is not an authority on availability.** A `GedraId` interned while a client was present stays
interned after it goes, so a cache *hit* proves the id was seen, never that it may be used now. The client check
runs **after** the cache rather than being short-circuited by it. This compounds with the limitation already
recorded on `GedraService.gedraIds` — the cache holds no guarantee of completeness, so a miss is not "does not
exist" either. Whoever eventually makes a miss authoritative must not quietly make a hit authoritative at the
same time.

**The table cache inherits it.** Rows held in memory are held for the clients this deployment supports, so
client presence is a filter on what is cached rather than a check applied afterward.

**It is testable without a second instance.** A client **defined in source code and not enabled in `unit`** is
precisely a client that is known and not present — one instance, no configuration reload, no frontend. A unit test
can declare one enabled in `local` only, then assert that in a `unit` boot its users cannot be created or sign in;
once the `GedraId` gate is built, that its ids are refused and an `allClients` caller can still reach it on the
calls that allow it. The disappearance case is the same state
arrived at differently, and the code cannot tell them apart, which is the point. If a test ever needs two
populations rather than one, `InstanceRegistry.register` plus a load flag is the second instrument, as the
sample module already does.

## Users, clients and personas

A **persona** says what relationship a user has to the application -- `member` (the default: the person
belongs to the client) or `admin` to start, `reviewer` and `advisor` sketched -- and is a **registry**
(`PERSONA` in the kernel, issue #750): each has a name, a label, and the roles a user of it starts with. It is
frozen at creation and part of the user's unique key with its identity, client and `personaSuffix`; changing
persona means creating a new user with its own data. The relationship with roles runs both ways: a persona
grants its default roles to a user created with it, and roles provide a default persona to a user created
without naming one (`PERSONA.defaultFor`), so a user created as an administrator, by the auto-admin domain or
by an explicit role list, is an `admin`. A persona is deliberately **not a role**: `admin` here
says how to read the user, and the roles say what they may do -- and no persona's default roles ever include
`allClients`, which is a grant an administrator makes to a user, never a property of what kind of user they
are. A client may later add its own personas under this word's reservation.

An administrator provisions a user with a persona and, for a further user of the same address, client and
persona (a UAT batch), a `personaSuffix`; a person proves such a user by invitation (phase E of #746), or, when the
address is the administrator's own, holds it from the start as an associated user. That is why the
`+clientId%persona` address convention that once did this on the controlled domains was **retired** in #750:
what survives of it is the no-tag rule alone -- on `example.com` and `KDR_ADMIN_EMAIL_DOMAIN`, an address with
**no `+`** is one of the deployment's own people and earns the auto-admin grant, and a `+` tag says only that
it is not.

`docs/development/deferred-work.md` used *persona* for a different payload in the same part of an address -- the
identity-bound quality that makes a test user fail on demand. That use has been renamed to **fault**, which is not a
coinage:
#227 already uses it for deliberate failure. The formal concept keeps the word.

**The fixtures take an explicit client, persona and personaSuffix**, which makes any address convention a
convenience rather than the mechanism -- and is what everything else is tested through.

## Usage types, and what a caller is shown

**Usage type changes no boot-time fence.** A badly configured test instance in production still boots, and
nothing about a client's type makes an instance a test instance.

**Endpoints reserved for `test` or `demo` clients stay available to every client.** Their *visibility* is
filtered so ordinary users are not shown them, and **each endpoint asserts for itself** — as part of its
ordinary security check on incoming data — that the client it is pointed at has the right usage type. The
constraint is the target client's type, not the deployment environment, which means such an endpoint must be
able to determine a client **from the data it was given**: "carries something that identifies a client" is a
requirement on its input.

*Not built as written.* What was built fences by the **deployment** instead: a test endpoint exists only on a test
instance (`forTestingOnly`, issues #125 and #1149), and a client's `testFeatures` are honored only there (issue
#696). The deployment fence is the outer guard, and it is what keeps test endpoints out of production. Whether a
client's usage type should also be asserted inside it, so that on a test instance a test endpoint reaches only
`test` or `demo` clients, is open (see "Open questions").

**A "normal" user is one whose client is customer-facing.** The question these filters answer is not what
somebody may *do* — the endpoints stay callable — but what is worth *showing* them, which is a fact about their
relationship to the product rather than about their permissions. A support engineer on a deliberately restricted
account is still someone for whom our fixtures are informative; a client admin with wide permissions inside
their own client is still someone for whom they are noise.

Defining it this way removes the conflation in the current shorthand, where "normal" means *lacking
`allClients`* — a scope capability standing in for "is one of us", which are different facts. It also gives the
right answer for a user in an internal client with no privileges: not normal, because what we show them should
reflect that they are one of us.

Once per-client typed endpoints exist, the reason to hide the generic ones from a client's people is not that
they are dangerous but that **theirs are better** — a curation decision rather than a security posture.

**"Visible to some, callable by all" is a third axis**, beside roles (what may be done) and `ReadScope` (over
whose rows). The patch work reached the same need and recorded it as a per-endpoint `notAdvertised` flag,
deliberately distinct from access; the flag says *do not advertise* and "normal" says *to whom*. Two callers,
so it should be built once. *Neither half is built:* no endpoint carries a `notAdvertised` flag, and nothing reads
`audience` to decide what a caller is shown.

## The traits a client supports

**`ClientDef.includedTraits`** is the declared list: trait ids and group names, written by the client, and a
**minimum** rather than a total. **`supportedTraits(client)`** is the computed set the entry and edit unions are
built from: the included list with its groups expanded, plus every trait the client customized, extended or
defined.

Two names rather than one, because `supportedTraits` is the honest name for the *result* and a misleading one
for the *input* — a trait customized in the `GedraConfig` is supported without appearing in the list, and an
attribute claiming to list what a client supports, which does not list everything a client supports, is the kind
of near-truth somebody eventually relies on. `included` pairs with the thing it is defined against: a client
**includes** traits it takes as they stand and **customizes** the ones it changes.

That leaves three named concepts rather than two overloaded ones:

| | |
|---|---|
| `traitsFor(client)` *(exists)* | what a client can **see** — its own and global's, for `$ref` resolution |
| `supportedTraits(client)` *(since built)* | what a client may **use** in forms and data entry |
| `ClientDef.includedTraits` | what a client **wrote down** to get there |

### Why declaration, and not the alternatives

**Empty variant declarations were ruled out** — declaring a trait with an empty body purely to pull it in. They
read as the tidiest option and are the most dangerous, because **an empty declaration is indistinguishable from
an unfinished one**: nobody can tell "I support this as it stands" from "I meant to narrow this and have not got
to it". Worse, an empty block *looks like dead code*, so tidying a config silently withdraws support for a trait
whose stored data is still there — a cosmetic edit with a data-visible consequence.

**Tags were kept in reserve.** A tag applied by hand to every global trait restates a fact already computable
from the trait's config being owned by `GID.globalClient`, and a manually maintained restatement drifts:
somebody adds a global trait, forgets the tag, and every client expecting it silently does not support it. If
tags arrive later they become *a way to define a group*, not a rival notion of support.

**A mandatory cross-check was rejected** — requiring a trait the bundle itself declares to appear in the list as
well. A client's traits may be spread across several `GedraConfig`s, so the list would live in one bundle and
name traits declared in another; adding a trait would mean editing two files, one possibly owned by a different
author. The redundancy would not merely be wordy, it would couple bundles that are deliberately separable.

### `#allGlobal`

The supported list holds trait ids **and group names**, distinguished by a sigil no trait id can contain.
`#allGlobal` is a **functional** group: membership computed at load time from what is global, never written
down, so it cannot fall out of step the way a tag can.

On the surface it has the same action at a distance as a tag — ship a global trait and every client using it
supports that trait at once. The difference is where the coupling lives. A tag adds a hidden step somebody must
remember and can get wrong; `#allGlobal` is a client **declaring that it tracks a computed set**, which is what
it asked for and the only thing it could have meant.

**A functional group is refused when `audience` is `customer` and `usageType` is `production`** — both
conditions, and each does real work.

The rule was never really about production. What makes `#allGlobal` dangerous is that *we* ship a global trait
and it becomes editable in a client that never reviewed it — which is a statement about **two parties**: somebody
else depends on this and did not consent to the change. Production was a proxy for that condition rather than
the condition itself.

So a *customer's* test or dev client may track every new global trait, and arguably should — that is how they
would preview what is coming. An *internal* client in production may too, because there is no second party:
we ship the trait, we run the client, we are the reviewer. Only the combination is the case the rule exists for.

Stated as the principle rather than the mechanism: **a client's supported set must be fully determined by its
own definition when somebody other than us depends on it.**

**Not a sixth usage type.** Making `internal` a usage type would undo the separation the two axes exist for:
`usageType` says what a client is *for*, `audience` says *whose* it is, and the first internal demo client would
force the question "which is it?". That is the same conflation as using `allClients` to mean "is one of us".

**And it makes `audience` an authority axis.** It stops being a label the moment it gates a capability, so it
must not be self-declarable: a client authoring its own configuration could otherwise write `audience:
internal` and take a functional group with it. Set by the platform -- source code, or an `allClients`
administrator -- and refused from a client's own administrator (issue #1108), as "Who sets each field" records.

### Two consequences

**`public` stops being an exception.** A client whose supported set is exactly `#allGlobal`, and which varies
nothing, computes a schema *equal* to the global one — and with structural sharing, "equal" can be "the same
object". So `public` being the client that never creates a variant is not a rule the schema builder enforces but
the result of what `public` declares. `hub` becomes the second such client. Nothing needs to refuse `public`
the ability to declare a trait, either: doing so would simply make it a variant like anyone else, and the thing
worth protecting is the *anonymous* schema being the global one, which no client can touch.

**Withdrawing support is not data loss.** An entry whose trait a client no longer supports lands on the
manufactured union's default branch and is carried as plain JSON — the branch #301 argued for on the grounds
that meeting an unknown trait is ordinary. Support governs what a client may *edit*, not what it may *hold*,
which is what makes an allowlist tolerable to change at all.

## Endpoints, and what path separation buys

A client's own endpoints carry the **`clientId` in the path**, in a recognizable pattern, so two clients never
define the same path with different behavior. `RequestService` had to become client-aware -- it resolves a
client endpoint's types against that client's variant (issue #387) -- and that was the destabilizing part; but
clean path separation paid for a good deal of it.

**It dissolves the type-cache problem rather than answering it.** `RequestService` caches resolved input and
output types keyed by endpoint path, and the worry was that two clients resolving one endpoint to different
types would silently get each other's. With distinct paths per client, each path resolves to exactly one type,
so the key is sound as it stands. The condition it rests on is worth naming: the **generic** endpoints, which
every client shares, resolve against the **global** schema only — which is already the rule, since the general
patch knows only global traits.

**It also narrows what a variant is for.** A client's *additive* definitions — new traits, new types — live in
the client's own namespace and are ordinary distinct entries in one bag; nothing has to be varied to hold them.
What genuinely needs a variant is an **overlay**: when a client narrows an existing type, that name means
something different for that client, and a `$ref` pointing at the original name has to resolve to the narrowed
form. So the variant machinery is invoked by clients that overlay, not by clients that merely add — which is
the same boundary as limiting cloning to what a client actually modified.

## How much of the store a variant covers

**Whatever the code is simplest carrying.** The preference is to vary only what needs varying, but if an
implementation naturally sweeps in more than that, it is not worth avoiding: the CPU and memory involved are
small enough to ignore, and simplicity of code is the thing actually being bought.

The part of this that is *not* about size has its own answer. `SqlTopicService` reads the table catalog out of
the store at boot, before any client exists, so what matters is which store a caller with no client is given —
and that is already settled: **no client means the global one**, the same rule anonymous callers follow. With
that, a variant carrying tables identical to global's is harmless, and sharing the map by reference makes it
free.

## Rebuilding a variant, and what sharing requires

**A client's version is rebuilt whole.** When the data behind its traits, schema or endpoints changes, the
client's version is constructed again from the source objects and data; there is no differential repair of a
computed config object. That removes a whole class of bug — a half-repaired cache is the kind of wrong that
looks right — at the cost of paying full construction each time, which is exactly why limiting how much is
cloned matters.

"Rebuilt whole" means *not patched*, not that nothing may be shared. A rebuild is still free to point at
already-built objects for the parts a client did not modify.

**Construction-time mutability is expected and fine.** Building a graph with relationships needs attributes
that are written while it is assembled and settled once it is done — `SchVariants` resolving its branches is
exactly that — and cloning into a parallel hierarchy of immutable classes to avoid it would be ceremony that
buys nothing.

The one rule that follows, and it is about the *builder* rather than about the types: a variant **may create new
nodes and point at old ones; it must not write into old ones.** With sharing, the invariant stops being "nothing
mutates after its own construction" and becomes "nothing mutates after any construction that might have shared
it". That is a discipline for one piece of code, not a property to enforce in the type system, and it is
checkable in a test — build a variant, then assert the global store's types are untouched.

## Where a client's schema is applied

The generic endpoints — create, patch, and the rest — **validate against the caller's client variant**, without
becoming per-client endpoints. The two halves sit at different points and do not conflict:

- **At the boundary**, an endpoint's published and resolved input type stays **global**. The form shows global
  traits, a client's own trait arrives on the union's default branch as plain JSON, and the resolved type is the
  same for every caller — which is what keeps the path-keyed type caches sound.
- **In the service**, the entries about to be stored are validated against the **caller's client** union. So a
  client trait that arrived as raw JSON is checked properly after all, against the definition its own client
  declared.

Permissive at the edge, strict where it is stored. That is what makes "a client's variant schema is applied when
creating and patching entries" true without pulling per-client generated endpoints forward — those came later
(issue #387), and are where a client gets *typed input* rather than merely validated storage.

## Inheritance between clients

**`extendsFromClientId` does not need to support chains.** One level: a client extends a template, and that is
all.

**How a client is built on another** (issue #945). Each of the base client's *source* configs is **cloned** under
the extending client's id, and the extending client's own configuration is applied over the clone. Cloning rather
than referencing is what makes the base's definitions the client's own: the client's schema variant, cfacts,
workflows and listing columns include them with no knowledge of extension, and an entry the client stores against
a base trait resolves against the client. The clone keeps the base's namespace, which the base goes on owning, so no
type is renamed and every `$ref` inside the base still resolves; the extending client cannot author into it.

The merge is **by rule, per kind of definition**, and only two kinds look inside a definition:

| Definition | When the client defines the same one |
| --- | --- |
| Trait, by trait id | The client's replaces the base's, whole. It is an override, not a conflict. |
| Schema type, by qualified name | The client's replaces the base's, whole. |
| Cfact, by name | The client's replaces the base's. |
| Workflow, by workflow id | The client's replaces the base's, whole. Its tasks are not merged: a task list has an order, and placing a new task relative to its neighbors is not built, so a client that changes a base workflow restates it. |
| Creation or survey workflow | The client's replaces the base's of that kind, whatever its id. |
| Report, by report id | The client's replaces the base's, whole (issue #979). Both are client-owned bare ids; a global report is rooted and never replaced. Each report a client may run also has typed endpoints of its own, generated from it on every reload — see `docs/design/reporting.md`, "Typed endpoints per report". |
| Trait usages (listing columns) | The client's list replaces the base's list. |
| Fragment copy | Merged key by key: two levels, string values. The base's layer sits below the client's own. |
| UiBlock | The UiBlock merge (keyed arrays, recursive), the base's layer below the client's own. |
| File ref, by ref id | The client's source ref replaces the base's; a stored one overrides it instead. See [`file-refs.md`](file-refs.md#inheritance-sandboxes-and-what-is-stored). |
| Client definition | The client's own fields stand; see `extendsFromClientId` above for what is defaulted. |

So the built client holds each definition **once**. A redefinition takes the base's definition out of the clone
rather than sitting beside it; two of the client's *own* configs defining one id remain the collision they always
were, reported and the second dropped. The base's state and config traits are not cloned, since both are global.

The clone is rebuilt whenever the client's configuration is: at boot, on every reload, and in the trial a write
runs, so a revision that starts or stops redefining a base trait changes the clone with it. A **sandbox** of an
extending client does not carry its parent's clone. It keeps `extendsFromClientId` and is cloned against its own
configuration, which is its parent's drafts.

**Overlays do compose**, though: an alter or extend can be applied on top of another. Which raises whether a
narrowing check runs against the schema immediately below it or against the base of the stack — and the two
turn out to be equivalent rather than merely indistinguishable in practice. Narrowing is transitive: if each
overlay accepts no more than the one it sits on, then by induction it accepts no more than the base. **Checking
the immediate parent is therefore both sufficient and cheaper**, since nothing has to carry a reference to the
base.

## Domains, and what they may decide

`domainPrefix` and `customDomain` matter to exactly two things: the **anonymous** state of the application —
which web resources an unauthenticated visitor sees — and **self-registration**, which client a new account
lands in.

That is the whole of it, and it is what keeps a host-derived client safe. The client comes from a header the
caller controls, where everything else derives it from an authenticated profile; confined to presentation and a
registration default it never widens what is readable, which stays `ReadScopeRules`' business, from the profile.

## `public` is not a client in the ordinary sense

Every user in `public` is effectively their own client, able to create form entries for themselves alone (a
later capability). **No normal user holds admin privilege over `public`**, so there is no client-scoped
administrator there and `ReadScope.ofUser` is the only width that ever applies to content.

How that is enforced (issue #805): in `public` there is no difference between an administrator and an ordinary
user, so every self-registered `public` user *is* given `admin` -- and an administrator in `public` without
`allClients` has the `ownIdentity` admin scope. Their user administration reaches their own identity's users
(`ReadScope.ofIdentity`, a width only the user table answers), so they can create and manage variants of
themselves at their own address; their content reads stay `ofUser`; and the client's configuration endpoints
refuse them (`AdminRules.requireClientAdministrator`). The rule is keyed on the client, not on how the role
arrived, so no route to `admin` in `public` reaches further. An administrator provisioned with no client named
lands in `hub` instead (`AddressRules.defaultClient`).

That is what settles the tension in giving `public` a usage type. The worry was that typing it `demo` would put
demo conveniences — relaxed security, bulk delete — within reach of the client that holds every registered
user. Those conveniences are administrative, and `public` grants no administrator, so the reach does not exist.

## The per-client schema

Fetching the current schema is a **method on the context** (issue #946): `cxt.getClientSchema()` returns the store of
the client bound to the context -- its variant, or the global store when it varies nothing -- and
`cxt.getGlobalSchema()` the global store, which endpoint resolution uses deliberately (`SchemaService.storeFor` says
why). The `SchemaService` holds a variant of the schema stack per client, with cloning limited to what a client
actually modified so a variant costs what it changed rather than the size of the schema.

**Anonymous callers get the default global schema**; they need one and have no client to take it from.
**`public` uses the global schema exactly**, per the identity case above.

**Invalidation is explicit and rare.** A **client admin asks for it through an endpoint** (the config reload,
issue #616; other nodes then follow through the sync, #618) — nothing detects a write and rebuilds behind
anybody's back — and not for a `staticConfig` client in production, which takes nothing stored there.

---

# Open questions

The only place to look for status.

## Nothing blocking

Nothing open blocks work. The first slice (issue #343) shipped long ago, as has most of what this document planned;
what remains is parked by choice or a question of ordering rather than design.

## Open

**Whether a client's usage type gates test and demo endpoints.** The deployment fence is built and keeps them out
of production; asserting the target client's type inside it, on a test instance, is not (see "Usage types, and what
a caller is shown").

**What a caller is shown.** Filtering what is advertised by whether a caller's client is customer-facing, and the
`notAdvertised` flag beside it, are decided and not built.

## Answered

**Dynamic disabling** arrived as retirement (issue #1111): the platform's `retired` flag takes a client out of
every environment live, with no restart, and brings it back as it was. See "Environments, availability and
retirement". Nothing decided here had to change for it.
