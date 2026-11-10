[← Abstract](abstract.md) · [Contents](README.md#the-pages)

# Committee review

The committee review is a normal workflow that not every talk may enter, and not every person may work on. A talk
may enter it once its core details are complete. A committee member records a review and accepts the talk, and then an
organizer confirms it for the program. The speaker can read the review but cannot change it.

Two pieces of configuration go into it, and this page shows both:

- [the review trait](#the-review-trait), in the `base` bundle;
- [the workflow](#workflow), in the `review` bundle.

The trait is an addition to the `base` bundle; the [client page](client.md#what-the-bundle-holds) lists everything the
bundle now holds.

The committee itself is a label, `committee`, that the [client](client.md) lists in `userLabels`. An administrator
puts the label on a user, and that user is a committee member.

The organizer does this on the Users page, where each user's **Labels** show a **committee** box. Beside it is the
note the client's definition gives the label (in `userLabelNotes`), then what the label lets a person do, worked out
from this workflow: *Saves “Record the review” in “Committee review”* and *Approves “Accept the talk” in “Committee
review”*. Last comes how many users have it. While a user has no label, the page says what they can't do without one.

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
    "title": "Committee review",
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
        "description": "Feedback the speaker will see.",
        "g-longText": true
      }
    }
  }
}
```

The notes are long text, so the form gives the reviewer a box of several lines to write them in. See
[Long text](reference.md#long-text).

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
        "$comment": "The committee reviews a talk once its core details are complete, then accepts it, and an organizer confirms it.",
        "label": "Committee review",
        "highlightStart": true,
        "eligibility": [
          {
            "id": "coreDetailsDone",
            "$comment": "Reviewers need the track.",
            "test": "kdr:coreDetailsComplete",
            "explanation": "The talk's core details have to be complete before the committee reviews it."
          }
        ],
        "tasks": [
          {
            "id": "recordReview",
            "$comment": "Only a committee member may save this task; the speaker sees it read-only.",
            "label": "Record the review",
            "keyword": "review",
            "traits": [{ "traitId": "review", "required": true }],
            "saves": [{ "id": "saveReview", "label": "Save the review", "kind": "edit" }],
            "functions": [{ "fn": "userHasLabel", "label": "committee", "cfact": "kdr:reviewer" }],
            "saveWhen": "kdr:reviewer"
          },
          {
            "id": "accept",
            "$comment": "Two people accept a talk, in order: a committee member, then an organizer.",
            "label": "Accept the talk",
            "traits": [],
            "saves": [],
            "functions": [{ "fn": "userHasLabel", "label": "committee", "cfact": "kdr:reviewer" }],
            "approval": {
              "cfact": "talkAccepted",
              "steps": [
                {
                  "id": "committee",
                  "label": "Committee acceptance",
                  "approveWhen": "kdr:reviewer,~kdr:isOwner",
                  "waiting": "A committee member accepts the talk.",
                  "prompt": "Read the review, then accept the talk for the program.",
                  "button": "Accept the talk",
                  "cfact": "talkRecommended"
                },
                {
                  "id": "organizer",
                  "label": "Organizer confirmation",
                  "approveWhen": "kdr:hasAdminLevel,~kdr:isOwner",
                  "waiting": "An organizer confirms the talk for the program.",
                  "prompt": "The committee accepted this talk. Confirm it for the program.",
                  "button": "Confirm for the program"
                }
              ]
            },
            "display": {
              "select": [
                { "cfactExpression": "talkAccepted", "mode": "default" },
                {
                  "cfactExpression": "~kdr:isCta",
                  "mode": "text",
                  "text": "The review has to be recorded before the talk can be accepted.",
                  "disabled": true
                },
                { "cfactExpression": "kdr:reviewer|kdr:hasAdminLevel", "mode": "default" },
                { "mode": "text", "text": "The committee accepts the talk, then an organizer confirms it for the program." }
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

Seven things are new. The reference explains each in general under
[Facts and eligibility](reference.md#facts-and-eligibility) and
[Who may work on a task](reference.md#who-may-work-on-a-task); here is what they do for the review.

**An eligibility test.** The `eligibility` list has one test: the form must have the fact `kdr:coreDetailsComplete`,
which the core details workflow gives it once every trait the workflow requires has an entry. A talk whose core details
are not done cannot enter the review, and the forms list shows the `explanation` instead of offering it.

**A highlighted Start.** Its `highlightStart: true` asks the forms list to point the right people at the review
before it starts. While a talk may enter the review and has not, its first task (`recordReview`) stands in for its next
step: a person who could save that task sees **Start** highlighted, as their turn, and one click puts the talk into the
review and opens the task. Everyone else sees the review waiting, muted, with the reason under it: the task is
someone else's to do. It only points people at the talk. Who may enter it is still decided by the eligibility test
alone.

**A task only a committee member may save.** The `recordReview` task runs a `userHasLabel` function, which asserts
the fact `kdr:reviewer` for a person whose user has the `committee` label. Its `saveWhen` then allows the save for
that person alone. The speaker sees the task, and the review once it is recorded, but cannot save it.

The `locks` list is the other half of that rule, and the platform insisted on it: writing this bundle without the
lock was refused, because a save rule alone could be bypassed by editing the form another way. The lock says that
while the talk is in the review, the `review` trait may be changed only by whoever may save `recordReview`, or by an
administrator overriding it. Its `when` is the condition under which it holds, and `#always` means for as long as the
talk is in the workflow.

