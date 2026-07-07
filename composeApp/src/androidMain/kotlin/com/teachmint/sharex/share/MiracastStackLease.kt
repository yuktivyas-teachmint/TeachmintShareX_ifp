package com.teachmint.sharex.share.shared

/**
 * In-process ownership guard for Miracast socket binders.
 *
 * Background advertiser and foreground host controller run in the same app process,
 * and both stacks bind the same ports (RTSP/MS-MICE/HTTP). This lease ensures only
 * one stack owns those listeners at a time.
 */
object MiracastStackLease {
    const val OWNER_IN_PROCESS = "in_process_host"
    const val OWNER_BACKGROUND = "background_service"

    private val lock = Any()
    @Volatile
    private var owner: String? = null

    fun tryAcquire(requestedOwner: String): Boolean = synchronized(lock) {
        if (owner == null || owner == requestedOwner) {
            owner = requestedOwner
            true
        } else {
            false
        }
    }

    fun release(releasingOwner: String) = synchronized(lock) {
        if (owner == releasingOwner) {
            owner = null
        }
    }

    fun currentOwner(): String? = synchronized(lock) { owner }
}

