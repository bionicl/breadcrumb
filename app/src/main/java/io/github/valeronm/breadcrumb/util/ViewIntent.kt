package io.github.valeronm.breadcrumb.util

import android.content.Context
import android.content.Intent
import android.net.Uri

/** A device with no app for [uri] gets [onNoApp] rather than the exception `startActivity` throws. */
internal fun Context.viewUri(uri: Uri, onNoApp: () -> Unit) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure { onNoApp() }
}
