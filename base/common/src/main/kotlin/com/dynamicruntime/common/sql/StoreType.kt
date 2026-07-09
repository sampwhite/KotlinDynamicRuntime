package com.dynamicruntime.common.sql

/**
 * The primitive storage encoding of a column — how its value is written to and read from the database.
 * Derived from the column's JSON Schema `type`/`format` (see [SqlTypeUtil.storeTypeForSchema]). For a list
 * column this is the encoding of each element; the list itself is stored as an encoded string.
 *
 * A closed set with operational meaning, so an enum fits (per the code guide). Entry names are
 * lowerCase-first to match our constant style, which trips the enum-entry naming inspection.
 */
@Suppress("EnumEntryName")
enum class StoreType { string, integer, float, boolean, date, binary, map }
