# TeachmintShareX Remote Signaling Server

A standalone WebSocket signaling server for enabling web-based screen sharing connections.

## Purpose

This server acts as a relay between:
- **Host devices** (Desktop/Android/iOS) that want to share their screens
- **Web viewers** (Browser-based clients) that want to view the shared screens

## How It Works

1. **Host Registration**: Desktop/mobile hosts register with the server
2. **Host Discovery**: Web clients request the list of available hosts
3. **Connection Setup**: Web clients join a specific host
4. **Message Relay**: The server relays WebRTC signaling messages (offers, answers, ICE candidates) between hosts and viewers

## Running Locally

```bash
# Build the server
./gradlew :signaling-server:build

# Run the server
./gradlew :signaling-server:run

# Server will start on http://localhost:8090
```

## Running in Production

```bash
# Build a fat JAR
./gradlew :signaling-server:shadowJar

# Run the JAR
java -jar build/libs/signaling-server-1.0.0-all.jar

# Or set a custom port
PORT=3000 java -jar build/libs/signaling-server-1.0.0-all.jar
```

## Deployment

You can deploy this server to any platform that supports Java/Kotlin applications:

- **Heroku**: `heroku create` and push
- **Railway**: Connect your repository
- **AWS/GCP/Azure**: Deploy as a container or JAR
- **VPS**: Run with systemd service

## Environment Variables

- `PORT`: Server port (default: 8090)

## Endpoints

- `ws://localhost:8090/ws` - WebSocket endpoint for signaling
- `GET /health` - Health check endpoint

## Message Protocol

See `SignalingMessage.kt` for the complete message protocol.

Key messages:
- `register_host` - Host registers itself
- `list_hosts` - Client requests available hosts
- `join_host` - Client joins a specific host
- `relay` - Server relays messages between peers
- `offer`, `answer`, `ice` - WebRTC signaling messages
