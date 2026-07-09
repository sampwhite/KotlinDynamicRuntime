package com.dynamicruntime.common.sql

import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC

/**
 * The kinds of a database the runtime can configure: an in-memory H2 (tests / the in-memory application
 * mode), a file-backed H2 (a simple single-node deployment), and PostgreSQL (a real deployment). A closed,
 * operational set that is serialized into configuration, so an enum fits (per the code guide); entry names
 * are lowerCase-first to match our constant style, and are also the accepted `KDR_DB_TYPE` values.
 */
@Suppress("EnumEntryName")
enum class DbType {
    h2Memory,
    h2File,
    postgres,
    ;

    companion object {
        /** Parses a configured/env value to a [DbType], failing with a config error on an unknown value. */
        fun parse(value: Any?): DbType {
            val s = value?.toString()?.trim()
            return entries.firstOrNull { it.name == s }
                ?: throw KdrException(
                    "Unknown database type '$value'; expected one of ${entries.map { it.name }}.",
                    null, EXC.badInput, SRC.config, ACT.conversion,
                )
        }
    }
}
