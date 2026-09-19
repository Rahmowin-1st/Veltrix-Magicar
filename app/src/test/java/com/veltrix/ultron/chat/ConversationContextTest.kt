package com.veltrix.ultron.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextTest {
    @Test
    fun followUpArtifactContextCanBeUpdatedAndReset() {
        val context = ConversationContext()
        context.updateFollowUp {
            it.copy(
                activeMissionId = "mission-1",
                activeArtifactIds = listOf("artifact://flow/video-1"),
                lastIntent = "send_to_telegram"
            )
        }

        assertEquals("mission-1", context.followUpContext().activeMissionId)
        assertEquals(listOf("artifact://flow/video-1"), context.followUpContext().activeArtifactIds)

        context.resetFollowUp()
        assertTrue(context.followUpContext().activeArtifactIds.isEmpty())
        assertEquals(null, context.followUpContext().activeMissionId)
    }
}
