#include "include/ts_demuxer.h"
#include <android/log.h>
#include <cstring>

#define LOG_TAG "MiracastTS"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace miracast {

TSDemuxer::TSDemuxer(H264Callback video_callback, AACCallback audio_callback)
    : video_callback_(std::move(video_callback))
    , audio_callback_(std::move(audio_callback))
    , pmt_pid_(0)
    , video_pid_(0)
    , audio_pid_(0)
    , audio_stream_type_(0)
{
}

void TSDemuxer::processTSPacket(const uint8_t* data, size_t size) {
    if (size != TS_PACKET_SIZE) {
        return;
    }

    // TS sync byte
    if (data[0] != 0x47) {
        LOGE("Invalid TS sync byte: 0x%02X", data[0]);
        return;
    }

    // Parse TS header
    bool transport_error = (data[1] & 0x80) != 0;
    bool payload_start = (data[1] & 0x40) != 0;
    bool priority = (data[1] & 0x20) != 0;
    uint16_t pid = ((static_cast<uint16_t>(data[1]) & 0x1F) << 8) | data[2];

    uint8_t scrambling = (data[3] >> 6) & 0x03;
    bool has_adaptation = (data[3] & 0x20) != 0;
    bool has_payload = (data[3] & 0x10) != 0;
    uint8_t continuity = data[3] & 0x0F;

    if (transport_error || !has_payload || scrambling != 0) {
        return;
    }

    size_t offset = 4;

    // Skip adaptation field if present
    if (has_adaptation) {
        if (offset >= size) return;
        uint8_t adaptation_length = data[offset];
        offset += 1 + adaptation_length;
        if (offset >= size) return;
    }

    // Process payload based on PID
    const uint8_t* payload = data + offset;
    size_t payload_size = size - offset;

    if (pid == PAT_PID) {
        processPAT(payload, payload_size);
    } else if (pid == pmt_pid_ && pmt_pid_ != 0) {
        processPMT(payload, payload_size);
    } else if (pid == video_pid_ && video_pid_ != 0) {
        processPES(payload, payload_size, pid);
    } else if (pid == audio_pid_ && audio_pid_ != 0) {
        processPES(payload, payload_size, pid);
    }
}

void TSDemuxer::processPAT(const uint8_t* data, size_t size) {
    // Skip pointer field if present
    size_t offset = 0;
    if (size > 0 && data[0] != 0) {
        offset = 1 + data[0];
    } else if (size > 0) {
        offset = 1;
    }

    if (offset + 8 > size) return;

    // Parse PAT header
    uint8_t table_id = data[offset];
    if (table_id != 0x00) return; // Not PAT

    uint16_t section_length = ((static_cast<uint16_t>(data[offset + 1]) & 0x0F) << 8) | data[offset + 2];
    offset += 8; // Skip to program entries

    // Parse program entries
    size_t programs_end = offset + section_length - 9; // Exclude header and CRC
    while (offset + 4 <= programs_end) {
        uint16_t program_number = (static_cast<uint16_t>(data[offset]) << 8) | data[offset + 1];
        uint16_t program_pid = ((static_cast<uint16_t>(data[offset + 2]) & 0x1F) << 8) | data[offset + 3];

        if (program_number != 0) {
            // Found PMT PID
            if (pmt_pid_ != program_pid) {
                pmt_pid_ = program_pid;
                LOGD("Found PMT PID: 0x%04X", pmt_pid_);
            }
            break;
        }

        offset += 4;
    }
}

void TSDemuxer::processPMT(const uint8_t* data, size_t size) {
    // Skip pointer field
    size_t offset = 0;
    if (size > 0 && data[0] != 0) {
        offset = 1 + data[0];
    } else if (size > 0) {
        offset = 1;
    }

    if (offset + 12 > size) return;

    // Parse PMT header
    uint8_t table_id = data[offset];
    if (table_id != 0x02) return; // Not PMT

    uint16_t section_length = ((static_cast<uint16_t>(data[offset + 1]) & 0x0F) << 8) | data[offset + 2];
    uint16_t program_info_length = ((static_cast<uint16_t>(data[offset + 10]) & 0x0F) << 8) | data[offset + 11];

    offset += 12 + program_info_length;

    // Parse stream entries
    size_t streams_end = offset + section_length - 13 - program_info_length; // Exclude CRC
    while (offset + 5 <= streams_end) {
        uint8_t stream_type = data[offset];
        uint16_t elementary_pid = ((static_cast<uint16_t>(data[offset + 1]) & 0x1F) << 8) | data[offset + 2];
        uint16_t es_info_length = ((static_cast<uint16_t>(data[offset + 3]) & 0x0F) << 8) | data[offset + 4];

        // Check for H.264 video stream (type 0x1B or 0x24)
        if ((stream_type == 0x1B || stream_type == 0x24) && video_pid_ == 0) {
            video_pid_ = elementary_pid;
            LOGD("Found H.264 video PID: 0x%04X (stream_type: 0x%02X)", video_pid_, stream_type);
        }

        // Check for audio stream (AAC ADTS 0x0F, AAC LATM 0x11, LPCM 0x83)
        if ((stream_type == STREAM_TYPE_AAC_ADTS ||
             stream_type == STREAM_TYPE_AAC_LATM ||
             stream_type == STREAM_TYPE_LPCM) && audio_pid_ == 0) {
            audio_pid_ = elementary_pid;
            audio_stream_type_ = stream_type;
            LOGD("Found audio PID: 0x%04X (stream_type: 0x%02X)", audio_pid_, stream_type);
        }

        offset += 5 + es_info_length;
    }
}

