package com.rawmechatronics.easyscanner

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Pads [view] clear of the system bars. Targeting Android 15+ draws every
 * window edge to edge, so without this the content starts under the status
 * bar; AppCompat already counts its action bar into the top inset it passes on.
 */
fun padBelowBars(view: View) {
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
}
