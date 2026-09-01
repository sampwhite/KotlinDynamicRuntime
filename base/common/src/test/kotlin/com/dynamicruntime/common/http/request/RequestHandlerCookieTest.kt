package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrInstanceConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Instant

/**
 * The attributes on a `Set-Cookie` this handler issues (issue #431): `SameSite` is stated rather than left to
 * a browser default, and `Secure` follows the node's configuration rather than a caller-controlled
 * `X-Forwarded-For`. The handler is hand-built with a bound config, the way `RequestHandlerSentGuardTest`
 * builds one, so no instance is booted.
 */
class RequestHandlerCookieTest : StringSpec({

    fun handler(env: String) =
        RequestHandler(KdrInstanceConfig("cookie-$env", env, ENV.liveSource), "GET", "/kda/thing", emptyMap(), mutableMapOf())

    fun setCookieOf(h: RequestHandler): String = h.rptResponseHeaders["set-cookie"]!!.single()

    "a session cookie is Path=/, HttpOnly, and SameSite=Lax by default" {
        val h = handler(ENV.local)
        h.addResponseCookie("kdrAuth", "token", null as Instant?)
        val cookie = setCookieOf(h)
        cookie shouldContain "Path=/"
        cookie shouldContain "HttpOnly"
        cookie shouldContain "SameSite=Lax"
    }

    // The point of the change: local dev is plain HTTP, so the cookie must not be Secure or the browser drops
    // it -- and that must hold with no X-Forwarded-For anywhere in sight.
    "Secure is absent in local dev, so a plain-HTTP localhost cookie still comes back" {
        val h = handler(ENV.local)
        h.addResponseCookie("kdrAuth", "token", null as Instant?)
        setCookieOf(h) shouldNotContain "Secure"
    }

    // And the security fix: Secure is present off local, decided by the environment rather than a header the
    // caller controls (this handler sets no forwardedFor at all).
    "Secure is present outside local dev, and is not a function of X-Forwarded-For" {
        val h = handler(ENV.prod)
        h.addResponseCookie("kdrAuth", "token", null as Instant?)
        setCookieOf(h) shouldContain "Secure"
    }

    "a perimeter cookie can carry SameSite=Strict" {
        val h = handler(ENV.prod)
        h.addResponseCookie("kdrEnvAuth", "v", null as Instant?, CKI.strict)
        setCookieOf(h) shouldContain "SameSite=Strict"
    }
})
