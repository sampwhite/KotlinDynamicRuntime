<!-- Conventions for these pages: a key table the first time a shape appears; bold-lead paragraphs for what is new
     since the previous page; one nav line at the top of each page and a "Next:" line at the bottom; the reference page
     holds anything the walkthrough would otherwise repeat. ConfDemoDocsTest holds every JSON block to the demo. -->

# The conference demo

The conference demo, `confdemo`, is a small conference's call for talks: speakers submit the talks they want to give.
There is no code written just for it. Everything it does comes from its configuration.

These pages walk through that configuration, one piece of JSON at a time, starting with the simplest piece.

## The pages

1. [The conference demo](README.md), this page: how to try the demo, and how a client is stored.
2. [Client](client.md): the client itself.
3. [Creating a talk](create-talk.md): the workflow that creates a talk.
4. [Talk survey](talk-survey.md): the traits every talk collects, the columns of the forms list, and the survey.
5. [Abstract](abstract.md): the first normal workflow, and a trait with a long text, a list, and a multi-select.
6. [Committee review](committee-review.md): a workflow only some talks may enter, and only some people may work on.
7. [Workshop logistics](workshop-logistics.md): a workflow gated on a computed fact, and a layout that takes over.
8. [Reports](reports.md): two reports over the talks, one a roster and one a summary.
9. [Every bundle](bundles.md): the full JSON of every bundle, in one place.

The [reference](reference.md) explains the things the pages have in common, such as choices and field layouts. The
pages link to it as they go.

## Try it

There are two ways to try the demo: join it on a public reference site, or run it on a test instance of your own.

### On a public reference site

A public reference site, such as [dynamicruntime.org](https://dynamicruntime.org), is a working instance of KDR that
anyone can sign in to and look around. The demo is already there, ready to join.

1. Choose **Register** from the menu, and create an account with your email address. (If you already have one,
   choose **Log in** instead.)
2. On the home page, under **Try a demo**, choose **Join…** on the **Conference demo** card.
3. Read **Before you join**, check the box that says you understand, and choose **Join as Speaker**.
4. Open **My forms** from the menu, and choose **Create a form**.
5. Type a title in the form that opens, and choose **Create the talk**.

The talk appears in your forms list. Joining gives you a user of your own email in the demo, and the demo is shared
with everyone else who joins it, so other visitors see the talks you add (and you see theirs). You can also join as an
organizer or a committee member, from the same card, to see the demo as they do.

### On a test instance of your own

A test instance is one you run yourself, from a checkout of the
[KotlinDynamicRuntime repository](https://github.com/sampwhite/KotlinDynamicRuntime). The repository's
[README](https://github.com/sampwhite/KotlinDynamicRuntime/blob/main/README.md) explains how to set it up and start
it. Once it is running:

1. Open **Simulations** from the menu, and choose **Run** on **conf-demo**.
2. Choose **Sign in as** next to the speaker.
3. Type a title in the form that opens, and choose **Create the talk**.

The talk appears in the speaker's forms list.

To change the demo without changing the original, give the simulation a suffix. With the suffix `mine`, it creates a
separate client, `confdemomine`, that you can edit freely.

## How a client is stored

A client's configuration is stored in **bundles**. A bundle is a named piece of configuration that is written, read,
and published on its own.

Inside a bundle, the configuration is sorted into **slots**. Each slot holds one kind of definition, as a list of
entries:

<!-- kdr-doc: illustration -->
```json
{
  "kdr:clientDef": [
    { "clientId": "confdemo", "name": "Conference demo" }
  ],
  "kdr:workflowDef": [
    { "workflowId": "createTalk", "definition": { "entry": "creation" } }
  ]
}
```

Here the slots are `kdr:clientDef` and `kdr:workflowDef`. Their entries are shortened placeholders; the pages that
follow show each one in full.

The demo keeps the client in one bundle, and each workflow in a bundle of its own. That is a choice worth copying: a
bundle is published as a whole, so two workflows in one bundle are always published together, and there is no way to
move one out later.

| Bundle | What it holds |
| --- | --- |
| `base` | The client, its own traits, and the columns of the forms list |
| `createTalk` | The workflow that creates a talk |
| `talkSurvey` | The survey every talk goes through |
| `abstract` | The workflow that collects the abstract |
| `review` | The committee's review |
| `logistics` | Planning a workshop |
| `reports` | The two reports an organizer runs |

The JSON on these pages is what a running instance returns for each bundle. The reference says
[how to read it](reference.md#reading-the-json-on-these-pages).

Next: [Client](client.md).
