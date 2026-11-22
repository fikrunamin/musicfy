package com.example.musicfy.constants

import android.os.Build

/**
 * Cover style to use when the user has not picked one.
 *
 * Edge-to-edge art leans on the backdrop blur behind it to separate the cover from the controls,
 * and that blur needs API 31. Below that the full-bleed art runs straight under the chrome with
 * nothing softening it, so squared reads far better there. This is only the *default* - an
 * explicit choice in Player customise still wins on every device.
 */
val DefaultPlayerCoverStyle: PlayerCoverStyle
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PlayerCoverStyle.EDGE_TO_EDGE
    } else {
        PlayerCoverStyle.SQUARED
    }
