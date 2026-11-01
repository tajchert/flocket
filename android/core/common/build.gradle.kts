// :core:common - AppResult/AppError, Clock and the Logger facade. May depend on:
// kotlinx-coroutines only (it is the bottom of the graph). No Android.
plugins {
    id("flocket.jvm.library")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
}
