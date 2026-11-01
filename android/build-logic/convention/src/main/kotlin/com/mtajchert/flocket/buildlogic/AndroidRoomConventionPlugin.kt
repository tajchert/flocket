package com.mtajchert.flocket.buildlogic

import androidx.room3.gradle.RoomExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Room 3 with KSP; schemas are exported to `<module>/schemas`. */
class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("androidx.room3")
            extensions.configure<RoomExtension> {
                schemaDirectory("$projectDir/schemas")
            }
            addDependency("api", bundle("room"))
            addDependency("implementation", lib("sqlite-framework"))
            addDependency("ksp", lib("room-compiler"))
        }
}
