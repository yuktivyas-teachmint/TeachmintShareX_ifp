#include <jni.h>
#include <android/log.h>
#include <memory>
#include <map>
#include "include/rtp_receiver.h"
#include "include/ts_demuxer.h"

#define LOG_TAG "MiracastJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace miracast;

// Structure to hold receiver context
struct ReceiverContext {
    std::unique_ptr<RTPReceiver> receiver;
    std::unique_ptr<TSDemuxer> demuxer;
    JavaVM* jvm;
    jobject callback_obj; // Global reference to Kotlin callback
};

// Map of handles to receiver contexts
static std::map<jlong, std::unique_ptr<ReceiverContext>> receivers;
static jlong next_handle = 1;

// Helper to get JNIEnv for current thread
static JNIEnv* getJNIEnv(JavaVM* jvm) {
    JNIEnv* env = nullptr;
    if (jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        jvm->AttachCurrentThread(&env, nullptr);
    }
    return env;
}

extern "C" {

/**
 * Initialize native RTP receiver
 * @param env JNI environment
 * @param thiz Object reference
 * @param port UDP port to listen on
 * @param callback Kotlin callback object for receiving data
 * @return Handle to native receiver (0 on error)
 */
JNIEXPORT jlong JNICALL
Java_com_example_teachmintsharex_share_miracast_rtp_NativeRtpReceiver_nativeInit(
    JNIEnv* env, jobject thiz, jint port, jobject callback) {

    LOGD("nativeInit: port=%d", port);

    try {
        // Create receiver context
        auto context = std::make_unique<ReceiverContext>();

        // Get JavaVM
        if (env->GetJavaVM(&context->jvm) != JNI_OK) {
            LOGE("Failed to get JavaVM");
            return 0;
        }

        // Create global reference to callback object
        context->callback_obj = env->NewGlobalRef(callback);
        if (!context->callback_obj) {
            LOGE("Failed to create global reference to callback");
            return 0;
        }

        // Create TS demuxer with video AND audio callbacks
        auto video_callback = [context_ptr = context.get()](const uint8_t* data, size_t size, uint64_t pts_us, bool is_keyframe) {
            // Get JNI environment
            JNIEnv* env = getJNIEnv(context_ptr->jvm);
            if (!env) {
                LOGE("Failed to get JNIEnv in video callback");
                return;
            }

            // Find callback method
            jclass callback_class = env->GetObjectClass(context_ptr->callback_obj);
            jmethodID method = env->GetMethodID(callback_class, "onH264Data", "([BJIZ)V");

            if (!method) {
                LOGE("Failed to find onH264Data method");
                env->DeleteLocalRef(callback_class);
                return;
            }

            // Create Java byte array
            jbyteArray java_data = env->NewByteArray(size);
            if (java_data) {
                env->SetByteArrayRegion(java_data, 0, size, reinterpret_cast<const jbyte*>(data));

                // Call Kotlin callback
                env->CallVoidMethod(context_ptr->callback_obj, method,
                                  java_data, (jlong)pts_us, (jint)size, (jboolean)is_keyframe);

                env->DeleteLocalRef(java_data);
            }

            env->DeleteLocalRef(callback_class);
        };

        auto audio_callback = [context_ptr = context.get()](const uint8_t* data, size_t size, uint64_t pts_us) {
            // Get JNI environment
            JNIEnv* env = getJNIEnv(context_ptr->jvm);
            if (!env) {
                LOGE("Failed to get JNIEnv in audio callback");
                return;
            }

            // Find callback method
            jclass callback_class = env->GetObjectClass(context_ptr->callback_obj);
            jmethodID method = env->GetMethodID(callback_class, "onAudioData", "([BJ)V");

            if (!method) {
                // Audio callback not implemented on Kotlin side – silently skip
                env->DeleteLocalRef(callback_class);
                return;
            }

            // Create Java byte array
            jbyteArray java_data = env->NewByteArray(size);
            if (java_data) {
                env->SetByteArrayRegion(java_data, 0, size, reinterpret_cast<const jbyte*>(data));

                // Call Kotlin callback
                env->CallVoidMethod(context_ptr->callback_obj, method,
                                  java_data, (jlong)pts_us);

                env->DeleteLocalRef(java_data);
            }

            env->DeleteLocalRef(callback_class);
        };

        context->demuxer = std::make_unique<TSDemuxer>(video_callback, audio_callback);

        // Create RTP receiver with TS demuxer callback
        context->receiver = std::make_unique<RTPReceiver>(
            port,
            [demuxer = context->demuxer.get()](const uint8_t* data, size_t size, uint64_t timestamp) {
                // Process TS packets (188 bytes each)
                const size_t TS_PACKET_SIZE = 188;
                for (size_t offset = 0; offset + TS_PACKET_SIZE <= size; offset += TS_PACKET_SIZE) {
                    demuxer->processTSPacket(data + offset, TS_PACKET_SIZE);
                }
            }
        );

        // Store context with unique handle
        jlong handle = next_handle++;
        receivers[handle] = std::move(context);

        LOGD("nativeInit: created receiver with handle %lld", (long long)handle);
        return handle;

    } catch (const std::exception& e) {
        LOGE("Exception in nativeInit: %s", e.what());
        return 0;
    }
}

/**
 * Start receiving RTP packets
 */
JNIEXPORT jboolean JNICALL
Java_com_example_teachmintsharex_share_miracast_rtp_NativeRtpReceiver_nativeStart(
    JNIEnv* env, jobject thiz, jlong handle) {

    LOGD("nativeStart: handle=%lld", (long long)handle);

    auto it = receivers.find(handle);
    if (it == receivers.end()) {
        LOGE("Invalid handle: %lld", (long long)handle);
        return JNI_FALSE;
    }

    bool success = it->second->receiver->start();
    return success ? JNI_TRUE : JNI_FALSE;
}

/**
 * Stop receiving
 */
JNIEXPORT void JNICALL
Java_com_example_teachmintsharex_share_miracast_rtp_NativeRtpReceiver_nativeStop(
    JNIEnv* env, jobject thiz, jlong handle) {

    LOGD("nativeStop: handle=%lld", (long long)handle);

    auto it = receivers.find(handle);
    if (it != receivers.end()) {
        it->second->receiver->stop();
    }
}

/**
 * Check if receiver is running
 */
JNIEXPORT jboolean JNICALL
Java_com_example_teachmintsharex_share_miracast_rtp_NativeRtpReceiver_nativeIsRunning(
    JNIEnv* env, jobject thiz, jlong handle) {

    auto it = receivers.find(handle);
    if (it == receivers.end()) {
        return JNI_FALSE;
    }

    return it->second->receiver->isRunning() ? JNI_TRUE : JNI_FALSE;
}

/**
 * Get packet count
 */
JNIEXPORT jlong JNICALL
Java_com_example_teachmintsharex_share_miracast_rtp_NativeRtpReceiver_nativeGetPacketCount(
    JNIEnv* env, jobject thiz, jlong handle) {

    auto it = receivers.find(handle);
    if (it == receivers.end()) {
        return 0;
    }

    return static_cast<jlong>(it->second->receiver->getPacketCount());
}

/**
 * Get bytes received
 */
JNIEXPORT jlong JNICALL
Java_com_example_teachmintsharex_share_miracast_rtp_NativeRtpReceiver_nativeGetBytesReceived(
    JNIEnv* env, jobject thiz, jlong handle) {

    auto it = receivers.find(handle);
    if (it == receivers.end()) {
        return 0;
    }

    return static_cast<jlong>(it->second->receiver->getBytesReceived());
}

/**
 * Destroy native receiver
 */
JNIEXPORT void JNICALL
Java_com_example_teachmintsharex_share_miracast_rtp_NativeRtpReceiver_nativeDestroy(
    JNIEnv* env, jobject thiz, jlong handle) {

    LOGD("nativeDestroy: handle=%lld", (long long)handle);

    auto it = receivers.find(handle);
    if (it != receivers.end()) {
        // Stop receiver
        it->second->receiver->stop();

        // Delete global reference to callback
        if (it->second->callback_obj) {
            env->DeleteGlobalRef(it->second->callback_obj);
        }

        // Remove from map (will destroy unique_ptr)
        receivers.erase(it);

        LOGD("nativeDestroy: destroyed receiver %lld", (long long)handle);
    }
}

} // extern "C"
