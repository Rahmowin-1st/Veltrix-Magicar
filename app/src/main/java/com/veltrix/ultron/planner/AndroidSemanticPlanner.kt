package com.veltrix.ultron.planner

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.veltrix.ultron.devices.DevicePlatform
import com.veltrix.ultron.devices.UniversalActionType
import java.util.Locale

data class AndroidInstalledApp(
    val label: String,
    val packageName: String
)

interface AndroidAppTargetResolver {
    fun resolve(raw: String): String?
    fun plannerHint(limit: Int = 120): String

    /**
     * Last local-only transformation before Android context is handed to a model.
     * Test/fake resolvers default to identity; the production catalog installs the
     * owner privacy firewall backed by the same durable permission store.
     */
    fun filterPlannerContext(context: PlannerContext): PlannerContext = context
}

class AndroidAppCatalog(context: Context) : AndroidAppTargetResolver {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val privacyFirewall = PlannerObservationPrivacyFirewall(
        AndroidOwnerPlannerPermissionStore(appContext)
    )

    fun launcherApps(): List<AndroidInstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .asSequence()
            .mapNotNull { resolve ->
                val pkg = resolve.activityInfo?.packageName?.trim().orEmpty()
                if (pkg.isEmpty()) return@mapNotNull null
                val label = runCatching { resolve.loadLabel(packageManager).toString().trim() }.getOrDefault(pkg)
                AndroidInstalledApp(label = label.ifBlank { pkg }, packageName = pkg)
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy<AndroidInstalledApp> { it.label.lowercase(Locale.ROOT) }.thenBy { it.packageName })
            .toList()
    }

    override fun resolve(raw: String): String? {
        val target = raw.trim()
        if (target.isEmpty()) return null
        val apps = launcherApps()
        apps.firstOrNull { it.packageName.equals(target, ignoreCase = true) }?.let { return it.packageName }

        val normalized = normalize(target)
        val exactLabels = apps.filter { normalize(it.label) == normalized }
        if (exactLabels.size == 1) return exactLabels.single().packageName

        val prefixLabels = apps.filter {
            val label = normalize(it.label)
            label.startsWith(normalized) || normalized.startsWith(label)
        }
        return prefixLabels.singleOrNull()?.packageName
    }

    override fun plannerHint(limit: Int): String = launcherApps()
        .take(limit)
        .joinToString(prefix = "installed_android_apps=[", postfix = "]") { app ->
            "${app.label}=>${app.packageName}"
        }

    override fun filterPlannerContext(context: PlannerContext): PlannerContext = privacyFirewall.filter(context)

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "")
}

/**
 * Android-specific semantic normalization around a provider-neutral planner.
 * A model may refer to an app by human label, but execution receives only a
 * package name that is actually present in the local launcher catalog.
 *
 * Privacy filtering intentionally happens after the local app-catalog hint is
 * assembled and before delegation to the model. If the current resource is HARD
 * DENY, the firewall removes both screen-derived data and the catalog/memory hints
 * from that turn, so hidden app content cannot leak through model context.
 */
class AndroidSemanticPlanner(
    private val delegate: AiPlanner,
    private val catalog: AndroidAppTargetResolver
) : AiPlanner {
    override fun plan(context: PlannerContext): PlannerResult =
        normalize(delegate.plan(withCatalog(context)))

    override fun replan(
        context: PlannerContext,
        previous: PlannerProposal,
        reason: String
    ): PlannerResult = normalize(delegate.replan(withCatalog(context), previous, reason))

    private fun withCatalog(context: PlannerContext): PlannerContext {
        if (context.device.platform != DevicePlatform.ANDROID) return context
        val withCatalog = context.copy(
            memoryHints = (context.memoryHints + catalog.plannerHint()).takeLast(40)
        )
        return catalog.filterPlannerContext(withCatalog)
    }

    private fun normalize(result: PlannerResult): PlannerResult {
        if (result !is PlannerResult.Proposed) return result
        val graph = result.proposal.graph
        val normalizedNodes = buildList {
            for (node in graph.nodes) {
                if (node.action.type == UniversalActionType.BROWSER_NAVIGATE) {
                    val verification = if (node.verification.mode == VerificationMode.URI_PREFIX) {
                        node.verification.copy(
                            mode = VerificationMode.ACTION_ACCEPTED,
                            expected = null,
                            description = "Android accepted browser navigation intent"
                        )
                    } else node.verification
                    add(node.copy(verification = verification))
                    continue
                }
                if (node.action.type != UniversalActionType.OPEN_APP) {
                    add(node)
                    continue
                }
                val rawTarget = node.action.target?.trim().orEmpty()
                val resolved = catalog.resolve(rawTarget)
                    ?: return PlannerResult.Rejected(
                        PlannerFailure(
                            code = "APP_NOT_RESOLVED",
                            message = "Installed Android app could not be resolved: $rawTarget",
                            retryable = true
                        )
                    )
                val verification = if (node.verification.mode == VerificationMode.APP) {
                    node.verification.copy(expected = resolved)
                } else node.verification
                add(
                    node.copy(
                        action = node.action.copy(target = resolved),
                        targetScope = resolved,
                        verification = verification
                    )
                )
            }
        }
        return PlannerResult.Proposed(
            result.proposal.copy(graph = graph.copy(nodes = normalizedNodes))
        )
    }
}
