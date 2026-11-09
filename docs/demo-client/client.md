[← The conference demo](README.md) · [Contents](README.md#the-pages)

# Client

The `base` bundle's `kdr:clientDef` slot holds one entry. It says who the client is and what it is made of. The bundle's
other slots hold the client's own traits and the columns of the forms list, and the [core details
workflow](talk-core-details.md), which is the first thing to use them, explains them.

<!-- kdr-doc: bundle=base pointer=/kdr:clientDef/0 -->
```json
{
  "clientId": "confdemo",
  "name": "Conference demo",
  "description": "A small conference's call for talks: speakers submit talks, and a committee reviews them and accepts them for the program.",
  "usageType": "demo",
  "audience": "internal",
  "enabledEnvironments": ["unit", "local", "dev"],
  "preload": false,
  "staticConfig": false,
  "includedTraits": ["kdr:name"],
  "webResourcesId": "kdr:confdemo",
  "userLabels": ["committee"],
  "userLabelNotes": { "committee": "Give this to the people who review talks and accept them for the program." },
  "joinRoles": [
    {
      "name": "speaker",
      "label": "Speaker",
      "persona": "member",
      "summary": "Submit a talk, and follow it through the committee's review."
    },
    {
      "name": "organizer",
      "label": "Organizer",
      "persona": "admin",
      "summary": "See every talk submitted, and run the conference's workflows."
    },
    {
      "name": "committee",
      "label": "Committee member",
      "persona": "editor",
      "userLabels": ["committee"],
      "summary": "Review the talks whose core details are complete, and accept them."
    }
  ]
}
```

| Key | What it means |
| --- | --- |
| `clientId` | The client's id. Every form and every user that belongs to the client has this id stored on it, so there is never any doubt about which client something belongs to. |
| `name` | The name people see. |
| `description` | A note about the client, for whoever maintains it. A demo's is shown to visitors too, on its card on the home page and on its Join page, so the demo's says what it is. |
| `usageType` | What the client is for. The value `demo` marks a client that exists to show the product. |
| `audience` | Whose client it is. The value `internal` means it belongs to the platform, not to a customer. |
| `enabledEnvironments` | Where the client runs. Every client runs in `unit` and `local`, so that tests and local development can reach it. |
| `preload`, `staticConfig` | Two switches for production clients, both off here. |
| `includedTraits` | The shared traits the client uses. Here there is one, `kdr:name`, the trait that gives a form its name. |
| `webResourcesId` | The package of images the client presents itself with, which a component of the platform ships. The demo's holds its brand mark, the speech bubble in the app bar and on its home page, and no tab icon, so the browser tab keeps the site's. Only a client declared in code can name a package, so the demo has one where it is declared in code, as on a reference deployment, and none where the conf-demo simulation writes it on a test instance. |
| `userLabels` | Labels an administrator can put on a user. A workflow can ask whether a user has one; [Committee review](committee-review.md) asks for `committee`. |
| `userLabelNotes` | A sentence for each label, saying who should have it. The Users page shows it beside the label, together with what the client's workflows do with the label. |
| `joinRoles` | The roles a visitor can join the demo as, on a site that offers joining. Joining gives the visitor a user of their own email in the client. Its `persona` decides what kind of user that is: an ordinary user (`member`), an editor (`editor`), who sees and edits every form of the client but administers nothing, or an administrator (`admin`). The demo's three roles each take a different persona, so one person can hold all three. (Two roles of the same persona would each need a `personaSuffix` to tell them apart.) Its `userLabels` are put on the user. The Join page shows each role's `label` and `summary`. |

## Traits

A **trait** is a named part of a form's data, with its own schema. A form is made of traits.

Some traits, such as `kdr:name`, are defined by the platform and shared by every client, and a client lists the ones
it uses in `includedTraits`. A client can also define traits of its own, with whatever fields it needs. The pages on
the demo's workflows show several, each where the workflow that collects it is introduced. The first two appear in
[Talk core details](talk-core-details.md).

## Home page

The home page is the first page a visitor sees after joining the demo. The platform ships its words, and a deployment
can change them: a public reference site's home page says what the site is. The demo changes two of them, the title
and the introduction, so that its home page says what the demo is and where to start:

<!-- kdr-doc: bundle=base pointer=/kdr:fragmentDef/0 -->
```json
{
  "fileId": "home",
  "content": {
    "home": {
      "title": "Conference demo",
      "intro": "The **Conference demo** is a small conference's call for talks. Speakers submit the talks they want to give, and a committee reviews each one and accepts it for the program. The demo is shared with everyone who joins it, so you see the talks other visitors add, and they see yours.\n\nAs a speaker, open **My forms** from the menu, and choose **Create a form** to submit a talk. As a member of the committee, once a talk is ready for your review, this page lists it under **Waiting on you**. [Part 1 of the walk-through](/docs/walk-through.md#part-1-the-conference-demo) takes you through both, a step at a time."
    }
  }
}
```

| Key | What it means |
| --- | --- |
| `fileId` | The file of shipped words this entry changes. The `home` file holds the words of the home page and of the parts of the app around it, such as the menu. |
| `content` | The words that change, by namespace and then by key: here the `title` and `intro` keys of the `home` namespace. Every other word in the file stays as it was. |

The introduction is Markdown, so it can make words bold and link to a page. Its link opens the first part of the
walk-through, in the app. An entry like this one can only change words the file already has: a key the file does
not have is refused when the bundle is saved, so a misspelled key is caught rather than ignored. The demo's words win
over the deployment's, because the most specific words win. The copy reference explains
[a client's copy overlay](../reference/copy.md#a-clients-copy-overlay) in full.

**What a visitor sees.** After joining, the home page is titled **Conference demo**, with the introduction under it.
A speaker's next step, **My forms**, is named there, and so is the **Waiting on you** card, which appears on a
committee member's home page once a talk is ready for their review.

## What the bundle holds

The pages that follow add to the `base` bundle as they go: the client's name for a platform trait, its own traits,
its columns in the forms list and how the list shows workflows, a shared type, its facts, and a menu entry. Each is shown on the page where it first matters. This table is the
bundle as a whole, kept current as the pages add to it.

| Slot | What it holds | Explained on |
| --- | --- | --- |
| `kdr:clientDef` | The client's definition, above. | This page |
| `kdr:traitDef` | Five traits: `session` and `questionnaire`, `abstract`, `review`, and `logistics`. | [Talk core details](talk-core-details.md), [Abstract](abstract.md), [Committee review](committee-review.md), [Workshop logistics](workshop-logistics.md) |
| `kdr:usageDef` | The three columns of the forms list: Talk, Track, and Audience. | [Talk core details](talk-core-details.md#columns-in-the-forms-list) |
| `kdr:schemaDef` | The demo's name for the platform's `kdr:name` trait, and the shared `Speaker` type. | [Creating a talk](create-talk.md#the-traits-name), [Abstract](abstract.md#a-shared-type) |
| `kdr:cfactDef` | The client's two facts, `isWorkshop` and `talkAccepted`. | [Workshop logistics](workshop-logistics.md#a-computed-fact) |
| `kdr:uiBlockDef` | The menu entry that turns the Workflows page on. | [Abstract](abstract.md#what-the-speaker-sees) |
| `kdr:listingDef` | How the forms list shows each form's workflows: all in one column. | [Talk core details](talk-core-details.md#workflows-in-one-column) |
| `kdr:fragmentDef` | The home page's title and introduction. | [Home page](#home-page), above |

Next: [Creating a talk](create-talk.md).
