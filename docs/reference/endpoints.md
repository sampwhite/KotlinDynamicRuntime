<!-- kdr-generated: begin -- copied from its generator by `./gradlew :launch:refDocsDump`; edit the generator, not this -->
# Endpoints

Every endpoint the runtime offers, one line each, grouped by section: the first segment of its path, which decides who may call it. A path here is under the API context root, `/kda` unless a deployment names another, so `GET /health` is called as `GET /kda/health`.

Each endpoint's input and output schema is in the endpoint catalog, `GET /schema/endpoints` (one endpoint by its path and method at `GET /schema/endpoint`), which lists only what the caller may call. To ask a test node, or a session in env debug, why an endpoint is withheld, add `_debug=explainAccess`. What each role reaches, and whose data each caller reads, is in [Roles and access](roles-and-access.md).

The notes on an endpoint:

- **Per client**: it has a copy for each client at a path naming the client, `/gedra/<client>/...` for `/gedra/...`. The copy answers with that client's schema and refuses another client's data.
- **Published**: part of the published API, the endpoints documented for callers outside the team. It decides what the catalog offers a caller without env auth, never who may call.
- **Session only**: a signed-in session may call it, an API key may not.
- **Test only**: present only on a test instance, and only to a caller with test access.

Not listed: a client's copies (named by the note above), the endpoints a node generates from a client's own configuration (a report's typed run), and the multi-node test harness's fixtures. The sample component's endpoints are listed; a deployment carries them only in a developer environment.

## `admin`

A signed-in caller at the `admin` level or above and holding `allClients` may call these.

| Method | Path | Notes | What it does |
|---|---|---|---|
| GET | `/admin/client/config/bundle` |  | Fetches one of a named client's configurations as a whole bundle (issue #685). |
| POST | `/admin/client/config/bundle/delete` |  | Deletes an empty configuration (issue #1461): one whose latest revision and latest published revision hold no entries -- so a published one is emptied, the empty revision published, and then deleted. Refused when either holds entries (400), and when the configuration has been written since it was read (409, errorCode 'staleEntries'). Its revisions stop being loaded, listed, and read; one made again under its name starts past every revision it had. |
| GET | `/admin/client/config/bundle/impact` |  | What publishing a named client's configurations together would do to the data that client already stores (issues #935, #1352). |
| POST | `/admin/client/config/bundle/patch` |  | Edits the slot entries of one or more of a named client's configurations -- per-slot add/replace/merge/delete over each latest revision, in one transaction judged together (issues #732, #1352); with a dry run and a staleness check, as the client-scoped patch has (issue #1434). What each slot's entries hold, and what each action's data must carry, is in [the client configuration reference](/docs/reference/client-configuration.md). |
| POST | `/admin/client/config/bundle/publish` |  | Publishes the latest editable revision of one or more of a named client's configurations, together (issues #685, #1352). |
| POST | `/admin/client/config/bundle/revert` |  | Reopens a named client's published configuration for editing (issue #734): mints a new editable revision copied from the published head, which stays immutable. A no-op when already editable; refused for a published-only client. |
| POST | `/admin/client/config/bundle/write` |  | Writes a named client's configuration from a whole bundle -- create or replace (issue #685). What each slot's entries hold is in [the client configuration reference](/docs/reference/client-configuration.md). |
| GET | `/admin/client/config/bundles` |  | Lists a named client's configurations, each as a summary of its latest revision (issue #685). |
| GET | `/admin/client/config/editor` |  | Everything the bundle editor page draws for a named client, in one read (issue #1436), as the client-scoped read answers it. |
| GET | `/admin/client/config/entries` |  | The interior of one of a named client's configurations: its stored slot entries (issues #685, #1352). |
| POST | `/admin/client/config/import` |  | Bulk-imports a set of client configurations (issue #733): each client's bundles written and judged as one set, testFeatures stripped+logged off a test instance, affected clients reloaded. |
| POST | `/admin/client/config/publishedOnly` |  | Sets whether a named client consumes only its published configuration in this environment (issue #685). |
| POST | `/admin/client/config/reload` |  | Reloads a named client's stored configuration on this node, and announces the sync marker (issue #685). |
| POST | `/admin/client/create` |  | Creates a client (issue #1054): writes its definition as its first stored configuration ('main'), publishes it and loads the client, so it is present at once wherever this node's environment is one it is enabled in. The definition is held to what any written definition is -- its schema, a domain no other client holds, a trial load that judges its id, its environments and the template it extends -- and refused for the same reasons, in the same words. Refused with a 409 for an id that is taken, by a client in code or in stored configuration. With 'newCustomer' it makes a customer too, whose id is the client's and whose root the client is (issue #1107). Unless 'starterWorkflows' is false, it starts with a creation workflow and a core details workflow made from the deployment's templates, and includes the trait they collect (issue #1346). What else a definition does not hold at creation -- its other traits and workflows, its test features -- is added through its configuration afterwards. |
| GET | `/admin/client/definition` |  | One client's definition: its attributes, supported traits, usage rules and workflow ids. |
| POST | `/admin/client/retirement/set` |  | Retires a client, or brings a retired one back (issue #1111). A retired client keeps everything it stored and is inert: absent in every environment, its users unable to sign in, their open sessions no longer honored, and its sandbox with it. Brought back, it is as it was. Takes effect live, whatever the client's tier. Refused for a client defined in code (retired there), a sandbox (retired with its parent), a customer's root client, and a definition whose configuration carries unpublished changes. |
| GET | `/admin/clients` |  | Lists the clients enabled in this environment, as they were declared. |
| GET | `/admin/clients/summary` |  | Every present client with a brief summary: its workflow ids, trait ids and usage-column labels -- or, with allKnown, every client this node knows of, each with its status. |
| POST | `/admin/formDocForUser` |  | Creates a form document owned by another user, in that user's client (issue #672). For an `allClients` admin; the shared `/gedra/formDoc/create` is unchanged. |
| GET | `/admin/gedraState` |  | Reads one gedra's state entries (issue #600). |
| POST | `/admin/gedraState` |  | Replaces one gedra's state entries with the supplied set (issue #600); a whole-set upsert. |
| DELETE | `/admin/user` |  | Deletes a user: recoverable (disabled) by default, or -- when permanent -- disabled with the user's email and identity obfuscated so the deletion cannot be undone. |
| POST | `/admin/user/create` |  | Creates a user directly, bypassing self-service email verification. |
| GET | `/admin/user/identity` |  | The person behind a user: their identity's facts, and the users of it you administer. |
| POST | `/admin/user/invite` |  | Sends (or re-sends) the invitation for a user nobody has claimed yet. |
| GET | `/admin/user/labelSuggestions` |  | The user labels a client suggests, and those its users already carry -- what a label editor offers. Suggestions only: any label may be applied. |
| POST | `/admin/user/remind` |  | Mails a claimed user a reminder of where and how to sign in. |
| POST | `/admin/user/setEnabled` |  | Enables or disables a user's account (a disabled account cannot log in). |
| POST | `/admin/user/setLabels` |  | Replaces a user's free-form labels -- what a workflow can test for (a reviewer, say). Labels grant nothing. |
| POST | `/admin/user/setName` |  | Sets a user's name, and whether the account is a business rather than a person. |
| POST | `/admin/user/setOrg` |  | Sets or clears a user's primary organization within their client. |
| POST | `/admin/user/setRoles` |  | Replaces a user's roles -- the call that grants or revokes administrator privileges. |
| GET | `/admin/userSearch` |  | Searches active users over the in-memory cache -- email and name substrings, client, persona, roles, labels, and date ranges -- sorted by most of those fields (default: newest first). Returns up to '500'. |
| GET | `/admin/users` |  | Lists users, newest first, optionally filtered by a search term over email, username, and name. |

## `app`

Anyone may call these, signed in or not.

| Method | Path | Notes | What it does |
|---|---|---|---|
| POST | `/app/envAuth` |  | Set this session's env-auth state: off (suppress), on (restore), or debug (issue #517). |
| GET | `/app/ui/config` |  | Returns deployment-wide app config visible to the entire frontend. |

## `auth`

Anyone may call these, signed in or not.

| Method | Path | Notes | What it does |
|---|---|---|---|
| POST | `/auth/claim/register` | session only | Registers and logs in as the user an address, client and persona name, with the mailed code. |
| POST | `/auth/claim/sendVerify` | session only | Mails a verification code for the user an address, client and persona name. |
| GET | `/auth/form/createToken` | session only | Issues a form auth token for subsequent auth requests. |
| POST | `/auth/invitation/accept` | session only | Accepts an invitation: registers the invited user and logs in as it. |
| POST | `/auth/invitation/preview` | session only | Says what an invitation link is for, without accepting it. |
| GET | `/auth/jwks` | published | The public keys that verify an access token, as a JSON Web Key Set: what a service checking a token needs, with no call back here. |
| POST | `/auth/login/byCode` | session only | Logs a user in with a username/email and verification code. |
| POST | `/auth/login/byPassword` | session only | Logs a user in by password (familiar devices only). |
| POST | `/auth/login/google` | session only | Logs a user in from a Google ID token (linking the account on first use). |
| POST | `/auth/newContact/sendVerify` | session only | Emails a verification code to a new email contact. |
| GET | `/auth/self/info` | published | Returns the caller's user info (anonymous when not logged in). |
| POST | `/auth/self/leaveSandbox` | session only | Switches a session in a sandbox to another of the person's users (a fresh session). |
| POST | `/auth/token` | published | Exchanges the API key sent as 'Authorization: Bearer kdr_…' for an access token: a signed JWT acting as the key would, lasting a day by default and never past the key's expiry. Asked again with the key for a new one. Form-encoded, as an OAuth client library sends it, or JSON. |
| GET | `/auth/ui/config` |  | Returns the config for constructing the auth (register/login) UI. |
| PUT | `/auth/user/createInitial` | session only | Creates the initial user row from a verified email contact. |
| POST | `/auth/user/sendVerify` | session only | Emails a verification code to an existing user. |
| PUT | `/auth/user/setLoginData` | session only | Sets the user's username (and optional password) and logs in. |
| PUT | `/auth/user/setPassword` | session only | Sets or changes the user's password (verified by a code). |

## `clientAdmin`

A signed-in caller at the `admin` level or above may call these.

| Method | Path | Notes | What it does |
|---|---|---|---|
| POST | `/clientAdmin/apiKey/access` | session only | Makes the API key of a user the caller administers read-only, or gives it full access. Giving it full access is held to what making a key for that user is. |
| POST | `/clientAdmin/apiKey/create` | session only | Makes an API key for a user the caller administers. The key acts as that user, never as the caller; the answer carries the whole key, the one time it is sent. |
| POST | `/clientAdmin/apiKey/extend` | session only | Extends the API key of a user the caller administers, by days or by months. |
| POST | `/clientAdmin/apiKey/revoke` | session only | Revokes the API key of a user the caller administers, at once and for good. |
| GET | `/clientAdmin/apiKeys` | session only | Lists the API keys of the users the caller administers, soonest to expire first. |
| POST | `/clientAdmin/assistant/cancel` | session only | Asks the turn running in one of the caller's conversations to stop: it stops at its next step, answering any tool call it had not run. Says whether there was a turn to stop. |
| GET | `/clientAdmin/assistant/conversation` | session only | One of the caller's conversations: its messages after afterSeq (all of them when absent), and its turns -- what the assistant page polls while a turn runs. |
| GET | `/clientAdmin/assistant/conversations` | session only | The caller's conversations with the workflow assistant in the client they are signed in to -- or, signed in to its sandbox, in the client itself -- most recent first. |
| POST | `/clientAdmin/assistant/message` | session only | Sends the workflow assistant a message, starting a turn, and answers at once: poll the conversation for what comes of it. A new conversation when no conversationId is given. Refused, with an errorCode, when the assistant is not on for the client, it has no sandbox, its allowance is spent, the caller has sent as many messages this hour as the assistant takes, or a turn is still running in the conversation. |
| GET | `/clientAdmin/assistant/status` | session only | Whether the workflow assistant can be used for the caller's client -- it is on, and the client has a sandbox -- how much of the month's allowance is used, and what using it sends to Anthropic. |
| POST | `/clientAdmin/assistant/switchOn` | session only | Switches the workflow assistant on for the caller's own client, where it is offered: a self-serve client, on a deployment that offers it to them, which no operator has set. The administrator acknowledges what using it sends to Anthropic (the status's dataNotice), as an operator does; the client starts with the self-serve budget. Refused from inside the client's sandbox (errorCode assistantInSandbox), and where it is not offered (errorCode assistantNotOffered). Answers the status after. |
| POST | `/clientAdmin/client/copy/apply` |  | Sets or resets several keys of one fragment file for a client in one save (issue #1062), so they take effect together: written into one stored configuration -- the one already setting any of the keys, else the first overlaying the file, else 'copy' -- trial-checked once, then published and reloaded once; a refused publish puts every key back. Each change is judged as a set or reset of one key is, and a key may be changed once. Keys held by two stored configurations are refused, since saving them would publish each on its own. For a client with a Shadow Sandbox (issue #930) the save is a draft its sandbox shows. |
| GET | `/clientAdmin/client/copy/keys` |  | Every piece of copy an administrator may override for a client -- each key of each fragment file that has a base, with the value that client reads -- including the backend files the content server never serves. The caller's own client, or one an administrator who sees every client names. |
| POST | `/clientAdmin/client/copy/reset` |  | Removes a client's stored value for one key and makes that take effect, so the key reads as the client's code or the shipped copy says. Refused when no stored value sets it. For a client with a Shadow Sandbox (issue #930) the change is saved as a draft instead -- written and reloaded, not published -- so its sandbox shows it and it goes live once published. |
| POST | `/clientAdmin/client/copy/set` |  | Sets one key's value for a client and makes it take effect: written into the client's stored configuration (the config already overlaying the file, else one named 'copy', created on the first edit), refused with its findings when a trial reload would find a new problem, then published and reloaded. A key the client's code sets is overridden, not replaced. For a client with a Shadow Sandbox (issue #930) the change is saved as a draft instead -- written and reloaded, not published -- so its sandbox shows it and it goes live once published. |
| POST | `/clientAdmin/client/create` |  | Creates a client under the caller's customer (issue #1112), for an administrator of the customer's root client. Its id is '<customer>_<name>'; it holds the creator's choices and what the root passes on -- its customer, the environments it runs in, its usage type, audience, and web resources -- and starts with no other configuration than its starter workflows, a creation workflow and a core details workflow, unless 'starterWorkflows' is false (issue #1346). The creator gets their own admin user there, returned rather than switched to. Refused for a customer on the free plan, which makes no clients, and otherwise for what the platform's create refuses, in the same words. |
| GET | `/clientAdmin/client/definition` |  | One client's definition, as `/admin/client/definition` answers it: the caller's own client, or the one named by an administrator who may see every client. |
| POST | `/clientAdmin/client/definition/set` |  | Edits a client's definition (issues #1026, #1116): its name, note, time zone, and suggested user labels -- and, for an administrator who sees every client, the platform's fields the editor offers: what it is for, whose it is, the environments it is enabled in, its preload, its domain prefix and custom domain, and its web resources (a client's own administrator naming one is refused, 403). Its customer and the template it extends are not edited here, nor the traits it includes (`/clientAdmin/client/traits/set`). An absent field is left as it is; a blank one clears it (the name may not be blank). Written to the client's stored definition under the config lock, refused when a trial reload finds a new problem, then published and reloaded; for a client with a Shadow Sandbox (issue #930) saved as a draft instead -- written and reloaded, not published -- so its sandbox shows it and it goes live once published. Refused for a client defined in code (its definition is edited there), for a sandbox (its definition is its parent's), and while the definition's configuration has somebody's unpublished changes, which a live save would publish. |
| GET | `/clientAdmin/client/files` | published | A client's file refs (issues #1153, #1154): the named files its configuration holds, each with the record of the file -- the file store's, or for a local file what the disk says of it -- and none of its content. For a client with a sandbox these are the sandbox's -- the latest revision, drafts included -- each saying whether the client's own people are served it yet, and after them those its people are served that a draft removed (`removedInDraft`). |
| POST | `/clientAdmin/client/files/describe` |  | Rewords the description of one of a client's file refs (issue #1176), or clears it with a blank one, live or as a draft as an upload is. The ref names the file it named. A 400 when no stored configuration holds the ref: one the client was shipped with keeps its description until a file is uploaded over it. |
| GET | `/clientAdmin/client/files/download` |  | The file of one of a client's file refs (issue #1153), as the listing shows it: what an administrator previews, on the page of a client that is not their own or of a draft from outside the sandbox. A 404 when there is no such ref, or its file is missing. Sent as a download, so opening its address shows no page. An address that carries the hash of the file returned may be kept by the caller's own browser. |
| GET | `/clientAdmin/client/files/download/meta` | published | What the file of one of a client's file refs is, without the file (issue #1131): the record the listing shows of it -- the file store's, or for a local file what the disk says of it now. The download's inputs and the same 404s: no such ref, or its file is missing. |
| POST | `/clientAdmin/client/files/local` |  | Names a local file as one of a client's file refs (issue #1164), replacing what the ref named: a file the deployment keeps on the server, under a root it has opened to clients and in the client's own directory there. Nothing is uploaded and nothing is copied -- the ref is served the file as it is, by the hash of its bytes when it is read. The ref is of the `clientLocal` kind, which reaches the client's own directory and nothing else. Refused with a 403 for a root that is not open to clients (a 400 for a caller who sees every client, who names any other file by a typed entry); and with a 400 when the file is not there to be served as an image, which says one thing whatever the reason. Live or a draft as an upload is. |
| POST | `/clientAdmin/client/files/remove` |  | Removes one of a client's file refs (issue #1153), live or as a draft as an upload is. The file it named is left in the file store: an older revision of the configuration still names it. |
| POST | `/clientAdmin/client/files/restore` |  | Puts back, in a client's draft, a file ref a draft removed (issue #1188): the entry the client's own people are served, exactly as it is published -- the file it names, its description, and any fields this release does not read -- into the configuration that holds it. Only for a client with a sandbox, and a ref the listing marks `removedInDraft`; nothing is uploaded or named again. |
| GET | `/clientAdmin/client/files/roots` |  | The roots of local files that are open to clients (issue #1164), by name: where a client's own administrator may name a file as a ref, the path being looked for in the client's own directory there. The names as the deployment declared them, and nothing of the disk -- not what is in a root, nor whether there is yet a directory of that name. The same for every client; empty where the deployment has opened none, which is the default. |
| GET | `/clientAdmin/client/files/takes` | published | What a client's file refs take (issue #1176): the most bytes a file may be, the kinds of image and the content types an upload may be sent as, how many refs a client is served, and the roots open to clients (those `/clientAdmin/client/files/roots` lists). The deployment's answer and no client's -- it is what a page asks before it offers to add a ref, so that it says what the backend will hold it to. |
| POST | `/clientAdmin/client/files/upload` | published | Uploads a file as one of a client's file refs (issue #1153), replacing what the ref named: an image -- PNG, SVG, ICO, JPEG or WebP -- that is what it says it is, up to the size a ref takes. The bytes are stored under their hash and the ref's entry written into the client's stored configuration, in one call. Live at once for a client without a sandbox; for one with, a draft its sandbox shows until it is published. |
| POST | `/clientAdmin/client/formStates/recalculate` |  | Recalculates the stored state of a client's forms now (issue #1412) -- which workflows each is in, and where -- as the nightly recalculation would, so that a workflow added or changed, or a change published, shows on forms nobody has saved since. Synchronous, and refused for a client holding more forms than its overview row's formStatesRecalcLimit. Engagements and approvals are never changed. Answers busy, having done nothing, while another recalculation of the client's forms runs. |
| GET | `/clientAdmin/client/menu/items` |  | The home menu's items for a client, in the menu's order: the shipped label and condition, the client's, what each condition's audience is called, the audiences the item may be shown to, and whether the client's stored configuration changes the item. Every item the menu holds, before any one caller's cfacts are applied. The caller's own client, or one an administrator who sees every client names. |
| POST | `/clientAdmin/client/menu/reset` |  | Removes a client's stored changes to one home-menu item and makes that take effect, so the item shows as the client's code or the shipped menu says. Refused when no stored change touches it. For a client with a Shadow Sandbox (issue #930) the change is saved as a draft instead -- written and reloaded, not published -- so its sandbox shows it and it goes live once published. |
| POST | `/clientAdmin/client/menu/set` |  | Renames, hides or shows one home-menu item for a client and makes it take effect: written into the client's stored configuration (the config already changing the item, else one overlaying the menu, else 'copy'), refused when a trial reload would find a new problem, then published and reloaded. Hiding or showing is presentation, not permission: the section gate still decides who may reach a page. Showing names the condition of one of the item's audiences, as the items listing gives them. For a client with a Shadow Sandbox (issue #930) the change is saved as a draft instead -- written and reloaded, not published -- so its sandbox shows it and it goes live once published. |
| GET | `/clientAdmin/client/overrides` |  | The copy (Markdown fragment keys) and interface (UiBlock items, such as menu entries) that one client's own configuration changes, each with what everybody else gets, what the client gets, and the config -- source or stored -- that set it. A stored config's value wins over the client's source one. The components' own overlays are part of what everybody else gets, not the client's changes. A client this node does not carry has none. |
| POST | `/clientAdmin/client/sandbox` |  | Adds or removes a client's sandbox (issue #932): sets the sandbox flag of the client's stored definition, publishes it and reloads, so the sandbox is loaded or withdrawn at once. A client with a sandbox runs only its published configuration; the sandbox runs the latest, for previewing it. Refused for a client defined in code (its flag is set there), for a sandbox, and while the definition's configuration has unpublished changes, which this would publish. Removing a sandbox keeps its users and data. |
| GET | `/clientAdmin/client/traits` |  | The global traits a client may include (issue #1117): each with its description, the kinds of gedra it applies to, and its fields as the platform declares them, with whether the client customizes it -- and whether the client may include #allGlobal. The caller's own client, or one an administrator who sees every client names. |
| POST | `/clientAdmin/client/traits/design` |  | Creates or edits one of a client's own traits from a design (issue #1118). A design naming no trait makes a new one, in a stored configuration of its own; one naming a stored trait edits it where it is stored. Keys are made from first labels and never change after, and a kept field keeps its kind. Written under the config lock, refused when a trial reload finds a new problem -- the plan's limits on schemas and attributes among them -- then published and reloaded; for a client with a Shadow Sandbox saved as a draft instead. An edit that would leave stored forms holding data the trait no longer accepts is refused with the impact report unless the impact is acknowledged. Refused for a sandbox (its traits are its parent's) and for a trait declared in code. |
| POST | `/clientAdmin/client/traits/narrow` |  | Narrows a global trait for a client (issue #1119): hides fields from its forms (in the layout, so the data still accepts them), requires fields in its data, and restricts fields' choices -- moves that only narrow. Written as the client's alteration of the trait's data type, in a stored configuration of its own on the first narrowing, or where a stored alteration is; an empty narrowing removes it. Then as every editor's save: published and reloaded, or a draft for a client with a Shadow Sandbox, whose impact on the live client's stored forms is reported for information. A live save leaving stored forms holding data the narrowed trait refuses is refused with the impact report unless the impact is acknowledged. Refused for a sandbox, and for an alteration declared in code. |
| POST | `/clientAdmin/client/traits/set` |  | Sets the traits a client includes (issue #1117): global trait ids, and #allGlobal for a client that may take it, as the whole list in the order to keep. An entry the list adds must be one of those; one already there is kept as it is. Written to the client's stored definition under the config lock, refused when a trial reload finds a new problem, then published and reloaded -- refused, with the impact report, when that would leave forms the client stores holding a trait it no longer supports, unless the impact is acknowledged. For a client with a Shadow Sandbox (issue #930) saved as a draft instead, judged when it is published. Refused for a client defined in code, for a sandbox (its traits are its parent's), and while the definition's configuration has somebody's unpublished changes, which a live save would publish. |
| GET | `/clientAdmin/client/usages` |  | A client's listing columns (issue #1491): each column of its forms listing as stored, with how its value reads and the stored configuration holding it, or why it cannot be changed here; and the form traits a column may read, with their fields. For a client with a Shadow Sandbox, the columns its sandbox runs, drafts included. The caller's own client, or one an administrator who sees every client names. |
| POST | `/clientAdmin/client/usages/layout` |  | Sets how a client's forms listing draws each row's workflows (issue #1599): `split`, a Your turn column beside the Workflows cell, or `combined`, one cell. Stored in the configuration already holding the client's listing settings, else the editors' own, where copy and menu edits land; a stored setting stands over one the client's code declares. Then as every editor's save: published and reloaded, or a draft for a client with a Shadow Sandbox. |
| POST | `/clientAdmin/client/usages/remove` |  | Removes a column from a client's forms listing (issue #1491), from the stored configuration holding it; one the client does not store is refused. A client showing the deployment's default columns makes the others its own; one removing its last column of its own shows the defaults again. Then as every editor's save. |
| POST | `/clientAdmin/client/usages/save` |  | Adds a column to a client's forms listing, or changes one (issue #1491). A new column is stored in the configuration holding the client's definition, and a change where the column is stored; one the client does not store is refused. A column is keyed by its trait and its heading, so a heading changed replaces the entry where it stands. Its trait, and its column id, are fixed once saved: the server makes a column id from the heading when the trait already has a column. A client showing the deployment's default columns makes them its own first. Then as every editor's save: published and reloaded, or a draft for a client with a Shadow Sandbox; the trial refuses a column that would add a problem. |
| GET | `/clientAdmin/client/web` |  | A client's web assets (issue #1130): every slot -- the favicon, the brand mark -- with the metadata of the image it shows and none of its content: the one uploaded to it, else the one the client's web resources package ships (issue #1301), saying which. A slot showing neither is listed without one, and the client's people then see the deployment's own image. A sandbox lists its parent's, which are what it shows. |
| GET | `/clientAdmin/client/web/download` |  | The image one slot of a client's web assets shows (issue #1130) -- its upload, else its package's -- as a file: what an administrator looking at another client's page previews, since the static address serves only the caller's own client's. A 404 when the slot is empty, or the node knows no such client. Sent as a download, so opening its address shows no page. An address that carries the hash of the image returned may be kept by the caller's own browser, for the session that was allowed it; any other is never kept. |
| GET | `/clientAdmin/client/web/download/meta` |  | What the image one slot of a client's web assets shows is, without the image (issue #1131): its type, size and hash, the name it was uploaded under, who uploaded it and when -- or for its package's (issue #1301), the file's name in the package and when this node read it. The download's inputs and the same 404s: an empty slot, or a client the node does not know. Read from the store as it is now, not from what this node last read of it. |
| POST | `/clientAdmin/client/web/remove` |  | Removes the image uploaded to one slot of a client's web assets (issue #1130), so its people see its web resources package's image again, or the deployment's own where the package has none (issue #1301), at once. Answers the slot as it now is; emptying one that held nothing uploaded changes nothing. Refused for a sandbox, which shows its parent's. |
| POST | `/clientAdmin/client/web/upload` |  | Uploads the image of one slot of a client's web assets (issue #1130), replacing the one there: a PNG, SVG, ICO, JPEG or WebP, up to the slot's size. What the upload says it is must be what its content is; an SVG must be well-formed and carry no script. It takes effect at once for everyone in the client -- an upload is not part of the client's configuration, so there is no draft and no publish -- and is refused for a sandbox, which shows its parent's. |
| GET | `/clientAdmin/client/workflows` |  | A client's workflows (issue #1120): the templates a workflow can be made from -- each with why it cannot be now, when it cannot -- the client's own, each with its template or why its slots cannot be changed here, and the traits its workflows may collect. The caller's own client, or one an administrator who sees every client names. |
| POST | `/clientAdmin/client/workflows/params` |  | Changes the parameters of one of a client's workflows (issues #1356, #1384, #1386, #1635) -- `windows`, a normal workflow's time windows: `lifetime`, `relevancy` and `engagement`, each a half-open `{start, end}` of moments, either bound optional; `visibility`, who sees a workflow of any kind: `{mode, labels}`; and `enabled`, whether a normal workflow is turned on: turned off, it is as if not configured, its stored data kept -- in the client or sandbox named, whoever declared the workflow. Each parameter in `params` becomes the scope's own, and each named in `unset` follows its fallback again; any other is left as it is. In force at once, with no draft and no publish; what they replace is kept among the earlier settings. A change that would, now, freeze or hide forms engaged with the workflow -- turning it off hides them -- or take one out of its owner's sight, is refused with the impact report unless the impact is acknowledged. |
| POST | `/clientAdmin/client/workflows/remove` |  | Removes one of a client's workflows made from a template (issue #1346), from the stored configuration holding it. Then as every editor's save. A live removal of a workflow forms take part in is refused with the impact report unless the impact is acknowledged. Refused for a sandbox, and for a workflow written whole or not stored by the client. |
| POST | `/clientAdmin/client/workflows/save` |  | Makes a workflow from a template for a client, or changes the slots of one made so (issue #1120): its name, and per data-entry task its label and the traits it collects. Stored as the workflow's overlay of the template, in a configuration of its own for a new workflow, or where the workflow is. Then as every editor's save: published and reloaded, or a draft for a client with a Shadow Sandbox, whose impact on the live client's stored forms is reported for information. A live change leaving stored forms affected is refused with the impact report unless the impact is acknowledged. A new workflow is seen only by whom `visibility` names, administrators only by default, from before it exists; made as a draft, that is the sandbox's, and the client's own is administrators only (issue #1384). |
| GET | `/clientAdmin/clients/overview` |  | The clients this administrator oversees, each with its status, the origin of its definition, and its counts of stored configurations, forms and active users. An allClients administrator sees every client this node knows of -- present, not enabled here, dropped by a check, or known only from stored configuration -- and a client-scoped one their own. Counts are computed for an absent client too: what a dropped client holds is exactly what its administrator needs to see. |
| GET | `/clientAdmin/config/bundle` | published | Fetches one configuration's latest revision as a whole bundle. |
| POST | `/clientAdmin/config/bundle/delete` |  | Deletes an empty configuration (issue #1461): one whose latest revision and latest published revision hold no entries -- so a published one is emptied, the empty revision published, and then deleted. Refused when either holds entries (400), and when the configuration has been written since it was read (409, errorCode 'staleEntries'). Its revisions stop being loaded, listed, and read; one made again under its name starts past every revision it had. |
| GET | `/clientAdmin/config/bundle/impact` |  | What publishing configurations' latest revisions together would do to the data the client already stores (issues #935, #1352), without publishing them. Called in a Shadow Sandbox, about its parent's data. |
| POST | `/clientAdmin/config/bundle/patch` | published | Edits the interior slot entries of one or more configurations (issues #732, #1352): per-slot add/replace/merge/delete, addressed by slot + primary key, applied over each one's latest revision, all in one transaction and judged together -- so an entry can move from one configuration to another. A configuration not yet written is created. The rest of each is left as it was. A dry run answers what the patch would do without storing it, and an edit saying what its caller read is refused if that has changed since (issue #1434). What each slot's entries hold, and what each action's data must carry, is in [the client configuration reference](/docs/reference/client-configuration.md). |
| POST | `/clientAdmin/config/bundle/publish` |  | Publishes the latest editable revision of one or more configurations, together and judged as one change (issue #1352); one already published is left as it is. For a published-only client, refused when making them live would affect the data the client already stores (issue #935) -- the refusal's errorCode is 'publishImpact' and its extraData carries the report under 'impactReport' -- unless the call acknowledges it. |
| POST | `/clientAdmin/config/bundle/revert` |  | Reopens a published configuration for editing (issue #734): mints a new editable revision copied from the published head, which stays immutable. A no-op when the head is already editable; refused for a published-only client. |
| POST | `/clientAdmin/config/bundle/write` | published | Writes a configuration from a whole bundle, applying the version/publish and diff-before-stamp rules. What each slot's entries hold is in [the client configuration reference](/docs/reference/client-configuration.md). |
| GET | `/clientAdmin/config/bundles` | published | Lists the configurations this client carries, each as a summary of its latest revision. |
| GET | `/clientAdmin/config/drafts` |  | The client's configurations whose latest revision is not published: their names and how many. From inside a sandbox, its parent's, which is what the sandbox runs. |
| GET | `/clientAdmin/config/editor` |  | Everything the bundle editor page draws, in one read (issue #1436): every configuration the client stores, with its latest revision and, when that is a draft, the published revision beside it; and each slot's entry type and key fields, with the schema types they need. |
| GET | `/clientAdmin/config/entries` | published | The interior of one configuration: its stored slot entries, each with its own accounting. |
| POST | `/clientAdmin/config/publishedOnly` |  | Sets whether this client consumes only its published configuration on nodes in this environment. |
| POST | `/clientAdmin/config/reload` |  | Reloads this client's stored configuration on this node, without a restart. |
| GET | `/clientAdmin/customer/overview` |  | One customer and its clients (issue #1114): any customer, for an administrator who sees every client; their own, for an administrator of the customer's root client, client-wide -- read-only, each still editing only their own client. Each client is a row of the Clients overview: its facts, its plan usage, its publish and sandbox state, and its issues. |
| GET | `/clientAdmin/customers` |  | The customers this deployment declares (issue #1114), for an administrator who sees every client: each with its plan, its root client, and how many clients it owns. Clients no customer owns -- platform, test, and self-serve ones -- are on the Clients page. |
| GET | `/clientAdmin/design/definition` |  | Reads one definition by its Design View address: where it was declared, and its authored entry. |
| POST | `/clientAdmin/design/heading` |  | Sets or clears a workflow's own heading for a type its pages draw, and the introduction under it, and reloads the client. |
| POST | `/clientAdmin/design/label` |  | Sets a label a workflow owns -- its own (the page title), a task's or a save's -- in its stored definition, and reloads the client. |
| POST | `/clientAdmin/design/layoutEntry` |  | Sets or clears a workflow's own layout entry for one field of a type it shows, and reloads the client. |
| POST | `/clientAdmin/design/sharedField` |  | Sets a field's copy and choices in the definition the client declares, for every workflow on the client. |
| POST | `/clientAdmin/design/sharedHeading` |  | Sets or removes a type's heading, and the introduction under it, in the definition the client declares, for every workflow on the client. |
| POST | `/clientAdmin/design/shownFields` |  | Sets which fields a workflow's form shows for a type, in order -- and so which its save writes -- or stops choosing them, and reloads the client. |
| POST | `/clientAdmin/report/create` |  | Makes a report from simple choices (issue #1492), in the client's `reports` configuration, made when it has none. One-way: the report is changed or removed afterwards in the bundle editor. Its id is made from its name, and one the client already has is refused. Each column's id is made from its heading; a column giving `key` reads the one entry of a keyed trait those values name, its path reading every entry otherwise. Then as every editor's save: published and reloaded, or a draft for a client with a Shadow Sandbox; the trial refuses a report that does not bind. |
| GET | `/clientAdmin/report/history` |  | A report's stored snapshots for a client (issue #1034), newest first and paged by cursor: each the report's grouped run -- its own grouping, over the whole client -- as it was when taken, by the nightly job for a report that asks for history or by someone pressing Snapshot now. Client-wide: refused for an administrator confined to an organization, whose own runs would narrow what a snapshot shows. |
| GET | `/clientAdmin/report/paths` |  | The report paths a client's report may read (issue #1492), for making one from simple choices: the form's own facts, each form trait's fields, and each workflow's standing and approvals, each bound against the client as its configuration load binds a report -- so a page offers only what will be accepted -- with its kind and whether a form may have several values there. For a client with a Shadow Sandbox, what the sandbox runs. The caller's own client, or one an administrator who sees every client names. |
| POST | `/clientAdmin/report/remove` |  | Removes a report the client stores (issue #1492), from the stored configuration holding it, wherever that is. One declared in code, or copied from the client this one is built on, is refused. Its snapshots are kept. Then as every editor's save: published and reloaded, or a draft for a client with a Shadow Sandbox. |
| GET | `/clientAdmin/report/run` |  | Runs one named report over a client's forms (issue #981). A detail run has a row per form, in form-id order; an aggregate run a row per group of the grouped-by columns' values, in their order with no value last, and one total row when it groups by nothing. Paged by cursor: send each page's `next` back as `after` until a page has none. Every form that exists for the whole walk is returned exactly once whatever is edited meanwhile, and one created during it appears at the end; a group is never skipped or repeated, though its count can change between pages. A client with more forms than KDR_REPORT_SCAN_LIMIT is refused. |
| POST | `/clientAdmin/report/snapshot` |  | Takes a snapshot of a report now (issue #1034): its grouped run -- the report's own grouping and excluded columns, over the whole client -- stored as it is at this moment, and returned. Any report may be snapshotted by hand, one grouping by nothing as its one total row; the nightly job takes only those that ask for history. Client-wide: refused for an administrator confined to an organization. Refused past KDR_REPORT_SCAN_LIMIT forms, as a run is. |
| GET | `/clientAdmin/reports` |  | The named reports one client may run (issue #981) -- the global ones and the client's own -- each column with the kind, combine and multiplicity its path was bound to, and where the report was declared. The summary carries the report problems that dropped a report, so one that is missing is explained. |
| DELETE | `/clientAdmin/user` |  | Deletes a user: recoverable (disabled) by default, or -- when permanent -- disabled with the user's email and identity obfuscated so the deletion cannot be undone. |
| POST | `/clientAdmin/user/create` | published | Creates a user directly, bypassing self-service email verification. |
| GET | `/clientAdmin/user/identity` |  | The person behind a user: their identity's facts, and the users of it you administer. |
| POST | `/clientAdmin/user/invite` |  | Sends (or re-sends) the invitation for a user nobody has claimed yet. |
| GET | `/clientAdmin/user/labelSuggestions` |  | The user labels a client suggests, and those its users already carry -- what a label editor offers. Suggestions only: any label may be applied. |
| POST | `/clientAdmin/user/remind` |  | Mails a claimed user a reminder of where and how to sign in. |
| POST | `/clientAdmin/user/setEnabled` | published | Enables or disables a user's account (a disabled account cannot log in). |
| POST | `/clientAdmin/user/setLabels` | published | Replaces a user's free-form labels -- what a workflow can test for (a reviewer, say). Labels grant nothing. |
| POST | `/clientAdmin/user/setName` | published | Sets a user's name, and whether the account is a business rather than a person. |
| POST | `/clientAdmin/user/setOrg` | published | Sets or clears a user's primary organization within their client. |
| POST | `/clientAdmin/user/setRoles` | published | Replaces a user's roles -- the call that grants or revokes administrator privileges. |
| GET | `/clientAdmin/userSearch` |  | Searches active users over the in-memory cache -- email and name substrings, client, persona, roles, labels, and date ranges -- sorted by most of those fields (default: newest first). Returns up to '500'. |
| GET | `/clientAdmin/users` | published | Lists users, newest first, optionally filtered by a search term over email, username, and name. |

## `clientDirectory`

A signed-in caller holding `editor` (or at the `admin` level) may call these.

| Method | Path | Notes | What it does |
|---|---|---|---|
| GET | `/clientDirectory/userSearch` |  | Searches active users over the in-memory cache -- email and name substrings, client, persona, roles, labels, and date ranges -- sorted by most of those fields (default: newest first). Returns up to '500'. |
| GET | `/clientDirectory/users` |  | Lists users, newest first, optionally filtered by a search term over email, username, and name. |

## `clientOperator`

A signed-in caller at the `operator` level or above may call these.

| Method | Path | Notes | What it does |
|---|---|---|---|
| GET | `/clientOperator/cfacts` | per client | The cfacts this client knows -- each one's group and what makes it true -- assembled as Markdown, with whether each is present for the caller now. Answers with the caller's own client's set; a path naming a client answers with that client's. |

## `demo`

Anyone may call these, signed in or not.

| Method | Path | Notes | What it does |
|---|---|---|---|
| GET | `/demo/file/download` |  | Download a previously uploaded file by id; the response body is the file itself. |
| GET | `/demo/file/list` |  | List the uploaded files, newest first. |
| POST | `/demo/file/upload` |  | Upload a file; answers with the stored file's metadata. |
| POST | `/demo/schema/sample` |  | A sample list endpoint exercising nested request/response schema, choices, dates, booleans, and integers. |

## `fixture`

Anyone may call these, signed in or not.

| Method | Path | Notes | What it does |
|---|---|---|---|
| POST | `/fixture/becomeUser` | session only, test only | Test-only: create (or find) a user by email and immediately log in as them. |
| POST | `/fixture/clock` | test only | Test-only: travel the instance clock (advance / set / freeze / unfreeze / reset). |
| GET | `/fixture/fragmentDemo` | test only | Demo: a content element whose text pulls a fragment on the backend and one on the frontend (#505). |
| POST | `/fixture/gedra/formDocEntries/fillOut` | test only | Validates a set of form-document entries against their traits and answers with them filled out as storing them would leave them. Stores nothing. |
| PUT | `/fixture/schema/complex` | test only | Processes a deeply nested, recursive object, expanding its tree's parent chain into result items (capped by `limit`). Exercises deep $ref validation and the recursive $defs population. |
| POST | `/fixture/simulate/conf-demo` | test only | Provisions the conference demo -- the client the developer documentation in docs/demo-client walks through -- and a speaker, an organizer and a committee member to sign in as. Give a suffix for a fresh copy (confdemo2), so you can change it without changing the one the documentation describes. A rerun rewrites that client's configuration; its users and forms stay. |
| POST | `/fixture/simulate/design-demo` | test only | Provisions the Design View demo client -- a client defined entirely in data, with one form showing each case the inspector tells apart -- and an administrator and a requester to sign in as. Give a suffix for a fresh copy (designdemo2, designdemoeva), so a review starts from the demo as shipped rather than from the last session's edits. A rerun rewrites that client's configuration, Design View edits included; its users and forms stay. |
| POST | `/fixture/simulate/impact-demo` | test only | Provisions the publish impact demo (issue #935): a client with a sandbox, three forms stored under its two traits, and an unpublished draft of its traits that would strand them -- one trait dropped, a field made required that no stored form has. Publishing the draft from the client page is refused with the impact report; Check impact shows it first. Give a suffix for a fresh copy (impactdemo2). A rerun publishes the harmless traits again and leaves the draft anew; its forms accumulate. |
| POST | `/fixture/simulate/listing-demo` | test only | Provisions the listing demo (issue #1316): a client holding many forms and users -- by default 650 forms and 550 users, past the 500-row cap -- so the forms listing and the Users page can be reviewed against long lists. Each form has a searchable title, a category, an amount and a due date; a few owners hold many forms, most a few, and some none; one user in six is unclaimed. A rerun tops up to the counts rather than adding them again, so asking for more grows the lists and asking for fewer changes nothing. Give a suffix for a fresh copy (listingdemo2). |
| POST | `/fixture/simulate/report-demo` | test only | Creates forms for the sample's reports to show (issue #1005): 32 acme forms over three owners, five auditors and five reporting years, and 10 globex forms with yearly records -- enough for a second page of a detail run and several groups of a grouped one. Each run ADDS that many forms again; it never rewrites. Lists an acme administrator for the Reports page, and an administrator over all clients for its Client picker and globex's reports. |
| POST | `/fixture/simulate/report-history-demo` | test only | Gives the Reports page's History view a series to draw (issue #1036): everything 'report-demo' creates, then 5 days of 6 more acme forms each, with a snapshot of acme's two history reports stored after each day's forms and dated that day -- the last 5 days, ending today. The snapshots are backdated rows marked as a simulation's; the node's clock is not moved, so every form is created now and only the counts and sums differ from day to day -- a date column reads the same on all of them. Each run ADDS the forms again, replaces each past day's snapshot with a newer one (a past day keeps its latest), and adds another for today. Shares its forms and users with 'report-demo', so running both only adds forms. |
| GET | `/fixture/simulatedEmails` | test only | Test-only: recent emails captured instead of sent (available when email is simulated). |
| GET | `/fixture/simulations` | test only | Test-only: lists the simulations this node offers -- the endpoints tagged 'simulation' -- in the catalog's shape. |
| DELETE | `/fixture/verb` | test only | Test-only: the DELETE half of a two-verbs-one-path fixture. |
| GET | `/fixture/verb` | test only | Test-only: the GET half of a two-verbs-one-path fixture. |

## `gedra`

A signed-in caller may call these.

| Method | Path | Notes | What it does |
|---|---|---|---|
| DELETE | `/gedra/formDoc` | per client, published | Deletes a form document, so that it is no longer readable or listed. |
| GET | `/gedra/formDoc` | per client, published | Fetches one form document by its gedra id. |
| POST | `/gedra/formDoc/create` | per client, published | Creates a form document carrying the supplied entries, and answers with it as stored. |
| POST | `/gedra/formDoc/import` | per client, published | Imports form documents (as a search returns them) for a user, forgiving faults per the flags (issue #545). |
| GET | `/gedra/formDoc/locks` | per client, published | Lists the traits of a form locked for the caller by the workflows it is engaged with, and whether the caller may override each lock -- and, as keptToOwner, whether the whole form is read-only for the caller. |
| POST | `/gedra/formDoc/recomputeState` | per client, published | Recomputes one form's derived state, its per-workflow entries included, and answers with the result. |
| GET | `/gedra/formDoc/singletonWorkflows` | per client, published | Lists the engaged workflows behind a form's Needs Review or Finished chip, with each one's name and what its current task asks of the caller. |
| GET | `/gedra/formDoc/states` | per client, published | Answers one form's state entries as stored, with no recompute. The entries of a workflow that is not live -- deleted from the configuration, or past its lifetime -- are left out unless includeRetired asks for them, when retiredWorkflows names those workflows. Those of a workflow the caller does not see are left out either way. |
| GET | `/gedra/formDoc/values` | per client, published | Distinct values a text column takes across the caller's own form documents, for a filter box's type-ahead. Case-insensitive, capped, and confined to what the caller may see. |
| GET | `/gedra/formDocs` | per client, published | Lists the form documents the caller may see, most recently written first, a page at a time. |
| POST | `/gedra/patch` | per client, published | Changes entries on one or more gedras, and answers with what became of each edit. Each gedra is changed in a transaction of its own, in order; a refusal for one of several says, under its extraData, which were 'committed' before it, which it is for ('refused'), and which were 'notAttempted'. |
| GET | `/gedra/workflow/aggregate` | per client, published | Lists the normal workflows the caller may work with -- those being calculated, and any appearing on a form they may see -- each with how many of those forms are eligible, engaged and finished. |
| POST | `/gedra/workflow/approve` | per client, published | Approves an approval task of a normal workflow for a form, as a reviewer of that task, and answers with the form's state as it then stands. |
| POST | `/gedra/workflow/engage` | per client, published | Puts a form into a normal workflow, or takes it back out, and answers with the form's state as it then stands. Engaging a form that fails the workflow's eligibility tests is refused with the reasons. |
| POST | `/gedra/workflow/save` | per client, published | Saves a workflow task's entries. A `create` save creates the form (or, if incomplete, answers with the unmet required traits -- not an error); an `edit` save updates the form named by gedraId, recomputes its state and answers with its id, the new state being the workflow view's to show. |
| GET | `/gedra/workflow/view` | per client, published | Resolves a workflow for rendering: its tasks, each trait's schema ref, and resolved labels. With no workflowId, the caller's client's creation workflow. |

## `health`

Anyone may call these, signed in or not.

| Method | Path | Notes | What it does |
|---|---|---|---|
| GET | `/health` |  | Basic health and identity of this node (uptime, node id, version, cluster membership). |

## `home`

Anyone may call these, signed in or not.

| Method | Path | Notes | What it does |
|---|---|---|---|
| GET | `/home/ui/config` |  | Returns the config for constructing the shell (layout, links, menu). |

## `logout`

Anyone may call these, signed in or not.

| Method | Path | Notes | What it does |
|---|---|---|---|
| GET | `/logout` | session only | Logs the current user out (clears the session cookie). |

## `operator`

A signed-in caller at the `operator` level or above and holding `allClients` may call these. A GET that reaches the node directly, not through a proxy, needs none of this.

| Method | Path | Notes | What it does |
|---|---|---|---|
| POST | `/operator/accessToken/rotateSigningKey` | session only | Publishes a new key to sign access tokens with, which begins to sign a couple of minutes later, once every node holds it. The old key stays published, and goes on verifying, until every token it signed may have expired. |
| POST | `/operator/assistant/client/set` |  | Switches the assistant on or off for a client and sets its monthly budget. Switching it on from off needs acknowledgeDataNotice: what is sent to Anthropic is in the listing's dataNotice. |
| GET | `/operator/assistant/clients` |  | Every client this node carries -- and any it no longer does that an operator once set -- with its assistant switch, monthly budget, and this month's use; the summary says whether the node can reach the model at all. |
| GET | `/operator/boot/checks` |  | Reports every boot check this node ran: the mode each resolved to, and what each found. |
| POST | `/operator/cache/reload` |  | Forces this node's table cache(s) to reload now: one table when 'table' is given, else all. Acts on the node that serves the request -- which is the node whose state the report shows. |
| GET | `/operator/cache/state` |  | Reports this node's in-memory table caches and the change dates all nodes share. |
| GET | `/operator/db/tables` |  | Lists the database tables registered for this instance. |
| GET | `/operator/env/reference` |  | The environment variables this node declares, assembled as Markdown with each variable's resolved value here. |
| GET | `/operator/fileStore/buckets` |  | The file store's buckets -- every one a component declared -- and, in the summary, what the store is. |
| GET | `/operator/fileStore/download` |  | One file of a bucket, as a download under the name it was stored under, else its key's last segment with its kind's extension -- checked against the hash its record holds, as every read is, so a damaged file is refused rather than handed out as good. A 404 when there is no such file. |
| GET | `/operator/fileStore/download/meta` |  | What one file of a bucket is, without the file: its record. The download's inputs, and the same 404. |
| GET | `/operator/fileStore/list` |  | The folders and files directly under one folder of a bucket. Folders are read off the keys, as the store keeps none; a file's record is folded into its row, and a file missing its record or its content is listed flagged. A folder holding more than the limit is cut, folders first, and says so. |
| GET | `/operator/flavors` |  | The deployment flavors this node declares and runs with, and what each active one set. |
| GET | `/operator/fragments/check` |  | Syntax-checks this instance's Markdown fragment files, reporting problems with positions, and reports the data each entry reads. With 'data', also reports required paths it lacks; that is a presence check and does not detect a value of the wrong type. |
| POST | `/operator/job/abort` |  | Asks a batch job's active launch to abort; its holder stops at its next heartbeat. |
| GET | `/operator/job/exceptions` |  | Reports the jobs whose tasks failed on a gedra (issue #871), within the caller's reach. |
| POST | `/operator/job/launch` |  | Launches a batch job from an endpoint, on every client this node carries or the ones named. |
| GET | `/operator/job/status` |  | Reports how each registered batch job's launches and clients stand, or one job's when named. |
| GET | `/operator/job/trace` |  | Reports a launch's trace (issue #879): the decisions it made, in the order they were recorded. |
| GET | `/operator/request/largeBodies` |  | The endpoints that accept a request body larger than the default (issue #1446), each by method and full path, the API context root included, with the two limits: what a firewall in front of the deployment has to let through. Fixed when the node starts, so a job keeping the firewall's rules in step may cache it. |
| GET | `/operator/system/info` |  | Reports this node's build, identity, uptime and JVM statistics, optionally requesting a garbage collection first. |

## `profile`

A signed-in caller may call these.

| Method | Path | Notes | What it does |
|---|---|---|---|
| POST | `/profile/self/clearPassword` | session only | Removes the caller's password (opt out of password login). |
| POST | `/profile/self/setName` |  | Sets the name the caller is shown under; blank clears it. |
| GET | `/profile/ui/config` | session only | Returns the config for constructing the profile page. |

## `schema`

Anyone may call these, signed in or not.

| Method | Path | Notes | What it does |
|---|---|---|---|
| GET | `/schema/endpoint` |  | Looks up a single registered endpoint by exact HTTP method and path, in the same shape as /schema/endpoints (a one-element `endpoints` list plus the shared $defs). |
| GET | `/schema/endpoints` |  | Lists the registered endpoints (with input/output schema and a shared $defs), filtered by namespace, HTTP method, or a path regex. |
| GET | `/schema/metaSchema` |  | The schema for schema: the shape of every keyword this runtime reads in a schema definition, generated from the tables its parser reads, with the rules of the dialect the document cannot state. |

## `user`

A signed-in caller may call these.

| Method | Path | Notes | What it does |
|---|---|---|---|
| POST | `/user/self/apiKey/access` | session only | Makes one of the signed-in user's API keys read-only, or gives it full access. |
| POST | `/user/self/apiKey/create` | session only | Makes an API key for the signed-in user. The answer carries the whole key, the one time it is sent. |
| POST | `/user/self/apiKey/extend` | session only | Extends one of the signed-in user's API keys by days or by months. |
| POST | `/user/self/apiKey/revoke` | session only | Revokes one of the signed-in user's API keys, at once and for good. |
| GET | `/user/self/apiKeys` | session only | Lists the signed-in user's API keys, revoked and expired ones included, newest first. |
| POST | `/user/self/createClient` | session only | Creates the caller's own self-serve client (issue #1113), for a person signed in to 'public', who creates one. Its id is generated from its name ('free_...'); it has no customer, so it is on the free plan, and it has a sandbox. The creator gets their own admin user there, and the session moves to it. Its home page's wordmark, title, intro and note may be given too (issue #1336): written and published with the client, so they show at once. Otherwise refused for what the platform's create refuses, in the same words. |
| POST | `/user/self/join` | session only | Joins a demo client as one of its roles (issue #1248): a user of the caller's own identity there, found or made, with the role's persona and labels; the session moves to it. Refused without the acknowledgment that the client is a shared demo space, unless the caller has joined it before. Offered only to a caller in 'public' on a deployment that offers joining; absent elsewhere. |
| GET | `/user/self/joinable` |  | The demo clients the caller may join (issue #1248), each with the roles it may be joined as and the caller's user in each role they hold. Offered only to a caller in 'public' on a deployment that offers joining; absent elsewhere. |
| POST | `/user/self/openSandbox` | session only | Opens a client's sandbox as the caller's own user in it (a fresh session). |
| POST | `/user/self/removePublic` | session only | Permanently removes one of the signed-in person's own users in the 'public' placeholder client. |
| POST | `/user/self/setDefault` | session only | Chooses which of the signed-in person's users their address logs in as. |
| POST | `/user/self/switch` | session only | Becomes another of the signed-in person's users (a fresh session). |
| POST | `/user/self/switchClient` | session only | Becomes the signed-in person's user in a client (a fresh session): their default there. |
| GET | `/user/self/users` |  | Lists the users the signed-in person may act as. |

## On an edge node

An edge serves the application's endpoints too, and adds these, under the same section rules.

### `auth`

Anyone may call these, signed in or not.

| Method | Path | Notes | What it does |
|---|---|---|---|
| POST | `/auth/env/login` |  | Sign in to this environment with a Google account whose address is in the permitted domain. |
| GET | `/auth/env/logout` |  | Clear this environment's sign-in, dropping the perimeter cookie -- the counterpart to signing in. |
<!-- kdr-generated: end -->

## About this file

**Everything above this section is generated** from the endpoints and section rules of two test nodes, an
application node and an edge, booted with every component the repository ships, so it cannot disagree with what is
declared or with the access the dispatcher enforces. It is not served on the **Documents** page: a running node holds
only the components its deployment carries. Its catalog, `GET /schema/endpoints`, lists what the caller may call there,
with the schemas. `./gradlew :launch:refDocsDump` copies it into this file, and the suite fails while the copy is
behind the code, so edit the endpoint (below), never the copy.

## Where it comes from

| What | Source |
|---|---|
| An endpoint's path, method, description, and notes | the endpoint builders in [`EndpointBuilder.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/endpoint/EndpointBuilder.kt), called beside each endpoint's handler |
| The sections and the rules each enforces | the section lists in [`RequestService.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/http/request/RequestService.kt), and [`SectionRules.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/http/request/SectionRules.kt) |
| Which endpoints have a copy per client | `hasClientCopy` in [`ClientEndpoints.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/startup/ClientEndpoints.kt) |
| The reference page | [`EndpointIndex.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/endpoint/EndpointIndex.kt) |
| The boots it is made from | `endpointIndex` in [`ReferenceCopies.kt`](../../launch/test/kdn/ReferenceCopies.kt) |
