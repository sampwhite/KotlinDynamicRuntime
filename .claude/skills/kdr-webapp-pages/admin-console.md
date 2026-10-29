## The admin console: editing someone's identity and authority (issue #225)

The Users page edits several **independent** things, and treating any two as one control is the mistake the
whole screen is arranged to prevent. Three of them are **authority**:

- **Access level** — a rung of `RoleLadder` (`user` < `operator` < `admin`), shown as a *single-choice*
  `Select`, because the levels are an ordering and holding two is not a thing one can be.
- **All clients** — an off-ladder **capability**, a checkbox. The level says *what* someone may do; this says
  *whose data* they may do it to. Different axes.
- **Editor** (issue #1432) — an off-ladder **role**, a checkbox beside the level and offered below the admin level,
  since an administrator already holds everything it adds (`rolesWithEditor`). It keeps the level, and the persona
  follows it while creating until one is chosen (`personaForRoles`). Ticking it on an existing user leaves the persona
  alone, so the search's **Roles** filter (`USF.roles`) is how to list the editors.
- **Organization** — an optional narrowing *within* a client. Blank means client-wide.

A fourth is **belonging**, and behaves unlike the other three:

- **Client** — which client the account is in (issue #352). Chosen when the user is **created** and never
  again: their content carries the client both in its `client` column and inside every `GedraId`, so moving
  someone would strand it. There is no set-client call for an editor to offer, which is why this is a `Select`
  while creating and a read-only field afterward — the one control on this page whose absence is the point.
  Offered only to a caller holding `allClients`, who is also the only one able to read `/admin/clients` to
  populate it; a scoped administrator's client is not a decision. The list gains a **Client** column under the
  same condition, because a column that says one thing says nothing.
- **Persona** (issue #750) — **which of a person's users this is** (part of the user's key, with the client and
  the persona suffix), from the kernel's `PERSONA` registry (`Member`, `Editor`, `Admin`), chosen at creation and
  never again, so a `Select` while creating and read-only afterward. It is *not* authority: no access decision reads
  it, and a user's roles move on without it. So wherever a persona is shown beside roles that differ from its
  defaults -- the Persona column, the editor's read-only field, an editor's view of a user, and the person view --
  it says the access the user has (`PERSONA.withActing`, issue #1450: `Member (Administrator access)`, `Admin (No
  access)` for a sandbox user acting with nothing), read from `actingRoles` where the row has them. It is worded as
  access, never as "acts as", which on this page names the user a sandbox user acts as. Personas
  grant roles by default and roles provide a default persona: until the administrator picks a persona it
  follows the access level (`personaForLevel`), picking one moves the level to its defaults (`levelForPersona`),
  and only a chosen persona is sent, so the backend applies the same rule to an unnamed one. The Status column
  says `unclaimed` for a user nobody has logged into yet (no registered date; the activated date is stamped at
  creation and says nothing about that).
  Creating a user at your **own** address makes an associated user of yours, registered at once and in the
  badge's switcher. The **Persona suffix** box is on every create form (issue #797; optional -- a first
  `Member B` needs it as much as a second one). A create that collides with an existing user of the same
  address, client and persona -- the backend's duplicate-key refusal, recognized by the envelope's logical
  `errorCode` (`AERR.userKeyTaken`), never by its wording -- switches the box's hint to say why it is now
  needed. **Create a user for me** (issue #797) opens the same form on the caller's own address, locked
  (`/wa/users/me`): the user is registered on creation and no invitation is sent. Every successful save on
  the page bumps the refresh bus, so a user created, disabled or deleted for yourself shows in the badge's
  switcher at once. The list shows both in a **Persona** column (`Member B`), and a **Registered** column says
  when the person claimed the user.
  **The person behind the user** (issue #770): the editor opens with the identity's view from
  `GET /clientAdmin/user/identity?userId=` (`AdminIdentity`) -- the person's other users as a list, when there
  is more than one, each choosable to edit that one instead (the open one marked, what tells each apart as the
  badge says it, its status), then a read-only **Summary**: whether the address is proven, whether a password
  is set, which user an unnamed login lands on, and the open user's dates. The read runs through the same
  user-admin scope as the rest, so a client administrator sees only the person's users in their own client,
  and "Signs in as" is a dash rather than a hint when the login lands outside it (`identitySiblings`,
  `identitySummary`). The identity's own facts -- the address proven, a password set, where a login lands, and
  each row's `hasPassword` -- describe the person's users in every client, so the backend gives them only to a
  caller who sees every client, or about their own identity (issue #1298, `AdminRules.seesIdentityFacts`); for
  anyone else the Summary leaves those three rows out (`AdminIdentity.identityFactsGiven`) and the Status column
  says nothing of a password. The rule is by who asks, never by what the identity holds, so their absence says
  nothing about the person.
  **An editor** (issue #1432) browses the page **read-only** (`usersReadOnly`, from `HFEAT.browsesUsers`). It gets the
  list and search with no create buttons, and a user's details (`userDetails`: address, name, persona, access,
  organization, labels, status) in place of the editor. Nothing is fetched that only an administrator may read: the
  person behind the user, label suggestions, and the client choices. The page's reads (`AdminApi.listUsers`,
  `searchUsers`) are the **user directory's** (`/clientDirectory/*`) for everyone, the same handlers as `clientAdmin`'s,
  which admit an administrator by implication. So the forms list's user filter works for an editor too, and only the
  writes go to `clientAdmin`. The forms listing names itself and shows owners by `HFEAT.seesClientForms` (an
  administrator or an editor; `ShellFacts.seesClientForms`).
  **In `public`** (issue #805) every self-registered user is an administrator of themselves only: the Users page
  lists their own identity's users, "Create user" is not offered (`mayCreateForOthers`; the backend refuses any
  other address and any other client), and "Create a user for me" is how they make variants. Their data reads
  stay their own, and the `clientAdmin/config` endpoints refuse them.

Two are **identity**, and sit at the top of the editor beside the email for that reason — they say who the
account is, not what it may do:

- **Name** — the account's real-world name: a person's full name, or a business's. Non-unique display copy,
  never an identifier.
- **Business account** — `isEntity`. It says how to *read* the name, not which field to read: there is one
  name input whose label switches between "Full name" and "Business name", rather than a second field
  appearing. That mirrors `UserProfile.displayName`, which is `name ?: publicName` with no reference to the
  flag, so the console and the app cannot disagree about what is shown.

One more is neither authority nor identity:

- **Labels** (issue #786) — free-form words an administrator puts on a user (`reviewer`) for a workflow's
  `userHasLabel` function to test. They **grant nothing**, which is why they sit apart from the access level
  rather than beside it. A tags `Select` offering the *user's* client's suggestions (`labelSuggestions`, fetched
  by `ensureLabelSuggestions` the first time that client is asked for and kept keyed by client, so a slow reply can
  only fill its own client's entry — never land on the next user's editor) while accepting any other label typed.
  On the create form too, offering the suggestions of the client the form names, and always sent with the create:
  given, even empty, they replace any a recovered disabled user carried. The backend applies them in the same
  follow-up write as a disable, so labels alone are not counted as an edit. The suggestions are fetched from
  effects keyed on the editor's client, not from `startEdit`/`startCreate`, which the route listener reaches with
  stale closures. The list
  has a **Labels** column and filter (`USF.labels`, `UserFilterChoices.labels`: an `AutoComplete` of the searched
  client's suggestions that also takes a typed label), and the search box matches a label too. Editable on your
  **own** row too, deliberately — a label is not privilege,
  and the approve endpoint (#787) enforces the second-person rule itself: a reviewer can never approve a form
  they own (in production, a form owned by any of their users). Saving compares
  after `normalizeUserLabels`, the kernel rule the backend stores by, so re-spacing a label is no change.

**Unticking "Business account" keeps the name.** It used to clear it, which was right while only a business
had one and is silent data loss now that a person does — reclassifying an account should not discard what it
is called. The list shows **Name** and a plain Person/Business **Type**.

**`username` is gone from this page** — no create field, no column — but it keeps its unique column, its index
and its role as a login id, and the search still matches it. A username is an identifier, not a name, and the
console displays none, so advertising it in the search placeholder would point at something you cannot see
here. Do not "tidy" that by dropping the match as well.

**Compose, never replace.** A role list is sent whole, so an edit that rebuilds it from one control silently
drops the others. `RoleLadder.rolesAtLevel(current, level)` moves someone between rungs while preserving
anything off the ladder, and `rolesWithCapability(roles, capability, granted)` moves a capability on or off.
`draftRoles` composes them **in that order** — that is what lets a level change keep a capability and a
capability change keep a level. Saving compares role *sets*, not levels, or a capability-only edit would look
like no change and never be sent.

**Controls that could only ever fail are not shown.** "All clients" appears only to a caller who holds it (the
backend refuses to grant reach the granter lacks), and the Organization field is editable only by someone not
confined to one (a confined administrator may assign only their own). The **Operator** rung of the level
`Select` follows the same rule since #464: it is deployment-wide (it requires `allClients`), so it is offered
only to a caller who holds `allClients` — via `offeredAccessLevels(operatorSelectable)`, covered under
`jsNodeTest` — except when the edited user already is an operator, so the value still shows and can be kept
(anti-escalation checks *adding*, not the result set). This is the advertise-versus-serve drift issue #211
exists to remove, relocated into a form: a control that can only produce a 400 is worse than no control.

**A sandbox user is shown with what it acts with** (issue #1198). In a sandbox the console's user info adds
`actingRoles` -- what the sandbox's rules let the user act with, which its row's `roles` need not say -- and the
Users table's Roles cell says it beside them when the two differ (`rolesCellText`). A sandbox user somebody
opened carries `opened`, and -- when the caller may know of that user (the parent's, or one they administer) --
`openedBy` and, while it still gives access, `actsAsClient`: it acts with that user's roles (and organization,
when the parent's), so the editor shows "Acts with" and a note saying whom it acts as (`openedUserNote`) in place
of the level, capability and organization controls, which the backend refuses on such a user; the save sends
neither a role nor an organization change for one.

Editing **yourself** disables the level, the capability and the enabled flag — another administrator has to
change those, so an account cannot demote or disable itself into a lock-out. Organization is deliberately *not*
disabled there: the field is only rendered for a caller who has none, and giving yourself one *narrows* you.
Note the one-way door that creates for a client-scoped administrator — once confined, `requireAssignableOrg`
will not let them clear it, so they need a peer (an `allClients` holder is exempt from the rule entirely). The
backend treats that as co-equal administration rather than a lock-out.

**But a control that produces a silent no-op is the same family**, and there the fix is a hint rather than
hiding, because the state is legal and worth keeping: `allClients` below the Administrator level is stored and
inert (the full-scope surface needs *both*, issue #237). `isAllClientsDormant(level, granted)` — pure, covered
under `jsNodeTest` — drives a note saying so, and saying that it is **kept**. Do not "fix" this by disabling
the checkbox: demoting an administrator should leave the capability dormant rather than make someone remember
to re-grant it, which is the same reasoning that makes `rolesAtLevel` preserve capabilities at all.

`AdminApi` calls the **`clientAdmin`** paths ([UADEP], renamed from `userAdmin` in #466), not the full-scope
`admin` ones. That surface serves both kinds of administrator correctly — a capability holder is simply
unconfined on it — so the console needs no branch on who is asking. `canManageUsers` from the home config
decides whether the page is offered at all; it shapes the UI and is not the enforcement point, which stays the
section gate.

Paths, field names and the ladder all come from `base/kernel`, so a backend rename breaks compilation here
rather than at runtime.

**From a user to what is theirs** (issue #1081): each row ends in a **Go to** column -- `Forms · Client`, the
`UserLinks` component -- and the editor of an existing user shows the same two links under its Client field. `Forms`
is `userFormsHref`: the forms listing confined to that user **by id, never by address**. The listing's `user` takes
either, but an address names a person, and the backend resolves it to one of their users the caller may see -- their
*default* when it is one of those, else their first (issue #1293) -- which for somebody with users in two clients (or
two personas in one) need not be the row the link was on. For an `allClients` administrator
the link also carries the user's `client` -- when it is one this node carries (`userFormsSurface`, read off the
page's `clientChoices`) -- so the listing is drawn on that client's surface; for a client not carried here it carries
none, since a surface that does not exist would fail to load where the bare link still lists the user's forms. A
scoped administrator's carries none either, as the Clients page's count links do not. `Client` is `clientDetailHref`, the one
builder of `/wa/clients/<id>`. The cell stops a click from also opening the row's editor (`onCell`, as the forms
table's workflow cell does), and the column is pinned right with Status -- a column after a pinned one must be.
They are links, not buttons: each goes somewhere and changes nothing. A `public` self-administrator is shown neither
(`userLinksOffered`): they read only their own forms and the Clients page refuses them, so both could only fail.
The forms scope bar's box holds what was applied -- the id the link carried, or an address somebody typed -- and
says who the listing is confined to on a line beneath it: "User 12: Pat Lee — pat@x.test [Member B]"
(`scopeUserLabel`). That comes from **the listing itself** (issue #1095): its `summary` carries `scopeUser`, the user
the server resolved the request's `user` to, so the name is of the rows on screen by construction, a user with no
forms is still named, and there is no second request. It matters most for an address, which names a person: the
server resolves it to one of their users in the caller's reach (their default where it can), and this line is the
only thing that says which of their users that was.
The bracket is the persona and its suffix (the client first, for an `allClients` administrator) -- always, since
saying it costs a word where finding out whether the person has other users would cost a lookup.
