plugins {
    `kotlin-dsl`
}

group = "com.mtajchert.flocket.buildlogic"

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Compile-only: the plugins themselves are put on the classpath by the root build
    // (`alias(libs.plugins.x) apply false`), and convention plugins apply them by id.
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.room.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = "flocket.jvm.library"
            implementationClass = "com.mtajchert.flocket.buildlogic.JvmLibraryConventionPlugin"
        }
        register("androidLibrary") {
            id = "flocket.android.library"
            implementationClass = "com.mtajchert.flocket.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "flocket.android.compose"
            implementationClass = "com.mtajchert.flocket.buildlogic.AndroidComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "flocket.android.feature"
            implementationClass = "com.mtajchert.flocket.buildlogic.AndroidFeatureConventionPlugin"
        }
        register("androidApplication") {
            id = "flocket.android.application"
            implementationClass = "com.mtajchert.flocket.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidRoom") {
            id = "flocket.android.room"
            implementationClass = "com.mtajchert.flocket.buildlogic.AndroidRoomConventionPlugin"
        }
        register("hilt") {
            id = "flocket.hilt"
            implementationClass = "com.mtajchert.flocket.buildlogic.HiltConventionPlugin"
        }
    }
}
