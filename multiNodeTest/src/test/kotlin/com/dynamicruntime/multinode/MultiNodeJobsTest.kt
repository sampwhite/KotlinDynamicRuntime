package com.dynamicruntime.multinode

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The harness's own judgments (issue #872): what counts as two executions of one task running at once, and when the
 * component switches on. The scenarios themselves run in the harness, against real processes.
 */
class MultiNodeJobsTest : StringSpec({
    fun exec(task: String, start: Long, end: Long?, launch: String = "run1", node: String = "n1") =
        WorkExecution(launch, "hub", task, node, start, end)

    "two completed executions of one task that overlap in time are an overlap" {
        val found = findOverlaps(listOf(exec("t1", 0, 100, node = "a"), exec("t1", 50, 150, node = "b")))
        found.size shouldBe 1
        found.single().let { listOf(it.a.node, it.b.node) } shouldBe listOf("a", "b")
    }

    "executions one after another, of different tasks, or of different launches are not" {
        findOverlaps(
            listOf(
                exec("t1", 0, 100), exec("t1", 100, 200), // back to back
                exec("t2", 0, 100), exec("t3", 50, 150), // different tasks
                exec("t4", 0, 100, launch = "run1"), exec("t4", 50, 150, launch = "run2"), // different launches
            ),
        ).size shouldBe 0
    }

    "an execution cut off before its end is not counted against the one that redoes its task" {
        // A killed node's execution never completes; the task is rightly redone by whoever adopts the work.
        findOverlaps(listOf(exec("t1", 0, null, node = "killed"), exec("t1", 50, 150, node = "adopter"))).size shouldBe 0
    }

    "the work report counts ended executions and the most any one task ran" {
        val report = workReport(listOf(exec("t1", 0, 100), exec("t1", 200, 300), exec("t2", 0, null)))
        report[MNJ.executions] shouldBe 3
        report[MNJ.ended] shouldBe 2
        report[MNJ.incomplete] shouldBe 1
        report[MNJ.maxPerTask] shouldBe 2
    }

    "the component is off unless configured or switched on, and never on a production node" {
        fun cxt(env: String, vararg entries: Pair<String, Any?>): KdrCxt {
            val config = KdrInstanceConfig("mnTest-$env-${entries.size}", env, ENV.liveSource)
            entries.forEach { (k, v) -> config.put(k, v) }
            return KdrCxt.mkSimpleCxt("mnTest", config)
        }
        val component = MultiNodeTestComponent()
        component.isLoaded(cxt(ENV.local)) shouldBe false
        component.isLoaded(cxt(ENV.local, MNT.multiNodeTest to mapOf(MNT.enabled to true))) shouldBe true
        component.isLoaded(cxt(ENV.local, MultiNodeTestComponent.enabledVar.name to "true")) shouldBe true
        // The configuration decides over the switch, either way.
        component.isLoaded(
            cxt(ENV.local, MNT.multiNodeTest to mapOf(MNT.enabled to false), MultiNodeTestComponent.enabledVar.name to "true"),
        ) shouldBe false
        component.isLoaded(cxt(ENV.prod, MNT.multiNodeTest to mapOf(MNT.enabled to true))) shouldBe false
    }
})