void TSDemuxer::processPES(const uint8_t* data, size_t size, uint16_t pid) {
    auto& pes_buffer = pes_buffers_[pid];

    // Check if this is a new PES packet (starts with 0x000001)
    if (size >= 6 && data[0] == 0x00 && data[1] == 0x00 && data[2] == 0x01) {
        // Flush previous PES if exists
        if (!pes_buffer.data.empty()) {
            if (pid == video_pid_) {
                flushPES(pid);
            } else if (pid == audio_pid_) {
                flushAudioPES(pid);
            }
        }

        // Start new PES
        pes_buffer.data.clear();
        pes_buffer.has_pts = false;

        uint8_t stream_id = data[3];
        uint16_t pes_length = (static_cast<uint16_t>(data[4]) << 8) | data[5];

        size_t offset = 6;

        // Parse optional PES header for video OR audio streams
        // Video stream IDs: 0xE0-0xEF, Audio stream IDs: 0xC0-0xDF
        if ((stream_id & 0xE0) == 0xE0 || (stream_id & 0xE0) == 0xC0) {
            if (size >= offset + 3) {
                uint8_t pts_dts_flags = (data[offset + 1] >> 6) & 0x03;
                uint8_t pes_header_length = data[offset + 2];

                offset += 3;

                // Parse PTS if present
                if ((pts_dts_flags & 0x02) && size >= offset + 5) {
                    pes_buffer.pts = parsePTS(data + offset);
                    pes_buffer.has_pts = true;
                }

                offset += pes_header_length;
            }
        }

        // Append PES payload
        if (offset < size) {
            pes_buffer.data.insert(pes_buffer.data.end(), data + offset, data + size);
        }
    } else {
        // Continuation of PES packet
        if (pes_buffer.data.size() + size < MAX_PES_SIZE) {
            pes_buffer.data.insert(pes_buffer.data.end(), data, data + size);
        } else {
            LOGE("PES buffer overflow, discarding");
            pes_buffer.data.clear();
        }
    }
}

void TSDemuxer::flushPES(uint16_t pid) {
    auto it = pes_buffers_.find(pid);
    if (it == pes_buffers_.end() || it->second.data.empty()) {
        return;
    }

    auto& pes = it->second;

    // Check for H.264 NAL unit (starts with 0x000001 or 0x00000001)
    const uint8_t* nal_data = pes.data.data();
    size_t nal_size = pes.data.size();

    // Find NAL start code
    size_t start_offset = 0;
    if (nal_size >= 4 && nal_data[0] == 0 && nal_data[1] == 0 && nal_data[2] == 0 && nal_data[3] == 1) {
        start_offset = 4;
    } else if (nal_size >= 3 && nal_data[0] == 0 && nal_data[1] == 0 && nal_data[2] == 1) {
        start_offset = 3;
    }

    if (start_offset > 0 && start_offset < nal_size) {
        uint8_t nal_type = nal_data[start_offset] & 0x1F;
        bool is_keyframe = (nal_type == 5); // IDR slice

        uint64_t pts_us = pes.has_pts ? pes.pts : 0;

        // Call callback with H.264 NAL unit
        if (video_callback_) {
            video_callback_(nal_data, nal_size, pts_us, is_keyframe);
        }
    }

    pes.data.clear();
}

void TSDemuxer::flushAudioPES(uint16_t pid) {
    auto it = pes_buffers_.find(pid);
    if (it == pes_buffers_.end() || it->second.data.empty()) {
        return;
    }

    auto& pes = it->second;

    if (!audio_callback_) {
        pes.data.clear();
        return;
    }

    const uint8_t* audio_data = pes.data.data();
    size_t audio_size = pes.data.size();
    uint64_t pts_us = pes.has_pts ? pes.pts : 0;

    if (audio_size > 0) {
        audio_callback_(audio_data, audio_size, pts_us);
    }

    pes.data.clear();
}

uint64_t TSDemuxer::parsePTS(const uint8_t* data) {
    // PTS is 33-bit value encoded in 5 bytes
    uint64_t pts = 0;
    pts |= (static_cast<uint64_t>(data[0]) & 0x0E) << 29;
    pts |= (static_cast<uint64_t>(data[1])) << 22;
    pts |= (static_cast<uint64_t>(data[2]) & 0xFE) << 14;
    pts |= (static_cast<uint64_t>(data[3])) << 7;
    pts |= (static_cast<uint64_t>(data[4]) >> 1);

    // Convert from 90kHz to microseconds
    return (pts * 1000000ULL) / 90000ULL;
}

void TSDemuxer::reset() {
    pmt_pid_ = 0;
    video_pid_ = 0;
    audio_pid_ = 0;
    audio_stream_type_ = 0;
    pes_buffers_.clear();
    LOGD("TSDemuxer reset");
}

} // namespace miracast
