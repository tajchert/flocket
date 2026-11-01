package com.mtajchert.flocket.core.terminal.engine

/** Creates [TerminalEngine]s. Production binds the libghostty factory; tests bind a fake. */
fun interface TerminalEngineFactory {
    fun create(
        cols: Int,
        rows: Int,
    ): TerminalEngine
}
