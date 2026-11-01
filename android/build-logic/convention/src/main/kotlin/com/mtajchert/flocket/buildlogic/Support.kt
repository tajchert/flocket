package com.mtajchert.flocket.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.add
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

internal const val ROOT_PACKAGE = "com.mtajchert.flocket"

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun Project.lib(alias: String) = libs.findLibrary(alias).get()

internal fun Project.bundle(alias: String) = libs.findBundle(alias).get()

internal fun Project.versionInt(alias: String): Int =
    libs
        .findVersion(alias)
        .get()
        .requiredVersion
        .toInt()

internal fun Project.addDependency(
    configuration: String,
    notation: Any,
) {
    dependencies.add(configuration, notation)
}

/** `:core:terminal-ghostty` -> `com.mtajchert.flocket.core.terminal.ghostty`. */
internal fun Project.moduleNamespace(): String = ROOT_PACKAGE + path.replace("-", "").replace(':', '.')

internal val JAVA_VERSION = JavaVersion.VERSION_17

/** SDK levels, Java level and unit-test settings shared by every Android module type. */
internal fun Project.configureAndroidCommon(extension: CommonExtension) {
    extension.compileSdk = versionInt("compileSdk")
    extension.defaultConfig.minSdk = versionInt("minSdk")
    extension.defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    extension.compileOptions.sourceCompatibility = JAVA_VERSION
    extension.compileOptions.targetCompatibility = JAVA_VERSION
    // Robolectric needs merged resources and manifests.
    extension.testOptions.unitTests.isIncludeAndroidResources = true
    configureTestJvm()
}

/** Robolectric reflects into the JDK; these flags keep it working on JDK 21. */
internal fun Project.configureTestJvm() {
    tasks.withType<Test>().configureEach {
        // Skeleton modules have no tests yet; an empty test task is fine.
        failOnNoDiscoveredTests.set(false)
        jvmArgs(
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        )
    }
}

internal fun Project.addUnitTestDependencies() {
    addDependency("testImplementation", bundle("testing"))
}

/**
 * Compose compiler, BOM, bundles, debug tooling and Robolectric Compose test dependencies.
 * [screenshot] also enables Compose Preview Screenshot Testing for library modules.
 */
internal fun Project.configureCompose(
    extension: CommonExtension,
    screenshot: Boolean,
) {
    pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
    extension.buildFeatures.compose = true
    val bom = dependencies.platform(lib("androidx-compose-bom"))
    addDependency("implementation", bom)
    addDependency("implementation", bundle("compose"))
    addDependency("implementation", lib("androidx-compose-material-icons-extended"))
    addDependency("debugImplementation", bundle("compose-debug"))
    addDependency("testImplementation", bom)
    addDependency("testImplementation", bundle("testing-robolectric"))
    addDependency("testImplementation", lib("androidx-compose-ui-test-manifest"))
    if (screenshot) {
        pluginManager.apply("com.android.compose.screenshot")
        extension.experimentalProperties["android.experimental.enableScreenshotTest"] = true
        addDependency("screenshotTestImplementation", lib("screenshot-validation-api"))
        addDependency("screenshotTestImplementation", lib("androidx-compose-ui-tooling"))
    }
}
