package com.mtajchert.flocket.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

internal const val PACKAGE_NAME = "com.mtajchert.flocket"

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStartupWithoutProfile() = measureStartup(CompilationMode.None())

    @Test
    fun coldStartupWithProfile() =
        measureStartup(
            CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
        )

    private fun measureStartup(compilationMode: CompilationMode) =
        rule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.COLD,
            iterations = 5,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
        }
}
