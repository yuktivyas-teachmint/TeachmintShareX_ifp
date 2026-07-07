#include "include/rtp_receiver.h"
#include <android/log.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <thread>
#include <cstring>

#define LOG_TAG "MiracastRTP"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace miracast {

RTPReceiver::RTPReceiver(int port, TSPacketCallback callback)
    : socket_fd_(-1)
    , port_(port)
    , callback_(std::move(callback))
    , running_(false)
    , packet_count_(0)
    , bytes_received_(0)
{
    LOGD("RTPReceiver created for port %d", port);
}

RTPReceiver::~RTPReceiver() {
    stop();
}

bool RTPReceiver::start() {
    if (running_.load()) {
        LOGD("RTPReceiver already running");
        return true;
    }

    // Create UDP socket
    socket_fd_ = socket(AF_INET, SOCK_DGRAM, 0);
    if (socket_fd_ < 0) {
        LOGE("Failed to create UDP socket: %s", strerror(errno));
        return false;
    }

    // Set socket options
    int reuse = 1;
    if (setsockopt(socket_fd_, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse)) < 0) {
        LOGE("Failed to set SO_REUSEADDR: %s", strerror(errno));
    }

    // Increase receive buffer size for high throughput
    int rcvbuf_size = 4 * 1024 * 1024; // 4MB
    if (setsockopt(socket_fd_, SOL_SOCKET, SO_RCVBUF, &rcvbuf_size, sizeof(rcvbuf_size)) < 0) {
        LOGE("Failed to set SO_RCVBUF: %s", strerror(errno));
    }

    // Set timeout for receive to allow periodic checking of running flag
    struct timeval tv;
    tv.tv_sec = 1;
    tv.tv_usec = 0;
    if (setsockopt(socket_fd_, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv)) < 0) {
        LOGE("Failed to set SO_RCVTIMEO: %s", strerror(errno));
    }

    // Bind to port
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(port_);

    if (bind(socket_fd_, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        LOGE("Failed to bind to port %d: %s", port_, strerror(errno));
        close(socket_fd_);
        socket_fd_ = -1;
        return false;
    }

    LOGD("RTPReceiver bound to port %d", port_);

    // Start receive thread
    running_.store(true);
    receive_thread_ = std::make_unique<std::thread>(&RTPReceiver::receiveLoop, this);

    return true;
}

void RTPReceiver::stop() {
    if (!running_.load()) {
        return;
    }

    LOGD("Stopping RTPReceiver");
    running_.store(false);

    if (receive_thread_ && receive_thread_->joinable()) {
        receive_thread_->join();
    }

    if (socket_fd_ >= 0) {
        close(socket_fd_);
        socket_fd_ = -1;
    }

    LOGD("RTPReceiver stopped. Packets: %llu, Bytes: %llu",
         (unsigned long long)packet_count_.load(),
         (unsigned long long)bytes_received_.load());
}

void RTPReceiver::receiveLoop() {
    constexpr size_t MAX_PACKET_SIZE = 65536;
    auto buffer = std::make_unique<uint8_t[]>(MAX_PACKET_SIZE);

    LOGD("RTPReceiver loop started");

    while (running_.load()) {
        ssize_t received = recvfrom(socket_fd_, buffer.get(), MAX_PACKET_SIZE, 0, nullptr, nullptr);

        if (received < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                // Timeout - check running flag and continue
                continue;
            }
            LOGE("recvfrom error: %s", strerror(errno));
            break;
        }

        if (received == 0) {
            continue;
        }

        // Parse RTP packet
        const uint8_t* payload;
        size_t payload_size;
        uint32_t timestamp;

        if (parseRTPPacket(buffer.get(), received, payload, payload_size, timestamp)) {
            // Update stats
            packet_count_++;
            bytes_received_ += received;

            // Convert RTP timestamp (90kHz) to microseconds
            uint64_t timestamp_us = (static_cast<uint64_t>(timestamp) * 1000000ULL) / 90000ULL;

            // Call callback with TS payload
            if (callback_ && payload_size > 0) {
                callback_(payload, payload_size, timestamp_us);
            }
        }
    }

    LOGD("RTPReceiver loop ended");
}

bool RTPReceiver::parseRTPPacket(const uint8_t* data, size_t size,
                                 const uint8_t*& payload, size_t& payload_size,
                                 uint32_t& timestamp) {
    // RTP header: minimum 12 bytes
    if (size < 12) {
        return false;
    }

    // Parse RTP header
    uint8_t byte0 = data[0];
    uint8_t version = (byte0 >> 6) & 0x03;
    bool padding = (byte0 >> 5) & 0x01;
    bool extension = (byte0 >> 4) & 0x01;
    uint8_t csrc_count = byte0 & 0x0F;

    // Verify RTP version 2
    if (version != 2) {
        LOGE("Invalid RTP version: %d", version);
        return false;
    }

    // Calculate header size
    size_t header_size = 12 + (csrc_count * 4);
    if (size < header_size) {
        return false;
    }

    // Extract timestamp (32-bit, 90kHz for video)
    timestamp = (static_cast<uint32_t>(data[4]) << 24) |
                (static_cast<uint32_t>(data[5]) << 16) |
                (static_cast<uint32_t>(data[6]) << 8) |
                (static_cast<uint32_t>(data[7]));

    // Skip RTP header and CSRC identifiers
    size_t offset = header_size;

    // Handle extension header if present
    if (extension) {
        if (size < offset + 4) {
            return false;
        }
        uint16_t ext_length = (static_cast<uint16_t>(data[offset + 2]) << 8) |
                              static_cast<uint16_t>(data[offset + 3]);
        offset += 4 + (ext_length * 4);

        if (size < offset) {
            return false;
        }
    }

    // Handle padding if present
    if (padding) {
        if (size <= offset) {
            return false;
        }
        uint8_t pad_length = data[size - 1];
        if (pad_length > (size - offset)) {
            return false;
        }
        size -= pad_length;
    }

    // Extract payload
    payload = data + offset;
    payload_size = size - offset;

    return true;
}

} // namespace miracast
