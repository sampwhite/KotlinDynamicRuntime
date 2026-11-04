# Report paths

Each column of a report names where its value comes from with a path, such as `form.session.track`,
`workflow.review.category`, `user.name`, or `meta.formStatus`. The full reference describes how a path is written (its
sources, a keyed trait's selectors and `[*]`, and an entry's envelope after `@`), lists every attribute a path can end
in outside a trait's own data with its kind, and says how a column makes several values one.

**The reference is generated, so it is not kept in this file.** A running server makes its lists from the names the
path parser reads, so they cannot disagree with it. Read it on the server's **Documents** page, under **Report paths**,
where anyone may open it without signing in. On the public reference site it is at
[dynamicruntime.org/wa/docs/report-paths](https://dynamicruntime.org/wa/docs/report-paths).

On a running server, a link to this file opens the reference itself.

## Where it comes from

| What | Source |
| --- | --- |
| The attributes, each with its kind and what it holds | `RWF`, `RUSR`, `RMETA`, and `RENV` in [`ReportConstants.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/gedra/report/ReportConstants.kt) |
| The combines, each with what it gives | `ReportCombine` in [`ReportEval.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/gedra/report/ReportEval.kt) |
| The grammar the page states | [`ReportPath.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/gedra/report/ReportPath.kt) and [`ReportBind.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/gedra/report/ReportBind.kt) |
| The reference page, made from these | [`ReportPathReference.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/gedra/report/ReportPathReference.kt) |
