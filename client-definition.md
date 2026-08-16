# Client definitions

How a client is described, what it owns, and what a deployment does with one. Written before any of it exists,
as something to design against and argue with.

It is a companion to [`gedra-config-and-data.md`](gedra-config-and-data.md), which describes how a gedra is
identified and how a deployment can be split by client — the mechanism this document gives a definition to —
and to [`gedra-patch.md`](gedra-patch.md), whose endpoints are the first that will need a client's own view of
a schema.

**Nothing here is built.** Issue #340 is the planning issue.

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
  the GedraId.
* `description` - An optional internal description for humans to understand who, what, or why for the client.
* `usageType` - An enum list of usage purposes for the client which may grow over time. Some new behaviors
  or features may condition themselves on the usage type to control visibility of changes. 
  nly usage type of `production` in the prod deployment environment will have
  any strong guarantee that we will not potentially kill the entire client and start over.
  The current list is
  * `production` - Client is being actively used by users who care about the stability of the application.
  * `test` - The client is a client that exists only to run tests against. A test client may allow
    certain test fixture endpoints to be available, even in prod.
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
  can be `production`, `dev` or `test`. It is not merely a label -- it decides what a caller is proactively
  shown, and it relaxes the functional-group restriction below -- so **it may be set only in source code**, never
  by a client authoring its own configuration in data. The same reasoning that limits a database-defined client
  to extending template clients.
* `webResourcesId` - An ID that identifies a package of resources that can have things like a 
  custom favicon (plus other related images), general look and feel images, and CSS file. Note, two different
  clients can share a `webResourceId`. The mechanics of where these packages of resources are in source
  code, or in the file store, or in the database will be designed over time. At some future point,
  we will integrate with a CDN, and the `webResourceId` can potentially define where to find the resources in the CDN.
* `enabledEnvironments` - A list of environments where the client is enabled. Even if not enabled, it
  can still be referred to, but the client will otherwise be considered not to be present. 
  It defaults to the list {'unit', 'local', 'dev'}. It is an error if any environment besides `unit` and `local`
  is present and **both** `unit` and `local` are not also present. A client in active use must never be one that
  local development and the unit tests are locked out of.
  We do not need thorough test coverage for this attribute until we actually have real deployments. The
  environments that exist are `unit`, `local`, `dev`, `integration` and `prod`; `staging` is anticipated and
  does not exist yet.
  This is the **only** axis deciding which clients a deployment carries. A deployment focused on a subset of
  clients would therefore have to be its own environment.
* `preload` - Whether to practively load all computations done on freshly loaded cache for the client before the
  node says it is ready to receive requests. Not yet testable.
* `staticConfig` - If true, the client will not support dynamic reload of configuration when configuration is updated
  in data. Only applies to the client when deployed in prod. Not yet testable. If this is true, the preferred
  way to do configuration will be through source code changes.
* `extendsFromClientId` - A reference to another client. When done in source code, it can be any reference.
 When done as data in the database, it can only reference template clients. When this value is present
 when the client is loaded, it first clones in all the definitions from the other client and then applies
 any configuration in the current GedraConfig as extensions or overlay. This reference is usually done
 for three reasons. One, to build on a template to deduplicate the definitions of the clients. The other is
 to create a slightly variant client that previews new feature behavior that will eventually be put into
 the base client and formally released. The third, to create a test variant of a prod client for focused testing.
 If this value is present, it will also default the `webResourceId` to the `webResourceId` of the pulled in client
 and also pull in the same `organizations`. Note, this key can only cross-reference to a client that is
 defined in source code and will pull in only the source code definition. Any database data overlays that may be
 present will be ignored.
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
* `organizations` - A complex definition of organizations associated with this client, we will implement this
 at a later date.

As part of the client definition, the client declares the traits it supports. `includedTraits` names the ones it
takes as they stand; a trait the client alters, extends or defines is supported **without a second mention**, so
that list is a minimum rather than a total. The computed result -- the included list with its groups expanded,
plus everything the client customized -- is what forms and data entry are driven from.

