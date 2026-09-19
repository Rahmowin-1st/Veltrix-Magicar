package com.veltrix.ultron.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.veltrix.ultron.agents.AndroidAgentCommandRuntime
import com.veltrix.ultron.agents.AgentTaskReceipt
import com.veltrix.ultron.agents.AgentTaskStatus
import com.veltrix.ultron.agents.PlannerBackedAgentGateway
import com.veltrix.ultron.runtime.AndroidAiCommandRuntime
import com.veltrix.ultron.runtime.CommandApproval
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-scoped task runtime for remote agents/users. The same gateway instance
 * is used for bridge sync, approval UI, cancellation and audit state.
 */
object UltronAgentRuntime {
    private data class State(
        val credentials: BridgeCredentialStore,
        val bootstrap: AndroidSecureDeviceBootstrap,
        val capabilities: AndroidBridgeCapabilitySnapshotFactory,
        val gateway: PlannerBackedAgentGateway,
        val coordinator: RemoteBridgeCoordinator,
        val notifications: RemoteMissionNotifier,
        val diagnostics: RemoteBridgeDiagnostics
    )

    @Volatile private var state: State? = null
    private val controlWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "veltrix-agent-control").apply { isDaemon = true }
    }
    private val longPollWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "veltrix-agent-long-poll").apply { isDaemon = true }
    }
    private val backgroundLoopRunning = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun initialize(context: Context) {
        if (state != null) return
        synchronized(this) {
            if (state != null) return
            val appContext = context.applicationContext
            val credentials = BridgeCredentialStore(appContext)
            val bootstrap = AndroidSecureDeviceBootstrap(appContext, credentials)
            val diagnostics = RemoteBridgeDiagnostics()
            runCatching { credentials.load() }
                .onSuccess { saved ->
                    if (saved == null) diagnostics.markUnconfigured() else diagnostics.markConfigured()
                }
                .onFailure { error -> diagnostics.markFailure(error) }
            val aiRuntime = AndroidAiCommandRuntime(appContext)
            val gateway = PlannerBackedAgentGateway(AndroidAgentCommandRuntime(aiRuntime))
            val transport = HttpRemoteBridgeTransport(
                credentialsProvider = {
                    requireNotNull(credentials.load()) { "Remote bridge is not paired" }
                }
            )
            state = State(
                credentials = credentials,
                bootstrap = bootstrap,
                capabilities = AndroidBridgeCapabilitySnapshotFactory(appContext),
                gateway = gateway,
                coordinator = RemoteBridgeCoordinator(transport, gateway),
                notifications = RemoteMissionNotifier(appContext),
                diagnostics = diagnostics
            )
        }
    }

    /** Legacy/manual configuration remains for controlled migrations, not production UI. */
    fun configure(baseUrl: String, deviceBearerToken: String) {
        val current = requireState()
        current.credentials.save(baseUrl, deviceBearerToken)
        current.diagnostics.markConfigured()
        // Car edition stays idle after configuration. No unsolicited long-poll execution.
    }

    internal fun ensureSecureConfigurationAsync(callback: (Result<SecureEnrollmentReceipt>) -> Unit = {}) {
        val current = requireState()
        current.bootstrap.enrollAsync { result ->
            result.fold(
                onSuccess = {
                    current.diagnostics.markConfigured()
                    // Enrollment configures the secure bridge only. User wake remains the authority boundary.
                },
                onFailure = {
                    if (!isConfigured()) current.diagnostics.markUnconfigured()
                }
            )
            callback(result)
        }
    }

    internal fun pairWithCodeAsync(
        pairingCode: String,
        callback: (Result<SecureEnrollmentReceipt>) -> Unit = {}
    ) {
        val current = requireState()
        current.bootstrap.pairWithCodeAsync(pairingCode) { result ->
            result.fold(
                onSuccess = { current.diagnostics.markConfigured() },
                onFailure = {
                    if (!isConfigured()) current.diagnostics.markUnconfigured()
                }
            )
            callback(result)
        }
    }

    internal fun secureEnrollmentStatus(): SecureEnrollmentSnapshot =
        state?.bootstrap?.snapshot() ?: SecureEnrollmentSnapshot(SecureEnrollmentState.IDLE)

    fun clearConfiguration() {
        stopBackgroundLoop()
        requireState().apply {
            // Cancel the active enrollment first. Its local credential commit is
            // serialized against this cancellation, so owner clear always wins.
            bootstrap.resetManagedCredentialMetadata()
            credentials.clear()
            diagnostics.markUnconfigured()
            notifications.clearAll()
        }
    }

    fun isConfigured(): Boolean {
        val current = state ?: return false
        return runCatching { current.credentials.load() != null }
            .onSuccess { configured ->
                if (!configured) current.diagnostics.markUnconfigured()
            }
            .onFailure { error -> current.diagnostics.markFailure(error) }
            .getOrDefault(false)
    }

    fun isBackgroundLoopRunning(): Boolean = backgroundLoopRunning.get()

    fun connectionStatus(): RemoteBridgeConnectionSnapshot =
        state?.diagnostics?.snapshot()
            ?: RemoteBridgeConnectionSnapshot(RemoteBridgeConnectionState.UNCONFIGURED)

    fun task(taskId: String): AgentTaskReceipt? = state?.gateway?.get(taskId)

    fun activeTasks(): List<AgentTaskStatus> = state?.gateway?.activeTaskStatuses().orEmpty()

    fun refreshNotifications() {
        state?.let(::reconcileNotifications)
    }

    fun startBackgroundLoop() {
        val current = requireState()
        val configured = runCatching { current.credentials.load() != null }
            .onSuccess { available ->
                if (!available) current.diagnostics.markUnconfigured()
            }
            .onFailure { error -> current.diagnostics.markFailure(error) }
            .getOrDefault(false)
        if (!configured) return
        if (!backgroundLoopRunning.compareAndSet(false, true)) return

        longPollWorker.execute {
            var backoffMs = MIN_BACKOFF_MS
            try {
                while (backgroundLoopRunning.get()) {
                    val credentialsAvailable = runCatching { current.credentials.load() != null }
                    if (credentialsAvailable.isFailure) {
                        current.diagnostics.markFailure(requireNotNull(credentialsAvailable.exceptionOrNull()))
                        break
                    }
                    if (!credentialsAvailable.getOrDefault(false)) {
                        current.diagnostics.markUnconfigured()
                        break
                    }

                    current.diagnostics.markAttemptStarted()
                    val result = runCatching {
                        current.coordinator.waitAndSync(
                            snapshot = current.capabilities.snapshot(),
                            timeoutMs = LONG_POLL_MS
                        )
                    }
                    if (result.isSuccess) {
                        current.diagnostics.markSuccess()
                        reconcileNotifications(current)
                        backoffMs = MIN_BACKOFF_MS
                    } else if (backgroundLoopRunning.get()) {
                        val error = requireNotNull(result.exceptionOrNull())
                        current.diagnostics.markFailure(error, backoffMs)
                        if (!current.diagnostics.shouldRetry()) break
                        sleepInterruptibly(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    }
                }
            } finally {
                backgroundLoopRunning.set(false)
            }
        }
    }

    fun stopBackgroundLoop() {
        backgroundLoopRunning.set(false)
    }

    fun syncAsync(callback: (Result<BridgeSyncResult>) -> Unit = {}) {
        val current = requireState()
        controlWorker.execute {
            current.diagnostics.markAttemptStarted()
            val result = runCatching {
                current.coordinator.sync(current.capabilities.snapshot())
            }
            result.fold(
                onSuccess = {
                    current.diagnostics.markSuccess()
                    reconcileNotifications(current)
                },
                onFailure = { error -> current.diagnostics.markFailure(error) }
            )
            mainHandler.post { callback(result) }
        }
    }

    fun approveAsync(
        taskId: String,
        approval: CommandApproval,
        callback: (Result<AgentTaskReceipt>) -> Unit = {}
    ) {
        val current = requireState()
        controlWorker.execute {
            val result = runCatching {
                val receipt = current.gateway.approve(taskId, approval)
                current.coordinator.syncTrackedOnly()
                receipt
            }
            reconcileNotifications(current)
            mainHandler.post { callback(result) }
        }
    }

    fun pauseAsync(taskId: String, callback: (Result<AgentTaskReceipt>) -> Unit = {}) {
        val current = requireState()
        controlWorker.execute {
            val result = runCatching { current.coordinator.pauseLocal(taskId) }
            reconcileNotifications(current)
            mainHandler.post { callback(result) }
        }
    }

    fun takeOverAsync(taskId: String, callback: (Result<AgentTaskReceipt>) -> Unit = {}) {
        val current = requireState()
        controlWorker.execute {
            val result = runCatching { current.coordinator.takeOverLocal(taskId) }
            reconcileNotifications(current)
            mainHandler.post { callback(result) }
        }
    }

    fun resumeAsync(taskId: String, callback: (Result<AgentTaskReceipt>) -> Unit = {}) {
        val current = requireState()
        controlWorker.execute {
            val result = runCatching { current.coordinator.resumeLocal(taskId) }
            reconcileNotifications(current)
            mainHandler.post { callback(result) }
        }
    }

    fun cancelAsync(taskId: String, callback: (Result<AgentTaskReceipt>) -> Unit = {}) {
        val current = requireState()
        controlWorker.execute {
            val result = runCatching { current.coordinator.cancelLocal(taskId) }
            reconcileNotifications(current)
            mainHandler.post { callback(result) }
        }
    }

    private fun reconcileNotifications(current: State) {
        current.notifications.reconcile(current.gateway.activeTaskStatuses())
    }

    private fun sleepInterruptibly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun requireState(): State =
        requireNotNull(state) { "UltronAgentRuntime is not initialized" }

    private const val LONG_POLL_MS = 25_000
    private const val MIN_BACKOFF_MS = 1_000L
    private const val MAX_BACKOFF_MS = 30_000L
}
