<!-- Conventions for these pages are the conference demo's (docs/demo-client/README.md). ClaimsDemoDocsTest holds every
     JSON block to the demo. -->

# The claims demo

The claims demo, `claimsdemo`, is a small insurer's claims. A policyholder reports an incident, which is given a number
as the report is filed, and adds photographs and a document as evidence. An adjuster assesses the claim. An adjuster
approves a payment of up to $5,000, and a claims manager approves a larger one. What was decided is kept in a register
for the insurer's auditors. There is no code written just for the demo. Everything it does comes from its
configuration.

These pages walk through that configuration, one piece of JSON at a time. The [conference
demo](../demo-client/README.md) explains more of the basics, and these pages link to it rather than repeat it. They
dwell on what is new here:

- **Numbers given as a form is saved,** with a prefix that changes each day, in [Numbering incidents](numbering.md).
- **Files,** in [Evidence](evidence.md).
- **Approval limits by role,** and a decision that cannot change once it is made, in [Settlement](settlement.md).

## The pages

1. [The claims demo](README.md), this page: how to try the demo, and the bundles it is made of.
2. [Client](client.md): the client itself, the roles to join it as, and the columns of its forms list.
3. [Reporting an incident](reporting.md): the workflow that creates a claim, and the one that corrects its report.
4. [Numbering incidents](numbering.md): the counter that numbers each incident, and why it does so as the report is
   filed.
5. [Evidence](evidence.md): a workflow that collects files.
6. [Settlement](settlement.md): the assessment, and who approves its outcome.
7. [The claims register](register.md): the report an auditor reads.

## Try it

There are two ways to try the demo: join it on a public reference site, or run it on a test instance of your own.

### On a public reference site

A public reference site, such as [dynamicruntime.org](https://dynamicruntime.org), is a working instance of KDR that
anyone can sign in to and look around. The demo is already there, ready to join.

1. Choose **Register** from the menu, and create an account with your email address. (If you already have one, choose
   **Log in** instead.)
2. On the home page, under **Try a demo**, choose **Join…** on the **Claims demo** card.
3. Read **Before you join**, check the box that says you understand, and choose **Join as Policyholder**.
4. Open **My forms** from the menu, and choose **Create a form**.
5. Fill in the report, and choose **File the report**.

The claim appears in your forms list, under its incident's number. The demo is shared with everyone else who joins it,
so other visitors can read what you report and assess it: make the incident up, and use no real person's details. You
can also join as an adjuster or a claims manager, from the same card, to see the claims as they do. One person can hold
all three roles, so you can take a claim of your own from its report to its settlement.

### On a test instance of your own

A test instance is one you run yourself, from a checkout of the [KotlinDynamicRuntime
repository](https://github.com/sampwhite/KotlinDynamicRuntime). The repository's
[README](https://github.com/sampwhite/KotlinDynamicRuntime/blob/main/README.md) explains how to set it up and start it.
Once it is running:

1. Open **Simulations** from the menu, and choose **Run** on **claims-demo**.
2. Choose **Sign in as** next to the policyholder.
3. Fill in the report that opens, and choose **File the report**.

To change the demo without changing the original, give the simulation a suffix. With the suffix `mine`, it creates a
separate client, `claimsdemomine`, that you can edit freely.

## The bundles

A client's configuration is stored in bundles, and each bundle's configuration is sorted into slots. The conference
demo explains both under [How a client is stored](../demo-client/README.md#how-a-client-is-stored). The demo keeps the
client in one bundle, and each workflow in a bundle of its own:

| Bundle | What it holds | Explained on |
| --- | --- | --- |
| `base` | The client, its traits, its counter, and its forms list | [Client](client.md), and the page where each piece is first used |
| `reportIncident` | The workflow that creates a claim | [Reporting an incident](reporting.md) |
| `incidentDetails` | The core details workflow, which corrects a report | [Reporting an incident](reporting.md#correcting-a-report) |
| `evidence` | The workflow that collects files | [Evidence](evidence.md) |
| `settlement` | The assessment and its approval | [Settlement](settlement.md) |
| `register` | The claims register | [The claims register](register.md) |

## What a real insurer would add

The demo is the spine of a claims process. Most of what a real insurer would add is more configuration of the kinds
these pages show:

- **More kinds of incident,** each with questions of its own. A workflow that a claim enters only when it is of that
  kind would ask them, as the conference demo's [workshop logistics](../demo-client/workshop-logistics.md) is for
  workshops only.
- **A third limit,** for a director, as [Who approves it](settlement.md#who-approves-it) explains.
- **Two signatures on each claim,** the adjuster's recommendation and then a claims manager's approval. An approval can
  be given in [steps](../demo-client/reference.md#who-may-work-on-a-task), by different people in turn.
- **The report and the evidence locked once the claim is settled,** as the assessment is. The demo leaves them open so
  that a late document can still be added (see [What stays open](settlement.md#what-stays-open)).
- **Each policyholder's policy number** on their reports.

A settled claim can already be opened again. An administrator of the client sees **Reverse approval…** on the
approval, gives a reason, and the outcome then has to be approved again.

Some of what an insurer would add is not configuration today:

- **A claim assigned to one adjuster.** Every adjuster sees every new claim under **Waiting on you**, since nothing
  records whose claim it is.
- **Work sent back.** An approval can only be given. A claims manager cannot return an assessment to its adjuster with
  a note, and an adjuster cannot ask the policyholder for a missing document as a step that waits on the policyholder.
- **Deadlines.** Nothing works out how long a claim has been open, so nothing can mark one as overdue, and the register
  cannot show it.
- **An approver other than the assessor.** An approval can ask who the approver is, but not who saved an earlier step,
  so an adjuster may approve a small payment they assessed themselves (see
  [Who approves it](settlement.md#who-approves-it)).
- **A policy number checked against the insurer's own records,** and **a payment made through its own accounts.**
  Each would be an integration.
- **A mail to the policyholder** as their claim moves, since a workflow sends none.

Next: [Client](client.md).
