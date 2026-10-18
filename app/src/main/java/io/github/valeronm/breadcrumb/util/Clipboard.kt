package io.github.valeronm.breadcrumb.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService

/** Calls [onCopied] only below Android 13, which confirms a copy with its own overlay. */
internal fun Context.copyToClipboard(text: String, onCopied: () -> Unit) {
    getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText(text, text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) onCopied()
}
