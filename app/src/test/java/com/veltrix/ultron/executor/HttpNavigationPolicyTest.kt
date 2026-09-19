package com.veltrix.ultron.executor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpNavigationPolicyTest {
    @Test
    fun acceptsHttpsAndLoopbackHttp() {
        assertEquals(
            "https://example.com/path?q=1#part",
            HttpNavigationPolicy.normalize(" https://example.com/path?q=1#part ")
        )
        assertEquals(
            "http://127.0.0.1:8080/a",
            HttpNavigationPolicy.normalize("http://127.0.0.1:8080/a")
        )
    }

    @Test
    fun rejectsNonWebAndNonLoopbackCleartextSchemes() {
        listOf(
            "http://example.com/a",
            "javascript:alert(1)",
            "file:///sdcard/secret.txt",
            "intent://example.com/#Intent;scheme=https;end",
            "data:text/html,hello",
            "content://example/item/1",
            "veltrix://open/task"
        ).forEach { value ->
            assertNull("Expected rejection for $value", HttpNavigationPolicy.normalize(value))
        }
    }

    @Test
    fun rejectsCredentialBearingHostlessAndMalformedUrls() {
        listOf(
            "https://user:pass@example.com/private",
            "https:///missing-host",
            "https://",
            "not a url",
            "//example.com/path"
        ).forEach { value ->
            assertNull("Expected rejection for $value", HttpNavigationPolicy.normalize(value))
        }
    }

    @Test
    fun rejectsOversizedUrls() {
        val value = "https://example.com/" + "a".repeat(4_100)
        assertNull(HttpNavigationPolicy.normalize(value))
    }
}
