package com.mtajchert.flocket.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/** Hilt with KSP. */
class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("com.google.dagger.hilt.android")
            addDependency("implementation", lib("hilt-android"))
            addDependency("ksp", lib("hilt-compiler"))
        }
}
