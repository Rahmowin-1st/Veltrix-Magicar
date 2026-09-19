package com.veltrix.ultron.planner

import com.veltrix.ultron.agents.Principal
import com.veltrix.ultron.devices.DeviceDescriptor
import com.veltrix.ultron.devices.DeviceObservation
import com.veltrix.ultron.devices.UniversalActionResult
import com.veltrix.ultron.devices.UniversalExecutorRegistry
import java.util.UUID

enum class PlannerSessionState {
    READY,
    WAITING_USER,
    REPLAN_REQUIRED,
    PAUSED,
    TAKEN_OVER,
    CANCELLED,
    DONE,
    FAILED
}

data class PlannerSession(
    val id: String = UUID.randomUUID().toString(),
    val principal: Principal,
    val device: DeviceDescriptor,
    val proposal: PlannerProposal,
    val nextNodeIndex: Int = 0,
    val attempts: Map<String, Int> = emptyMap(),
    val evidence: List<String> = emptyList(),
    val state: PlannerSessionState = PlannerSessionState.READY,
    val message: String = proposal.graph.narration
) {
    val activeNode: ActionGraphNode?
        get() = proposal.graph.nodes.getOrNull(nextNodeIndex)
}

data class PlannerExecutionResult(
    val session: PlannerSession,
    val node: ActionGraphNode? = session.activeNode,
    val policy: PlannerPolicyResult? = null,
    val actionResult: UniversalActionResult? = null,
    val before: DeviceObservation? = null,
    val after: DeviceObservation? = null
)

fun interface PlannerSettler {
    fun await(node: ActionGraphNode, actionResult: UniversalActionResult)
}

fun interface PlannerVerifiedActionObserver {
    fun onVerified(
        session: PlannerSession,
        node: ActionGraphNode,
        before: DeviceObservation,
        after: DeviceObservation
    )
}

enum class PlannerExecutionEventType {
    PLAN_ACCEPTED,
    PERMISSION_REQUESTED,
    PERMISSION_ALLOW_ONCE,
    PERMISSION_ALWAYS_ALLOW,
    PERMISSION_DENIED,
    ACTION_DISPATCH_REQUESTED,
    ACTION_DISPATCH_ACCEPTED,
    ACTION_REJECTED,
    VERIFICATION_PASSED,
    VERIFICATION_FAILED,
    MISSION_PAUSED,
    MISSION_RESUMED,
    MISSION_CANCELLED,
    USER_TAKE_OVER,
    SESSION_DONE,
    SESSION_FAILED
}

data class PlannerExecutionEvent(
    val sessionId: String,
    val principalId: String,
    val principalKind: String,
    val deviceId: String,
    val type: PlannerExecutionEventType,
    val capability: String? = null,
    val actionType: String? = null,
    val targetScope: String? = null,
    val resultCode: String
)

/** Returns false when the event cannot be durably accepted. */
fun interface PlannerExecutionEventObserver {
    fun onEvent(event: PlannerExecutionEvent): Boolean
}

