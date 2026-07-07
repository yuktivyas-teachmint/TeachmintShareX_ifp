// SystemAudioCapture.cs
// Captures system playback audio via WASAPI loopback (Windows).
// Outputs raw PCM (48 kHz, mono, 16-bit signed LE) to stdout.
// Logs / errors go to stderr.
//
// Compiled at runtime by DesktopSystemAudioCapturer:
//   csc.exe /optimize /out:<binary> SystemAudioCapture.cs
//
// Targets C# 5.0 / .NET Framework 4.x for maximum compatibility.

using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

// ─────────────────────── COM enumerations ────────────────────────────────

enum EDataFlow { eRender = 0, eCapture = 1, eAll = 2 }
enum ERole { eConsole = 0, eMultimedia = 1, eCommunications = 2 }
enum AUDCLNT_SHAREMODE { Shared = 0, Exclusive = 1 }

// ─────────────────────── COM structures ──────────────────────────────────

[StructLayout(LayoutKind.Sequential, Pack = 1)]
struct WAVEFORMATEX
{
    public ushort wFormatTag;
    public ushort nChannels;
    public uint nSamplesPerSec;
    public uint nAvgBytesPerSec;
    public ushort nBlockAlign;
    public ushort wBitsPerSample;
    public ushort cbSize;
}

[StructLayout(LayoutKind.Sequential, Pack = 1)]
struct WAVEFORMATEXTENSIBLE
{
    public WAVEFORMATEX Format;
    public ushort wValidBitsPerSample;
    public uint dwChannelMask;
    public Guid SubFormat;
}

// ─────────────────────── COM interfaces ──────────────────────────────────
// Method order MUST match the native vtable layout.

[ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"),
 InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceEnumerator
{
    // EnumAudioEndpoints
    [PreserveSig]
    int EnumAudioEndpoints(EDataFlow dataFlow, uint stateMask, out IntPtr devices);
    // GetDefaultAudioEndpoint
    [PreserveSig]
    int GetDefaultAudioEndpoint(EDataFlow dataFlow, ERole role, out IMMDevice device);
    // GetDevice
    [PreserveSig]
    int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IMMDevice device);
    // RegisterEndpointNotificationCallback
    [PreserveSig]
    int RegisterEndpointNotificationCallback(IntPtr client);
    // UnregisterEndpointNotificationCallback
    [PreserveSig]
    int UnregisterEndpointNotificationCallback(IntPtr client);
}

[ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"),
 InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDevice
{
    // Activate
    [PreserveSig]
    int Activate(ref Guid iid, uint clsCtx, IntPtr activationParams,
                 [MarshalAs(UnmanagedType.IUnknown)] out object iface);
    // OpenPropertyStore
    [PreserveSig]
    int OpenPropertyStore(uint access, out IntPtr properties);
    // GetId
    [PreserveSig]
    int GetId(out IntPtr id);
    // GetState
    [PreserveSig]
    int GetState(out uint state);
}

[ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"),
 InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioClient
{
    [PreserveSig]
    int Initialize(AUDCLNT_SHAREMODE shareMode, uint streamFlags,
                   long hnsBufferDuration, long hnsPeriodicity,
                   IntPtr pFormat, IntPtr audioSessionGuid);
    [PreserveSig]
    int GetBufferSize(out uint numBufferFrames);
    [PreserveSig]
    int GetStreamLatency(out long hnsLatency);
    [PreserveSig]
    int GetCurrentPadding(out uint numPaddingFrames);
    [PreserveSig]
    int IsFormatSupported(AUDCLNT_SHAREMODE shareMode, IntPtr pFormat,
                          out IntPtr closestMatch);
    [PreserveSig]
    int GetMixFormat(out IntPtr pwfx);
    [PreserveSig]
    int GetDevicePeriod(out long hnsDefaultDevicePeriod,
                        out long hnsMinimumDevicePeriod);
    [PreserveSig]
    int Start();
    [PreserveSig]
    int Stop();
    [PreserveSig]
    int Reset();
    [PreserveSig]
    int SetEventHandle(IntPtr eventHandle);
    [PreserveSig]
    int GetService(ref Guid riid,
                   [MarshalAs(UnmanagedType.IUnknown)] out object service);
}

[ComImport, Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"),
 InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioCaptureClient
{
    [PreserveSig]
    int GetBuffer(out IntPtr data, out uint numFramesAvailable,
                  out uint flags, out ulong devicePosition,
                  out ulong qpcPosition);
    [PreserveSig]
    int ReleaseBuffer(uint numFramesRead);
    [PreserveSig]
    int GetNextPacketSize(out uint numFramesInNextPacket);
}

// ─────────────────────── Main program ────────────────────────────────────

class SystemAudioCapture
{
    // Target PCM output format — must match DesktopSystemAudioCapturer constants.
    const int TARGET_SAMPLE_RATE = 48000;
    const int TARGET_CHANNELS = 1;
    const int TARGET_BITS = 16;
    // 10 ms frame: 48000 * 1 * 2 / 100 = 960 bytes.
    const int FRAME_BYTES = TARGET_SAMPLE_RATE * TARGET_CHANNELS * (TARGET_BITS / 8) / 100;
    // 480 samples per 10 ms frame at 48 kHz mono.
    const int FRAME_SAMPLES = TARGET_SAMPLE_RATE / 100;

    static readonly Guid IID_IAudioClient =
        new Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
    static readonly Guid IID_IAudioCaptureClient =
        new Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317");
    static readonly Guid CLSID_MMDeviceEnumerator =
        new Guid("BCDE0395-E52F-467C-8E3D-C4579291692E");
    static readonly Guid KSDATAFORMAT_SUBTYPE_IEEE_FLOAT =
        new Guid("00000003-0000-0010-8000-00aa00389b71");
    static readonly Guid KSDATAFORMAT_SUBTYPE_PCM =
        new Guid("00000001-0000-0010-8000-00aa00389b71");

    const uint AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000;
    const uint AUDCLNT_BUFFERFLAGS_SILENT = 0x00000002;
    const uint CLSCTX_ALL = 0x17;

    static volatile bool running = true;

    [DllImport("ole32.dll")]
    static extern int CoInitializeEx(IntPtr reserved, uint coInit);
    [DllImport("ole32.dll")]
    static extern void CoTaskMemFree(IntPtr ptr);

    static string logFilePath = null;

    static void Log(string msg)
    {
        string line = DateTime.Now.ToString("HH:mm:ss.fff") + " AUDIO_HELPER: " + msg;
        try
        {
            Console.Error.WriteLine(line);
            Console.Error.Flush();
        }
        catch { }
        // Also write to a log file for diagnosis when console is unavailable.
        try
        {
            if (logFilePath != null)
                File.AppendAllText(logFilePath, line + Environment.NewLine);
        }
        catch { }
    }

    static void InitFileLog()
    {
        try
        {
            string dir = Path.Combine(Path.GetTempPath(), "sharex-audio-helpers");
            Directory.CreateDirectory(dir);
            logFilePath = Path.Combine(dir, "audio_helper.log");
            // Truncate previous log.
            File.WriteAllText(logFilePath, "");
            Log("Log file: " + logFilePath);
        }
        catch { }
    }

    static int Main(string[] args)
    {
        InitFileLog();
        int parentPid = -1;
        for (int i = 0; i < args.Length - 1; i++)
        {
            if (args[i] == "--exclude-pid")
            {
                int.TryParse(args[i + 1], out parentPid);
            }
        }

        // Parent-process watchdog: if the launching JVM exits, terminate
        // so we don't linger as an orphaned process.
        if (parentPid > 0)
        {
            Thread watchdog = new Thread(delegate()
            {
                while (running)
                {
                    Thread.Sleep(2000);
                    try { Process.GetProcessById(parentPid); }
                    catch
                    {
                        Log("Parent process " + parentPid +
                            " exited; shutting down");
                        running = false;
                        Environment.Exit(0);
                    }
                }
            });
            watchdog.IsBackground = true;
            watchdog.Start();
        }

        try
        {
            return CaptureLoop();
        }
        catch (Exception ex)
        {
            Log("ERROR: " + ex.Message);
            return 1;
        }
    }

    static int CaptureLoop()
    {
        int hr = CoInitializeEx(IntPtr.Zero, 0); // COINIT_MULTITHREADED

        Type enumeratorType = Type.GetTypeFromCLSID(CLSID_MMDeviceEnumerator);
        if (enumeratorType == null)
        {
            Log("ERROR: MMDeviceEnumerator CLSID not found");
            return 1;
        }
        IMMDeviceEnumerator enumerator =
            (IMMDeviceEnumerator)Activator.CreateInstance(enumeratorType);

        IMMDevice device;
        hr = enumerator.GetDefaultAudioEndpoint(
            EDataFlow.eRender, ERole.eConsole, out device);
        if (hr != 0)
        {
            Log("ERROR: No default audio render device (0x" +
                hr.ToString("X8") + "). Is an audio output connected?");
            return 1;
        }

        Guid iidAudioClient = IID_IAudioClient;
        object audioClientObj;
        hr = device.Activate(ref iidAudioClient, CLSCTX_ALL, IntPtr.Zero,
                             out audioClientObj);
        if (hr != 0)
        {
            Log("ERROR: Activate IAudioClient failed: 0x" +
                hr.ToString("X8"));
            return 1;
        }
        IAudioClient audioClient = (IAudioClient)audioClientObj;

        // ── Discover device mix format ──────────────────────────────────
        IntPtr mixFormatPtr;
        hr = audioClient.GetMixFormat(out mixFormatPtr);
        if (hr != 0)
        {
            Log("ERROR: GetMixFormat failed: 0x" + hr.ToString("X8"));
            return 1;
        }

        WAVEFORMATEX mixFmt =
            (WAVEFORMATEX)Marshal.PtrToStructure(mixFormatPtr,
                typeof(WAVEFORMATEX));
        int srcRate = (int)mixFmt.nSamplesPerSec;
        int srcChannels = mixFmt.nChannels;
        int srcBits = mixFmt.wBitsPerSample;
        bool srcIsFloat = false;

        if (mixFmt.wFormatTag == 0xFFFE && mixFmt.cbSize >= 22)
        {
            WAVEFORMATEXTENSIBLE ext =
                (WAVEFORMATEXTENSIBLE)Marshal.PtrToStructure(mixFormatPtr,
                    typeof(WAVEFORMATEXTENSIBLE));
            srcIsFloat = (ext.SubFormat == KSDATAFORMAT_SUBTYPE_IEEE_FLOAT);
        }
        else if (mixFmt.wFormatTag == 3) // WAVE_FORMAT_IEEE_FLOAT
        {
            srcIsFloat = true;
        }

        Log("Device format: " + srcRate + "Hz, " + srcChannels + "ch, " +
            srcBits + "bit, float=" + srcIsFloat);
        Log("Target format: " + TARGET_SAMPLE_RATE + "Hz, " +
            TARGET_CHANNELS + "ch, " + TARGET_BITS + "bit");

        // ── Initialize loopback capture ─────────────────────────────────
        long hnsBufferDuration = 2000000; // 200 ms in 100-ns units
        hr = audioClient.Initialize(
            AUDCLNT_SHAREMODE.Shared,
            AUDCLNT_STREAMFLAGS_LOOPBACK,
            hnsBufferDuration, 0,
            mixFormatPtr, IntPtr.Zero);
        CoTaskMemFree(mixFormatPtr);
        if (hr != 0)
        {
            Log("ERROR: Initialize loopback failed: 0x" +
                hr.ToString("X8"));
            return 1;
        }

        Guid iidCaptureClient = IID_IAudioCaptureClient;
        object captureClientObj;
        hr = audioClient.GetService(ref iidCaptureClient,
                                    out captureClientObj);
        if (hr != 0)
        {
            Log("ERROR: GetService IAudioCaptureClient failed: 0x" +
                hr.ToString("X8"));
            return 1;
        }
        IAudioCaptureClient captureClient =
            (IAudioCaptureClient)captureClientObj;

        hr = audioClient.Start();
        if (hr != 0)
        {
            Log("ERROR: Start failed: 0x" + hr.ToString("X8"));
            return 1;
        }

        Log("READY - capturing loopback at " + srcRate + "Hz " +
            srcChannels + "ch -> " + TARGET_SAMPLE_RATE +
            "Hz mono 16-bit");

        // ── Capture loop with 10 ms pacing ──────────────────────────────
        //
        // WASAPI delivers audio in variable-size packets (often 10 ms but
        // can be larger or bursty). Writing those bursts directly to stdout
        // causes the JVM reader to push audio to WebRTC in uneven chunks,
        // producing audible glitches on the remote receiver. Instead we
        // accumulate resampled mono-16-bit samples into a ring buffer and
        // drain exactly one 10 ms frame (480 samples / 960 bytes) per tick,
        // paced by a Stopwatch so the output is a smooth 100 Hz stream.
        //
        Stream stdout = Console.OpenStandardOutput();
        byte[] frameOut = new byte[FRAME_BYTES];
        int bytesPerSrcFrame = srcChannels * (srcBits / 8);
        bool needsResample = (srcRate != TARGET_SAMPLE_RATE);

        // Resampler state (persists across packets for continuity).
        double resamplePos = 0.0;
        double resampleStep = (double)srcRate / TARGET_SAMPLE_RATE;

        // Ring buffer of mono float samples (up to 200 ms = 9600 samples).
        const int RING_CAPACITY = FRAME_SAMPLES * 20;
        float[] ring = new float[RING_CAPACITY];
        int ringHead = 0; // next write position
        int ringCount = 0; // samples currently stored

        long totalFramesPushed = 0;
        long totalSilenceFrames = 0;
        long totalNonSilentFrames = 0;
        float peakSample = 0f;
        long lastLogMs = CurrentTimeMs();
        uint lastFlags = 0;

        // High-resolution pacing: emit one 10 ms frame every 10 ms.
        Stopwatch pacer = Stopwatch.StartNew();
        long nextFrameTick = pacer.ElapsedTicks;
        long ticksPer10ms = Stopwatch.Frequency / 100; // ticks in 10 ms

        while (running)
        {
            // ── 1. Drain all available WASAPI packets into the ring ──
            bool drained = false;
            while (true)
            {
                uint packetSize;
                hr = captureClient.GetNextPacketSize(out packetSize);
                if (hr != 0)
                {
                    Log("GetNextPacketSize failed: 0x" + hr.ToString("X8"));
                    running = false;
                    break;
                }
                if (packetSize == 0) break;

                IntPtr dataPtr;
                uint numFrames, flags;
                ulong devPos, qpcPos;
                hr = captureClient.GetBuffer(
                    out dataPtr, out numFrames, out flags,
                    out devPos, out qpcPos);
                if (hr != 0)
                {
                    Log("GetBuffer failed: 0x" + hr.ToString("X8"));
                    running = false;
                    break;
                }
                lastFlags = flags;

                if (numFrames > 0)
                {
                    bool isSilent =
                        (flags & AUDCLNT_BUFFERFLAGS_SILENT) != 0;

                    int rawBytes = (int)numFrames * bytesPerSrcFrame;
                    byte[] rawData = new byte[rawBytes];
                    if (!isSilent)
                    {
                        Marshal.Copy(dataPtr, rawData, 0, rawBytes);
                    }

                    float[] mono = ConvertToMonoFloat(
                        rawData, (int)numFrames,
                        srcChannels, srcBits, srcIsFloat, isSilent);

                    // Resample if needed, producing 48 kHz mono samples.
                    float[] outSamples;
                    if (needsResample)
                    {
                        outSamples = ResampleToFloat(
                            mono, ref resamplePos, resampleStep);
                    }
                    else
                    {
                        outSamples = mono;
                    }

                    // Track diagnostics.
                    bool frameHasAudio = false;
                    for (int s = 0; s < outSamples.Length; s++)
                    {
                        float abs = outSamples[s] < 0 ? -outSamples[s] : outSamples[s];
                        if (abs > peakSample) peakSample = abs;
                        if (abs > 0.0001f) frameHasAudio = true;
                    }
                    if (frameHasAudio) totalNonSilentFrames++;
                    else totalSilenceFrames++;

                    // Append to ring buffer, dropping oldest if full.
                    int toWrite = outSamples.Length;
                    if (toWrite > RING_CAPACITY)
                    {
                        // Extremely large packet; keep only the tail.
                        int skip = toWrite - RING_CAPACITY;
                        toWrite = RING_CAPACITY;
                        ringHead = 0;
                        ringCount = 0;
                        for (int i = 0; i < toWrite; i++)
                        {
                            ring[(ringHead + i) % RING_CAPACITY] = outSamples[skip + i];
                        }
                        ringHead = toWrite % RING_CAPACITY;
                        ringCount = toWrite;
                    }
                    else
                    {
                        // Drop oldest samples if ring is too full.
                        while (ringCount + toWrite > RING_CAPACITY)
                        {
                            // Advance read pointer by one frame to make room.
                            int readPos = (ringHead - ringCount + RING_CAPACITY) % RING_CAPACITY;
                            ringCount -= FRAME_SAMPLES;
                            if (ringCount < 0) ringCount = 0;
                        }
                        int writePos = ringHead;
                        for (int i = 0; i < toWrite; i++)
                        {
                            ring[writePos] = outSamples[i];
                            writePos = (writePos + 1) % RING_CAPACITY;
                        }
                        ringHead = writePos;
                        ringCount += toWrite;
                    }

                    drained = true;
                }

                captureClient.ReleaseBuffer(numFrames);
            }
            if (!running) break;

            // ── 2. Emit 10 ms frames at a steady pace ───────────────
            long now = pacer.ElapsedTicks;
            while (nextFrameTick <= now)
            {
                if (ringCount >= FRAME_SAMPLES)
                {
                    // Read one frame from the ring buffer.
                    int readPos = (ringHead - ringCount + RING_CAPACITY) % RING_CAPACITY;
                    for (int i = 0; i < FRAME_SAMPLES; i++)
                    {
                        float sample = ring[(readPos + i) % RING_CAPACITY];
                        if (sample > 1f) sample = 1f;
                        else if (sample < -1f) sample = -1f;
                        short val16 = (short)(sample * 32767f);
                        frameOut[i * 2] = (byte)(val16 & 0xFF);
                        frameOut[i * 2 + 1] = (byte)((val16 >> 8) & 0xFF);
                    }
                    ringCount -= FRAME_SAMPLES;
                }
                else
                {
                    // Not enough buffered audio — emit silence.
                    Array.Clear(frameOut, 0, FRAME_BYTES);
                    totalSilenceFrames++;
                }

                stdout.Write(frameOut, 0, FRAME_BYTES);
                totalFramesPushed++;
                nextFrameTick += ticksPer10ms;
            }
            stdout.Flush();

            // ── 3. Sleep until the next 10 ms boundary ───────────────
            long ticksRemaining = nextFrameTick - pacer.ElapsedTicks;
            if (ticksRemaining > 0)
            {
                int sleepMs = (int)(ticksRemaining * 1000 / Stopwatch.Frequency);
                if (sleepMs > 0) Thread.Sleep(sleepMs);
                else Thread.SpinWait(100); // sub-ms remainder
            }

            // Periodic stats log (~every 4 seconds).
            long nowMs = CurrentTimeMs();
            if (nowMs - lastLogMs >= 4000)
            {
                Log("pushed=" + totalFramesPushed +
                    " nonSilent=" + totalNonSilentFrames +
                    " silent=" + totalSilenceFrames +
                    " peak=" + peakSample.ToString("F6") +
                    " flags=0x" + lastFlags.ToString("X") +
                    " ringBuf=" + ringCount + "/" + RING_CAPACITY);
                peakSample = 0f;
                lastLogMs = nowMs;
            }
        }

        audioClient.Stop();
        Log("Capture stopped");
        return 0;
    }

    // ─────────────────────── Audio conversion ────────────────────────────

    /// <summary>
    /// Down-mix multi-channel source to mono float in [-1, 1].
    /// </summary>
    static float[] ConvertToMonoFloat(
        byte[] raw, int numFrames,
        int channels, int bits, bool isFloat, bool isSilent)
    {
        float[] mono = new float[numFrames];
        if (isSilent) return mono; // all zeros

        if (isFloat && bits == 32)
        {
            for (int i = 0; i < numFrames; i++)
            {
                float sum = 0f;
                int baseOff = i * channels * 4;
                for (int ch = 0; ch < channels; ch++)
                {
                    sum += BitConverter.ToSingle(raw, baseOff + ch * 4);
                }
                mono[i] = sum / channels;
            }
        }
        else if (!isFloat && bits == 16)
        {
            for (int i = 0; i < numFrames; i++)
            {
                float sum = 0f;
                int baseOff = i * channels * 2;
                for (int ch = 0; ch < channels; ch++)
                {
                    short s = BitConverter.ToInt16(raw, baseOff + ch * 2);
                    sum += s / 32768f;
                }
                mono[i] = sum / channels;
            }
        }
        else if (!isFloat && bits == 32)
        {
            for (int i = 0; i < numFrames; i++)
            {
                float sum = 0f;
                int baseOff = i * channels * 4;
                for (int ch = 0; ch < channels; ch++)
                {
                    int sample = BitConverter.ToInt32(raw, baseOff + ch * 4);
                    sum += sample / 2147483648f; // 2^31
                }
                mono[i] = sum / channels;
            }
        }
        else if (!isFloat && bits == 24)
        {
            for (int i = 0; i < numFrames; i++)
            {
                float sum = 0f;
                int baseOff = i * channels * 3;
                for (int ch = 0; ch < channels; ch++)
                {
                    int off = baseOff + ch * 3;
                    int sample = raw[off]
                               | (raw[off + 1] << 8)
                               | (raw[off + 2] << 16);
                    // Sign-extend 24-bit to 32-bit.
                    if ((sample & 0x800000) != 0)
                        sample |= unchecked((int)0xFF000000);
                    sum += sample / 8388608f; // 2^23
                }
                mono[i] = sum / channels;
            }
        }
        else
        {
            Log("Unsupported source format: " + bits +
                "bit float=" + isFloat + "; outputting silence");
        }

        return mono;
    }

    /// <summary>
    /// Resample mono float from source rate to 48 kHz using linear
    /// interpolation. Returns resampled float samples (for ring buffer
    /// accumulation). Updates <paramref name="pos"/> for continuity.
    /// </summary>
    static float[] ResampleToFloat(float[] mono, ref double pos, double step)
    {
        int estOutput = (int)((mono.Length / step) + 2);
        float[] result = new float[estOutput];
        int count = 0;

        while (pos < mono.Length - 1)
        {
            int idx = (int)pos;
            float frac = (float)(pos - idx);
            float sample;
            if (idx < 0)
                sample = mono[0];
            else if (idx >= mono.Length - 1)
                sample = mono[mono.Length - 1];
            else
                sample = mono[idx] * (1f - frac) + mono[idx + 1] * frac;

            if (count < result.Length) result[count++] = sample;
            pos += step;
        }

        pos -= mono.Length;

        // Trim to actual count.
        if (count < result.Length)
        {
            float[] trimmed = new float[count];
            Array.Copy(result, trimmed, count);
            return trimmed;
        }
        return result;
    }

    static long CurrentTimeMs()
    {
        return DateTime.UtcNow.Ticks / TimeSpan.TicksPerMillisecond;
    }
}
