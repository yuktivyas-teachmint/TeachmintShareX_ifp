package com.teachmint.sharex.share.shared

/**
 * Controls client orientation for reverse mirroring on mobile platforms.
 * Default behavior should stay portrait; when enabled, switch to landscape.
 */
expect fun setClientReverseOrientationEnabled(enabled: Boolean)
