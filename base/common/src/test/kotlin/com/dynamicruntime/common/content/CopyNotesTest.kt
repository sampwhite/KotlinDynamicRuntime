package com.dynamicruntime.common.content

import com.dynamicruntime.common.util.parseFragmentNotes
import com.dynamicruntime.common.util.parseMarkdownFragments
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import java.io.File

/**
 * Every key of every fragment file a module ships has a copy note (issue #1799): where a person sees it, for a key a
 * client is likely to reword, or `none: <why>` for the rest, its own or its namespace's. A key with neither fails, so
 * a new key -- a new mail's subject, say -- makes whoever adds it decide, rather than slipping past the keys the
 * assistant can find by where they are.
 *
 * Reads the files from the tree, handed in by the `copyNotesTest` task (`./gradlew :base:common:copyNotesTest`), so a
 * module's file is judged whatever its own tests boot. An overlay (`_overlay.md`, `_omnibus.md`) changes words, and
 * has no notes of its own. Only the platform's files (base/common's and base/kdn's) may locate a key, since those are
 * what `GrantsDemoDocsTest` holds the grants demo to.
 *
 * Theme: #CUSTX, #DOCDR.
 */
class CopyNotesTest : StringSpec({
    val repoRoot = File("../..").canonicalFile

    fun fragmentFiles(): List<File> {
        val paths = System.getProperty("kdr.fragmentFiles")
            ?: error("No fragment files were passed in. Run this with `./gradlew :base:common:copyNotesTest`.")
        return paths.split(File.pathSeparator).filter { it.isNotEmpty() }.map { File(it) }
            .filter { it.exists() && !it.name.endsWith("$fragmentOverlaySuffix.md") && !it.name.endsWith("$fragmentOmnibusSuffix.md") }
    }

    "every key of every shipped fragment file has a copy note" {
        val files = fragmentFiles()
        files.shouldNotBeEmpty()
        val unclassified = files.flatMap { file ->
            val text = file.readText()
            val notes = text.parseFragmentNotes()
            text.parseMarkdownFragments().flatMap { (ns, keys) ->
                keys.keys.filter { notes.noteFor(ns, it) == null }.map { "${file.relativeTo(repoRoot)}: $ns.$it" }
            }
        }
        withClue(
            "These keys have no copy note. Before each, or before its namespace, write `/-where <page>: <where on it>. -/` " +
                "for a key a client is likely to reword (a page of ${CPAGE.pages.joinToString(", ")}), or " +
                "`/-where none: <why not>. -/`. The grants demo rewords every located key, so `GrantsDemoDocsTest` (base:kdn) " +
                "then asks for its words and a row on its page.",
        ) {
            unclassified.shouldBeEmpty()
        }
    }

    "only the platform's own files say where a key is shown" {
        // The grants demo rewords every located key, and its test (base:kdn) sees the platform's components alone:
        // a key located in another module's file would escape it, so a located note there fails here instead.
        val platform = listOf("base/common/", "base/kdn/")
        val elsewhere = fragmentFiles().filter { file -> platform.none { file.relativeTo(repoRoot).path.startsWith(it) } }
            .flatMap { file ->
                val notes = file.readText().parseFragmentNotes()
                notes.byKey.flatMap { (ns, keys) ->
                    keys.filterValues { !FragmentNotes.isNotReworded(it) }.keys.map { "${file.relativeTo(repoRoot)}: $ns.$it" }
                }
            }
        withClue("These keys say where they are shown, outside the platform's files the grants demo is held to. Locate " +
            "keys only in base/common's or base/kdn's fragment files, or say `none` here.") {
            elsewhere.shouldBeEmpty()
        }
    }
})
