// :feature:hosts:impl - Screens, ViewModels and navigation entries of the hosts feature. Only :app depends on
// it. May depend on: its own :api, other features' :api, :core:* (the feature plugin adds the defaults).
import javax.inject.Inject

plugins {
    id("flocket.android.feature")
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.session)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    testImplementation(projects.core.testing)
    screenshotTestImplementation(projects.core.testing)
    screenshotTestImplementation(projects.core.designsystem)
    screenshotTestImplementation(projects.core.ui)
    implementation(projects.feature.onboarding.api)
}

// The helper and the agent instruction live once, in protocol/tools/. They are copied (not committed) into a
// generated assets folder by a task that feeds every variant's assets, so the APK ships them byte for byte.
abstract class SyncFlocketShareAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun sync() {
        fileSystem.sync {
            from(sources)
            into(outputDir)
        }
    }
}

val syncFlocketShareAssets =
    tasks.register<SyncFlocketShareAssets>("syncFlocketShareAssets") {
        sources.from(
            rootProject.file("../protocol/tools/flocket-share"),
            rootProject.file("../protocol/tools/agent-instruction.md"),
        )
    }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncFlocketShareAssets, SyncFlocketShareAssets::outputDir)
    }
}
