# Remote Signaling Server Setup Guide

This guide explains how to set up and use the remote signaling server for web-based screen sharing connections.

## Architecture Overview

The TeachmintShareX app now supports **two connection modes**:

### 1. **Local Network Mode** (Default for Android/iOS/Desktop)
- **Discovery**: UDP broadcast/multicast
- **Connection**: Direct WebSocket connection to host's local IP
- **Use Case**: Same WiFi network, low latency
- **Platforms**: Android, iOS, Desktop

### 2. **Remote Server Mode** (For Web Clients)
- **Discovery**: Fetch hosts from remote signaling server
- **Connection**: WebSocket connection via remote server relay
- **Use Case**: Internet connection, cross-network access
- **Platforms**: Web (wasmJs)

```
┌──────────────────────────────────────────────────────────────┐
│                 Local Network Mode                            │
│  Android/iOS/Desktop ←─UDP─→ Host Device (on same WiFi)      │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│                 Remote Server Mode                            │
│  Web Viewer ←─WSS─→ Remote Server ←─WSS─→ Host Device        │
└──────────────────────────────────────────────────────────────┘
```

## Setup Instructions

### Step 1: Start the Remote Signaling Server

#### Option A: Run Locally (Development)

```bash
cd signaling-server
./gradlew run
```

The server will start on `http://localhost:8090`

#### Option B: Deploy to Production

Deploy the signaling server to any platform that supports Java/Kotlin:

**Heroku:**
```bash
cd signaling-server
heroku create your-signaling-server
git push heroku main
```

**Railway:**
1. Connect your repository to Railway
2. Select the `signaling-server` directory as root
3. Deploy

**Docker:**
```bash
cd signaling-server
./gradlew build
docker build -t teachmint-signaling-server .
docker run -p 8090:8090 teachmint-signaling-server
```

**Manual Deployment:**
```bash
cd signaling-server
./gradlew shadowJar
java -jar build/libs/signaling-server-1.0.0-all.jar
```

### Step 2: Configure the Remote Server URL

Edit [`RemoteServerConfig.kt`](composeApp/src/commonMain/kotlin/com/example/teachmintsharex/share/shared/RemoteServerConfig.kt):

```kotlin
object RemoteServerConfig {
    const val REMOTE_SERVER_URL: String? = "wss://your-server.com/ws"
}
```

**Important:**
- Use `ws://` for local/development (unencrypted)
- Use `wss://` for production (TLS encrypted)
- Set to `null` to disable remote signaling

### Step 3: Update Host Controller (Optional - For Custom Deployment)

The host automatically uses the remote server URL from `RemoteServerConfig`. No code changes needed unless you want per-instance configuration.

If you need custom configuration, modify the host creation in platform-specific files:

**Example for JVM (Desktop):**
```kotlin
// composeApp/src/jvmMain/kotlin/...
actual fun createHostController(...): HostController {
    return DefaultHostController(
        scope = scope,
        discoveryService = discoveryService,
        webRtcEngine = webRtcEngine,
        iceServers = iceServers,
        remoteServerUrl = RemoteServerConfig.REMOTE_SERVER_URL // Uses config
    )
}
```

## How It Works

### For Host Devices (Desktop/Android/iOS)

When a host starts:
1. **Local Signaling Server** starts on the host device (UDP broadcast for local discovery)
2. **Remote Signaling Service** connects to the remote server (if URL is configured)
3. Host registers itself with the remote server as available
4. Host can now accept connections from:
   - **Local clients** (Android/iOS/Desktop) via UDP discovery
   - **Web clients** (Browser) via remote server

### For Web Viewers

When a web viewer starts:
1. Connects to the remote signaling server
2. Requests list of available hosts
3. Displays available hosts in the UI
4. When user selects a host:
   - Sends `JoinHost` message to server
   - Server relays signaling messages (offers, answers, ICE candidates)
   - WebRTC peer connection is established directly between host and viewer
   - **Media flows peer-to-peer** (server only relays signaling, not video)

### For Mobile/Desktop Viewers

Unchanged - they continue using UDP discovery and local network connections.

## Message Flow

