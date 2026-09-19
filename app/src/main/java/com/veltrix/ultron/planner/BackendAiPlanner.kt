package com.veltrix.ultron.planner

import com.veltrix.ultron.devices.DeviceCapability
import com.veltrix.ultron.devices.UniversalAction
import com.veltrix.ultron.devices.UniversalActionType
import com.veltrix.ultron.remote.BridgeCredentials
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

/**
 * Android-side planner adapter for the Veltrix backend.
 *
 * Provider credentials never enter the APK. The only credential used here is the
 * scoped device bearer produced by secure enrollment and stored through Android
 * Keystore. The backend may propose a graph, but Android still validates policy,
 * asks the user when required, executes locally, observes, and verifies.
 */
class BackendAiPlanner(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 60_000,
    private val credentialsProvider: () -> BridgeCredentials?
) : AiPlanner {

    override fun plan(context: PlannerContext): PlannerResult =
        request(context, previous = null, replanReason = null)

    override fun replan(
        context: PlannerContext,
        previous: PlannerProposal,
        reason: String
    ): PlannerResult = request(context, previous, reason)

    private fun request(
        context: PlannerContext,
        previous: PlannerProposal?,
        replanReason: String?
    ): PlannerResult {
        val credentials = runCatching { credentialsProvider() }.getOrNull()
            ?: return rejected("BACKEND_DEVICE_CREDENTIAL_MISSING", "Secure backend link is not configured")
        val networkDeviceId = credentials.deviceId?.trim()?.takeIf(String::isNotBlank)
            ?: return rejected("BACKEND_DEVICE_ID_UNBOUND", "Secure enrollment has not bound this device yet")

        val vision = context.visionFrame
        if (vision != null && vision.bytes.size > MAX_VISION_FRAME_BYTES) {
            return rejected("VISION_FRAME_TOO_LARGE", "One-shot screen frame exceeded the backend planner size limit")
        }
        if (vision != null && vision.mimeType !in SUPPORTED_VISION_MIME_TYPES) {
            return rejected("VISION_MIME_UNSUPPORTED", "One-shot screen frame format is unsupported")
        }

        val body = buildRequest(context, networkDeviceId, previous, replanReason).toString()
        if (body.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
            return rejected("BACKEND_REQUEST_TOO_LARGE", "Planner context exceeded the bounded backend request size")
        }

        return runCatching {
            val connection = URL(credentials.baseUrl.trimEnd('/') + PLAN_PATH)
                .openConnection() as HttpsURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.doOutput = true
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Authorization", "Bearer ${credentials.bearerToken}")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Cache-Control", "no-store")
                connection.outputStream.use { stream ->
                    stream.write(body.toByteArray(Charsets.UTF_8))
                }

                val status = connection.responseCode
                if (status !in 200..299) {
                    connection.errorStream?.close()
                    return rejected(
                        code = "BACKEND_HTTP_$status",
                        message = "Backend planner request was rejected",
                        retryable = status == 408 || status == 409 || status == 425 || status == 429 || status >= 500
                    )
                }
                parseResponse(readBounded(connection))
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error ->
            val code = if (error is BackendPlannerResponseTooLargeException) {
                "BACKEND_RESPONSE_TOO_LARGE"
            } else {
                "BACKEND_IO"
            }
            rejected(code, "Backend planner request failed", retryable = true)
        }
    }

    private fun buildRequest(
        context: PlannerContext,
        networkDeviceId: String,
        previous: PlannerProposal?,
        replanReason: String?
    ): JSONObject {
        val observation = context.observation
        val vision = context.visionFrame
        return JSONObject()
            .put("objective", bounded(context.objective, 16_000))
            .put("constraints", JSONArray(context.constraints.take(32).map { bounded(it, 1_000) }))
            .put(
                "device",
                JSONObject()
                    // Network identity is the attested token binding, not the process-local executor id.
                    .put("id", networkDeviceId)
                    .put("kind", context.device.kind.name)
                    .put("platform", context.device.platform.name)
                    .put(
                        "effectiveCapabilities",
                        JSONArray(context.device.effectiveCapabilities.map { it.name }.distinct().sorted())
                    )
            )
            .put(
                "observation",
                JSONObject()
                    .put("foregroundApp", observation.foregroundApp ?: JSONObject.NULL)
                    .put("foregroundWindow", observation.foregroundWindow ?: JSONObject.NULL)
                    .put("uri", observation.uri?.let { bounded(it, 4_000) } ?: JSONObject.NULL)
                    .put(
                        "visibleText",
                        JSONArray(observation.visibleText.take(60).map { bounded(it, 180) })
                    )
                    .put(
                        "semanticNodes",
                        JSONArray(observation.semanticNodes.take(160).map { node ->
                            JSONObject()
                                .put("left", node.left)
                                .put("top", node.top)
                                .put("right", node.right)
                                .put("bottom", node.bottom)
                                .put("text", node.text?.let { bounded(it, 160) } ?: JSONObject.NULL)
                                .put("hint", node.hint?.let { bounded(it, 120) } ?: JSONObject.NULL)
                                .put("contentDescription", node.contentDescription?.let { bounded(it, 160) } ?: JSONObject.NULL)
                                .put("viewId", node.viewId?.let { bounded(it, 180) } ?: JSONObject.NULL)
                                .put("className", node.className?.let { bounded(it, 120) } ?: JSONObject.NULL)
                                .put("clickable", node.clickable)
                                .put("editable", node.editable)
                                .put("scrollable", node.scrollable)
                                .put("enabled", node.enabled)
                                .put("focused", node.focused)
                        })
                    )
                    .put("screenFingerprint", observation.screenFingerprint ?: JSONObject.NULL)
            )
            .put("memoryHints", JSONArray(context.memoryHints.takeLast(20).map { bounded(it, 500) }))
            .put("recentEvidence", JSONArray(context.recentEvidence.takeLast(20).map { bounded(it, 500) }))
            .put("failedSteps", JSONArray(context.failedStepDescriptions.takeLast(12).map { bounded(it, 500) }))
            .put(
                "vision",
                vision?.let { frame ->
                    JSONObject()
                        .put("mimeType", frame.mimeType)
                        .put("sourcePackage", frame.sourcePackage ?: JSONObject.NULL)
                        .put("capturedAtEpochMs", frame.capturedAtEpochMs)
                        .put("base64", Base64.getEncoder().encodeToString(frame.bytes))
                } ?: JSONObject.NULL
            )
            .put(
                "previousSteps",
                JSONArray(previous?.graph?.nodes.orEmpty().takeLast(24).map { bounded(it.description, 300) })
            )
            .put("replanReason", replanReason?.let { bounded(it, 2_000) } ?: JSONObject.NULL)
    }

    private fun parseResponse(raw: String): PlannerResult = runCatching {
        val root = JSONObject(raw)
        when (root.getString("state")) {
            "REJECTED" -> {
                val code = root.optString("code", "BACKEND_REJECTED")
                    .take(120)
                    .ifBlank { "BACKEND_REJECTED" }
                PlannerResult.Rejected(
                    PlannerFailure(
                        code = code,
                        message = "Backend planner rejected the request ($code)",
                        retryable = root.optBoolean("retryable", false)
                    )
                )
            }
            "PROPOSED" -> {
                val proposal = root.getJSONObject("proposal")
                val steps = proposal.getJSONArray("steps")
                val nodes = buildList {
                    for (index in 0 until steps.length()) add(parseNode(steps.getJSONObject(index), index))
                }
                val graph = ActionGraph(
                    objective = proposal.getString("objective"),
                    narration = proposal.getString("narration"),
                    nodes = nodes
                )
                PlannerResult.Proposed(
                    PlannerProposal(
                        graph = graph,
                        providerId = root.getString("providerId").take(100),
                        modelId = root.getString("modelId").take(200),
                        confidence = proposal.optDouble("confidence", 0.5).coerceIn(0.0, 1.0),
                        explanation = proposal.optString("explanation", graph.narration).take(4_000)
                    )
                )
            }
            else -> rejected("BACKEND_RESPONSE_INVALID", "Backend planner returned an invalid state", retryable = true)
        }
    }.getOrElse {
        rejected("BACKEND_RESPONSE_INVALID", "Backend planner response did not match the typed contract", retryable = true)
    }

    private fun parseNode(json: JSONObject, index: Int): ActionGraphNode {
        val actionJson = json.getJSONObject("action")
        val actionType = UniversalActionType.valueOf(actionJson.getString("type").uppercase())
        val metadata = linkedMapOf<String, String>()
        actionJson.optJSONObject("metadata")?.let { values ->
            values.keys().forEach { key -> metadata[key] = values.optString(key).take(1_000) }
        }
        val action = UniversalAction(
            type = actionType,
            target = actionJson.optNullableString("target"),
            text = actionJson.optNullableString("text"),
            value = actionJson.optNullableString("value"),
            x = actionJson.optNullableDouble("x")?.toFloat(),
            y = actionJson.optNullableDouble("y")?.toFloat(),
            x2 = actionJson.optNullableDouble("x2")?.toFloat(),
            y2 = actionJson.optNullableDouble("y2")?.toFloat(),
            durationMs = actionJson.optNullableDouble("duration_ms")?.toLong(),
            metadata = metadata
        )
        val verificationJson = json.getJSONObject("verification")
        return ActionGraphNode(
            id = json.optString("id").takeIf(String::isNotBlank) ?: "step-${index + 1}",
            description = json.getString("description"),
            action = action,
            requiredCapability = DeviceCapability.valueOf(json.getString("required_capability").uppercase()),
            targetScope = json.optNullableString("target_scope"),
            risk = PlannerRisk.valueOf(json.optString("risk", "LOW").uppercase()),
            verification = VerificationRule(
                mode = VerificationMode.valueOf(verificationJson.getString("mode").uppercase()),
                expected = verificationJson.optNullableString("expected"),
                description = verificationJson.getString("description")
            ),
            maxAttempts = json.optInt("max_attempts", 2)
        )
    }

    private fun readBounded(connection: HttpsURLConnection): String {
        val declared = connection.getHeaderField("Content-Length")?.toLongOrNull()
        if (declared != null && declared > MAX_RESPONSE_BYTES) throw BackendPlannerResponseTooLargeException()
        val output = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_RESPONSE_BYTES) throw BackendPlannerResponseTooLargeException()
                output.write(buffer, 0, count)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun rejected(code: String, message: String, retryable: Boolean = false): PlannerResult.Rejected =
        PlannerResult.Rejected(PlannerFailure(code = code, message = message, retryable = retryable))

    private fun bounded(value: String, maxLength: Int): String = value.take(maxLength)

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeUnless(Double::isNaN)

    private class BackendPlannerResponseTooLargeException : Exception()

    companion object {
        private const val PLAN_PATH = "/v1/device/planner/plan"
        private const val MAX_REQUEST_BYTES = 360_000
        private const val MAX_RESPONSE_BYTES = 1_000_000
        // Keep enough headroom for JSON/base64 overhead under the server's 384 KB global body bound.
        private const val MAX_VISION_FRAME_BYTES = 180_000
        private val SUPPORTED_VISION_MIME_TYPES = setOf("image/jpeg", "image/png")
    }
}
