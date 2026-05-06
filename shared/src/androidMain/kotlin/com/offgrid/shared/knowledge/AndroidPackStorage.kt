package com.offgrid.shared.knowledge

import android.content.Context
import java.io.File

/** Same root for downloaded ZIPs and imported pack dirs (never split paths). */
fun androidPacksRoot(context: Context): File {
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    return File(base, "packs").apply { mkdirs() }
}
