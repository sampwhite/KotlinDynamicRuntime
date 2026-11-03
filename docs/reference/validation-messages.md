# Validation messages

When the runtime refuses a value, it says why, with a code and a message. The full reference lists every code, what
each means and which keywords raise it, the parameters a message for it may use, and how you can give a code a message
of your own, on a schema or on one form.

**The reference is generated, so it is not kept in this file.** A running server makes it from the validator's own list
of codes, so it cannot disagree with the validator. Read it on the server's **Documents** page, under **Validation
messages**, where anyone may open it without signing in. On the public reference site it is at
[dynamicruntime.org/wa/docs/validation-messages](https://dynamicruntime.org/wa/docs/validation-messages).

On a running server, a link to this file opens the reference itself.

## Where it comes from

| What | Source |
| --- | --- |
| The codes, each with what it means and the keywords that raise it | `SchFailCode` in [`SchValidator.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/schema/SchValidator.kt) |
| The parameters a message for each code may use | `errorContextNames` in [`SchLayout.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/schema/SchLayout.kt) |
| The reference page, made from these | [`SchMessageReference.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/schema/SchMessageReference.kt) |