```
┌─────────┐              ┌────────────────┐              ┌──────┐
│   Web   │              │ Remote Server  │              │ Host │
│ Viewer  │              │                │              │      │
└────┬────┘              └────────┬───────┘              └───┬──┘
     │                            │                          │
     │  1. ListHosts              │                          │
     │ ───────────────────────────>│                          │
     │                            │                          │
     │  2. HostsList              │                          │
     │ <───────────────────────────│                          │
     │                            │                          │
     │  3. JoinHost(hostId)       │                          │
     │ ───────────────────────────>│                          │
     │                            │  4. Relay(Hello)          │
     │                            │ ──────────────────────────>│
     │  5. Relay(Hello)           │                          │
     │ <───────────────────────────│                          │
     │                            │                          │
     │  6. Relay(Offer)           │                          │
     │ ───────────────────────────>│  7. Relay(Offer)         │
     │                            │ ──────────────────────────>│
     │                            │                          │
     │  8. Relay(Answer)          │  9. Relay(Answer)        │
     │ <───────────────────────────│ <──────────────────────────
     │                            │                          │
     │  10. ICE Candidates exchanged via Relay messages      │
     │ <──────────────────────────────────────────────────────>│
     │                            │                          │
     │  11. WebRTC Connection Established (Peer-to-Peer)     │
     │ <══════════════════════════════════════════════════════>│
     │           (Video/Audio streams directly)               │
```

## Security Considerations

1. **Use WSS in Production**: Always use `wss://` (WebSocket Secure) in production
2. **TURN Servers**: For NAT traversal, configure TURN servers in `IceServerConfig`
3. **Authentication** (Future): Consider adding authentication to the signaling server
4. **Rate Limiting** (Future): Implement rate limiting to prevent abuse

## Troubleshooting

### Web client can't see any hosts

**Check:**
1. Is the remote signaling server running?
2. Is `REMOTE_SERVER_URL` correctly configured?
3. Are hosts starting successfully? (Check host logs)
4. Check browser console for WebSocket connection errors

**Debug:**
```javascript
// In browser console
localStorage.setItem('debug', 'signaling:*')
```

### Connection works but no video

This is a **WebRTC issue**, not a signaling issue. The signaling server only helps establish the connection.

**Check:**
1. STUN/TURN server configuration in `IceServerConfig`
2. Firewall settings blocking UDP
3. Browser permissions for media

### Host not appearing in web viewer

**Check:**
1. Host logs: Is it connecting to remote server?
2. Server logs: Is it receiving the `RegisterHost` message?
3. Network: Can host reach the remote server URL?

## Environment Variables

The signaling server supports these environment variables:

- `PORT`: Server port (default: 8090)

Example:
```bash
PORT=3000 java -jar signaling-server.jar
```

## Next Steps

1. Deploy the signaling server to a cloud provider
2. Update `REMOTE_SERVER_URL` to point to your deployed server
3. Configure TURN servers for better NAT traversal
4. (Optional) Add authentication to the signaling server

## Platform Compatibility

| Platform | Discovery Method | Signaling Method | Notes |
|----------|-----------------|------------------|-------|
| Web (Browser) | Remote Server | Remote Server | Via WebSocket to deployed server |
| Android | UDP Broadcast | Local Server | Same WiFi required |
| iOS | UDP Multicast | Local Server | Same WiFi required |
| Desktop | UDP Broadcast | Local Server | Same WiFi required |

## Code Reference

- **Signaling Server**: [`signaling-server/`](signaling-server/)
- **Remote Config**: [`RemoteServerConfig.kt`](composeApp/src/commonMain/kotlin/com/example/teachmintsharex/share/shared/RemoteServerConfig.kt)
- **Web Discovery**: [`DiscoveryService.wasmJs.kt`](composeApp/src/wasmJsMain/kotlin/com/example/teachmintsharex/share/DiscoveryService.wasmJs.kt)
- **Remote Client**: [`RemoteSignalingClient.kt`](composeApp/src/commonMain/kotlin/com/example/teachmintsharex/share/client/RemoteSignalingClient.kt)
- **Host Service**: [`RemoteSignalingService.kt`](composeApp/src/commonMain/kotlin/com/example/teachmintsharex/share/host/RemoteSignalingService.kt)
