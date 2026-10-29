---
name: kdr-webapp-pages
description: >-
  What each page of the KotlinDynamicRuntime front end (webapp) decides and why, one reference file per page -- the
  Clients page (definition editor, panels, web assets and file refs, bundles, sandbox and publishing), the admin
  console (Users, roles, labels, the editor role), the forms listing, API keys, Customers, Reports, Design View,
  Simulations, the file store page, a client's web assets and file refs, and the public home page (demo clients,
  self-serve clients, the public test deployment). Read the page's file before changing, reviewing, or verifying that
  page or its API calls.
---

# The webapp's pages

`webapp/CLAUDE.md` holds what applies across the front end, and loads with any file under `webapp/`: static content
(Markdown fragments, the copy every page draws), UI-config endpoints, growing listings, buttons and links, times in
tables, choice widgets, errors, iterating without a rebuild, the `becomeUser` sign-in, the readable bundle, debug
pages, and frontend tests. Read it as well; the page files here assume it.

What follows is about one page or surface at a time. Read the file for the page you are working on, beside this one,
rather than all of them:

| File | Covers |
| --- | --- |
| [`clients-page.md`](clients-page.md) | The Clients page: the listing, a client's detail and panels, the definition editor, web assets and file refs, the menu and audiences, traits, sandbox and publishing, the bundle editor and editing bundles as JSON, the time-zone picker |
| [`admin-console.md`](admin-console.md) | The admin console (Users): editing someone's identity and authority -- levels, the `allClients` capability, the editor role, labels, user search |
| [`forms-listing.md`](forms-listing.md) | The forms listing: named for whose forms it shows, and the listing across clients (its address and its ways home) |
| [`reports-page.md`](reports-page.md) | The Reports page: running a report, its parameters, the growing table, snapshots and charts |
| [`design-view.md`](design-view.md) | Design View: marking a form with the definitions behind it, page-level copy, the `design-demo` simulation |
| [`client-web-assets.md`](client-web-assets.md) | A client's own web assets, and a client's file refs (the calls behind the Clients page's Files section) |
| [`api-keys.md`](api-keys.md) | API keys |
| [`customers-page.md`](customers-page.md) | The Customers page |
| [`simulations-page.md`](simulations-page.md) | The Simulations page |
| [`file-store-page.md`](file-store-page.md) | The operator's file store page |
| [`public-home.md`](public-home.md) | A public test deployment, joining a demo client, creating your own client from the home page, and the public home page |

A page file names the issues and source files behind it, and its tests where it has them; a file that names no test
does not mean the page has none, so look in `webapp/src/jsTest/` and the backend's tests as well. The code is the
authority where a file and the code disagree, and a change to a page's behavior updates its file in the same edit.
No test holds these files to the code, so that rule is what keeps them current.
