# The schema dialect

The runtime describes data with JSON Schema, and reads a dialect of it: most of the standard keywords, some refused,
and keywords of its own that begin with `g-`. The full reference lists every keyword the runtime reads, the shape
each one's value must take and what it means, the standard keywords it refuses, and the rules its parser applies
beyond the keywords' shapes.

**The reference is generated, so it is not kept in this file.** A running server makes it from the tables its
schema parser reads, so it cannot disagree with the parser. Read it on the server's **Documents** page, under
**Schema dialect**, where anyone may open it without signing in. On the public reference site it is at
[dynamicruntime.org/wa/docs/schema-dialect](https://dynamicruntime.org/wa/docs/schema-dialect). The same content is served as JSON, as a schema for
schemas, at `GET /schema/metaSchema`.

On a running server, a link to this file opens the reference itself.

## Where it comes from

| What | Source |
|---|---|
| The standard keywords read, each with its shape and meaning | [`SchStdKeywords.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/schema/SchStdKeywords.kt) |
| The `g-` keywords, each with its shape and meaning | [`SchGKeywords.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/schema/SchGKeywords.kt) |
| The standard keywords refused, each with what to write instead | `refusedKeywords` in [`SchParser.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/schema/SchParser.kt) |
| The schema for schemas, and the rules it cannot state | [`SchMetaSchema.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/schema/SchMetaSchema.kt) |
| The reference page, made from all of these | [`SchDialectReference.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/schema/SchDialectReference.kt) |
