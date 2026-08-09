package com.dynamicruntime.webapp

/**
 * antd's date type. Its `DatePicker` takes and returns a `Dayjs` object rather than a string, so a form field
 * whose value is the schema's string has to convert at that boundary — [dayjs] on the way in, and the object's
 * own `toISOString()` on the way out.
 *
 * Declared here rather than in `AntdComponents.kt` because that file is `@file:JsModule("antd")`, which maps
 * every declaration in it to a named export of antd; dayjs is its own module, and the function *is* that
 * module's export.
 *
 * Only the two operations the date widget needs are declared. Dayjs has a large surface, and none of the rest
 * is wanted here: the runtime's date type is the schema's string, and this exists purely to hand antd the
 * shape it insists on.
 */
@JsModule("dayjs")
@JsNonModule
external fun dayjs(date: String): Dayjs

/** The sliver of dayjs's object the date widget uses. */
external interface Dayjs {
    /** ISO-8601 in UTC with milliseconds and a literal `Z` — the same shape the kernel's `formatDate` writes. */
    fun toISOString(): String

    /** Whether the parse produced a real date; dayjs answers with an object either way. */
    fun isValid(): Boolean
}
