## A public test deployment (issue #1150)

A test instance the public can reach (`KDR_PUBLIC_TEST_DEPLOYMENT`, the backend's `PublicTestDeployment`): env-authed
people get the test surface (#1149); everyone else uses the app as on a real deployment, or by default is refused
every sign-in and registration with the auth copy's `error.testDeploymentSignIn`. The app config's
`publicTestDeployment` feature is served to every caller, and while it is true the shell draws `TestDeploymentBand`
along the bottom edge of every page (`.test-deployment-band`, a deep rose -- apart from the accent, the admin bar, the
sandbox and success) and marks the page root (`data-test-deployment`, `markPageTestDeployment`) so the last of a
page's content clears it. Fixed at the bottom, not the top, so it shows at any scroll without competing with the
sticky bar. Every mail says the same beneath its footer (`common.testDeploymentNote`).

The band's words are copy (issue #1697): the `home` file's `testDeployment.band` for the caller's client, as plain text,
which the app config serves as the `testDeploymentBand` setting beside the flag, so the band has them from its first
frame rather than switching words once the page's copy arrives. Blank or absent, it says `APP.defaultTestDeploymentBand`,
since the band is never drawn empty. The `reference` flavor says "A demonstration site: what you add here is cleared
from time to time."

## Site policies (issue #1711)

A deployment's privacy policy and terms of service are documents it names in the instance config keys
`HCFG.privacyPolicyDoc` and `HCFG.termsOfServiceDoc` (the boot is refused if one names a document no component
declares). The shell config sends their ids to every caller, signed in or out (`HFLD.privacyPolicy`,
`HFLD.termsOfService`), and `sitePolicyLinks` (`SitePolicies.kt`, covered by `SitePoliciesTest`) makes them links to the
documents page, which a signed-out visitor can read. They are drawn in three places, and nowhere on a deployment that
names neither:

- **`SiteFooter`**, under every page, after the page's boundary so a failed page still has it: what Google's sign-in
  verification looks for on the home page.
- **The sign-in page**, under the sign-in choices, in every mode: "Before you sign in: Privacy policy · Terms of
  service".
- **Profile's account panel**: "This site's policies: ...".

The words are the app's (`SITEW`), as a document's name is what a reader looks for. The `reference` flavor names
dynamicruntime.org's (kdn's `SitePolicies`, kept in `docs/dynamicruntime-org/`), which kdn declares only where the keys
name them, so another deployment neither serves nor links them.

## Joining a demo client (issue #1248)

On a deployment that offers it (`JOIN.offeredKey`, set by the `reference` flavor), a visitor signed in to `public` can
**join** a demo client -- `acme` or `designdemo` as an administrator, `confdemo` as a speaker, organizer or committee
member -- getting a user of their own identity in that **one shared** client. What a client may be joined as is its
definition's `joinRoles` (kernel `ClientJoinRole`: a persona, a suffix telling two roles' users apart, labels, and
what the page says of it), held at load by `ClientCheck.joinRoleFaults` (never on `public`, `hub`, a sandbox or a
production client).

- **`JoinPage` (`/wa/join/<client>`, `JoinApi.kt`)** lists the client's roles (`GET /user/self/joinable`) under the
  disclosure (`joinDisclosure`): that the space is shared, that an administrator -- another visitor -- sees the users'
  names and addresses and can change or disable them, and that it all goes when the site's data is cleared. A join
  is offered only once the acknowledgement is ticked, and `POST /user/self/join` refuses one without it. A join
  moves the session (`afterSessionChange`); a role already joined offers **Open as** instead (a switch).
- **The "Shared demo" chip** in the bar, while the shell config's `state.sharedDemo` is true, is the standing note on
  every page that what you do there other visitors see; clicked, it says so (`sharedDemoNoteText`).
