package com.veltrix.ultron.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidBridgeBuildProvenanceTest {
    @Test
    fun exactShaIsEmbeddedInDurablePlatformLabel() {
        val sha = "0123456789abcdef0123456789abcdef01234567"

        assertEquals(
            "ANDROID-37;build=$sha",
            bridgePlatformLabel(37, sha)
        )
    }

    @Test
    fun uppercaseShaIsNormalizedBeforeHeartbeat() {
        val uppercase = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"

        assertEquals(
            "ANDROID-37;build=abcdef0123456789abcdef0123456789abcdef01",
            bridgePlatformLabel(37, uppercase)
        )
    }

    @Test
    fun invalidOrLocalBuildIdentityIsOmittedRatherThanMisrepresented() {
        assertNull(normalizeBridgeBuildSha("unknown"))
        assertNull(normalizeBridgeBuildSha("0123456789abcdef"))
        assertEquals("ANDROID-37", bridgePlatformLabel(37, "unknown"))
        assertEquals("ANDROID-37", bridgePlatformLabel(37, ""))
    }

    @Test
    fun buildIdentityRejectsNonHexEvenAtExactLength() {
        val invalid = "g".repeat(40)

        assertNull(normalizeBridgeBuildSha(invalid))
        assertEquals("ANDROID-37", bridgePlatformLabel(37, invalid))
    }
}
