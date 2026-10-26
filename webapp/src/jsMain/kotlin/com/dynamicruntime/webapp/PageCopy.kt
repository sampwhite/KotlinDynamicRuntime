package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.util.evalTemplate
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.useEffect
import react.useState

/** Reads of a page's copy, outliving any one page so a page left mid-read does not cancel another's. */
private val pageCopyScope = MainScope()

/**
 * The copy of a page with no UI-config of its own (issue #1337) -- the Clients page, Reports, Design View -- from the
 * file the shell's config names for it ([HomeConfig.pageFragments]), re-read on every refresh generation as the
 * shell's own copy is. Empty until it arrives, and empty when it cannot be read: such a page's copy only explains, so a
 * failure leaves it unexplained (and says so on the console) rather than failing the page.
 */
fun usePageCopy(fileId: String): Copy {
    var copy by useState(Copy.empty)
    val generation = useRefreshGeneration()
    useEffect(generation, fileId) {
        pageCopyScope.launch {
            val read = apiResult {
                val ref = HomeApi.fetchConfig().pageFragments[fileId] ?: return@apiResult Copy.empty
                fetchCopyWithRetry(ref) { apiResult { HomeApi.fetchConfig().pageFragments[fileId] }.valueOrNull() }
            }
            read.failureOrNull()?.let { console.error("$errorLogPrefix could not load the '$fileId' copy: ${it.message}") }
            read.valueOrNull()?.let { copy = it }
        }
    }
    return copy
}

/**
 * The "What is this?" explanation in [ns] (issue #1337): its `whatIsThis` key ([HFRAG.explanation]), with any pull
 * (`${@t("ns.key")}`) resolved against the same file. Empty when the copy has none, and then [WhatIsThis] draws no
 * link.
 */
fun Copy.explanation(ns: String): String =
    opt(ns, HFRAG.explanation).orEmpty().evalTemplate(emptyMap(), resolver = fragmentResolver())
