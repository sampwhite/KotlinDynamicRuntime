package com.dynamicruntime.webapp

/**
 * A small Kotlin API marked `@JsExport`. Because the build enables
 * `generateTypeScriptDefinitions()`, every exported declaration here is emitted
 * into the bundle's `.d.ts` file — so `greet`/`Greeting` are callable from
 * TypeScript (or plain JS) with full type information, not just from Kotlin.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
data class Greeting(
    val message: String,
    val length: Int,
)

/** Builds a friendly [Greeting]; blank input falls back to a generic greeting. */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun greet(name: String): Greeting {
    val target = name.trim().ifBlank { "world" }
    val message = "Hello, $target! 👋"
    return Greeting(message = message, length = message.length)
}
