package com.teachmint.sharex.share.shared

expect object ScreenShareBackgroundExecution {
    fun begin(reason: String = "screen-share")
    fun end()
}
