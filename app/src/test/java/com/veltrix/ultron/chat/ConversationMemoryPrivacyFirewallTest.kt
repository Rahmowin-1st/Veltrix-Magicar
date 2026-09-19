package com.veltrix.ultron.chat

import com.veltrix.ultron.planner.InMemoryOwnerPlannerPermissionStore
import com.veltrix.ultron.planner.OwnerPlannerPermission
import com.veltrix.ultron.planner.OwnerPlannerPermissionDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMemoryPrivacyFirewallTest {
    private val ownerId = "owner"
    private val deviceId = "android-local"

    @Test
    fun rootObserveDenyRemovesAllMemoryBeforePlanner() {
        val store = InMemoryOwnerPlannerPermissionStore().apply {
            deny(ConversationMemoryPrivacyScopes.ROOT, ConversationMemoryPrivacyFirewall.OBSERVE_ACTION_SCOPE)
        }
        val firewall = ConversationMemoryPrivacyFirewall(store, ownerId, deviceId)
        val visible = firewall.filterForPlanner(
            listOf(ChatMessage(id = "m1", role = MessageRole.USER, text = "private")),
            FollowUpContext(activeAppPackage = "com.bank.app", lastIntent = "private intent")
        )

        assertTrue(visible.messages.isEmpty())
        assertEquals(FollowUpContext(), visible.followUp)
    }

    @Test
    fun oneMessageObserveDenyKeepsSiblingMemoryVisible() {
        val hidden = ChatMessage(id = "hidden-id", role = MessageRole.USER, text = "hidden")
        val visible = ChatMessage(id = "visible-id", role = MessageRole.USER, text = "visible")
        val hiddenScope = ConversationMemoryPrivacyScopes.messageScope(hidden.id)!!
        val store = InMemoryOwnerPlannerPermissionStore().apply {
            deny(hiddenScope, ConversationMemoryPrivacyFirewall.OBSERVE_ACTION_SCOPE)
        }
        val filtered = ConversationMemoryPrivacyFirewall(store, ownerId, deviceId)
            .filterForPlanner(listOf(hidden, visible), FollowUpContext(lastIntent = "keep"))

        assertEquals(listOf(visible), filtered.messages)
        assertEquals("keep", filtered.followUp.lastIntent)
    }

    @Test
    fun observeOnlyDenyDoesNotPreventLocalStorage() {
        val message = ChatMessage(id = "m-observe", role = MessageRole.USER, text = "local only")
        val store = InMemoryOwnerPlannerPermissionStore().apply {
            deny(
                ConversationMemoryPrivacyScopes.messageScope(message.id)!!,
                ConversationMemoryPrivacyFirewall.OBSERVE_ACTION_SCOPE
            )
        }
        val firewall = ConversationMemoryPrivacyFirewall(store, ownerId, deviceId)

        assertTrue(firewall.canStoreMessage(message))
        assertTrue(firewall.filterForPlanner(listOf(message), FollowUpContext()).messages.isEmpty())
    }

    @Test
    fun rootStoreDenyBlocksNewMessagesAndFollowUpPersistence() {
        val store = InMemoryOwnerPlannerPermissionStore().apply {
            deny(ConversationMemoryPrivacyScopes.ROOT, ConversationMemoryPrivacyFirewall.STORE_ACTION_SCOPE)
        }
        val firewall = ConversationMemoryPrivacyFirewall(store, ownerId, deviceId)

        assertFalse(
            firewall.canStoreMessage(
                ChatMessage(id = "m2", role = MessageRole.USER, text = "do not remember")
            )
        )
        assertFalse(firewall.canStoreFollowUp())
    }

    @Test
    fun wildcardHardDenyBlocksStorageAndAiObservation() {
        val store = InMemoryOwnerPlannerPermissionStore().apply {
            deny(ConversationMemoryPrivacyScopes.ROOT, "*")
        }
        val firewall = ConversationMemoryPrivacyFirewall(store, ownerId, deviceId)
        val message = ChatMessage(id = "m3", role = MessageRole.USER, text = "private")

        assertFalse(firewall.canStoreMessage(message))
        assertTrue(firewall.filterForPlanner(listOf(message), FollowUpContext(lastIntent = "x")).messages.isEmpty())
        assertEquals(FollowUpContext(), firewall.filterForPlanner(listOf(message), FollowUpContext(lastIntent = "x")).followUp)
    }

    @Test
    fun followUpObserveDenyDoesNotHideAllowedMessages() {
        val store = InMemoryOwnerPlannerPermissionStore().apply {
            deny(
                ConversationMemoryPrivacyScopes.FOLLOW_UP,
                ConversationMemoryPrivacyFirewall.OBSERVE_ACTION_SCOPE
            )
        }
        val message = ChatMessage(id = "m4", role = MessageRole.VELTRIX, text = "allowed")
        val filtered = ConversationMemoryPrivacyFirewall(store, ownerId, deviceId)
            .filterForPlanner(
                listOf(message),
                FollowUpContext(activePersonRef = "private-person", lastIntent = "private")
            )

        assertEquals(listOf(message), filtered.messages)
        assertEquals(FollowUpContext(), filtered.followUp)
    }

    @Test
    fun malformedMessageIdentityFailsClosed() {
        val firewall = ConversationMemoryPrivacyFirewall(
            InMemoryOwnerPlannerPermissionStore(),
            ownerId,
            deviceId
        )
        val malformed = ChatMessage(id = "", role = MessageRole.USER, text = "x")

        assertFalse(firewall.canStoreMessage(malformed))
        assertTrue(firewall.filterForPlanner(listOf(malformed), FollowUpContext()).messages.isEmpty())
    }

    private fun InMemoryOwnerPlannerPermissionStore.deny(targetScope: String, actionScope: String) {
        put(
            OwnerPlannerPermission(
                principalId = ownerId,
                deviceId = deviceId,
                targetScope = targetScope,
                actionScope = actionScope,
                riskClass = "*",
                decision = OwnerPlannerPermissionDecision.DENY
            )
        )
    }
}
