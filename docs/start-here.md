<!-- The documentation's front door (issue #1510): what to read, by what the reader wants to do. Listed first on a
     site's Documents page, and linked from the README's first screen and the home pages. A new guide joins the path
     it serves here, so this page stays the one map. Its links are repo-relative, so they work on GitHub and in the app
     alike. -->

# Start here

KotlinDynamicRuntime runs applications built around forms. People enter information, others review and approve it,
and it ends up in reports or another system: a call for talks, a grant program, a vendor's onboarding. Each such
application is a **client**: one organization's forms, workflows, people, and wording, all kept as configuration that
can change while the site runs.

The easiest way to see it is to try it. [dynamicruntime.org](https://dynamicruntime.org) is a public reference site,
where you can register, join a demo, and make a client of your own. Its database is cleared from time to time, so
nothing you do there can break anything for long.

The rest of this page says what to read for what you want to do.

| You want to | Read |
| --- | --- |
| Work on forms in a site somebody else runs | [Use a site](#use-a-site) |
| Set up and run a client for your organization | [Run a client](#run-a-client) |
| Run the runtime yourself, or change its code | [Build on it](#build-on-it) |
| Have a program or an AI assistant configure a client | [Have an AI configure it](#have-an-ai-configure-it) |

## Use a site

For a person who fills in forms, reviews them, or approves them.

1. **[The walk-through, part 1](walk-through.md#part-1-the-conference-demo).** On a reference site, you join the
   conference demo as a speaker, submit a talk, and answer its questions. Then you join its committee and review the
   talk you submitted. Each step says what to choose and what to type.
2. **"What is this?"** Beside the heading of many pages in the app, this link opens a short explanation of the page:
   what a form is, which workflows it may be in, and what the sandbox is.

## Run a client

For a person who sets up a client and looks after it: its forms and workflows, its people, and its words.

1. **[The walk-through, part 2](walk-through.md#part-2-a-client-of-your-own).** On a reference site, you create a
   client of your own and open its sandbox, where changes are tried before anyone else sees them. Then you change its
   words, design the information a form collects, make a workflow, try it, and publish it. Last, you invite somebody,
   choose who sees the workflow, and read its report.
2. **The Clients page's "What is this?" links.** Each panel of the page explains itself: the sandbox and publishing,
   the workflows, the menu, the words and images a client shows, and the rest.
3. **[The client configuration reference](reference/client-configuration.md),** for the bundle editor, which shows a
   client's whole configuration as JSON. It lists every part of the configuration and what each holds.

## Build on it

For a developer who runs the runtime, extends it, or contributes to it.

1. **[The README](../README.md#getting-started),** to set up a checkout and start a server of your own.
2. **[Extending](extending.md),** for every way a deployment, a component, or a client varies what the runtime does,
   and the rule behind them.
3. **[The conference demo](demo-client/README.md),** a client built from configuration alone, its JSON explained one
   piece at a time.
4. **[The schema dialect](reference/schema-dialect.md),** for the JSON Schema keywords the runtime reads, and its own.
5. **[Deploying a lightweight node](deploy/lightweight-node.md),** to run a site of your own on one small server.
6. **[Contributing](../CONTRIBUTING.md)** and **[the code guide](development/code-guide.md),** before you change the
   code. The README lists [the design documents](../README.md#design-documents) behind each part.

## Have an AI configure it

For a program, or an AI assistant, given a site's address and an API key.

1. **[Using the API](api-guide.md),** which is all a program needs: what a client is made of, what every call has in
   common, and a recipe for each job. A site serves it to programs at `/st/api/doc/api-guide`, and its endpoint catalog
   names that address.
2. **[API keys and access tokens](api-keys-and-tokens.md),** for how a key is made, used, and limited.
3. **[The walk-through, part 3](walk-through.md#part-3-for-developers),** in which you make a key, find an endpoint,
   and build a workflow from its JSON, in the bundle editor or by having an AI assistant build it through the API.
4. **[The client configuration reference](reference/client-configuration.md),** for every part of a client's
   configuration.

Most of this documentation was written by an AI and reviewed by people;
[the README says why](../README.md#how-this-was-made).
