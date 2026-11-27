[← Reporting an incident](reporting.md) · [Contents](README.md#the-pages)

# Numbering incidents

Every incident has a number, such as `INC-20261124-003`: the third reported on November 24, 2026. A policyholder can
read it out over the phone, and an adjuster can search the forms list for it.

The conference demo [numbers its talks](../demo-client/numbering.md) too, and explains counters from the start. The two
demos answer one question differently, which is *when* a form is given its number. A talk is given its id as its form
opens, so the speaker sees the id before saving. An incident is given its number as its report is saved. This page
shows how, and [when to choose which](#when-to-give-the-number).

## Counter

The counter is the one entry in the `base` bundle's `kdr:counterDef` slot:

<!-- kdr-doc: bundle=base pointer=/kdr:counterDef/0 -->
```json
{
  "counterId": "incident",
  "description": "Numbers the incidents, day by day, in the order their reports are filed.",
  "prefix": "INC-${compactDay}-",
  "digits": 3,
  "reset": "daily",
  "issue": "onSave"
}
```

| Key | What it means |
| --- | --- |
| `prefix` | The text before the count. It is a template, and `${compactDay}` is the day the number is given on, in the client's time zone, without its dashes: `20261124`. |
| `digits` | How many digits the count is padded to with zeroes. A day with more than 999 incidents goes on to `INC-20261124-1000`. |
| `reset` | When the count starts again at 1. The value `daily` starts it again on each of the client's days. A daily counter needs the day in its prefix, or its numbers would repeat, and one without it is refused when its bundle is written. |
| `issue` | When a form is given its number. The value `onSave` means as it is saved. Left out, it would be `onOpen`: as the form opens. |

## Trait

The number is the one field of a trait of its own, `filing`:

<!-- kdr-doc: bundle=base pointer=/kdr:traitDef/2 -->
```json
{
  "traitId": "filing",
  "typeName": "client.claimsdemo.FilingEntry",
  "description": "The incident's number, given as its report is filed.",
  "appliesTo": ["formDoc"],
  "dataSchema": {
    "type": "object",
    "title": "Filing",
    "properties": {
      "incidentNumber": {
        "type": "string",
        "title": "Incident number",
        "description": "The incident's number, given by the insurer's counter.",
        "g-counter": "incident"
      }
    },
    "required": ["incidentNumber"]
  }
}
```

The field `incidentNumber` names the counter with `g-counter`, one of the platform's additions to JSON Schema (see the
[schema dialect reference](../reference/schema-dialect.md)). A field like this is never typed. Once it is stored it
cannot be changed, and no other claim can be given the same number.

**The creation workflow collects the trait,** so every report is numbered as it is filed. Its task lists `filing` after
the incident and the reporter, with `required: false` (see [Reporting an incident](reporting.md#workflow)). The server
always fills it, so it need not be required of whoever files the report. A program that files a report through the API
sends the trait with its data empty, and the claim is given the next number.

## When to give the number

| | `onOpen` | `onSave` |
| --- | --- | --- |
| The person sees the number | Before they save | After they save |
| A form opened and left | Uses a number up | Uses none up |
| The count follows | The order the forms were opened | The order the forms were saved |
| On the form | A section shows the number | There is nothing to show until the form is saved |

**A report is a form people abandon.** Somebody opens it by mistake, or starts to report an accident and is called
away. Numbers given as the form opens would be used up by each of them, so a day with three reports might end at
`INC-20261124-011`, and anybody reading the register would ask what became of the other eight. Giving the number as the
report is saved leaves only the reports that were filed. And the number says what it seems to: the third one that day
was the third one filed.

**What it costs is the preview.** The policyholder does not know the number until they have filed the report. Here that
is no loss, since an incident has no number until it is reported. A form whose number has to be written on something
before it is saved, such as a label or a paper copy, wants `onOpen`.

**A number can still be missing.** Giving the number as the form is saved makes a gap rare. It does not make one
impossible:

- **A save that fails at the last moment.** The report is checked before it is given its number, so one refused for a
  missing answer uses none up. A save that fails after that, as the claim is written, has used its number.
- **A claim that is deleted.** Its number is not given to another claim.

So a counter like this one suits numbers that people read and search for. It does not by itself prove that nothing is
missing, which some records, such as invoices in some countries, are required to.

## What the policyholder sees

The report has no section for the number. Once the report is filed, the forms list says that *the form INC-20261124-003
was created*, and the claim's **Incident** column shows it. Choosing **View Info** opens the claim's own page, whose
first section, **Filing**, shows it as the **Incident number**, which nobody can change.

Next: [Evidence](evidence.md).
