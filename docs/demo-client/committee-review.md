[← Abstract](abstract.md) · [Contents](README.md#the-pages)

# Committee review

The committee review is a normal workflow that not every talk may enter, and not every person may work on. A talk
may enter it once its survey is complete. A committee member records a review, then accepts the talk; the speaker
can read the review but cannot change it.

Two pieces of configuration go into it, and this page shows both:

- [the review trait](#the-review-trait), in the `base` bundle;
- [the workflow](#workflow), in the `review` bundle.

The committee itself is a label, `committee`, that the [client](client.md) lists in `userLabels`. An administrator
puts the label on a user, and that user is a committee member.

## The review trait

The `review` trait is small: a score and a note for the speaker.

<!-- kdr-doc: bundle=base pointer=/kdr:traitDef/3 -->
```json
{
  "traitId": "review",
  "typeName": "client.confdemo.ReviewEntry",
  "description": "The committee's review of the talk.",
  "appliesTo": ["formDoc"],
  "dataSchema": {
    "type": "object",
    "properties": {
      "score": {
        "type": "integer",
        "title": "Score",
        "description": "The committee's score, from 1 to 5.",
        "minimum": 1,
        "maximum": 5
      },
      "notes": {
        "type": "string",
        "title": "Notes for the speaker",
        "description": "Feedback the speaker will see."
      }
    }
  }
}
```

Nothing here says who may fill it in. That is the workflow's job.

## Workflow

Here is the whole `review` bundle:

<!-- kdr-doc: bundle=review -->
```json
{
  "kdr:workflowDef": [
    {
      "workflowId": "review",
      "definition": {
        "workflowId": "review",
        "entry": "normal",
        "$comment": "The committee reviews a talk once its survey is complete, then accepts it.",
        "label": "Committee review",
        "eligibility": [
          {
            "id": "surveyDone",
            "$comment": "Reviewers need the track.",
            "test": "kdr:surveyComplete",
            "explanation": "The talk's survey has to be complete before the committee reviews it."
          }
        ],
        "tasks": [
          {
            "id": "recordReview",
            "$comment": "Only a committee member may save this task; the speaker sees it read-only.",
            "label": "Record the review",
            "traits": [{ "traitId": "review", "required": true }],
            "saves": [{ "id": "saveReview", "label": "Save the review", "kind": "edit" }],
            "functions": [{ "fn": "userHasLabel", "label": "committee", "cfact": "kdr:reviewer" }],
            "saveWhen": "kdr:reviewer"
          },
          {
            "id": "accept",
            "label": "Accept the talk",
            "traits": [],
            "saves": [],
            "functions": [{ "fn": "userHasLabel", "label": "committee", "cfact": "kdr:reviewer" }],
            "approval": {
              "cfact": "talkAccepted",
              "prompt": "Read the review, then accept the talk for the program.",
              "button": "Accept the talk"
            },
            "display": {
              "select": [
                { "cfactExpression": "talkAccepted", "mode": "text", "text": "Accepted by ${approvedByName}." },
                {
                  "cfactExpression": "~kdr:isCta",
                  "mode": "text",
                  "text": "The review has to be recorded before the talk can be accepted.",
                  "disabled": true
                },
                { "cfactExpression": "kdr:reviewer", "mode": "default" },
                { "mode": "text", "text": "A committee member accepts the talk." }
              ]
            }
          }
        ],
        "singletons": [{ "cfact": "kdr:finished", "when": "talkAccepted" }],
        "locks": [
          { "traitId": "review", "when": "#always", "writableVia": "recordReview", "overrideWhen": "kdr:hasAdminLevel" }
        ]
      }
    }
  ]
}
```

Four things are new. The reference explains each in general under
[Facts and eligibility](reference.md#facts-and-eligibility) and
[Who may work on a task](reference.md#who-may-work-on-a-task); here is what they do for the review.

**An eligibility test.** The `eligibility` list has one test: the form must have the fact `kdr:surveyComplete`,
which the survey gives it once every trait the survey requires has an entry. A talk whose survey is not done cannot
enter the review, and the forms list shows the `explanation` instead of offering it.

**A task only a committee member may save.** The `recordReview` task runs a `userHasLabel` function, which asserts
the fact `kdr:reviewer` for a person whose user has the `committee` label. Its `saveWhen` then allows the save for
that person alone. The speaker sees the task, and the review once it is recorded, but cannot save it.

The `locks` list is the other half of that rule, and the platform insisted on it: writing this bundle without the
lock was refused, because a save rule alone could be bypassed by editing the form another way. The lock says that
while the talk is in the review, the `review` trait may be changed only by whoever may save `recordReview`, or by an
administrator overriding it.

**An approval task.** The `accept` task collects no traits and offers no saves; its `approval` is a button. Pressing
it records who accepted the talk and when, and asserts the fact `talkAccepted`, which the client declared in its
`base` bundle. The task's `display` chooses what the step shows, trying its branches in order: once accepted, who
accepted it; before the review is recorded, a note that it comes first, greyed out; for a committee member, the
button; for anyone else, a line saying a committee member accepts the talk.

**A singleton.** The `singletons` list turns the workflow's own fact into one about the whole form: once
`talkAccepted` holds, the form has `kdr:finished`, and the forms list shows the talk as **Finished**.

## What the speaker sees

In the forms list, a talk whose survey is done shows **Committee review available**; one whose survey is not done
shows the explanation instead. The speaker can open the workflow and read it, and the page says *Someone else
completes this step; you can see it here.* Once the review is recorded, the speaker sees the score and the notes.

## What a committee member sees

A committee member is an administrator, so their forms list is **Forms**, with every speaker's talks. On a talk whose
survey is done, **Committee review** opens with the **Record the review** step editable: a **Score** and **Notes for
the speaker**, and a **Save the review** button. Once saved, the **Accept the talk** step offers its button. After it
is pressed, the step reads *Accepted by* and the member's name, and the talk's status in the forms list is
**Finished**.

Next: [Workshop logistics](workshop-logistics.md).
