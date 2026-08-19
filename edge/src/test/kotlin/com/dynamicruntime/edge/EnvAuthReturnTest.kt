package com.dynamicruntime.edge

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Coverage for where a caller lands after signing in (issue #386).
 *
 * The feature is the reason a bought solution did not fit: a challenge that drops you at the hostname rather
 * than the page you asked for is a poor experience. The care is because a redirect target read from a query
 * string is an **open redirect** unless proven otherwise -- and on a perimeter, a link to the genuine sign-in
 * host that lands somewhere else afterwards is exactly the shape of a credible phishing link.
 */
class EnvAuthReturnTest : StringSpec({

    "an ordinary path is honored" {
        EnvAuthReturn.sanitize("/ea/operator/system/info") shouldBe "/ea/operator/system/info"
        EnvAuthReturn.sanitize("/ea/schema/endpoints?limit=10") shouldBe "/ea/schema/endpoints?limit=10"
    }

    "nothing asked for lands at the default" {
        EnvAuthReturn.sanitize(null) shouldBe EnvAuthReturn.default
        EnvAuthReturn.sanitize("") shouldBe EnvAuthReturn.default
        EnvAuthReturn.sanitize("   ") shouldBe EnvAuthReturn.default
    }

    /**
     * The case a naive "starts with a slash" check passes, and the one that actually gets used: a browser
     * reads `//host` as protocol-relative and leaves the site, while the value looks local to a reviewer.
     */
    "a protocol-relative URL is refused, however it is spelled" {
        EnvAuthReturn.sanitize("//evil.example.com") shouldBe EnvAuthReturn.default
        EnvAuthReturn.sanitize("//evil.example.com/path") shouldBe EnvAuthReturn.default
        EnvAuthReturn.sanitize("/\\evil.example.com") shouldBe EnvAuthReturn.default
    }

    "anything off-site, or carrying a scheme, is refused" {
        EnvAuthReturn.sanitize("https://evil.example.com") shouldBe EnvAuthReturn.default
        EnvAuthReturn.sanitize("javascript:alert(1)") shouldBe EnvAuthReturn.default
        EnvAuthReturn.sanitize("data:text/html,x") shouldBe EnvAuthReturn.default
        EnvAuthReturn.sanitize("evil.example.com/path") shouldBe EnvAuthReturn.default
    }

    // Browsers have historically normalized a backslash to a slash, so it smuggles the above past a check
    // that only looks for slashes.
    "a backslash anywhere is refused" {
        EnvAuthReturn.sanitize("/ea\\..\\evil") shouldBe EnvAuthReturn.default
    }

    /**
     * These split a header or truncate a value. Built from char codes rather than written literally, since a
     * control character in source is invisible to a reviewer -- which is the same reason they are refused.
     * `isISOControl` covers DEL as well, which a `< ' '` test would miss.
     */
    "control characters are refused" {
        EnvAuthReturn.sanitize("/ea/info\nLocation: https://evil.example.com") shouldBe EnvAuthReturn.default
        EnvAuthReturn.sanitize("/ea/info" + 127.toChar()) shouldBe EnvAuthReturn.default
        EnvAuthReturn.sanitize("/ea/info" + 0.toChar()) shouldBe EnvAuthReturn.default
    }
})