- **Where joining is not offered**, or for a caller outside `public`, both endpoints answer 404, and the page shows
  that refusal. The demo clients `designdemo` and `confdemo` are provisioned at boot on a reference deployment
  (test instances only), so there is always one to join. The public home page (#1324) is where visitors meet it.
- **A joined user is stamped a visitor's** (issue #1668): `ClientJoin.join` records the role in the user's data
  (`AuthUserRow.joinedAs`), since persona and suffix cannot tell a visitor from the client's own administrator.
- **A joined user is the same person, by name and role** (issue #1689): a join gives a user with no name the name of
  the user the person joined from, and the switch list carries the role's label (`UserChoice.roleLabel`, from the
  client's `joinRoles` by `joinedAs`), which the bar's bracket says in place of the persona: `Pat [Conference demo ·
  Speaker]`, `[Conference demo · Committee member]`. A role the client no longer offers falls back to the persona.
- **Whether a visitor may change the demo client's configuration** is the deployment's (`JOIN.visitorsEditConfigKey`,
  off by default, on in the `reference` flavor; `ClientJoin.configEditRefusal`). Off, every configuration write --
  bundles, copy and menu, file refs, workflows' parameters, Design View -- refuses a visitor of a demo client (403),
  and the overview row's `configLocked` says why, so the Clients page, the bundle editor, and Design View show it
  read-only. A client the visitor made for themselves is theirs, untouched by it. Web assets are not configuration,
  and are not covered.

## Creating your own client from the home page (issue #1228)

For a caller **signed in** to `public` (a visitor who has not signed in acts in `public` too, and sees nothing of
it), Home draws `CreateOwnClientCard` (`CreateOwnClient.kt`) under its copy: a name, an optional note, and a time
zone (`TimeZonePicker`, starting on the browser's), with what creating one means (an id made from the name, the free
plan, the person its administrator). It posts `POST /user/self/createClient`, and a create that moved the session
moves the page with it (`afterSessionChange`): to the workflow assistant when the new client's menu offers it -- read
as its administrator, after the move (`landingAfterCreate`, issue #1569) -- so the person goes on to design its New form
and a workflow, and to the client's home otherwise. One that made the client but did not move -- the person's user there
could not be made (`userFailure`), or this node does not carry it -- says so (`createOwnClientFailureText`) and
re-reads the config. A person creates one, so the card asks the shell config which they made (`state.selfServeClient`,
read off the identity, for `public` callers only) rather than trying the create: once made, the card opens it
(`AuthApi.switchClient`), and a client this node no longer carries is said to be gone
(`state.selfServeClientAbsent`). It is the last section of the public home page, below.

**Starting from a template.** Where the deployment declares code-defined clients marked for public use
(`publicTemplate`) and enabled in its environment (`ClientService.publicTemplates`: not retired, extending none), the
shell config lists them to a person who has made no client (`state.selfServeTemplates`, each its id and name -- never
the definition's description, an internal note), and the card offers **Start from**: a blank client, or a template.
Nothing else is ever offered or accepted: the mark is the deployment's statement that anyone may build on it (issue
#1567). A platform operator may build a client on any code-defined client; a customer's administrator only on a
marked one. A choice a re-read of the config no longer lists falls back to a blank client (`effectiveStartFrom`). A
template's client extends it (`extendsFromClientId`): it inherits the template's traits, workflows, and copy, and
starts with no starter workflows, since a template is a whole starting point (the starters' checkbox is hidden). Its
New form is the template's until it writes its own creation workflow, which replaces the inherited one whole. The backend holds the choice to the same list and refuses anything
else before the person's one client is claimed. The sample component ships one, `starterkit`, enabled in `unit` and
`local` only, so a deployment running as `dev` does not show it to the public.

**Setting up the new client's home page as it is made** (issue #1336). Under those fields an optional group,
"Your client's home page", takes a wordmark, a title, an introduction and a note (the `home.md` keys `brand`, `title`,
`intro`, `note`, sent as `CLD.homeBrand` and its neighbours, blanks left out), and a favicon and a brand mark. The copy
rides the create: the backend writes it as the client's `copy` config beside `main`, in the same trial-judged write,
and **publishes both**, so it is live at once although a self-serve client has a sandbox and every later copy edit is
a draft. It lands where the Clients page's copy editor would put it, so editing it there later behaves as for any
client. The images cannot ride the create -- a web asset is a file, uploaded to a client that must already exist --
so once the create has moved the session, the card uploads each through the ordinary
`POST /clientAdmin/client/web/upload` as the new client's administrator. Every image is checked first against its slot
(`imageProblem`, by the kernel's `WebAssetKinds.slotDefs` and kinds, which the backend's checks read too), and Create
waits while one is wrong, so a file the server would refuse never leaves a client half set up. An upload refused
anyway leaves the card in place with a note naming which (`imageUploadsNote`) and **Continue to your client**: the
client exists, and the image is added from My client. The client's own note is labeled "Internal note" here, to keep
it apart from the home page's.

**Home's copy resolves links as a document's do** (issue #1510). The intro and the note are drawn with the shell's
`copyLinkResolver`, as `PublicHome`'s sections are, so `[start here](/docs/start-here.md)` opens the in-app document
rather than asking the server for `/docs/...`, which is a 404. Both the shared intro and the `reference` flavor's
point to "Start here" that way.

## Home cards (issue #1636)

Under its copy (the intro and the note) and above the public home page's sections, Home draws the **home cards** the
shell config sends this caller (`state.cards`, `HomeCards.kt`): a panel each, with its title, its Markdown text (links
resolved as the intro's are), a row of links, and a primary button. The backend decides which cards a caller gets,
from cfacts, as it decides the menu: the cards are a registered UiBlock (`homeCardsBlock`), so a component adds its own
with an overlay and a client hides or rewords one in its configuration. The page draws the list it is handed and adds
none of its own.

- **Register** (`HCARD.register`, issue #1652), for a visitor who has not signed in, wherever every address may sign in
  (`kdr:signInOpenToEveryone`): anywhere but a public test deployment that refuses the public. Its button goes to
  Register and its link to Log in. Its body is copy, the `home` file's `registerCard.text`, named by the card's
  `textCopy` (`homeCardBody`), so a deployment rewords it as it does the intro: the `reference` flavor's says what
  registering there is for and what the address is used for, which its intro said to everyone until then. The menu's
  Register and Log in are offered more widely (`kdr:signInOpenToSome`), since an administrator on the admin domain
  still signs in, and registers first, on a public test deployment whose login carries env auth. Both facts are
  `PublicTestDeployment`'s, asked of the rule the sign-in flows refuse by, and tested against it.
- **Register is the page's call to action** (`HCARD.actionFirst`, issue #1698), since on a phone its button sat
  several screens down, under the intro and its own three paragraphs. Such a card draws its button and links first, in
  one row (`home-card-lead`), then its words; and the row under the title draws, in place of a link to it, a primary
  button that does what the card's button does (`homeCardJumpButtons`, `HomeCard.leadsJumpRow`): Register opens the
  Register page. Not a scroll to the card: on a window that shows the whole page, a scroll moves nothing, and a button
  that does nothing reads as broken. The card is left out of what the row's links count as drawn, so a link the copy
  writes to it (the `reference` flavor's `[Register](#home.card.register)`) is dropped by `jumpLinksFor`, and
  `homeCardJumpLinks` adds none. A card with the flag but no page keeps its link; one without the flag draws as before.
- **The Register page says what the address is for, where it is typed** (issue #1698): the auth file's `register.intro`,
  empty in core, drawn above the form as the Log in page draws `login.intro`. The `reference` flavor repeats the card's
  promise there, since both Register buttons on the home page come straight to the form.
- **Waiting on you** (`HCARD.waiting`, issue #1637), first among a signed-in caller's, for anyone signed in to a client
  with a workflow whose step can wait (`kdr:clientHasWaitingWorkflows`: a normal or core details workflow), since its
  read is a pass over the forms the caller may see: the forms with a step that is the caller's to take, each linked to
  that step (`workflowPageRoute`, in edit mode; for a highlighted Start, issue #1662, engaging the form on arrival) with
  what it asks beside it, and "See all N" to the forms listing
  narrowed to them (`forms-listing.md`'s "Waiting on you"). Home reads it (`useWaitingOnYou`, the listing with `needsMe`
  and a limit of five) -- confined to the caller's own client for one who sees across clients, never the whole
  deployment -- and draws the card, and its link under the title, only once something waits (`visibleHomeCards`).
- **Unpublished drafts** (`HCARD.drafts`, issue #1638), next, for an administrator of a client with a sandbox
  (`kdr:clientHasSandbox`), where an edit is a draft until published: how many configurations have unpublished
  changes, worded as the Clients page's banner is (`draftsCountText`), the first three by name, and **Review and
  publish** to the client's publishing panel -- the sandbox's own page from inside the sandbox (`draftsPublishRoute`),
  as the assistant's drafts link. Home reads `/clientAdmin/config/drafts` (`useUnpublishedDrafts`), which answers for
  the sandbox's parent from inside it and skips the bundle listing's per-row checks, and draws the card only once a
  draft is known (`visibleHomeCards`).
- **Getting started** (`HCARD.checklist`, issue #1639), next, for the administrator of a self-serve client, never
  inside its sandbox (`kdr:inSandbox`), which has users and forms of its own: the steps that make the client useful,
  each checked off from its state by `/clientAdmin/client/checklist` (`ClientChecklist`) -- its New form different, as
  it runs, from the starter (so one changed back is not done; for a client whose base has a New form, one of its own at
  all), a workflow of its own, someone besides its
  founder, and a first form. The workflows are read as its editors change them, drafts included. The webapp leads with
  **Switch on the workflow assistant** where the menu offers the assistant and the person may switch it on
  (`checklistSteps`, from the status Home already reads; core never asks the module), and links each step that is left
  to where it is done: the client page's Forms and data panel, Users, or New form. The steps wait for the assistant's
  status where the menu offers it, so the list never grows a step after it is drawn. The card draws while a step is
  left; once every one is done, or **Hide this checklist** is pressed, it is put away in this browser for good
  (`checklistHiddenKey`, per client, read once per client) and Home stops reading it.
- **Core's other cards** are shortcuts: **Your client** for an administrator of one client (My client, Users, Reports),
  **Clients and customers** for every client's administrator, **Your client's forms** for an editor, and
  **Operating this deployment** for a deployment operator.
- **The assistant module's cards** (`ASST.homeCard`, `ASST.createHomeCard`): the workflow assistant, for whoever its
  menu entry is offered to, and "Design your own forms" for a person signed in to `public` where the deployment offers
  the assistant to self-serve clients. The create card asks whether that card was sent (`ownClientAssistantOffered`)
  and then says the page moves to the assistant once the client is made.
- **A card with a `kind`** reads what it needs when it is drawn, never in the polled shell config. The assistant's
  (`ASST.cardKind`) uses the assistant's status, read by Home, and while the person may switch it on its button reads the card's
  `offerLabel` ("Switch on the assistant", `assistantCardActionLabel`) -- in the card, so an overlay rewords it with
  the rest; until the read answers, or if it fails, the card's own words stand. A kind the page does not know draws
  the card's words alone. **A new kind is added in `HomeLiveReads` alone** (`HomeCards.kt`): its read in
  `useHomeLiveReads`, whether it has something to say (`shows`, which `visibleHomeCards` applies), its button's words,
  and its body. Each read goes through `useCardRead`, where only the latest read lands, so a slow answer from before a
  refresh never replaces a newer one.
- **A card's body may be copy** (`HCARD.textCopy`, issue #1652): `namespace.key` of the shell's `home` file, drawn in
  place of its literal `text`. A blank value draws no body, as blank copy removes anything else. It is how a card's words
  are reworded by a deployment flavor, which can reword copy but cannot overlay a block.
- **A card goes somewhere and never calls**: an action or a link that is not a route is dropped as it is read, and so
  is a link with no words, rather than show a page's internal id (`homeCardsFrom`). Each card's element id is
  `home.card.<id>` (`homeCardElementId`), so an address can name it.
- **A card may be named in the row of links under the title** (`HCARD.jumpLabel`), beside the copy's own jump links
  ("Start here", on the `reference` flavor): Home adds a link to each such card the copy does not link to already
  (`homeCardJumpLinks`; the `reference` flavor's row leads with Register's, issue #1652), written in the
  copy's own style -- list items after a list, `·` after a row on one line (`withCardJumpLinks`) -- and counts the
  cards' element ids among what is drawn, so `jumpLinksFor` keeps them. Core's cards all are, so an administrator,
  an editor, or an operator sees at the top what the page offers further down, as the public home page's sections are
  named there. The assistant's card is one too, since it may sit below a long introduction; its link reads the card's `offerLabel` while the person may switch it on, as its button
  does. Home reads the assistant's status once (`useAssistantCardView`) for both. A client's copy that blanks
  `home.jumpLinks` still leaves the cards' links; an overlay removes a card's by clearing its `jumpLabel`.
- **The shell config resolves the menu and the cards together** (`UiBlockService.resolveAll`), so the caller's cfacts
  are assembled once per read of the polled config, not once per block.
- The cards sit side by side where the page is wide enough (`.home-cards`).

## The public home page (issue #1324)

For a caller **signed in** to `public` (`isPublicHome`), Home draws `PublicHome` under its hero and intro: what a
person can do next on a teaching site, in two sections, each worded by `home.md`'s `@publicHome` keys (the
`reference` flavor rewords `demosIntro` for its teaching site).

1. **Try a demo**, only where the shell config says joining is offered (`state.joinOffered`) -- absent rather than
   empty elsewhere, and the page never asks: a card per client `GET /user/self/joinable` lists, its roles, an
   **Open as** switch for each role joined (`joinedRoles`), and **Join…** to the Join page while a role remains
   (`hasRoleToJoin`; one an administrator disabled is neither).
2. **Your own client**: `CreateOwnClientCard`.

There is no list of the person's other users (issue #1672): the account menu switches between them, and a demo's card
opens each role joined. It was a third section, drawn between these two once a person had a second user, and was
removed because it offered only switches the page and the menu already had.

Which of the two are drawn is `publicHomeSections(config)`, and the page draws by it. Each section's container has a
fixed id from `HSEC` (`home.demos`, `home.own-client`), never one taken from its heading, since the
heading is copy. The dot is deliberate: the id Markdown gives a heading (`headingSlug`) never has one, so a heading in
the copy above (an intro's "Demos", say) cannot take a section's id.

### Jump links under the title (issue #1576)

The sections sit below the hero, title, intro, and note, so Home draws a row of links to them directly under the title
(`nav.home-jump-links`). The links are copy, `home.jumpLinks` in `home.md`'s `@home`: a Markdown list, shipped as one
link per section by its id (`[Try a demo](#home.demos)`). A flavor or a client rewords, reorders, or extends it like
any other copy.

Copy cannot say who sees what, so `jumpLinksFor(source, drawn)` (`JumpLinks.kt`) filters it against
`publicHomeSections`:

- A list item whose every link is an anchor (`#id`) for a section not drawn is dropped, with its continuation lines
  and any items nested under it. So there is no "Try a demo" where joining is not offered.
- Anywhere else (a row written on one line, `[A](#home.demos) · [B](#home.own-client)`, or an item that also links
  elsewhere), such a link is removed with the separator after it when a separator or the line's end follows it, and
  is otherwise left as its words with no link, so a sentence still reads.
- Every other link is kept as written, including one to a document (`/docs/...`), a page (`/wa/...`), or outside the
  site. Links resolve as the intro's do (`copyLinkResolver`, which leaves an anchor alone).
- With no link left (an image is not one), or the key blank, no row is drawn. That is how a client's copy removes it.

Outside the public home page (a visitor who has not signed in, or any other client's Home), the drawn set is empty, so
only links to something other than a section would show; the shipped list shows nothing there. The `reference` flavor
puts **Register** (`#home.card.register`, the Register card, issue #1652) and **Start here** (`/docs/start-here.md`)
before the shipped links, so its visitors who have not signed in see those two -- Register as a button opening the
Register page (issue #1698).

The anchors need no routing: a Markdown anchor is the browser's to follow (#1125), `scroll-padding-top` stops it below
the app bar, and Back returns from it. An address that names a section on arrival (a reload after a jump) is scrolled
to by Home once it has drawn the page, since the browser looks for the element before Home has fetched what it draws.
Content that arrives later above the section (the demo cards) is left to the browser's scroll anchoring, which keeps
the section in place in Chrome and Firefox; Safari has none.

A component's props set inside its builder must be qualified when the calling component has state of the same name:
`PublicHome { this.config = current }`. A bare `config = current` resolves to Home's own state, sets it during render,
and loops (React #301).
