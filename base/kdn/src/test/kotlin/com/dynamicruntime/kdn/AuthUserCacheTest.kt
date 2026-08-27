package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.cache.SqlTableCacheService
import com.dynamicruntime.common.sql.cache.TCI
import com.dynamicruntime.common.sql.cache.TCS
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.truncateToMs
import com.dynamicruntime.common.user.AU
import com.dynamicruntime.common.user.AuthUserRow
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UT
import com.dynamicruntime.common.user.UserService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Duration.Companion.days

/**
 * Coverage for the `AuthUsers` table cache -- `UserService`'s single-row lookups served from memory, with SQL
 * behind them.
 *
 * The whole point of the cache is that it must be **indistinguishable** from the queries it replaces, so each
 * case here is about a way it could differ rather than about it being fast: a stale read after a write, a
 * shared mutable row leaking one caller's edit into another's, a disabled user disappearing instead of
 * falling through to SQL, and a change failing to reach the row other nodes read.
 *
 * Email addresses are prefixed `ucache-`: every test in a run shares one in-memory database, so a plain
 * address would collide with another spec's fixture and fail *that* test instead of this one.
 */
class AuthUserCacheTest : StringSpec({

    fun users(cxt: KdrCxt): UserService =
        UserService.get(cxt).also { it.checkInit(cxt) }

    "a written user is cached, and each read gets its own row rather than the cached one" {
        val cxt = Startup.mkTestBootCxt("userCache", "userCacheTest")
        val service = users(cxt)
        val cache = service.userCache.shouldNotBeNull()

        val userId = service.insertUser(
            cxt, AuthUserRow.mkInitialUser("ucache-basic@example.com", CL.public, listOf(ROLE.user)),
        )

        // The read that proves the cache picked the write up: it refreshes, so the snapshot is current after
        // it, and the row is there under both the primary key and the primaryId index.
        val first = service.queryByUserId(cxt, userId).shouldNotBeNull()
        first.primaryId shouldBe "ucache-basic@example.com"
        cache.snapshot.get(cache.idOf(userId)).shouldNotBeNull()
        cache.snapshot.byIndex(AU.primaryId, "ucache-basic@example.com").shouldNotBeNull()
        service.queryByPrimaryId(cxt, "ucache-basic@example.com").shouldNotBeNull().userId shouldBe userId

        // Callers mutate an AuthUserRow and hand it to updateUser, so the cache must never give out the
        // instance it holds: an edit that is abandoned (or rolled back) would otherwise be everyone's edit.
        val second = service.queryByUserId(cxt, userId).shouldNotBeNull()
        second shouldNotBe first // a distinct object, not the same one twice
        second.name = "Scribbled Over"
        service.queryByUserId(cxt, userId).shouldNotBeNull().name.shouldBeNull()
    }

    "an update is visible to the very next read, with no request boundary in between" {
        val cxt = Startup.mkTestBootCxt("userCacheRyw", "userCacheRywTest")
        val service = users(cxt)
        val userId = service.insertUser(
            cxt, AuthUserRow.mkInitialUser("ucache-ryw@example.com", CL.public, listOf(ROLE.user)),
        )
        service.queryByUserId(cxt, userId).shouldNotBeNull() // warm the cache with the pre-update row

        val row = service.queryAdministrableUser(cxt, userId, ReadScope.unrestricted).shouldNotBeNull()
        row.name = "Renamed Immediately"
        service.updateUser(cxt, row)

        // No request ended, so nothing was published to the shared state row -- this read is current only
        // because the write marked the cache directly (SqlTableCache.forceReload).
        service.queryByUserId(cxt, userId).shouldNotBeNull().name shouldBe "Renamed Immediately"
    }

    "a disabled user leaves the cache but still resolves, through the SQL fallback" {
        val cxt = Startup.mkTestBootCxt("userCacheOff", "userCacheOffTest")
        val service = users(cxt)
        val cache = service.userCache.shouldNotBeNull()
        val userId = service.insertUser(
            cxt, AuthUserRow.mkInitialUser("ucache-disabled@example.com", CL.public, listOf(ROLE.user)),
        )
        service.queryByUserId(cxt, userId).shouldNotBeNull()

        val row = service.queryAdministrableUser(cxt, userId, ReadScope.unrestricted).shouldNotBeNull()
        row.enabled = false
        service.updateUser(cxt, row)

        // The lookup still answers -- queryOne is documented to return disabled rows, and the cache changing
        // that would be a semantic change dressed up as an optimization.
        val found = service.queryByUserId(cxt, userId).shouldNotBeNull()
        found.enabled shouldBe false
        // ...and it answered from SQL, because a disabled row is not in the cache.
        cache.snapshot.get(cache.idOf(userId)).shouldBeNull()
        cache.snapshot.byIndex(AU.primaryId, "ucache-disabled@example.com").shouldBeNull()
    }

    /**
     * The half a single node cannot see: what makes *other* nodes reload. `endRequest` deliberately swallows
     * its own failures (it runs in the dispatcher's `finally`), so without an assertion on the row itself a
     * broken merge would show up only as a log line.
     */
    "a request that creates a user publishes the change to the shared cache-state row" {
        val cxt = Startup.mkTestBootCxt("userCacheState", "userCacheStateTest")
        val caches = SqlTableCacheService.get(cxt)

        // The state row is one row shared by the whole database, and every test in this run shares one
        // in-memory database -- so another spec's request has almost certainly written an `AuthUsers` entry
        // already, and asserting the entry merely *appears* would pass without this request doing anything.
        // Hence the assertion is on the date. This instance's clock is pushed well past anything another spec
        // travels to (the furthest is a thirty-day session expiry), so the entry can only be this later date
        // if this request is what wrote it: `mergeState` never moves a date backwards.
        cxt.instanceConfig.clock.advanceBy(400.days)
        // Truncated, because the two sides do not carry the same precision: `instanceNow` is
        // `Clock.System.now()` plus an offset, while `published` has been through a database timestamp. An
        // untruncated `startedAt` is later than the publish it is compared against whenever its sub-millisecond
        // remainder exceeds the time the request took -- which is most of the time, and intermittently.
        val startedAt = cxt.instanceNow().truncateToMs()

        // Goes through the real request pipeline, so the dispatcher's begin/end bracket runs.
        TestUser.create(cxt, "ucache-published@example.com").userId shouldNotBe 0L

        val published = caches.dbQueryState(cxt)[UT.authUsers].shouldNotBeNull()
        (published >= startedAt) shouldBe true
    }

    /**
     * `/operator/cache/state`. Response-schema validation is on in tests, so this also proves the declared
     * output type matches what the handler emits -- the reason the report is worth declaring a schema for at
     * all.
     */
    "the operator report shows this node's caches beside the dates every node shares" {
        val cxt = Startup.mkTestBootCxt("userCacheReport", "userCacheReportTest")
        val opal = TestUser.createOperator(cxt, "ucache-operator@example.com")

        // A request first, so there is something for the shared row to hold.
        TestUser.create(cxt, "ucache-reported@example.com")

        val report = opal.getData("/operator/cache/state")
        report[TCS.isDisabled] shouldBe false

        @Suppress("UNCHECKED_CAST")
        val caches = report.getValue(TCS.caches) as List<Map<String, Any?>>
        val authUsers = caches.single { it[TCI.tableName] == UT.authUsers }
        authUsers[TCI.isLoaded] shouldBe true
        authUsers[TCI.topic] shouldBe "auth"
        // The indexes AuthUserCache declares: the two unique lookups the cache exists for, plus the non-unique
        // `client` index the brute-force user search scopes a client-scoped listing through (issue #411).
        authUsers[TCI.indexes] shouldBe listOf(AU.username, AU.primaryId, PF.client)
        authUsers[TCI.queryFromDate].shouldNotBeNull() // a completed load is what sets it

        report.getValue(TCS.sharedState).toJsonMapOrEmpty()[UT.authUsers].shouldNotBeNull()

        // It is operator-gated, like everything else under the section: the report names the tables held in
        // memory and how far behind each one is, which is not an ordinary user's business.
        val plain = TestUser.create(cxt, "ucache-plain@example.com")
        plain.expectError(EXC.notAuthorized, "/operator/cache/state")
    }
})
