// :core:common - AppResult/AppError, dispatchers, Clock and the Logger facade. May depend on:
// :core:model, kotlinx-coroutines. No Android.
plugins {
    id("flocket.jvm.library")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
}
