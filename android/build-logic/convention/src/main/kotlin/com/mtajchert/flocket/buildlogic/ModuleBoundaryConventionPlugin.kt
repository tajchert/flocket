package com.mtajchert.flocket.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

/**
 * Enforces the module dependency rules from the architecture (docs/ARCHITECTURE.md):
 *
 * 1. `:feature:X:impl` never depends on another feature's `:impl` (only on `:api` modules).
 * 2. `:core:*` never depends on any `:feature:*`.
 * 3. `:feature:*:api` never depends on any `:impl`.
 *
 * The check runs while the project's configuration is being evaluated, so a violation fails the
 * build immediately and the Gradle configuration cache is still valid (nothing runs at
 * execution time, and a changed dependency invalidates the cache entry anyway).
 * Every convention plugin applies this plugin; applying it twice is a no-op.
 */
class ModuleBoundaryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.afterEvaluate {
            val violations = mutableListOf<String>()
            configurations.forEach { configuration ->
                configuration.dependencies.withType(ProjectDependency::class.java).forEach { dependency ->
                    violation(path, dependency.path)?.let {
                        violations += "$it (declared in configuration '${configuration.name}')"
                    }
                }
            }
            if (violations.isNotEmpty()) {
                throw GradleException(
                    "Module boundary violation(s) in $path:\n" +
                        violations.distinct().joinToString("\n") { " - $it" } +
                        "\nSee android/docs/ARCHITECTURE.md (layer rules).",
                )
            }
        }
    }

    internal companion object {
        private val featureImpl = Regex("""^:feature:([^:]+):impl$""")
        private val featureApi = Regex("""^:feature:([^:]+):api$""")

        /** Returns a human-readable violation for `from -> to`, or null when the edge is allowed. */
        fun violation(
            from: String,
            to: String,
        ): String? {
            val fromImpl = featureImpl.matchEntire(from)
            val toImpl = featureImpl.matchEntire(to)
            return when {
                fromImpl != null && toImpl != null && fromImpl.groupValues[1] != toImpl.groupValues[1] -> {
                    "feature impl $from must not depend on another feature's impl $to; depend on its :api instead"
                }

                from.startsWith(":core:") && to.startsWith(":feature:") -> {
                    "core module $from must not depend on feature module $to"
                }

                featureApi.matches(from) && to.endsWith(":impl") -> {
                    "feature api $from must not depend on an impl module ($to)"
                }

                else -> {
                    null
                }
            }
        }
    }
}
