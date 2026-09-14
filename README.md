# Bridge VPN

A simple, modern Android VPN client (Xray-based) with subscription import,
server ping, fastest-server auto-connect, and a clean Compose UI.

## Architecture

```
Android apps -> VpnService TUN -> AndroidLibXrayLite (startLoop) -> proxy -> Internet
```

- `MainActivity.kt`     — Compose UI (Home / Servers / Settings), state polling.
- `BridgeVpnService.kt` — foreground VPN service; real proxy verification.
- `XrayConfigBuilder.kt`— builds Xray JSON (VLESS/VMess/Trojan/SS + transports).
- `ProxyTools.kt`       — URL-safe Base64, TCP reachability ping, URI parsing.
- `ServerProfile.kt`    — one parsed server.
- `BridgeVpnState.kt`   — thread-safe state machine shared with the UI.

## Building

Pushing to `main` triggers `.github/workflows/build-apk.yml`, which downloads
the prebuilt `libv2ray.aar`, builds a debug APK, and uploads it as an artifact.
No local Android SDK required.
