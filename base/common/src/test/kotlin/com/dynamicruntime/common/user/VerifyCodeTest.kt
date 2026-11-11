package com.dynamicruntime.common.user

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * A verification code as typed against the one the node computes (issue #1785): case, spaces, and hyphens do not
 * matter, and a code that differs in any character is still wrong.
 */
class VerifyCodeTest : StringSpec({
    "a code is compared in capitals, without spaces or hyphens" {
        normalizeVerifyCode("af3 k9x") shouldBe "AF3K9X"
        normalizeVerifyCode("AF3-K9X") shouldBe "AF3K9X"
        normalizeVerifyCode("a f\t3-k9 x") shouldBe "AF3K9X"
        sameVerifyCode("AF3K9X", "af3-k9x") shouldBe true
    }

    "a code differing in any character, or in length, is wrong" {
        sameVerifyCode("AF3K9X", "AF3K9Y") shouldBe false
        sameVerifyCode("AF3K9X", "BF3K9X") shouldBe false
        sameVerifyCode("AF3K9X", "AF3K9") shouldBe false
        sameVerifyCode("AF3K9X", "AF3K9XA") shouldBe false
        sameVerifyCode("AF3K9X", "") shouldBe false
        sameVerifyCode("AF3K9X", "WRONGCODE") shouldBe false
    }
})
