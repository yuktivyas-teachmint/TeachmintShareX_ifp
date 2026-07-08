#ifndef MIRACAST_RTP_RECEIVER_H
#define MIRACAST_RTP_RECEIVER_H

#include <cstdint>
#include <functional>
#include <memory>
#include <atomic>
#include <thread>

namespace miracast {

/**
 * Native RTP receiver for Miracast streaming
 * Receives UDP/RTP packets and extracts MPEG-TS payload
 */
class RTPReceiver {
public:
    // Callback for received TS packets: (data, size, timestamp)
    using TSPacketCallback = std::function<void(const uint8_t*, size_t, uint64_t)>;

    /**
     * Create RTP receiver
     * @param port UDP port to listen on
     * @param callback Callback for received TS packets
     */
    RTPReceiver(int port, TSPacketCallback callback);
    ~RTPReceiver();

    // Disable copy/move
    RTPReceiver(const RTPReceiver&) = delete;
    RTPReceiver& operator=(const RTPReceiver&) = delete;

    /**
     * Start receiving RTP packets
     * @return true on success
     */
    bool start();

    /**
     * Stop receiving
     */
    void stop();

    /**
     * Check if receiver is running
     */
    bool isRunning() const { return running_.load(); }

    /**
     * Get number of packets received
     */
    uint64_t getPacketCount() const { return packet_count_.load(); }

    /**
     * Get number of bytes received
     */
    uint64_t getBytesReceived() const { return bytes_received_.load(); }

private:
    void receiveLoop();
    bool parseRTPPacket(const uint8_t* data, size_t size,
                       const uint8_t*& payload, size_t& payload_size,
                       uint32_t& timestamp);

    int socket_fd_;
    int port_;
    TSPacketCallback callback_;
    std::atomic<bool> running_;
    std::atomic<uint64_t> packet_count_;
    std::atomic<uint64_t> bytes_received_;
    std::unique_ptr<std::thread> receive_thread_;
};

} // namespace miracast

#endif // MIRACAST_RTP_RECEIVER_H
