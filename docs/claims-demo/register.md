[← Settlement](settlement.md) · [Contents](README.md#the-pages)

# The claims register

An insurer has to be able to say what it decided on each claim, when, and by whose authority. The demo's one report is
that list. Nobody fills it in. Each of its columns takes its value from the claim, so the register says what the claim
says.

Here is the whole `register` bundle:

<!-- kdr-doc: bundle=register -->
```json
{
  "kdr:reportDef": [
    {
      "reportId": "claimsRegister",
      "definition": {
        "reportId": "claimsRegister",
        "$comment": "Per form, a row for each claim. Grouped, how many claims of each kind were paid or declined, and what was paid.",
        "label": "Claims register",
        "description": "Every claim, what was decided on it, and when and by whom the decision was approved.",
        "columns": [
          { "columnId": "number", "label": "Incident", "path": "form.filing.incidentNumber" },
          { "columnId": "filed", "label": "Filed", "path": "meta.createdAt" },
          { "columnId": "kind", "label": "Kind", "path": "form.incident.kind" },
          { "columnId": "policyholder", "label": "Policyholder", "path": "user.name" },
          { "columnId": "status", "label": "Status", "path": "meta.formStatus" },
          { "columnId": "outcome", "label": "Outcome", "path": "form.assessment.outcome" },
          { "columnId": "amount", "label": "Amount", "path": "form.assessment.amount", "rollup": "sum" },
          {
            "columnId": "approvedAt",
            "label": "Approved",
            "path": "workflow.settlement.approval[approveOutcome].approvedAt"
          },
          {
            "columnId": "approvedBy",
            "label": "Approved by (user)",
            "path": "workflow.settlement.approval[approveOutcome].approvedBy"
          }
        ],
        "groupBy": ["kind", "outcome"]
      }
    }
  ]
}
```

The keys are explained on the conference demo's [Reports](../demo-client/reports.md#report). Two columns use a path it
does not.

**An approval can be a column.** The path `workflow.settlement.approval[approveOutcome].approvedAt` is when the
`approveOutcome` task of the settlement was approved, and `approvedBy` is the user id of whoever approved it. Both are
empty until then. Other attributes say whether an approval was reversed, when, by whom, and why. The [report paths
reference](../reference/report-paths.md#an-approval) lists them all.

**The day a claim was filed is the claim's own.** The path `meta.createdAt` is when the form was created, which is when
the report was filed. The incident's number says the day too, but as text.

**Grouped, it adds up.** The report's `groupBy` has a row for each kind of incident and outcome, with how many claims
there are, and the `rollup` on the amount adds up what they pay.

## What the claims manager sees

A claims manager is an administrator, so their menu has a **Reports** entry, with **Claims register** on its page. It
opens grouped: **Kind**, **Outcome**, **Forms**, and **Amount (sum)**. Run **Per form**, it has a row for each claim,
starting with an **Open** link to it, and then the columns in order. A claim that is not yet settled shows a dash for
what has not been decided. **Download CSV** saves whichever table is showing as a file.

An adjuster has no **Reports** entry. An editor sees every claim, and administers nothing.

This is the last page. [Contents](README.md#the-pages) lists them all.
