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

The [reference](reference.md) explains the things the pages have in common, such as choices and field layouts. The
pages link to it as they go.

## Try it

You can run the demo on any test instance of KDR.

1. Open the [Simulations page](#page=simulations) and run **conf-demo**.
2. Choose **Sign in as** next to the speaker.
3. Type a title in the form that opens, and choose **Create the talk**.

The talk appears in the speaker's forms list.

> **Note:** The Simulations page exists only on a test instance. The link above works when you read this page in the
> application, not on GitHub.

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

The JSON on these pages is what a running instance returns for each bundle. The reference says
[how to read it](reference.md#reading-the-json-on-these-pages).

Next: [Client](client.md).
