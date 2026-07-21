package com.dynamicruntime.kdn

import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.user.authTopic
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldNotBe

/**
 * Every topic's tables must be created during **startup**, as the system user -- never lazily by whichever
 * request happens to touch a topic first.
 *
 * Creating a table is DDL, and `SqlDatabase.executeSchemaChangeSql` refuses to run DDL on behalf of an end
 * user. So if creation were deferred to first use, a signed-in user's request that landed on a topic whose
 * tables were not all present would fail with "Schema-change SQL may only be executed by the system user".
 *
 * That is not hypothetical, and it is nastier than it looks: an *anonymous* caller's userId is the system user
 * id (both 0), so an anonymous first touch satisfies the guard by coincidence and everything appears fine. The
 * failure surfaces only when a **signed-in** user makes the first request after a deployment that added a table
 * to an existing topic, against a database that already holds the rest of it -- i.e. on upgrade, and never in a
 * fresh in-memory test. Adding `LinkedUsers` to the `auth` topic (issue #157) hit exactly that.
 *
 * Asserting the topics exist immediately after boot is what keeps the creation in startup: it fails if anyone
 * makes topic init lazy again.
 */
class TopicStartupInitTest : StringSpec({

    "the auth topic and its tables are created during startup, before any request" {
        val cxt = Startup.mkTestBootCxt("topicInit", "topicInitTest")
        val service = SqlTopicService.get(cxt)
        service shouldNotBe null
        // Populated by checkReady, so it is already there without a single request having been made.
        service!!.topics.keys shouldContain authTopic
    }

    "every topic the schema store declares is created, not just the one a test happens to use" {
        val cxt = Startup.mkTestBootCxt("topicInitAll", "topicInitAllTest")
        val service = SqlTopicService.get(cxt)!!
        val declared = cxt.getSchema().tables.values.map { it.topic }.toSet()
        declared.forEach { service.topics.keys shouldContain it }
    }
})
