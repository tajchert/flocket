package com.mtajchert.flocket.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Android library module: SDK levels, Java 17, Kover and unit-test configuration (incl. Robolectric flags). */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("org.jetbrains.kotlinx.kover")
            pluginManager.apply(ModuleBoundaryConventionPlugin::class.java)

            extensions.configure<LibraryExtension> {
                namespace = moduleNamespace()
                configureAndroidCommon(this)
            }
            addUnitTestDependencies()
        }
}
