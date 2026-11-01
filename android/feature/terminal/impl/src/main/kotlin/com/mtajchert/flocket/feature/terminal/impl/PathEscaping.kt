package com.mtajchert.flocket.feature.terminal.impl

private const val SHELL_SPECIAL = " \t\"'\\$`()&;|<>*?[]{},!"

/** Backslash-escapes characters a shell or agent prompt would split on, like a dropped path. */
internal fun escapeForShell(path: String): String =
    buildString {
        for (c in path) {
            if (c in SHELL_SPECIAL) append('\\')
            append(c)
        }
    }
