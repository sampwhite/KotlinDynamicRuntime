package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.util.ConvProblem
import com.dynamicruntime.common.naming.OwnedNameKind
import com.dynamicruntime.common.naming.isOwnedName
import com.dynamicruntime.common.util.Parsed

/**
 * A reference to one workflow definition: the **bundle** it lives in and its name within it (issue #533).
 *
 * A workflow is not its own gedra kind -- it is a member of a `gc.cd` config bundle -- so it cannot be named
 * by a `GedraId`, and its reference is a pair: `gc.cd.acme.acmeForms~2#createForm`. The bundle's revision is
 * what the reference pins, and that is the point of recording one at all: "which definition was in force when
 * this form was created" is answered by the bundle revision, with no backfill, the day config revisions
 * arrive.
 *
 * **Not a `GedraId`, and must not be parsed as one**: the `#` would land in the revision segment and misparse.
 * This is its own small type so that mistake is unavailable.
 */
class WfRef(val bundleId: GedraId, val workflowId: String) {
    init {
        // A rooted id (`kdr:review`, issue #953) as well as a bare one: the reference splits at its first `#`, and
        // neither form holds one.
        if (!isOwnedName(OwnedNameKind.workflow, workflowId)) {
            throw KdrException.mkConv("'$workflowId' cannot be a workflow id in a reference.")
        }
    }

    /** The text form: the bundle's full id, [WFD.refSep], the workflow id. */
    val text: String = "${bundleId.fullId}${WFD.refSep}$workflowId"

    override fun toString(): String = text
    override fun equals(other: Any?): Boolean = other is WfRef && other.text == text
    override fun hashCode(): Int = text.hashCode()

    companion object {
        /** Reads a reference back from [text]; throws when it is not one. */
        fun parse(text: String): WfRef = parseResult(text).orThrow()

        /** [parse], or null when [text] is null or not a reference -- for reading a stored value leniently. */
        fun parseOrNull(text: String?): WfRef? = text?.let { parseResult(it).valueOrNull() }

        /** [parse]'s outcome as a value (issue #909): the reference, or what is wrong with [text]. Never throws. */
        fun parseResult(text: String): Parsed<WfRef> {
            val at = text.indexOf(WFD.refSep)
            if (at <= 0 || at == text.length - 1) {
                return Parsed.failed(
                    ConvProblem.badFormat,
                    "'$text' is not a workflow reference: it needs a bundle id and a workflow id joined by " +
                        "'${WFD.refSep}'.",
                )
            }
            val workflowId = text.substring(at + 1)
            // Checked here as well as in `init`, so a bad id is a result rather than the constructor's throw.
            if (!isOwnedName(OwnedNameKind.workflow, workflowId)) {
                return Parsed.failed(ConvProblem.badFormat, "'$workflowId' cannot be a workflow id in a reference.")
            }
            return when (val bundle = GedraId.parseResult(text.substring(0, at))) {
                is Parsed.Ok -> Parsed.Ok(WfRef(bundle.value, workflowId))
                is Parsed.Failed -> bundle
            }
        }
    }
}
