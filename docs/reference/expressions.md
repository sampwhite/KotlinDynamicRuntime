# Expressions

Much of what a client configures can hold an expression between `${` and `}`: a line of copy, a column in the forms
list, a form's labels, a value computed from a form's other fields, and a validation message. The full reference
describes the language (its values, operators, and what a missing value does), and lists every function with an
example.

**The reference is generated, so it is not kept in this file.** A running server makes its list of functions from the
functions themselves, so it cannot disagree with them. Read it on the server's **Documents** page, under
**Expressions**, where anyone may open it without signing in. On the public reference site it is at
[dynamicruntime.org/wa/docs/expressions](https://dynamicruntime.org/wa/docs/expressions).

On a running server, a link to this file opens the reference itself.

## Where it comes from

| What | Source |
| --- | --- |
| The functions, each with what it gives, its arguments, and an example | `scriptFunctions` in [`ScriptFunc.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/util/ScriptFunc.kt) |
| The grammar the page's syntax states | [`ScriptExpr.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/util/ScriptExpr.kt) |
| The value rules the page's operators state | [`ScriptEval.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/util/ScriptEval.kt) |
| The reference page, made from these | [`ScriptReference.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/util/ScriptReference.kt) |
