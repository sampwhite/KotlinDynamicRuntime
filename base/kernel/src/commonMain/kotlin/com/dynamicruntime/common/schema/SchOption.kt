package com.dynamicruntime.common.schema

/**
 * A single choice in a property's `options` list: a [value] (the stored data) and
 * a display [label] (which defaults to the value when redundant). Part of the
 * custom `options` construct that lets a frontend render labeled choice lists —
 * pure data, so it cross-compiles to other platforms.
 */
data class SchOption(val value: String, val label: String)
