[← Evidence](evidence.md) · [Contents](README.md#the-pages)

# Settlement

The settlement decides a claim, in two steps. An adjuster assesses it, recommending that it be paid, and how much, or
declined, and why. Then the outcome is approved. An adjuster may approve a payment of up to $5,000, and a larger one
waits for a claims manager. Once an assessment is saved nobody can change it, so what was approved is what stays on the
record.

Three pieces of configuration go into it:

- [the assessment trait](#the-assessment-trait), in the `base` bundle, which works out whether the amount is over the
  limit;
- [the fact of an approval](#the-fact-of-an-approval), in the `base` bundle;
- [the workflow](#workflow), in the `settlement` bundle.

It builds on the conference demo's [committee review](../demo-client/committee-review.md), which explains eligibility
tests, a task that only some people may save, locks, and approval tasks.

## The assessment trait

<!-- kdr-doc: bundle=base pointer=/kdr:traitDef/4 -->
```json
{
  "traitId": "assessment",
  "typeName": "client.claimsdemo.AssessmentEntry",
  "description": "What the adjuster concludes.",
  "appliesTo": ["formDoc"],
  "dataSchema": {
    "type": "object",
    "title": "Assessment",
    "properties": {
      "outcome": {
        "type": "string",
        "title": "Outcome",
        "description": "What the adjuster recommends.",
        "g-options": [
          { "value": "pay", "label": "Pay the claim" },
          { "value": "decline", "label": "Decline the claim" }
        ]
      },
      "amount": {
        "type": "integer",
        "title": "Amount to pay (dollars)",
        "description": "The amount to pay, in whole dollars.",
        "minimum": 1,
        "maximum": 250000
      },
      "reasons": {
        "type": "string",
        "title": "Reasons",
        "description": "Why, for whoever approves it and for the claim's record.",
        "g-longText": true,
        "g-outerWhitespace": "trim"
      },
      "overLimit": {
        "type": "boolean",
        "title": "Over the adjusters' limit",
        "description": "Whether the amount is over what an adjuster may approve.",
        "g-derived": true
      }
    },
    "required": ["outcome", "reasons"],
    "if": {
      "properties": {
        "outcome": { "const": "pay" }
      },
      "required": ["outcome"]
    },
    "then": { "required": ["amount"] },
    "else": {
      "not": { "required": ["amount"] }
    },
    "g-onWrite": [{ "fn": "computeValue", "target": "overLimit", "script": "(amount ?: 0) > 5000" }]
  }
}
```

**The amount is asked for only when there is one.** The schema's `if`, `then`, and `else` are standard JSON Schema.
They say that the `amount` is required when the `outcome` is `pay`, and not allowed otherwise. So the form asks for it
once the adjuster chooses **Pay the claim**, and takes it away again if they choose **Decline the claim**.

**The limit is worked out, not typed.** The field `overLimit` has `"g-derived": true`, so no form asks for it, and one
sent by a program is ignored. The type's `g-onWrite` lists what is done with the data each time it is saved. Here that
is one function:

| Key | What it means |
| --- | --- |
| `fn` | The function. The function `computeValue` works out a value from the other fields. |
| `script` | The expression to work out. The expression `(amount ?: 0) > 5000` is true for an amount over 5,000. The `?: 0` says to use 0 when there is no amount, as a declined claim has none. See the [expressions reference](../reference/expressions.md). |
| `target` | The field the result is stored in. |

So every assessment is stored with a yes or a no, and the workflow can ask for it. Changing the limit is changing one
number in this script.

## The fact of an approval

An approval asserts a fact, which the client declares in the `kdr:cfactDef` slot of its `base` bundle:

<!-- kdr-doc: bundle=base pointer=/kdr:cfactDef -->
```json
[
  {
    "name": "settled",
    "description": "True once the claim's outcome is approved.",
    "group": "claimsDemo",
    "toFrontend": false
  }
]
```

The conference demo explains the keys under [A computed fact](../demo-client/workshop-logistics.md#a-computed-fact).

## Workflow

Here is the whole `settlement` bundle:

<!-- kdr-doc: bundle=settlement -->
```json
{
  "kdr:workflowDef": [
    {
      "workflowId": "settlement",
      "definition": {
        "workflowId": "settlement",
        "entry": "normal",
        "$comment": "An adjuster assesses the claim, and then its outcome is approved: by an adjuster up to their limit, by a claims manager above it.",
        "label": "Settlement",
        "highlightStart": true,
        "eligibility": [
          {
            "id": "reportComplete",
            "test": "kdr:coreDetailsComplete",
            "explanation": "The incident's report has to be complete before its claim is assessed."
          }
        ],
        "tasks": [
          {
            "id": "assess",
            "$comment": "An adjuster saves it while it is the step to do, so an assessment cannot change under its approval.",
            "label": "Assess the claim",
            "keyword": "review",
            "traits": [{ "traitId": "assessment", "required": true }],
            "saves": [{ "id": "saveAssessment", "label": "Save the assessment", "kind": "edit" }],
            "functions": [{ "fn": "userHasLabel", "label": "adjuster", "cfact": "kdr:reviewer" }],
            "saveWhen": "kdr:reviewer,kdr:isCta"
          },
          {
            "id": "approveOutcome",
            "$comment": "The computed overLimit decides who it waits on.",
            "label": "Approve the outcome",
            "traits": [],
            "saves": [],
            "functions": [
              { "fn": "userHasLabel", "label": "adjuster", "cfact": "kdr:reviewer" },
              {
                "fn": "ctaFilterByValue",
                "trait": "assessment",
                "valuePath": "overLimit",
                "cases": [
                  {
                    "value": "false",
                    "label": "An adjuster approves the outcome, since it pays $5,000 or less.",
                    "filter": "kdr:reviewer"
                  },
                  {
                    "value": "true",
                    "label": "A claims manager approves the outcome, since it pays more than $5,000.",
                    "filter": "kdr:hasAdminLevel"
                  }
                ],
                "otherwise": {
                  "label": "A claims manager approves the outcome.",
                  "filter": "kdr:hasAdminLevel"
                }
              }
            ],
            "approval": {
              "cfact": "settled",
              "prompt": "Read the claim and its assessment, then approve the outcome.",
              "button": "Approve the outcome",
              "approveWhen": "(kdr:reviewer|kdr:hasAdminLevel),~kdr:isOwner",
              "showTraits": ["filing", "incident", "evidence", "assessment"]
            },
            "display": {
              "select": [
                { "cfactExpression": "settled", "mode": "default" },
                {
                  "cfactExpression": "~kdr:isCta",
                  "mode": "text",
                  "text": "The claim has to be assessed before its outcome can be approved.",
                  "disabled": true
                },
                { "cfactExpression": "kdr:reviewer|kdr:hasAdminLevel", "mode": "default" },
                { "mode": "text", "text": "The claim has been assessed, and its outcome waits for approval." }
              ]
            }
          }
        ],
        "singletons": [{ "cfact": "kdr:finished", "when": "settled" }],
        "locks": [
          {
            "traitId": "assessment",
            "when": "#always",
            "writableVia": "assess",
            "overrideWhen": "kdr:hasAdminLevel"
          }
        ]
      }
    }
  ]
}
```

**A claim has to be complete to be assessed.** The one eligibility test asks for `kdr:coreDetailsComplete`, which the
[incident details](reporting.md#correcting-a-report) give a claim. With `highlightStart`, an adjuster sees a claim
nobody has started on as theirs to start.

### The assessment is saved once

The `assess` task runs `userHasLabel`, which asserts `kdr:reviewer` for a person with the `adjuster` label. Its
`saveWhen` asks for two facts: `kdr:reviewer`, so that only an adjuster may save it, and `kdr:isCta`, so that they may
save it only while it is the workflow's current step. Saving it makes the approval the current step. From then on the
task can be read and not saved.

The lock on `assessment` closes the other ways in. While the claim is in the settlement, the trait may be changed only
by whoever may save the `assess` task, which is nobody once it is saved. Its `overrideWhen` leaves one way: an
administrator, here a claims manager, may change it from the claim's **Raw edit** page, and only by giving a reason,
which is kept with the claim.

So an amount cannot grow after it is approved. A rule this strict is a choice. With `"saveWhen": "kdr:reviewer"` alone,
as the conference demo's review has it, an adjuster could correct their own typing, before the approval and after it.

### Who approves it

The `approveOutcome` task is an approval, and two keys decide who gives it.

**Who might.** The approval's `approveWhen` admits an adjuster or an administrator, and leaves out whoever owns the
claim.

**Who does, for this claim.** The task's `ctaFilterByValue` function reads a field of the form, and picks whom the step
waits on by its value:

| Key | What it means |
| --- | --- |
| `trait`, `valuePath` | The field it reads: the assessment's `overLimit`. |
| `cases` | For each `value`, the `filter` a person has to pass, and the `label` that says so to anyone who does not. |
| `otherwise` | The filter and the label for any other value, or none. |

A filter narrows `approveWhen` and never widens it. Where `overLimit` is `false`, the step waits on an adjuster
(`kdr:reviewer`). Where it is `true`, it waits on an administrator (`kdr:hasAdminLevel`). The `otherwise` is the one
for more authority, so a mistake in the cases sends a claim up to a manager rather than letting it through. A claim is
in **Waiting on you** for exactly the people its step waits on, so a manager is not shown every small payment.

These are approval limits by role. A third limit, for a director, would take a computed text in place of the yes or no
(`"adjuster"`, `"manager"`, `"director"`), a label for directors, and a case for each.

**What an approval does not ask.** An approval asks who the approver is, and whether they own the claim. It does not
ask who assessed it, so an adjuster may approve a small payment they assessed themselves. An insurer that wants a
second adjuster would give the approval to people with a label of their own.

### What the approval shows

Above its prompt, an approval shows the traits of the task before it. The approval's `showTraits` names others instead:
the claim's number, the incident, the evidence, and the assessment, so whoever approves the outcome reads all of it on
one page.

The task's `display` chooses what the step shows, trying its branches in order: once approved, who approved it and
when; before the assessment is saved, a note that it comes first; for an adjuster or a manager, what the approval
shows, with the button for whoever the step waits on and its `label` for the other; and for the policyholder, a line
saying that the outcome waits for approval.

### What stays open

Approving the outcome makes the claim **Finished**, with the `singletons` entry, as the conference demo's [committee
review](../demo-client/committee-review.md#workflow) does. The lock is on the assessment alone. The report and the
evidence can still be changed, so that a policyholder can send a late document, and an adjuster can be shown it.

## What the adjuster sees

An adjuster's forms list is **Forms**, with every claim. A claim nobody has assessed shows **Start Settlement**
highlighted, and is under **Waiting on you**, in the list and on the home page. One click starts it and opens **Assess
the claim**: **Outcome**, **Reasons**, and, once the outcome is **Pay the claim**, **Amount to pay (dollars)**. The
claim's status is **Needs Review**. The incident itself is on the claim's own page, which **View Info** in the forms
list opens.

After **Save the assessment**, the step is read-only and says *Nothing more is needed on this step*, and the assessment
has a line the adjuster did not type, **Over the adjusters' limit**. The link **Next: Approve the outcome** opens the
next step. For $5,000 or less it shows the claim, its assessment, and the button **Approve the outcome**, which asks
the adjuster to confirm. For more, in place of the button it says *A claims manager approves the outcome, since it pays
more than $5,000.*

## What the claims manager sees

A claims manager's home page lists each payment over $5,000 under **Waiting on you**, such as *INC-20261124-003 —
Approve the outcome*. Opening it shows what the adjuster saw, with the button. Once it is pressed the step says who
approved it, and the claim's status is **Finished**.

## What the policyholder sees

The policyholder sees the settlement muted in the forms list until it has started, with *Waiting on someone else* under
it. They can open it and read it, and the approval step tells them that the outcome waits for approval. Once it is
approved they see who approved it, and the claim's status is **Finished**.

Next: [The claims register](register.md).
