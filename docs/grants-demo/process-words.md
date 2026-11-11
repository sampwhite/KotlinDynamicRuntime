[← The home page](home-page.md) · [Contents](README.md#the-pages)

# The words of the process

A client's people know their process by its own words. To the fund, a form is an application, starting a workflow is
beginning a step, and a review is a score. The platform ships general words (Start, Continue, Review), which fit any
process and name none. This page rewords what the forms list, a workflow's page, a form, and a report call things, so
the fund's people read their own process wherever it is shown.

Here is the whole `formsWords` bundle:

<!-- kdr-doc: bundle=formsWords -->
```json
{
  "kdr:fragmentDef": [
    {
      "fileId": "home",
      "content": {
        "forms": {
          "noWorkflows": "No steps yet",
          "waitingToggle": "Needs you",
          "whatIsThis": "Each row is one **application**: a request for a grant, and everything entered for it. **Contains** lists its parts.\n\nAn application goes through **steps**: its budget, and a reviewer's score. Each is something somebody does, and the column beside the application shows where it stands in each."
        },
        "workflowCell": {
          "start": "Begin",
          "continue": "Pick up",
          "waiting": "Waiting its turn",
          "waitingOnOwner": "With the applicant",
          "waitingOnOthers": "With someone else",
          "waitingForApproval": "Awaiting the fund's approval",
          "yourTurnGroup": "Yours to do",
          "availableGroup": "Ready to begin",
          "waitingGroup": "With others",
          "finishedGroup": "Done",
          "yourTurnCount": "${n} yours to do",
          "availableCount": "${n} ready to begin",
          "waitingCount": "${n} with others",
          "finishedCount": "${n} done",
          "notStarted": "This application has not begun this step. Begin it to work on it."
        },
        "formEntries": {
          "label": "Line ${n}"
        },
        "ctaReview": {
          "verb": "Score",
          "filterLabel": "Needs a score",
          "columnHeader": "To score",
          "reportLabel": "Needs a score",
          "statusText": "Needs a Score"
        }
      }
    }
  ]
}
```

All of it is in the `home` file, in four of its namespaces:

| Namespace | What it holds |
| --- | --- |
| `forms` | The forms list's own words: its filter, its explanation, and what a form in no workflow shows. |
| `workflowCell` | The workflow column's links and headings, and the line on a workflow's page before a form begins it. |
| `formEntries` | What a form calls each entry of a list. |
| `ctaReview` | The words of the call to action `review`, which the demo's review task carries. |

## The words

| Where it is | Key | As shipped | In the demo |
| --- | --- | --- | --- |
| Forms: the workflow column of a form that is in no workflow. | `home: forms.noWorkflows` | — | No steps yet |
| Forms: the filter above the list onto the forms waiting on you. | `home: forms.waitingToggle` | Waiting on you | Needs you |
| Forms: what "What is this?" beside the heading explains. | `home: forms.whatIsThis` | A **form** is one document: what was entered… | Each row is one **application**: a request for… |
| Forms: the link in the workflow column that starts a workflow on a form, and the button that starts it on the workflow's page. | `home: workflowCell.start` | Start | Begin |
| Forms: the link in the workflow column to a workflow that is your turn, when its step names nothing more specific. | `home: workflowCell.continue` | Continue | Pick up |
| Forms: a workflow in the workflow column that waits on someone else, when nothing says why. | `home: workflowCell.waiting` | Waiting | Waiting its turn |
| Forms: a workflow in the workflow column waiting on the form's owner, who is not you. | `home: workflowCell.waitingOnOwner` | Waiting on the owner | With the applicant |
| Forms: a workflow in the workflow column waiting on the people its step names, who do not include you. | `home: workflowCell.waitingOnOthers` | Waiting on someone else | With someone else |
| Forms: a workflow in the workflow column waiting on an approval you may not give. | `home: workflowCell.waitingForApproval` | Waiting for approval | Awaiting the fund's approval |
| Forms: the heading over the workflows that are your turn, in a cell's list and as the column of them in the split layout. | `home: workflowCell.yourTurnGroup` | Your turn | Yours to do |
| Forms: the heading over the workflows you could start, in a cell's list. | `home: workflowCell.availableGroup` | Available | Ready to begin |
| Forms: the heading over the workflows waiting on someone else, in a cell's list. | `home: workflowCell.waitingGroup` | Waiting | With others |
| Forms: the heading over the finished workflows, in a cell's list. | `home: workflowCell.finishedGroup` | Finished | Done |
| Forms: how many workflows are your turn, where a narrow screen folds the workflow column to counts. | `home: workflowCell.yourTurnCount` | `${n} your turn` | `${n} yours to do` |
| Forms: how many workflows you could start, where a narrow screen folds the workflow column to counts. | `home: workflowCell.availableCount` | `${n} available` | `${n} ready to begin` |
| Forms: how many workflows wait on someone else, where a narrow screen folds the workflow column to counts. | `home: workflowCell.waitingCount` | `${n} waiting` | `${n} with others` |
| Forms: how many workflows are finished, where a narrow screen folds the workflow column to counts. | `home: workflowCell.finishedCount` | `${n} finished` | `${n} done` |
| Forms: the link in the workflow column to a review that is your turn. | `home: ctaReview.verb` | Review | Score |
| Forms: the filter onto the forms needing a review, and a review waiting on someone else. | `home: ctaReview.filterLabel` | Needs review | Needs a score |
| Forms: the heading of the column of reviews that are your turn, in the split layout. | `home: ctaReview.columnHeader` | To review | To score |
| Forms: the Status column's chip on a form waiting on a review. | `home: ctaReview.statusText` | Needs Review | Needs a Score |
| Workflow: the line under the title, for a form that has not started the workflow. | `home: workflowCell.notStarted` | This form has not started this workflow. Start… | This application has not begun this step. Begin… |
| Form: the heading over each entry of a list, unless the form's layout gives the field its own. | `home: formEntries.label` | `Entry ${n}` | `Line ${n}` |
| Reports: a review waiting on a form, as a report's table shows it. | `home: ctaReview.reportLabel` | Needs review | Needs a score |

**A call to action's words go together.** The demo's review task carries the keyword `review`, which asks the people
who may act on it to review the application. The keyword's five words are in the `ctaReview` namespace: the link a
reviewer follows, the filter onto the applications waiting for one, the column of them, the chip in the Status column,
and a report's label. The demo changes all five, so a reviewer reads "Score" wherever the review is shown.

**What a list's entries are called** is one key for every list of every form, so it is general words: the demo's are
`Line ${n}`, for its budget's lines. A form's layout can give one list words of its own (its `entryLabel`), which
then win over this key for that list.

**Some words are not copy.** The menu's **My forms** is a menu item, which a client renames on its **Menu** panel,
beside **Copy** on its page under **Clients**. A few of the forms list's words are still the app's own, with no key to
reword: its **New form** and **Create a form** buttons and its count of forms among them. So the fund's applicants
still read "form" there.

## What you see

Join the demo as an applicant (see [Try it](README.md#try-it)), and open your forms from the menu.

1. Choose **Create a form**, type a project's name and an amount, and choose **Create the application**.
2. In the forms list, the application's workflow column offers **Begin** on its **Budget** step, and says its
   **Review** is **With someone else**. On a narrow screen the column folds to counts: **1 ready to begin · 1 with
   others**.
3. Choose **Begin**: the **Budget** page opens, and the line under its title says **This application has not begun this
   step**. Choose **Begin** there, then **+ Add** in **Budget lines**: the entry is headed **Line 1**.

Then join as a reviewer, from the same card on the home page, and open the forms list.

1. The list has a **To score** column. An application whose review nobody has begun offers **Begin** there, and its
   **Budget** step, which is the applicant's, says **With the applicant**.
2. Choose **Begin**: the review opens, ready for a score. Back in the list, the link says **Score**.
3. Open **Filters**: **Needs a score** narrows the list to the applications waiting for one.

On a test instance of your own, sign in as the demo's administrator and open **Reports**: the **Applications** report's
**Review** column says **Needs a score** for each application waiting for one.

Next: [The mails](mails.md).
