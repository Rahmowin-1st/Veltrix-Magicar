package com.veltrix.ultron.remote

import com.veltrix.ultron.devices.ControlProfile
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class HttpRemoteBridgeTransport(
    private val credentialsProvider: () -> BridgeCredentials,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000
) : RemoteBridgeTransport {

    override fun heartbeat(snapshot: BridgeCapabilitySnapshot) {
        request(
            method = "POST",
            path = "/v1/device/heartbeat",
            body = JSONObject()
                .put("platform", snapshot.platform)
                .put("availableCapabilities", JSONArray(snapshot.availableCapabilities.sorted()))
                .put("grantedCapabilities", JSONArray(snapshot.grantedCapabilities.sorted()))
        )
    }

    override fun claimTask(): RemoteTaskLease? =
        parseTask(request("POST", "/v1/device/tasks/claim", JSONObject()))

    override fun waitForTask(timeoutMs: Int): RemoteTaskLease? {
        val bounded = timeoutMs.coerceIn(1_000, 30_000)
        return parseTask(
            request(
                method = "POST",
                path = "/v1/device/tasks/wait",
                body = JSONObject().put("timeoutMs", bounded),
                readTimeoutOverrideMs = bounded + 5_000
            )
        )
    }

    override fun inspectTask(taskId: String): RemoteTaskControl {
        val root = request("GET", "/v1/device/tasks/${encodePathSegment(taskId)}")
        return RemoteTaskControl(
            taskId = root.getString("taskId"),
            state = RemoteTaskState.valueOf(root.getString("state")),
            narration = root.optString("narration", root.getString("state"))
        )
    }

    override fun reportTask(taskId: String, report: RemoteTaskReport) {
        val body = JSONObject()
            .put("state", report.state.name)
            .put("narration", report.narration)
        if (report.evidence.isNotEmpty()) body.put("evidence", JSONArray(report.evidence))
        request("POST", "/v1/device/tasks/${encodePathSegment(taskId)}/state", body)
    }

    private fun parseTask(root: JSONObject): RemoteTaskLease? {
        if (root.isNull("task")) return null
        val task = root.getJSONObject("task")
        return RemoteTaskLease(
            taskId = task.getString("taskId"),
            principalId = task.getString("principalId"),
            principalKind = RemotePrincipalKind.valueOf(task.getString("principalKind").uppercase()),
            principalDisplayName = task.getString("principalDisplayName"),
            objective = task.getString("objective"),
            constraints = task.getJSONArray("constraints").toStringList(),
            requiredCapabilities = task.getJSONArray("requiredCapabilities").toStringList().toSet(),
            controlProfile = ControlProfile.valueOf(task.getString("controlProfile"))
        )
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        readTimeoutOverrideMs: Int? = null
    ): JSONObject {
        val credentials = credentialsProvider()
        val baseUrl = BridgeCredentialStore.normalizeHttpsBaseUrl(credentials.baseUrl)
        require(credentials.bearerToken.isNotBlank()) { "Bridge bearer token is missing" }
        val connection = URL(baseUrl + path).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutOverrideMs ?: readTimeoutMs
            connection.setRequestProperty("Authorization", "Bearer ${credentials.bearerToken}")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                // Never propagate server-controlled response text into Android diagnostics/log surfaces.
                throw RemoteBridgeHttpException(code)
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONArray.toStringList(): List<String> =
        buildList(length()) {
            for (index in 0 until length()) add(getString(index))
        }

    private fun encodePathSegment(value: String): String {
        require(value.isNotBlank()) { "Path segment must not be blank" }
        return java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }
}
