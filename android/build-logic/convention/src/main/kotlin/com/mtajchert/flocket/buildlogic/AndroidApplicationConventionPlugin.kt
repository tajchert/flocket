package com.mtajchert.flocket.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Application module: SDK levels, Compose, Hilt, R8 for release, Kover. The module sets its own namespace/applicationId. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("org.jetbrains.kotlinx.kover")
            pluginManager.apply("flocket.hilt")
            pluginManager.apply(ModuleBoundaryConventionPlugin::class.java)

            extensions.configure<ApplicationExtension> {
                configureAndroidCommon(this)
                defaultConfig {
                    targetSdk = versionInt("targetSdk")
                    versionCode = 4
                    versionName = "0.1.3"
                    vectorDrawables.useSupportLibrary = true
                }
                buildTypes {
                    release {
                        isMinifyEnabled = true
                        isShrinkResources = true
                        proguardFiles(
                            getDefaultProguardFile("proguard-android-optimize.txt"),
                            "proguard-rules.pro",
                        )
                    }
                    // Same R8 setup as release but signed with the debug key, for macrobenchmarks.
                    create("benchmarkRelease") {
                        initWith(getByName("release"))
                        signingConfig = signingConfigs.getByName("debug")
                    }
                    create("nonMinifiedRelease") {
                        signingConfig = signingConfigs.getByName("debug")
                    }
                    debug {
                        isDebuggable = true
                        applicationIdSuffix = ".debug"
                    }
                }
                buildFeatures.buildConfig = true
                packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
                configureCompose(this, screenshot = false)
            }
            addUnitTestDependencies()
            addDependency("testImplementation", lib("hilt-android-testing"))
            addDependency("kspTest", lib("hilt-compiler"))
        }
}
