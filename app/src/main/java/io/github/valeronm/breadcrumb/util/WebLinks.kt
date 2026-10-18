package io.github.valeronm.breadcrumb.util

import android.content.Context
import androidx.core.net.toUri
import io.github.valeronm.breadcrumb.BuildConfig

internal object WebLinks {
    const val SITE = "https://breadcrumb.place/"

    // Play's listing points at this page as the privacy policy, so its URL is fixed.
    const val PRIVACY_POLICY = "https://breadcrumb.place/privacy/"
    const val VIEWER = "https://breadcrumb.place/viewer/"
    const val SOURCE = "https://github.com/valeronm/breadcrumb"
    const val PLAY = "https://play.google.com/store/apps/details?id=${BuildConfig.PLAY_PACKAGE}"
}

internal fun Context.openInBrowser(url: String, onNoApp: () -> Unit) = viewUri(url.toUri(), onNoApp)
