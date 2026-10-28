/- Copy for the home/shell widget-group: the landing page and the surrounding nav chrome.
   Fetched via /st/<appId>/md/home:<buildId>. Values are Markdown; the frontend renders them with the
   kernel's renderMarkdown(). The links themselves are data (state.links of the home UI-config), not copy --
   only their surrounding wording lives here. -/

# @home
/- The wordmark shown beside the brand mark in the home page's hero. Absent = no hero. -/
# +brand KDR
# +title Welcome
# +intro
This is the **KotlinDynamicRuntime** runtime shell. Everything you see is assembled from data the backend
serves: the layout below comes from a UI-config endpoint, and this copy comes from a Markdown fragment file.

Open **Documents**, under Reference in the menu, to read more.

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