The client can choose also to alter an existing trait by changing UI options and can
only narrow, or can create a new trait by extension, or can define a completely new trait. However,
all schemas not defined in other clients are visible to the client. **Visible and supported are different
sets**: everything global stays visible so that `$ref`s resolve, while a trait the client never mentions is not
supported by it for forms or data entry. 

The client can define new schemas using the same process of altering, extending, or new as is done for traits.
It will be typical for complex traits that they will pull in schemas using "$ref" and it is quite
possible that a client will target the schema that was pulled in by the "$ref". This would leave the parent
part of the schema alone.

When the client's custom view of traits and types is implemented in code, the client will have
its own customized version of the schema maintained by the SchemaService, reached through the context.
Anonymous callers get the default global schema, and a client that varies nothing computes a schema equal to
it -- which is why `public` never becomes a variant. This customized version
is rebuilt any time there is a data update to the config, unless `staticConfig` is true in which
case it will only occur when a formal deployment occurs or a scheduled event or admin action
takes place.

The test fixture that allows creation of users will have a parameter to specify the client, and a similar
option will exist for creating users for internal unit tests. And for users ending in `example.com` or
the env value of `KDR_ADMIN_EMAIL_DOMAIN`, we will support an option where the value after a `+` will specify
the clientId. There is one extra feature to this, the characters for the client ID are only read up
to the first character that would make it an illegal clientId. An example would be, `user1+acme#featureX@example.com`.
For this user the `clientId` would be `acme` not `acme#featureX`. When a new user is created with such
an embedded `clientId` in its email, the user will be created in that client, if it exists and is enabled. If it
does not exist or is not enabled, the user falls back to `public`, with a warning logged and nothing else said.

A `%persona` suffix after the client id names a **persona**, so `user1+hub%admin@example.com` is an admin of
`hub`; `%` is one of the characters that terminates the client id. **A client with no persona is an ordinary
user**: `user1+acme@example.com` is a normal user of `acme`, not an administrator of it. A persona is deliberately not called a
role: for now it is a **subset** of the roles, and it **cannot grant `allClients`**. When more capabilities
exist there will be a formal definition of a persona and of how it maps onto roles and capabilities, with some
capabilities possibly depending on the client's own definition. An address on these two domains with **no**
`+` at all grants `admin` and `operator` in whatever client the user is assigned to. Outside production such
admins will eventually also hold `allClients`; in production only a subset will, and `example.com` does not work
there at all. This replaces the present rule, under which a `+` tag *disqualifies* an address from being made an
administrator.

One thing not addressed here is how clients will introduce their variants to Markdown fragments.
Also not addressed here is the automatic creation of endpoints that are restricted down
to the `traits` defined by the client. Both are future issues.


---

# What it means, and what it takes

## The model

A **client is a config object**, not a table row. It is a typed field beside `traits` on a `GedraConfig` whose
`GedraId` already carries the client, so the id *is* the binding — nothing has to say which client a definition belongs to twice.
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
- **Namespace ownership is already enforced** — one owner per namespace — which is the mechanism behind a client
  seeing global's definitions and its own and nobody else's.
- **`entryUnionDefs` / `entryEditUnionDefs`** are already **functions of (client, kind)**, called once today with
  the global scope. Per-client views are those functions called again with a different set.
- **`traitsFor(client)`** answers "this client's traits, plus global's" — which is the **visibility** question.
  The set the unions should be built from is the client's *supported* traits, which nothing computes yet.
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
   the `+client%persona` email convention.
5. Probably **`extendsFromClientId` for source-code clients** — clone then overlay. The largest single piece
   here, and reasonably its own step.

## What has to wait

