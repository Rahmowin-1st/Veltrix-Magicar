package com.veltrix.ultron.platform

import com.veltrix.ultron.planner.PlannerExecutionAuditBus
import com.veltrix.ultron.planner.PlannerExecutionEvent
import com.veltrix.ultron.planner.PlannerExecutionEventObserver
import com.veltrix.ultron.planner.PlannerExecutionEventType

/** Bridges platform-neutral planner facts into the Android encrypted audit sink. */
object AndroidPlannerAuditAdapter {
    fun initialize() {
        PlannerExecutionAuditBus.install(PlannerExecutionEventObserver(::record))
    }

    private fun record(event: PlannerExecutionEvent): Boolean {
        if (isInitialPlanEvent(event)) {
            val submitted = AndroidAuditTrail.record(
                AndroidAuditEventInput(
                    missionId = event.sessionId,
                    principalKind = event.principalKind,
                    principalId = event.principalId,
                    deviceId = event.deviceId,
                    type = AndroidAuditEventType.MISSION_SUBMITTED,
                    resultCode = "OBJECTIVE_ACCEPTED"
                )
            )
            if (!submitted) return false
        }

        return AndroidAuditTrail.record(
            AndroidAuditEventInput(
                missionId = event.sessionId,
                principalKind = event.principalKind,
                principalId = event.principalId,
                deviceId = event.deviceId,
                type = event.type.toAndroidType(),
                capability = event.capability,
                actionType = event.actionType,
                targetScope = event.targetScope,
                resultCode = event.resultCode
            )
        )
    }

    private fun isInitialPlanEvent(event: PlannerExecutionEvent): Boolean =
        (event.type == PlannerExecutionEventType.PLAN_ACCEPTED && event.resultCode == "PLAN_VALID") ||
            (event.type == PlannerExecutionEventType.SESSION_FAILED &&
                (event.resultCode == "PLAN_REJECTED" || event.resultCode == "PLAN_INVALID"))

    private fun PlannerExecutionEventType.toAndroidType(): AndroidAuditEventType = when (this) {
        PlannerExecutionEventType.PLAN_ACCEPTED -> AndroidAuditEventType.PLAN_ACCEPTED
        PlannerExecutionEventType.PERMISSION_REQUESTED -> AndroidAuditEventType.PERMISSION_REQUESTED
        PlannerExecutionEventType.PERMISSION_ALLOW_ONCE -> AndroidAuditEventType.PERMISSION_ALLOW_ONCE
        PlannerExecutionEventType.PERMISSION_ALWAYS_ALLOW -> AndroidAuditEventType.PERMISSION_ALWAYS_ALLOW
        PlannerExecutionEventType.PERMISSION_DENIED -> AndroidAuditEventType.PERMISSION_DENIED
        PlannerExecutionEventType.ACTION_DISPATCH_REQUESTED -> AndroidAuditEventType.ACTION_DISPATCH_REQUESTED
        PlannerExecutionEventType.ACTION_DISPATCH_ACCEPTED -> AndroidAuditEventType.ACTION_DISPATCH_ACCEPTED
        PlannerExecutionEventType.ACTION_REJECTED -> AndroidAuditEventType.ACTION_REJECTED
        PlannerExecutionEventType.VERIFICATION_PASSED -> AndroidAuditEventType.VERIFICATION_PASSED
        PlannerExecutionEventType.VERIFICATION_FAILED -> AndroidAuditEventType.VERIFICATION_FAILED
        PlannerExecutionEventType.MISSION_PAUSED -> AndroidAuditEventType.MISSION_PAUSED
        PlannerExecutionEventType.MISSION_RESUMED -> AndroidAuditEventType.MISSION_RESUMED
        PlannerExecutionEventType.MISSION_CANCELLED -> AndroidAuditEventType.MISSION_CANCELLED
        PlannerExecutionEventType.USER_TAKE_OVER -> AndroidAuditEventType.USER_TAKE_OVER
        PlannerExecutionEventType.SESSION_DONE -> AndroidAuditEventType.VERIFICATION_PASSED
        PlannerExecutionEventType.SESSION_FAILED -> AndroidAuditEventType.MISSION_FAILED
    }
}
