#include <cstdint>
#include <android/log.h>

#define LOG_TAG "RTPParser"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace miracast {

// Additional RTP parsing utilities can be added here
// Currently most RTP parsing is handled in rtp_receiver.cpp

/**
 * Validate RTP packet header
 */
bool validateRTPHeader(const uint8_t* data, size_t size) {
    if (size < 12) {
        return false;
    }

    uint8_t version = (data[0] >> 6) & 0x03;
    return version == 2;
}

/**
 * Extract RTP sequence number
 */
uint16_t getRTPSequenceNumber(const uint8_t* data) {
    return (static_cast<uint16_t>(data[2]) << 8) | data[3];
}

/**
 * Extract RTP SSRC
 */
uint32_t getRTPSSRC(const uint8_t* data) {
    return (static_cast<uint32_t>(data[8]) << 24) |
           (static_cast<uint32_t>(data[9]) << 16) |
           (static_cast<uint32_t>(data[10]) << 8) |
           (static_cast<uint32_t>(data[11]));
}

} // namespace miracast
