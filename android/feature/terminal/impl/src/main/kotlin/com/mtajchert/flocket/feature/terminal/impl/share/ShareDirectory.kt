package com.mtajchert.flocket.feature.terminal.impl.share

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/** `cacheDir/shares`: where downloaded shares live (the FileProvider's `shares` path). */
class ShareDirectory(
    val root: File,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(File(context.cacheDir, ShareFiles.DIRECTORY))
}
