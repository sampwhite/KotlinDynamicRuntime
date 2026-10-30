## A public test deployment (issue #1150)

A test instance the public can reach (`KDR_PUBLIC_TEST_DEPLOYMENT`, the backend's `PublicTestDeployment`): env-authed
people get the test surface (#1149); everyone else uses the app as on a real deployment, or by default is refused
every sign-in and registration with the auth copy's `error.testDeploymentSignIn`. The app config's
`publicTestDeployment` feature is served to every caller, and while it is true the shell draws `TestDeploymentBand`
along the bottom edge of every page (`.test-deployment-band`, a deep rose -- apart from the accent, the admin bar, the
sandbox and success) and marks the page root (`data-test-deployment`, `markPageTestDeployment`) so the last of a
page's content clears it. Fixed at the bottom, not the top, so it shows at any scroll without competing with the
sticky bar. Every mail says the same beneath its footer (`common.testDeploymentNote`).

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

## Creating your own client from the home page (issue #1228)

For a caller **signed in** to `public` (a visitor who has not signed in acts in `public` too, and sees nothing of
it), Home draws `CreateOwnClientCard` (`CreateOwnClient.kt`) under its copy: a name, an optional note, and a time
zone (`TimeZonePicker`, starting on the browser's), with what creating one means (an id made from the name, the free
plan, the person its administrator). It posts `POST /user/self/createClient`, and a create that moved the session
moves the page with it (`afterSessionChange`). One that made the client but did not move -- the person's user there
could not be made (`userFailure`), or this node does not carry it -- says so (`createOwnClientFailureText`) and
re-reads the config. A person creates one, so the card asks the shell config which they made (`state.selfServeClient`,
read off the identity, for `public` callers only) rather than trying the create: once made, the card opens it
(`AuthApi.switchClient`), and a client this node no longer carries is said to be gone
(`state.selfServeClientAbsent`). It is the last section of the public home page, below.

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

## The public home page (issue #1324)

For a caller **signed in** to `public` (`isPublicHome`), Home draws `PublicHome` under its hero and intro: what a
person can do next on a teaching site, in three sections, each worded by `home.md`'s `@publicHome` keys (the
`reference` flavor rewords `demosIntro` for its teaching site).

1. **Try a demo**, only where the shell config says joining is offered (`state.joinOffered`) -- absent rather than
   empty elsewhere, and the page never asks: a card per client `GET /user/self/joinable` lists, its roles, an
   **Open as** switch for each role joined (`joinedRoles`), and **Join…** to the Join page while a role remains
   (`hasRoleToJoin`; one an administrator disabled is neither).
2. **Your other accounts**: the shell config's `users` without the current one (`otherAccounts`), each labeled as the
   bar labels it and a **Switch**.
3. **Your own client**: `CreateOwnClientCard`.

A component's props set inside its builder must be qualified when the calling component has state of the same name:
`PublicHome { this.config = current }`. A bare `config = current` resolves to Home's own state, sets it during render,
and loops (React #301).
