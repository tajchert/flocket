package com.mtajchert.flocket.journey

/** Marks the quick subset of the device journeys (`scripts/e2e-device.sh journey --smoke`). */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class Smoke
