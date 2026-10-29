# A client's configuration

A client's configuration is stored as bundles, each made of slots -- its traits, its workflows, its reports -- and
each slot of entries. The full reference lists every slot, the fields an entry in it holds, and the types those refer
to. It also says what a write or an edit of one must send: what each edit action's data must carry, how an edit says
what it read, and what is checked when.

**The reference is generated, so it is not kept in this file.** A running server makes it from the slot shapes a
configuration write is held to, so it cannot disagree with what the server accepts. Read it on the server's
**Documents** page, under **Client configuration**, where anyone may open it without signing in. On the public
reference site it is at
[dynamicruntime.org/wa/docs/client-configuration](https://dynamicruntime.org/wa/docs/client-configuration).

On a running server, a link to this file opens the reference itself.

## Where it comes from

| What | Source |
|---|---|
| The slots, each with its key and the shape of its entries | [`CoreConfigTraits.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/gedra/CoreConfigTraits.kt) |
| The slot shapes compiled, as a write is held to them | [`ConfigSlotGate.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/gedra/ConfigSlotGate.kt) |
| The endpoints that write and edit a configuration | [`GedraConfigEndpoints.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/gedra/GedraConfigEndpoints.kt) |
| The reference page, made from all of these | [`ConfigReference.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/gedra/ConfigReference.kt) |