**A search keyword.** The `recordReview` task's `keyword` says what kind of step it is: `review`, a step that waits on
someone to look the talk over. While a step carrying it is the workflow's next step, the forms list shows the talk as
**Needs Review**. The `accept` task carries `review` too without saying so, as every approval task does, so the talk
needs review from the moment it enters the workflow until it is accepted.

**An approval task.** The `accept` task collects no traits and offers no saves. Its `approval` is a button, which
records who pressed it and when. Once the talk is accepted, the approval asserts the fact `talkAccepted`, which the
client declared in its `base` bundle.

**An approval in two steps.** The approval's `steps` are given in order, each by a different kind of person. First a
committee member presses **Accept the talk**, then an organizer presses **Confirm for the program**, and only then does
`talkAccepted` hold. Each step has its own `prompt` and `button`, and an `approveWhen` saying who may give it: the
committee's step takes a reviewer, and the organizer's an administrator (`kdr:hasAdminLevel`). Both leave out the talk's
own speaker, with `~kdr:isOwner`. A step's `label` names it in the record once it is given, and its `waiting` tells
anyone else who the step is waiting on. The committee's step also asserts a fact of its own as it is given,
`talkRecommended`, which a lock or another workflow could test. While a step is the one the talk waits on, its
`approveWhen` decides whose turn it is in the forms list, so the talk moves from the committee's **Waiting on you** to
the organizers'.

The task's `display` chooses what the step shows, trying its branches in order: once accepted, the steps and who gave
each; before the review is recorded, a note that it comes first, greyed out; for a committee member or an organizer,
the step waiting and its button for whoever may press it; for anyone else, a line saying how a talk is accepted.

**What the approval shows.** Above its prompt, an approval task shows the review it is accepting: the traits of the
task before it, read-only, so a committee member reads the score and the notes before pressing the button. That is the
default, which is why the `accept` task says nothing about it. An approval that should show other tasks names them in
its approval's `showTasks`, such as `"showTasks": ["recordReview"]`, and an empty list shows none.

**A singleton.** The `singletons` list turns the workflow's own fact into one about the whole form: once
`talkAccepted` holds, the form has `kdr:finished`, and the forms list shows the talk as **Finished**. That is a
different thing from the workflow finishing, which it does when its tasks are complete, as any workflow does. The
singleton is what lets a workflow decide that the *talk* is done. The framework has two such facts a workflow may
map its own onto: `kdr:finished`, which the forms list shows as **Finished**, and `kdr:ownerOnly`, which keeps a form to
its owner, its reviewers, and administrators. **Needs Review** is not one of them: the `review` keyword gives it.

## What the speaker sees

In the forms list, a talk whose core details are done shows **Committee review** in its **Workflows** column muted, with
*Waiting on someone else* under it, since the review is the committee's to start. A talk whose core details are not done
shows the workflow greyed, with the explanation when clicked. The speaker can open the workflow and read it, and the
page says *Someone else completes this step; you can see it here.* Once the review is recorded, the speaker sees the
score and the notes.

## What a committee member sees

A committee member is an editor. An editor sees every form of the client and may edit any of them, except one kept
to its owner. It administers nothing: no users, no configuration, and no reports. So a committee member's forms list
is **Forms**, with every speaker's talks. An editor is not at an administrator's level, so when they open a talk's
abstract to edit it, the form leaves off the note for the organizers, as the speaker's does (see
[Abstract](abstract.md)).

On a talk whose core details are done, the member's forms list shows **Start** and **Committee review** highlighted in
its **Workflows** column, and its **Waiting on you** button narrows the list to the talks waiting on them. One click
puts the talk into the review and opens the **Record the review** step, with no second **Start** to press. The talk's
status in the forms list is then **Needs Review**. The step is editable: a **Score** and **Notes for the speaker**, and
a **Save the review** button. Once saved, the step says *Nothing more is needed on this step*, and a link beside it,
**Next: Accept the talk**, opens that step, which offers its button. After it is pressed, the step reads *Committee
acceptance by* and who accepted it, then *An organizer confirms the talk for the program.* The talk still needs
review, and has left the member's **Waiting on you**.

## What the organizer sees

An organizer is an administrator, who sees every talk, so their forms list is **Forms**. Once the committee has
accepted a talk, the organizer's forms list shows **Committee review** highlighted in its **Workflows** column, and the
talk is under their **Waiting on you**. Opening it and choosing **Accept the talk** shows the review read-only, the line
*Committee acceptance by* and who accepted it, and the button **Confirm for the program**. After it is pressed, the
step lists both acceptances, and the talk's status in the forms list is **Finished**.

Next: [Workshop logistics](workshop-logistics.md).
