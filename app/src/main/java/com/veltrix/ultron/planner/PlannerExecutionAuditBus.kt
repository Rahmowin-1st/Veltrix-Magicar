package com.veltrix.ultron.planner

/**
 * Process-scoped adapter point between platform-neutral planner events and the
 * Android encrypted audit sink. Tests and non-Android callers remain permissive
 * until the Application installs the real sink.
 */
object PlannerExecutionAuditBus : PlannerExecutionEventObserver {
    @Volatile
    private var delegate: PlannerExecutionEventObserver? = null

    fun install(observer: PlannerExecutionEventObserver?) {
        delegate = observer
    }

    override fun onEvent(event: PlannerExecutionEvent): Boolean =
        delegate?.onEvent(event) ?: true
}
