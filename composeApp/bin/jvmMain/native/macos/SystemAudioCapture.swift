// SystemAudioCapture.swift
// Captures system playback audio via ScreenCaptureKit (macOS 12.3+).
// Outputs raw PCM (48 kHz, mono, 16-bit signed LE) to stdout.
// Logs / errors go to stderr.
//
// Compiled at runtime by DesktopSystemAudioCapturer:
//   swiftc -O -o <binary> SystemAudioCapture.swift

import Foundation
import ScreenCaptureKit
import CoreMedia

// ──────────────────────────── Configuration ─────────────────────────────
private let kSampleRate  = 48000
private let kChannels    = 1  // mono output to WebRTC
private let kBitsPerSample = 16

// ──────────────────────────── Audio handler ─────────────────────────────

@available(macOS 12.3, *)
final class AudioCaptureHandler: NSObject, SCStreamOutput, SCStreamDelegate {

    private let stdout = FileHandle.standardOutput

    func stream(
        _ stream: SCStream,
        didOutputSampleBuffer sampleBuffer: CMSampleBuffer,
        of type: SCStreamOutputType
    ) {
        guard type == .audio else { return }
        guard let blockBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else { return }

        var length = 0
        var dataPointer: UnsafeMutablePointer<Int8>?
        let status = CMBlockBufferGetDataPointer(
            blockBuffer,
            atOffset: 0,
            lengthAtOffsetOut: nil,
            totalLengthOut: &length,
            dataPointerOut: &dataPointer
        )
        guard status == kCMBlockBufferNoErr, let ptr = dataPointer, length > 0 else { return }

        // Determine channel count from the sample buffer format description.
        let inputChannels: Int
        if let formatDesc = CMSampleBufferGetFormatDescription(sampleBuffer),
           let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(formatDesc) {
            inputChannels = Int(asbd.pointee.mChannelsPerFrame)
        } else {
            inputChannels = 2  // SCK default is stereo
        }

        // ScreenCaptureKit delivers 32-bit float PCM.
        // Convert to 16-bit signed integer, downmixing to mono if needed.
        let floatCount = length / MemoryLayout<Float32>.size
        let floatPtr = UnsafeRawPointer(ptr).bindMemory(to: Float32.self, capacity: floatCount)

        let monoSampleCount: Int
        if inputChannels >= 2 {
            monoSampleCount = floatCount / inputChannels
        } else {
            monoSampleCount = floatCount
        }

        var pcm16 = Data(count: monoSampleCount * MemoryLayout<Int16>.size)
        pcm16.withUnsafeMutableBytes { rawBuffer in
            let int16Ptr = rawBuffer.bindMemory(to: Int16.self)
            if inputChannels >= 2 {
                // Downmix to mono by averaging channels.
                for i in 0..<monoSampleCount {
                    var sum: Float32 = 0
                    for ch in 0..<inputChannels {
                        let idx = i * inputChannels + ch
                        if idx < floatCount { sum += floatPtr[idx] }
                    }
                    let mixed = sum / Float32(inputChannels)
                    let clamped = max(-1.0, min(1.0, mixed))
                    int16Ptr[i] = Int16(clamped * Float32(Int16.max))
                }
            } else {
                for i in 0..<monoSampleCount {
                    let clamped = max(-1.0, min(1.0, floatPtr[i]))
                    int16Ptr[i] = Int16(clamped * Float32(Int16.max))
                }
            }
        }

        stdout.write(pcm16)
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        log("Stream stopped with error: \(error.localizedDescription)")
        exit(1)
    }
}

// ──────────────────────────── Main ──────────────────────────────────────

private func log(_ msg: String) {
    fputs("AUDIO_HELPER: \(msg)\n", stderr)
}

guard #available(macOS 12.3, *) else {
    log("ERROR: Requires macOS 12.3 or later")
    exit(1)
}

// Collect PIDs to exclude: the --exclude-pid argument AND our own parent
// process (the JVM that launched us via ProcessBuilder).
var excludePIDs: Set<pid_t> = []
let parentPID = getppid()
if parentPID > 0 { excludePIDs.insert(parentPID) }

