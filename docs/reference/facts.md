# Facts

A fact is a yes-or-no statement about the request, a task, or a form, and a client's configuration tests facts to
decide who sees a menu entry or a field, who may save a task, and which forms a workflow takes. The full reference says
where facts come from, where a test is written and which facts it sees there, and how a test is written (which is not
the `${…}` expression language), then lists every fact the platform declares.

**The reference is generated, so it is not kept in this file.** A running server makes its list of facts from the
declarations themselves, so it cannot disagree with them. Read it on the server's **Documents** page, under **Facts**,
where anyone may open it without signing in. On the public reference site it is at
[dynamicruntime.org/wa/docs/facts](https://dynamicruntime.org/wa/docs/facts).

On a running server, a link to this file opens the reference itself.

## Where it comes from

| What | Source |
| --- | --- |
| The platform's facts, each with its group, what makes it true, and whether it reaches the page | `addCommonCFacts` in [`CoreCFacts.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/cfact/CoreCFacts.kt) |
| The grammar the page's syntax states | [`CFactParser.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/cfact/CFactParser.kt) |
| The reference page, made from these | [`FactsReference.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/cfact/FactsReference.kt) |
