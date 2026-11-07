/- Copy for the home/shell widget-group: the landing page and the surrounding nav chrome.
   Fetched via /st/<appId>/md/home:<buildId>. Values are Markdown; the frontend renders them with the
   kernel's renderMarkdown(). The links themselves are data (state.links of the home UI-config), not copy --
   only their surrounding wording lives here. -/

# @home
/- The wordmark shown beside the brand mark in the home page's hero. Absent = no hero. -/
# +brand KDR
# +title Welcome
/- Under the title (issue #1576): links to the page's sections further down, by their ids -- `#home.demos`,
   `#home.accounts`, `#home.own-client` (the public home page's; a heading's id never has a dot, so none can clash). A
   link to a section not drawn for the caller is left out, and with no link left the row is not drawn, so empty removes
   it. A link to anything else (`/docs/...`, `/wa/...`) is always kept. -/
# +jumpLinks
- [Try a demo](#home.demos)
- [Your other accounts](#home.accounts)
- [Your own client](#home.own-client)

# +intro
This is the **KotlinDynamicRuntime** runtime shell. Everything you see is assembled from data the backend
serves: the layout below comes from a UI-config endpoint, and this copy comes from a Markdown fragment file.

To find your way around, [start here](/docs/start-here.md): it is first under **Documents**, in the menu's Reference
section, and says what to read for what you want to do.

/- Beneath the intro, and empty as shipped, so nothing shows (issue #1221): room for a component's own word on a
   deployment -- a deployment flavor's, say -- without its competing with whoever rewords the intro. -/
# +note


# @publicHome
/- The public home page (issue #1324): for a person signed in to `public`, under the intro, what they can do next --
   try a demo, open one of their other accounts, or make a client of their own. -/
/- Above the sections (issue #1429). Empty as shipped; the `reference` flavor links its walk-through here. -/
# +walkThrough
# +demosTitle Try a demo
# +demosIntro
Join a demo client with a user of your own email. Each one is shared with everyone who joins it.
# +accountsTitle Your other accounts
# +accountsIntro
The users you have in other clients. Choose one to switch to it; the account menu does the same.
/- Under the create card's starter workflows (issue #1384). Empty as shipped; the `reference` flavor says how a workflow
   made later is opened to people. -/
# +createClientNote

# @registerCard
/- The Register card's body (issue #1652): a home card for a visitor who has not signed in, wherever anyone may sign in
   (`HCARD.register`). Its title, button, and Log in link are the card's own; this is its text, here so a deployment or
   a flavor rewords it as it does the intro. Empty draws the card with no text. -/
# +text
Register a user of your own with your email address. Most of this site, the walk-through in its documents included,
starts from being signed in. If you have registered before, log in instead.

# @forms
/- What a forms-listing row with no workflows shows in the workflow column (issue #791). A client overlays it:
   "Coming Soon...", a link to the workflow pages, "No workflows available." -/
# +noWorkflows —
/- The forms listing's "What is this?" (issue #1337): what a form is, and the workflows it may be in. -/
# +whatIsThis
A **form** is one document: what was entered for one request, report or case, kept together. Its parts are
**traits**, such as a name, a site visit or an expense report, and the leading columns show the ones this listing
picks out. **Contains** lists them all.

A **workflow** is a process a form goes through, such as a review or a follow-up. It is made of **tasks**, each a
step that somebody does: filling in part of the form, or approving it. One form can be in several workflows at once,
since each one works on the parts of the form it needs. Where the listing has a workflow column, it shows where the
form stands in each.


# @workflowCell
/- The words of the forms listing's workflow cell (issue #1598). A link to a workflow that is your turn says its verb:
   the call to action's keyword's (`ctaReview.verb`), else what its step asks in words, else `continue`. One you could
   start says `start`. One waiting on someone else is muted and says why: the call to action's filter label when the
   filter keeps you out, else one of the `waiting...` lines; a workflow frozen outside its relevancy window says when
   it `opens` or `closed`, `${at}` being the moment as the table writes one ("in 3d", "2026-10-30"). -/
# +start Start
# +continue Continue
# +waiting Waiting
# +waitingOnOwner Waiting on the owner
# +waitingOnOthers Waiting on someone else
# +waitingForApproval Waiting for approval
# +opens Opens ${at}
# +closed Closed ${at}
# +closedUndated Closed

# @ctaReview
/- The `review` search keyword's words (issue #1596): what a call to action asking for a review is called wherever it is
   shown. A client's overlay rewords them as it rewords other copy. `statusText` is the Status column's chip for a form
   waiting on a review. -/
# +verb Review
# +filterLabel Needs review
# +columnHeader Review
# +reportLabel Needs review
# +statusText Needs Review


# @workflowForms
/- A workflow's own forms listing (issue #1337), opened from the Workflows page. -/
# +whatIsThis
A **workflow** is a process a form goes through, made of **tasks**: each a step that somebody does, such as filling
in part of the form or approving it. This page lists the forms by where they stand in this one workflow:

- **Eligible:** the form could start the workflow, and has not.
- **Engaged:** the workflow has started on the form, and work is under way.
- **Finished:** the workflow is done with the form.

A form can be in several workflows at once, so the same form may be listed under another workflow too.


# @sandbox
/- The sandbox chip's "What is this?" (issue #1337), beneath what the chip says of the sandbox the session is in. -/
# +whatIsThis
![A change is saved as a draft the sandbox runs, and is published to the client's people](img:kdr:sandbox)

A sandbox is where a client's changes are tried before its people see them. It runs the client's **latest**
configuration, drafts included, while the client's own people go on using what was last **published**.

- **Users and data of its own.** Forms made here stay here, and the client's own forms are not shown here.
- **Changes are drafts.** A change to the client's configuration made here (its copy, menu, traits, workflows or
  files) shows here at once. It reaches the client's people only when it is published, from the stored
  configuration on the sandbox's client page.
- **You act with your own access.** Your user here has the access of the user you opened the sandbox from, and loses
  it when that user does.


# @nav
# +title Documents
# +homeLabel Home
# +emptyNote No documents are available in this deployment.

/- The words of every "What is this?" link in the app, and of closing what one opened (issue #1330): the app's words,
   so the shell's copy rather than each page's. A page's own file holds only the explanation itself. -/
# @whatIsThis
# +link What is this?
# +hide Hide