- **Organizations**, said in the specification and big enough to be several issues.
- **The per-client compiled schema.** The assembly functions are ready; the *store* is not.
- **Domain routing** (`domainPrefix`, `customDomain`), which needs a client resolved from the request rather
  than from the caller — a new trust level rather than a new lookup.
- **`webResourcesId` packaging**, `preload` and `staticConfig`; the specification already calls the last two
  untestable today.
- **Markdown fragment variants** and **auto-generated per-client endpoints**, both named as future issues.

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
application's default anonymous web resources serving as its resources. It is for demoing rather than real use,
and its own shape is still to be sketched.

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
The first row carrying it would appear when something writes a client-scoped table **from a context whose client
was defaulted** — internal batch activity, or users created in this client once it is defined. Settling the name
before that happens means `CL.hub` arrives with the first slice and no row ever carries the old value, so there
is nothing to migrate.

## Environments, availability and retirement

**`enabledEnvironments` is the only axis.** There is no separate node-level notion of which clients a deployment
carries. A consequence worth knowing: two deployments in the same environment necessarily carry the same
clients, so a deployment focused on a subset would have to be its own environment, and `ENV` is a closed set of
five.

**Both `unit` and `local` must be present whenever any other environment is named.** The reason belongs with the
rule: a client in active use must never be one that local development and the unit tests are locked out of.
`integration` is a real environment; `staging` is anticipated and does not exist.

**Retirement is by un-enabling, not deleting** — reversibly, on purpose. The definition stays, the content
stays, only access stops. That is the same decision #326 made for a gedra one level up: keep the row, flip a
flag, let reads treat it as absent. Two things follow from the resemblance being real. **Un-enabling reclaims
nothing** — a retired client's content is still stored and re-enabling brings all of it back, including data
somebody may have assumed was gone; deleting a client for real is a different question with the shape of a
purge. And **the client list only grows**, which is fine for a per-request set lookup though every definition
still loads at boot.

## A client that is not there

**If a client definition is not present, everything scoped by it behaves as though it were not there** — its
users cannot get in, its content cannot be read. Not an error and not a permission failure: absent.

`allClients` holders are the exception, on *some* calls only: they may address a resource in a client that no
longer exists, which is what makes support and recovery possible. Being a widening exception, it wants naming
call by call rather than granted as a class.

Three things follow.

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

**This is testable today.** A client **defined in source code and not enabled in `unit`** is precisely a client
that is known and not present — one instance, no configuration reload, no frontend. A unit test can declare one
enabled in `local` only, then assert that in a `unit` boot its ids are refused, its users cannot be created, and
an `allClients` caller can still reach it on the calls that allow it. The disappearance case is the same state
arrived at differently, and the code cannot tell them apart, which is the point. If a test ever needs two
populations rather than one, `InstanceRegistry.register` plus a load flag is the second instrument, as the
sample module already does.

## Users, clients and personas

On the two domains this applies to — `example.com` and `KDR_ADMIN_EMAIL_DOMAIN`:

- **no `+`** grants `admin` **and** `operator` in whatever client the user is assigned to;
- **`+<clientId>`** puts the user in that client;
- **`%<persona>`** after the client id names the role — `user1+hub%admin@example.com` is an admin of `hub`.
  The client id is read only up to the first character that could not be in one, so `%` terminates it.

Outside production such admins will eventually also hold `allClients`; in production only a subset will, and
`example.com` does not work there at all.

**A persona is not a role, and the difference is the point.** For now it is a subset of the roles, and it
**cannot grant `allClients`** — so the persona vocabulary is the *escalation ceiling of the email convention*:
whatever it grows into, an address can never mint a caller with global scope. A formal definition of a persona,
and of how one maps onto roles and capabilities, waits until there are more capabilities to map — some of which
may depend on the client's own definition.

One consequence reads backwards until it is said out loud: **naming a persona narrows**. `user+acme%operator` is
*less* privileged than `user@acme.com`, because the plain address takes the full auto-admin grant while the
persona takes only what it names. The two are separate paths — the `allClients` that non-production admins
receive comes from the auto-admin rule, never from a persona.

