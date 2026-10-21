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

Open **Documents** from the menu to read more.

/- Beneath the intro, and empty as shipped, so nothing shows (issue #1221): room for a component's own word on a
   deployment -- a deployment flavor's, say -- without its competing with whoever rewords the intro. -/
# +note


# @publicHome
/- The public home page (issue #1324): for a person signed in to `public`, under the intro, what they can do next --
   try a demo, open one of their other accounts, or make a client of their own. -/
# +demosTitle Try a demo
# +demosIntro
Join a demo client with a user of your own email. Each one is shared with everyone who joins it.
# +accountsTitle Your other accounts
# +accountsIntro
The users you have in other clients. Choose one to switch to it; the account menu does the same.

# @forms
/- What a forms-listing row with no workflows shows in the workflow column (issue #791). A client overlays it:
   "Coming Soon...", a link to the workflow pages, "No workflows available." -/
# +noWorkflows —

# @nav
# +title Documents
# +homeLabel Home
# +emptyNote No documents are available in this deployment.

/- The words of every "What is this?" link in the app, and of closing what one opened (issue #1330): the app's words,
   so the shell's copy rather than each page's. A page's own file holds only the explanation itself. -/
# @whatIsThis
# +link What is this?
# +hide Hide