class PlannerExecutionEngine(
    private val executors: UniversalExecutorRegistry,
    private val policy: PlannerPolicyGate,
    private val validator: ActionGraphValidator = ActionGraphValidator(),
    private val settler: PlannerSettler = PlannerSettler { _, _ -> },
    private val verifiedActionObserver: PlannerVerifiedActionObserver = PlannerVerifiedActionObserver { _, _, _, _ -> },
    private val executionEventObserver: PlannerExecutionEventObserver = PlannerExecutionAuditBus,
    private val executionControl: PlannerExecutionControlGate = AlwaysRunningPlannerExecutionControl
) {
    fun start(
        principal: Principal,
        context: PlannerContext,
        planner: AiPlanner,
        sessionId: String = UUID.randomUUID().toString()
    ): PlannerExecutionResult {
        return when (val planned = planner.plan(context)) {
            is PlannerResult.Rejected -> {
                val session = rejectedSession(sessionId, principal, context.device, context.objective, planned.failure.message)
                emit(session, null, PlannerExecutionEventType.SESSION_FAILED, "PLAN_REJECTED")
                PlannerExecutionResult(session = session)
            }
            is PlannerResult.Proposed -> {
                val validation = validator.validate(planned.proposal.graph, context.device)
                if (!validation.valid) {
                    val session = rejectedSession(
                        sessionId,
                        principal,
                        context.device,
                        context.objective,
                        validation.issues.joinToString("; ") { "${it.code}: ${it.message}" }
                    )
                    emit(session, null, PlannerExecutionEventType.SESSION_FAILED, "PLAN_INVALID")
                    PlannerExecutionResult(session = session)
                } else {
                    val session = PlannerSession(
                        id = sessionId,
                        principal = principal,
                        device = context.device,
                        proposal = planned.proposal
                    )
                    if (!emit(session, null, PlannerExecutionEventType.PLAN_ACCEPTED, "PLAN_VALID")) {
                        PlannerExecutionResult(
                            session = session.copy(
                                state = PlannerSessionState.FAILED,
                                message = "Audit trail unavailable before execution"
                            )
                        )
                    } else {
                        controlInterruption(session, null) ?: PlannerExecutionResult(session = session)
                    }
                }
            }
        }
    }

    fun executeUntilBlocked(
        initial: PlannerSession,
        approvedNodeId: String? = null,
        maxAutoSteps: Int = 12
    ): PlannerExecutionResult {
        var result = PlannerExecutionResult(initial)
        var autoSteps = 0
        while (result.session.state == PlannerSessionState.READY && autoSteps < maxAutoSteps) {
            result = executeNext(
                session = result.session,
                approved = approvedNodeId != null && result.session.activeNode?.id == approvedNodeId
            )
            autoSteps += 1
        }
        return if (autoSteps >= maxAutoSteps && result.session.state == PlannerSessionState.READY) {
            val session = result.session.copy(
                state = PlannerSessionState.REPLAN_REQUIRED,
                message = "Autonomy budget reached; re-plan required"
            )
            emit(session, session.activeNode, PlannerExecutionEventType.SESSION_FAILED, "AUTONOMY_BUDGET")
            PlannerExecutionResult(session = session)
        } else result
    }

    fun executeNext(
        session: PlannerSession,
        approved: Boolean = false,
        rememberApproval: Boolean = false
    ): PlannerExecutionResult {
        if (
            session.state == PlannerSessionState.DONE ||
            session.state == PlannerSessionState.FAILED ||
            session.state == PlannerSessionState.CANCELLED
        ) {
            return PlannerExecutionResult(session)
        }
        controlInterruption(session, session.activeNode)?.let { return it }
        if (session.state == PlannerSessionState.PAUSED || session.state == PlannerSessionState.TAKEN_OVER) {
            return PlannerExecutionResult(session)
        }

        val node = session.activeNode ?: return finishWithoutNode(session)

        val firstPolicy = policy.evaluate(session.principal, session.device, node, consumeDelegation = false)
        if (firstPolicy.decision == PlannerPolicyDecision.DENY) {
            emit(session, node, PlannerExecutionEventType.PERMISSION_DENIED, "POLICY_DENY")
            val failed = session.copy(state = PlannerSessionState.FAILED, message = firstPolicy.reason)
            emit(failed, node, PlannerExecutionEventType.SESSION_FAILED, "POLICY_DENY")
            return PlannerExecutionResult(
                session = failed,
                node = node,
                policy = firstPolicy
            )
        }
        if (firstPolicy.decision == PlannerPolicyDecision.ASK_USER && !approved) {
            if (!emit(session, node, PlannerExecutionEventType.PERMISSION_REQUESTED, "USER_APPROVAL_REQUIRED")) {
                return PlannerExecutionResult(
                    session = session.copy(
                        state = PlannerSessionState.FAILED,
                        message = "Audit trail unavailable before permission request"
                    ),
                    node = node,
                    policy = firstPolicy
                )
            }
            return controlInterruption(session, node) ?: PlannerExecutionResult(
                session = session.copy(state = PlannerSessionState.WAITING_USER, message = firstPolicy.reason),
                node = node,
                policy = firstPolicy
            )
        }

        if (firstPolicy.decision == PlannerPolicyDecision.ASK_USER && approved) {
            val type = if (rememberApproval) {
                PlannerExecutionEventType.PERMISSION_ALWAYS_ALLOW
            } else {
                PlannerExecutionEventType.PERMISSION_ALLOW_ONCE
            }
            val code = if (rememberApproval) "USER_ALWAYS_ALLOW" else "USER_ALLOW_ONCE"
            if (!emit(session, node, type, code)) {
                return PlannerExecutionResult(
                    session = session.copy(
                        state = PlannerSessionState.FAILED,
                        message = "Audit trail unavailable before approved execution"
                    ),
                    node = node,
                    policy = firstPolicy
                )
            }
        }

        if (firstPolicy.decision == PlannerPolicyDecision.ASK_USER && approved && rememberApproval) {
            policy.rememberOwnerAlwaysAllow(session.principal, session.device, node)
        }

        if (firstPolicy.decision == PlannerPolicyDecision.ALLOW) {
            val consumed = policy.evaluate(session.principal, session.device, node, consumeDelegation = true)
            if (consumed.decision != PlannerPolicyDecision.ALLOW) {
                if (consumed.decision == PlannerPolicyDecision.ASK_USER) {
                    emit(session, node, PlannerExecutionEventType.PERMISSION_REQUESTED, "DELEGATION_CONSUMED")
                } else {
                    emit(session, node, PlannerExecutionEventType.PERMISSION_DENIED, "DELEGATION_DENY")
                }
                return controlInterruption(session, node) ?: PlannerExecutionResult(
                    session = session.copy(
                        state = if (consumed.decision == PlannerPolicyDecision.ASK_USER) {
                            PlannerSessionState.WAITING_USER
                        } else PlannerSessionState.FAILED,
                        message = consumed.reason
                    ),
                    node = node,
                    policy = consumed
                )
            }
        }

        val before = executors.observe(session.device.id)
            ?: return failForAuditAwareReason(session, node, firstPolicy, "Device observation unavailable", "OBSERVATION_UNAVAILABLE")

        val dispatchControl = executionControl.snapshot(session.id)
        if (dispatchControl.state != PlannerControlState.RUNNING) {
            return interruptedResult(session, node, dispatchControl, policy = firstPolicy, before = before)
        }
        if (!emit(session, node, PlannerExecutionEventType.ACTION_DISPATCH_REQUESTED, "DISPATCH_REQUESTED")) {
            return PlannerExecutionResult(
                session = session.copy(
                    state = PlannerSessionState.FAILED,
                    message = "Audit trail unavailable before action dispatch"
                ),
                node = node,
                policy = firstPolicy,
                before = before
            )
        }

        val immediatelyBeforeDispatch = executionControl.snapshot(session.id)
        if (
            immediatelyBeforeDispatch.state != PlannerControlState.RUNNING ||
            immediatelyBeforeDispatch.generation != dispatchControl.generation
        ) {
            return interruptedResult(session, node, immediatelyBeforeDispatch, policy = firstPolicy, before = before)
        }

        val actionResult = executors.dispatch(session.device.id, node.toUniversalAction())
        val attempt = (session.attempts[node.id] ?: 0) + 1
        val attempts = session.attempts + (node.id to attempt)
        if (!actionResult.accepted) {
            emit(session, node, PlannerExecutionEventType.ACTION_REJECTED, "DISPATCH_REJECTED")
            val afterRejectControl = executionControl.snapshot(session.id)
            if (
                afterRejectControl.state != PlannerControlState.RUNNING ||
                afterRejectControl.generation != dispatchControl.generation
            ) {
                return interruptedResult(
                    session.copy(attempts = attempts),
                    node,
                    afterRejectControl,
                    policy = firstPolicy,
                    actionResult = actionResult,
                    before = before
                )
            }
            return PlannerExecutionResult(
                session = session.copy(
                    attempts = attempts,
                    state = if (attempt >= node.maxAttempts) PlannerSessionState.FAILED else PlannerSessionState.REPLAN_REQUIRED,
                    message = actionResult.message
                ),
                node = node,
                policy = firstPolicy,
                actionResult = actionResult,
                before = before
            )
        }
        val dispatchAuditAccepted = emit(
            session,
            node,
            PlannerExecutionEventType.ACTION_DISPATCH_ACCEPTED,
            "DISPATCH_ACCEPTED"
        )

        settler.await(node, actionResult)
        val after = executors.observe(session.device.id)
            ?: return PlannerExecutionResult(
                session = session.copy(
                    attempts = attempts,
                    state = PlannerSessionState.REPLAN_REQUIRED,
                    message = "Post-action observation unavailable"
                ),
                node = node,
                policy = firstPolicy,
                actionResult = actionResult,
                before = before
            ).also {
                emit(it.session, node, PlannerExecutionEventType.SESSION_FAILED, "POST_OBSERVATION_UNAVAILABLE")
            }

        val verification = verify(node, before, after, actionResult)
        val verificationAuditAccepted = if (verification.verified) {
            runCatching { verifiedActionObserver.onVerified(session, node, before, after) }
            emit(session, node, PlannerExecutionEventType.VERIFICATION_PASSED, "VERIFIED")
        } else {
            emit(session, node, PlannerExecutionEventType.VERIFICATION_FAILED, "VERIFY_FAILED")
        }

        val postActionControl = executionControl.snapshot(session.id)
        if (
            postActionControl.state != PlannerControlState.RUNNING ||
            postActionControl.generation != dispatchControl.generation
        ) {
            val reconciledEvidence = if (verification.verified) {
                (session.evidence + verification.evidence).distinct()
            } else session.evidence
            return interruptedResult(
                session.copy(attempts = attempts, evidence = reconciledEvidence),
                node,
                postActionControl,
                policy = firstPolicy,
                actionResult = actionResult,
                before = before,
                after = after
            )
        }

        if (!verification.verified) {
            return PlannerExecutionResult(
                session = session.copy(
                    attempts = attempts,
                    state = if (attempt >= node.maxAttempts) PlannerSessionState.FAILED else PlannerSessionState.REPLAN_REQUIRED,
                    message = verification.message
                ),
                node = node,
                policy = firstPolicy,
                actionResult = actionResult,
                before = before,
                after = after
            )
        }

        if (!dispatchAuditAccepted || !verificationAuditAccepted) {
            return PlannerExecutionResult(
                session = session.copy(
                    attempts = attempts,
                    evidence = (session.evidence + verification.evidence).distinct(),
                    state = PlannerSessionState.FAILED,
                    message = "Action was observed but durable audit persistence failed; completion withheld"
                ),
                node = node,
                policy = firstPolicy,
                actionResult = actionResult,
                before = before,
                after = after
            )
        }

        val combinedEvidence = (session.evidence + verification.evidence).distinct()
        val nextIndex = session.nextNodeIndex + 1
        val done = nextIndex >= session.proposal.graph.nodes.size
        val advanced = session.copy(
            nextNodeIndex = nextIndex,
            attempts = attempts,
            evidence = combinedEvidence,
            state = if (done) PlannerSessionState.DONE else PlannerSessionState.READY,
            message = if (done) "Verified done" else verification.message
        )
        if (done && !emit(advanced, node, PlannerExecutionEventType.SESSION_DONE, "VERIFIED_DONE")) {
            return PlannerExecutionResult(
                session = advanced.copy(
                    state = PlannerSessionState.FAILED,
                    message = "Verification passed but final audit persistence failed; completion withheld"
                ),
                node = node,
                policy = firstPolicy,
                actionResult = actionResult,
                before = before,
                after = after
            )
        }
        return PlannerExecutionResult(
            session = advanced,
            node = node,
            policy = firstPolicy,
            actionResult = actionResult,
            before = before,
            after = after
        )
    }

    fun replan(
        session: PlannerSession,
        context: PlannerContext,
        planner: AiPlanner,
        reason: String
    ): PlannerExecutionResult {
        controlInterruption(session, session.activeNode)?.let { return it }
        val replanned = planner.replan(context, session.proposal, reason)
        return when (replanned) {
            is PlannerResult.Rejected -> {
                val failed = session.copy(state = PlannerSessionState.FAILED, message = replanned.failure.message)
                emit(failed, failed.activeNode, PlannerExecutionEventType.SESSION_FAILED, "REPLAN_REJECTED")
                PlannerExecutionResult(failed)
            }
            is PlannerResult.Proposed -> {
                val validation = validator.validate(replanned.proposal.graph, session.device)
                if (!validation.valid) {
                    val failed = session.copy(
                        state = PlannerSessionState.FAILED,
                        message = validation.issues.joinToString("; ") { "${it.code}: ${it.message}" }
                    )
                    emit(failed, failed.activeNode, PlannerExecutionEventType.SESSION_FAILED, "REPLAN_INVALID")
                    PlannerExecutionResult(failed)
                } else {
                    val replannedSession = session.copy(
                        proposal = replanned.proposal,
                        nextNodeIndex = 0,
                        state = PlannerSessionState.READY,
                        message = replanned.proposal.graph.narration
                    )
                    if (!emit(replannedSession, null, PlannerExecutionEventType.PLAN_ACCEPTED, "REPLAN_VALID")) {
                        PlannerExecutionResult(
                            replannedSession.copy(
                                state = PlannerSessionState.FAILED,
                                message = "Audit trail unavailable before re-plan execution"
                            )
                        )
                    } else {
                        controlInterruption(replannedSession, null) ?: PlannerExecutionResult(replannedSession)
                    }
                }
            }
        }
    }

    fun recordControlTransition(
        session: PlannerSession,
        type: PlannerExecutionEventType,
        resultCode: String
    ): Boolean {
        require(
            type == PlannerExecutionEventType.MISSION_PAUSED ||
                type == PlannerExecutionEventType.MISSION_RESUMED ||
                type == PlannerExecutionEventType.MISSION_CANCELLED ||
                type == PlannerExecutionEventType.USER_TAKE_OVER
        ) { "Not a control transition event: $type" }
        return emit(session, session.activeNode, type, resultCode)
    }

    private fun finishWithoutNode(session: PlannerSession): PlannerExecutionResult {
        controlInterruption(session, null)?.let { return it }
        val done = session.copy(state = PlannerSessionState.DONE, message = "Verified done")
        return if (emit(done, null, PlannerExecutionEventType.SESSION_DONE, "VERIFIED_DONE")) {
            PlannerExecutionResult(done)
        } else {
            PlannerExecutionResult(
                done.copy(
                    state = PlannerSessionState.FAILED,
                    message = "Audit trail unavailable at completion"
                )
            )
        }
    }

    private fun controlInterruption(
        session: PlannerSession,
        node: ActionGraphNode?
    ): PlannerExecutionResult? {
        val snapshot = executionControl.snapshot(session.id)
        return if (snapshot.state == PlannerControlState.RUNNING) null else interruptedResult(session, node, snapshot)
    }

    private fun interruptedResult(
        session: PlannerSession,
        node: ActionGraphNode?,
        snapshot: PlannerControlSnapshot,
        policy: PlannerPolicyResult? = null,
        actionResult: UniversalActionResult? = null,
        before: DeviceObservation? = null,
        after: DeviceObservation? = null
    ): PlannerExecutionResult {
        val (state, eventType, code, defaultMessage) = when (snapshot.state) {
            PlannerControlState.PAUSED -> ControlResult(
                PlannerSessionState.PAUSED,
                PlannerExecutionEventType.MISSION_PAUSED,
                "OWNER_PAUSE",
                "Paused by owner"
            )
            PlannerControlState.TAKEN_OVER -> ControlResult(
                PlannerSessionState.TAKEN_OVER,
                PlannerExecutionEventType.USER_TAKE_OVER,
                "OWNER_TAKE_OVER",
                "Owner took control"
            )
            PlannerControlState.CANCELLED -> ControlResult(
                PlannerSessionState.CANCELLED,
                PlannerExecutionEventType.MISSION_CANCELLED,
                "OWNER_CANCEL",
                "Cancelled by owner"
            )
            PlannerControlState.RUNNING -> ControlResult(
                PlannerSessionState.PAUSED,
                PlannerExecutionEventType.MISSION_PAUSED,
                "STALE_GENERATION",
                "Execution generation changed; revalidation required"
            )
        }
        val interrupted = session.copy(
            state = state,
            message = snapshot.reason ?: defaultMessage
        )
        emit(interrupted, node, eventType, code)
        return PlannerExecutionResult(
            session = interrupted,
            node = node,
            policy = policy,
            actionResult = actionResult,
            before = before,
            after = after
        )
    }

    private fun failForAuditAwareReason(
        session: PlannerSession,
        node: ActionGraphNode,
        policyResult: PlannerPolicyResult,
        message: String,
        code: String
    ): PlannerExecutionResult {
        controlInterruption(session, node)?.let { return it }
        val failed = session.copy(state = PlannerSessionState.REPLAN_REQUIRED, message = message)
        emit(failed, node, PlannerExecutionEventType.SESSION_FAILED, code)
        return PlannerExecutionResult(
            session = failed,
            node = node,
            policy = policyResult
        )
    }

    private fun emit(
        session: PlannerSession,
        node: ActionGraphNode?,
        type: PlannerExecutionEventType,
        resultCode: String
    ): Boolean = runCatching {
        executionEventObserver.onEvent(
            PlannerExecutionEvent(
                sessionId = session.id,
                principalId = session.principal.id,
                principalKind = session.principal.kind.name,
                deviceId = session.device.id,
                type = type,
                capability = node?.requiredCapability?.name,
                actionType = node?.action?.type?.name,
                targetScope = node?.targetScope ?: node?.action?.target,
                resultCode = resultCode
            )
        )
    }.getOrDefault(false)

    private fun verify(
        node: ActionGraphNode,
        before: DeviceObservation,
        after: DeviceObservation,
        actionResult: UniversalActionResult
    ): NodeVerification {
        val expected = node.verification.expected
        val verified = when (node.verification.mode) {
            VerificationMode.APP -> after.foregroundApp == expected
            VerificationMode.WINDOW -> after.foregroundWindow?.contains(expected.orEmpty(), ignoreCase = true) == true
            VerificationMode.TEXT_PRESENT -> after.visibleText.any { it.contains(expected.orEmpty(), ignoreCase = true) }
            VerificationMode.TEXT_ABSENT -> after.visibleText.none { it.contains(expected.orEmpty(), ignoreCase = true) }
            VerificationMode.URI_PREFIX -> after.uri?.startsWith(expected.orEmpty()) == true
            VerificationMode.CONTENT_CHANGED ->
                before.foregroundApp != after.foregroundApp ||
                    before.foregroundWindow != after.foregroundWindow ||
                    before.visibleText != after.visibleText ||
                    before.uri != after.uri
            VerificationMode.ACTION_ACCEPTED -> actionResult.accepted
        }
        val evidence = if (verified) {
            listOf(
                "node:${node.id}",
                "verification:${node.verification.mode}",
                "device:${after.deviceId}",
                "app:${after.foregroundApp ?: "unknown"}"
            )
        } else emptyList()
        return NodeVerification(
            verified = verified,
            evidence = evidence,
            message = if (verified) node.verification.description else "Verification failed: ${node.verification.description}"
        )
    }

    private fun rejectedSession(
        sessionId: String,
        principal: Principal,
        device: DeviceDescriptor,
        objective: String,
        message: String
    ): PlannerSession {
        val proposal = PlannerProposal(
            graph = ActionGraph(objective = objective, narration = message, nodes = emptyList()),
            providerId = "none",
            modelId = "none",
            confidence = 0.0,
            explanation = message
        )
        return PlannerSession(
            id = sessionId,
            principal = principal,
            device = device,
            proposal = proposal,
            state = PlannerSessionState.FAILED,
            message = message
        )
    }

    private data class NodeVerification(
        val verified: Boolean,
        val evidence: List<String>,
        val message: String
    )

    private data class ControlResult(
        val state: PlannerSessionState,
        val eventType: PlannerExecutionEventType,
        val code: String,
        val message: String
    )
}