`deferred-work.md` used *persona* for a different payload in the same part of an address — the identity-bound
quality that makes a test user fail on demand. That use has been renamed to **fault**, which is not a coinage:
#227 already uses it for deliberate failure. The formal concept keeps the word.

**This supersedes the rule where a `+` tag disqualifies an address from auto-admin**, and the inversion is the
thing to be careful about: an account deliberately created as a non-admin under the old rule reads as a client
assignment under the new one. It also overtakes a `deferred-work.md` item — *auto-admin should grant the level,
not global scope* — whose trigger has now fired and whose answer turns out to be environment-dependent rather
than the flat rule it anticipated. That item should be promoted and rewritten rather than followed.

**An address naming a client that does not exist falls back to `public`**, logging a warning and otherwise
staying silent. Marked as likely to evolve.

**The fixtures take an explicit client and role**, which makes the email convention a convenience rather than
the mechanism — and makes it the part worth building first, since everything else can be tested through it.

## Usage types, and what a caller is shown

**Usage type changes no boot-time fence.** A badly configured test instance in production still boots, and
nothing about a client's type makes an instance a test instance.

**Endpoints reserved for `test` or `demo` clients stay available to every client.** Their *visibility* is
filtered so ordinary users are not shown them, and **each endpoint asserts for itself** — as part of its
ordinary security check on incoming data — that the client it is pointed at has the right usage type. The
constraint is the target client's type, not the deployment environment, which means such an endpoint must be
able to determine a client **from the data it was given**: "carries something that identifies a client" is a
requirement on its input.

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
deliberately distinct from access; the flag says *do not advertise* and "normal" says *to whom*. Two callers now,
so it should be built once.

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
| `supportedTraits(client)` | what a client may **use** in forms and data entry |
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
internal` and take a functional group with it. Settable in source, refused from data — the treatment the
specification already gives to which clients a database-defined client may extend.

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
define the same path with different behavior. `RequestService` still has to become client-aware, and that is
the destabilizing part; but clean path separation pays for a good deal of it.

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
creating and patching entries" true without pulling per-client generated endpoints forward — those remain later
work, and are where a client gets *typed input* rather than merely validated storage.

## Inheritance between clients

**`extendsFromClientId` does not need to support chains.** One level: a client extends a template, and that is
all.

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
administrator there and `ReadScope.ofUser` is the only width that ever applies.

That is what settles the tension in giving `public` a usage type. The worry was that typing it `demo` would put
demo conveniences — relaxed security, bulk delete — within reach of the client that holds every registered
user. Those conveniences are administrative, and `public` grants no administrator, so the reach does not exist.

## The per-client schema

Fetching the current schema becomes a **method on the context**, and the `SchemaService` holds a variant of the
schema stack per client, with cloning limited to what a client actually modified so a variant costs what it
changed rather than the size of the schema.

**Anonymous callers get the default global schema**; they need one and have no client to take it from.
**`public` uses the global schema exactly**, per the identity case above.

**Invalidation is explicit and rare.** Today a variant cannot be invalidated at all. When live data editing
exists, a **client admin asks for it through an endpoint** — nothing detects a write and rebuilds behind
anybody's back — and only for a client that is not in production or has `staticConfig` false.

---

# Open questions

The only place to look for status.

## Nothing blocking

Everything the first slice needs is decided. What remains is either parked by choice or a question of ordering
rather than design.

## Open

**Dynamic disabling** is a topic of its own, to be taken up when it arrives. Nothing decided here is expected
to change because of it, so it does not need anticipating.

**Sequencing the auto-admin inversion.** Under today's rule a `+` tag means *not an admin*; under the new one it
means *this client*. Any existing plus-addressed account at a real admin domain changes meaning when this lands.
There is probably no such deployment yet, which is exactly why the change is cheap now and expensive later.
