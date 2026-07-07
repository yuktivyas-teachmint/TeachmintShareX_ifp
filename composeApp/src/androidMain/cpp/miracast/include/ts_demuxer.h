#ifndef MIRACAST_TS_DEMUXER_H
#define MIRACAST_TS_DEMUXER_H

#include <cstdint>
#include <functional>
#include <vector>
#include <map>

namespace miracast {

/**
 * MPEG-TS demultiplexer
 * Extracts H.264 video and AAC audio from MPEG-TS packets
 */
class TSDemuxer {
public:
    // Callback for H.264 NAL units: (data, size, pts_us, is_keyframe)
    using H264Callback = std::function<void(const uint8_t*, size_t, uint64_t, bool)>;

    // Callback for AAC audio frames: (data, size, pts_us)
    using AACCallback = std::function<void(const uint8_t*, size_t, uint64_t)>;

    explicit TSDemuxer(H264Callback video_callback, AACCallback audio_callback = nullptr);
    ~TSDemuxer() = default;

    /**
     * Process TS packet (188 bytes)
     * @param data TS packet data
     * @param size Should be 188 bytes
     */
    void processTSPacket(const uint8_t* data, size_t size);

    /**
     * Reset demuxer state
     */
    void reset();

private:
    static constexpr size_t TS_PACKET_SIZE = 188;
    static constexpr uint16_t PAT_PID = 0x0000;
    static constexpr size_t MAX_PES_SIZE = 2 * 1024 * 1024; // 2MB

    // AAC stream types in MPEG-TS PMT
    static constexpr uint8_t STREAM_TYPE_AAC_ADTS = 0x0F;  // ISO/IEC 13818-7 (AAC ADTS)
    static constexpr uint8_t STREAM_TYPE_AAC_LATM = 0x11;  // ISO/IEC 14496-3 (AAC LATM)
    static constexpr uint8_t STREAM_TYPE_LPCM     = 0x83;  // LPCM (some WFD sources)

    struct PESBuffer {
        std::vector<uint8_t> data;
        uint64_t pts;
        bool has_pts;
    };

    void processPAT(const uint8_t* data, size_t size);
    void processPMT(const uint8_t* data, size_t size);
    void processPES(const uint8_t* data, size_t size, uint16_t pid);
    void flushPES(uint16_t pid);
    void flushAudioPES(uint16_t pid);
    uint64_t parsePTS(const uint8_t* data);

    H264Callback video_callback_;
    AACCallback audio_callback_;
    uint16_t pmt_pid_;
    uint16_t video_pid_;
    uint16_t audio_pid_;
    uint8_t audio_stream_type_;
    std::map<uint16_t, PESBuffer> pes_buffers_;
};

} // namespace miracast

#endif // MIRACAST_TS_DEMUXER_H