let args = CommandLine.arguments
if let idx = args.firstIndex(of: "--exclude-pid"), idx + 1 < args.count,
   let pid = Int32(args[idx + 1]) {
    excludePIDs.insert(pid)
}

log("Starting system audio capture (exclude PIDs: \(excludePIDs))...")

let handler = AudioCaptureHandler()
// Keep a strong reference for the process lifetime.
// Without this, SCStream can be deallocated after startCapture() returns,
// which causes intermittent "application connection being interrupted" stops.
private var activeStream: SCStream?
private var parentWatchdog: DispatchSourceTimer?

// If the launching JVM dies, this helper can become orphaned and continue
// running. That stale process can break future capture sessions, so exit
// automatically when parent ownership is lost.
let initialParentPID = getppid()
parentWatchdog = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
parentWatchdog?.schedule(deadline: .now() + .seconds(2), repeating: .seconds(2))
parentWatchdog?.setEventHandler {
    let currentParent = getppid()
    if currentParent == 1 || currentParent == 0 || currentParent != initialParentPID {
        log("Parent process changed/exited (initial=\(initialParentPID), current=\(currentParent)); shutting down helper")
        exit(0)
    }
}
parentWatchdog?.resume()

Task {
    do {
        let content = try await SCShareableContent.excludingDesktopWindows(
            false,
            onScreenWindowsOnly: false
        )
        guard let display = content.displays.first else {
            log("ERROR: No display found")
            exit(1)
        }

        // Log all apps so we can debug PID matching.
        log("Available applications (\(content.applications.count)):")
        for app in content.applications {
            let marker = excludePIDs.contains(app.processID) ? " << EXCLUDE" : ""
            log("  PID \(app.processID) - \(app.bundleIdentifier)\(marker)")
        }

        // Find apps matching any of our exclude PIDs.
        let appsToExclude = content.applications.filter { excludePIDs.contains($0.processID) }

        let filter: SCContentFilter
        if !appsToExclude.isEmpty {
            for app in appsToExclude {
                log("Excluding app PID \(app.processID) (\(app.bundleIdentifier))")
            }
            filter = SCContentFilter(
                display: display,
                excludingApplications: appsToExclude,
                exceptingWindows: []
            )
        } else {
            log("WARNING: None of the exclude PIDs \(excludePIDs) found in running applications")
            filter = SCContentFilter(display: display, excludingWindows: [])
        }

        let config = SCStreamConfiguration()

        // Audio configuration — capture system audio only, never the mic.
        config.capturesAudio = true
        config.sampleRate = kSampleRate
        if #available(macOS 13.0, *) {
            config.channelCount = kChannels
            // Keep this disabled: on some macOS 15 setups it can cause
            // immediate "application connection interrupted" stream stops.
            // We already avoid self-capture by excluding the parent JVM app
            // via SCContentFilter.excludingApplications above.
            config.excludesCurrentProcessAudio = false
        }
        // macOS 15+: ScreenCaptureKit can capture the microphone alongside
        // system audio. Explicitly disable this to prevent voice echo.
        if #available(macOS 15.0, *) {
            config.captureMicrophone = false
            log("captureMicrophone explicitly disabled (macOS 15+)")
        }

        // We only need audio; skip expensive video capture.
        config.width = 2
        config.height = 2
        config.minimumFrameInterval = CMTime(value: 1, timescale: 1)  // 1 fps minimum

        let stream = SCStream(filter: filter, configuration: config, delegate: handler)
        activeStream = stream

        try stream.addStreamOutput(
            handler,
            type: .audio,
            sampleHandlerQueue: DispatchQueue.global(qos: .userInteractive)
        )
        try await stream.startCapture()

        log("READY - capturing at \(kSampleRate)Hz mono 16-bit")
    } catch {
        log("ERROR: \(error.localizedDescription)")
        exit(1)
    }
}

// Keep the process alive until killed by the parent.
dispatchMain()
