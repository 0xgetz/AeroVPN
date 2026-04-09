# AeroVPN

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://www.android.com/)
[![API](https://img.shields.io/badge/SDK-24%2B-orange.svg)](https://developer.android.com/about/versions/nougat/android-7.0)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-purple.svg)](https://kotlinlang.org/)

**Production-ready Android VPN application with multi-protocol support - No ads, privacy first, APK < 15MB**

AeroVPN is a lightweight, feature-rich VPN client for Android that supports multiple tunneling protocols including WireGuard, V2Ray (VMess/VLess/Trojan/Shadowsocks), SSH, and more. Built with modern Android technologies like Jetpack Compose and optimized for minimal size without compromising functionality.

## Table of Contents

- [Features](#features)
- [Screenshots](#screenshots)
- [Prerequisites](#prerequisites)
- [Build Instructions](#build-instructions)
- [Installation](#installation)
- [Usage Guide](#usage-guide)
- [Protocol Support](#protocol-support)
- [Configuration Examples](#configuration-examples)
- [Troubleshooting](#troubleshooting)
- [FAQ](#faq)
- [Contributing](#contributing)
- [License](#license)

---

## Features

### Core Features
- **Multi-Protocol Support**: WireGuard, V2Ray (VMess, VLess, Trojan, Shadowsocks), SSH, and custom UDP tunnels
- **No Ads**: Completely ad-free experience with no tracking or telemetry
- **Lightweight**: Optimized APK size under 15MB with full functionality
- **Material 3 Design**: Modern, beautiful UI with dark theme support
- **Split Tunneling**: Choose which apps use VPN connection
- **Kill Switch**: Automatically blocks internet if VPN disconnects
- **Auto-Reconnect**: Re-establishes connection when network changes

### Advanced Tools
- **IP Hunter**: Check your public IP address and location
- **Payload Generator**: Create custom HTTP/TLS payloads for tunneling
- **DNS Checker**: Test and optimize DNS servers
- **Host Checker**: Verify host connectivity and SSL certificates
- **Slow DNS Detector**: Test DNS tunnel performance
- **TCP Optimizer**: Configure TCP no-delay settings
- **App Filter**: Manage split tunneling permissions
- **Ping Tool**: Test server latency
- **Config Export/Import**: Backup and restore configurations
- **Connection Share**: Share VPN via hotspot or USB tethering

### Security & Privacy
- No logs policy
- No user accounts required
- Local-only configuration storage
- Encrypted configuration export
- No analytics or tracking

---

## Screenshots

> **Note**: Screenshots section - Add your app screenshots here

```
┌─────────────────────────────────┐
│      HOME SCREEN MOCKUP         │
│                                 │
│    [  Large Connect Button  ]   │
│                                 │
│    Server: Singapore #1         │
│    Protocol: WireGuard          │
│    Ping: 45ms ●                 │
│                                 │
│    Status: Disconnected         │
└─────────────────────────────────┘

┌─────────────────────────────────┐
│    SERVER LIST SCREEN MOCKUP    │
│                                 │
│  🇸🇬 Singapore #1      [★] 45ms │
│  🇯🇵 Tokyo #2          [✓] 62ms │
│  🇺🇸 New York #3       [ ] 180ms│
│  🇬🇧 London #4         [ ] 150ms│
│  🇦🇺 Sydney #5         [ ] 120ms│
│                                 │
│  [+ Add Server] [Search...]     │
└─────────────────────────────────┘

┌─────────────────────────────────┐
│     TOOLS SCREEN MOCKUP         │
│                                 │
│  [IP Hunter]    [Payload Gen]   │
│  [DNS Checker]  [Host Checker]  │
│  [Slow DNS]     [TCP No Delay]  │
│  [App Filter]   [Ping Test]     │
│  [Export/Import][Share Connect] │
└─────────────────────────────────┘
```

**Placeholder for actual screenshots:**
- `screenshots/home_screen.png` - Main connection screen
- `screenshots/server_list.png` - Server selection interface
- `screenshots/tools_menu.png` - Advanced tools grid
- `screenshots/settings.png` - App settings
- `screenshots/connecting.png` - Connection in progress

---

## Prerequisites

Before building AeroVPN, ensure you have the following installed:

### Required Software
- **Android Studio**: Hedgehog (2023.1.1) or newer
  - Download: [https://developer.android.com/studio](https://developer.android.com/studio)
- **JDK**: Version 17 or higher
  - Verify: `java -version`
- **Android SDK**: API Level 34 (Android 14)
  - Installed automatically via Android Studio
- **Git**: For version control
  - Download: [https://git-scm.com](https://git-scm.com)

### Minimum Requirements
- **Operating System**: Windows 10/11, macOS 11+, or Linux (64-bit)
- **RAM**: 8 GB minimum (16 GB recommended)
- **Disk Space**: 10 GB free space
- **Internet**: For downloading dependencies

### Optional (for Advanced Development)
- **Android Device**: Physical device with Android 7.0+ for testing
- **Android Emulator**: For testing without physical device
- **ADB**: Android Debug Bridge for device communication

---

## Build Instructions

Follow these steps to build AeroVPN from source:

### 1. Clone the Repository

```bash
# Clone using HTTPS
git clone https://github.com/0xgetz/AeroVPN.git

# Or clone using SSH
git clone git@github.com:0xgetz/AeroVPN.git

# Navigate to project directory
cd AeroVPN
```

### 2. Open in Android Studio

1. Launch Android Studio
2. Select **File** → **Open**
3. Browse to the cloned `AeroVPN` directory
4. Click **OK** to open the project
5. Wait for Gradle sync to complete (may take a few minutes)

### 3. Configure Build Variants

AeroVPN supports multiple build variants:

| Variant | Description | Minify | Debuggable |
|---------|-------------|--------|------------|
| `debug` | Development build | No | Yes |
| `release` | Production build | Yes | No |

To select a build variant:
1. Go to **Build** → **Select Build Variant**
2. Choose `debug` or `release` from the dropdown

### 4. Build the APK

#### Option A: Using Android Studio (GUI)
1. Go to **Build** → **Build Bundle(s) / APK(s)** → **Build APK(s)**
2. Wait for build to complete
3. Click **locate** in the popup to find the APK

#### Option B: Using Command Line (Terminal)

```bash
# Navigate to project root
cd AeroVPN

# Make Gradle wrapper executable (Linux/macOS)
chmod +x gradlew

# Build debug APK
./gradlew assembleDebug
# Windows: gradlew.bat assembleDebug

# Build release APK (requires signing)
./gradlew assembleRelease

# Build all variants
./gradlew assemble
```

### 5. Build Output Locations

After successful build, APKs are located at:

```
AeroVPN/app/build/outputs/apk/
├── debug/
│   └── app-debug.apk              (~25 MB)
└── release/
    ├── app-release.apk            (~12 MB)
    └── app-release-unsigned.apk   (requires signing)
```

### 6. Signing Release APK (Optional)

To create a signed release APK for distribution:

#### Method A: Android Studio
1. **Build** → **Generate Signed Bundle / APK**
2. Select **APK**
3. Create new keystore or select existing one
4. Fill in key information
5. Select `release` build variant
6. Click **Finish**

#### Method B: Command Line

Create `keystore.properties` in project root:
```properties
storePassword=your_store_password
keyPassword=your_key_password
keyAlias=your_key_alias
storeFile=/path/to/your/keystore.jks
```

Then build:
```bash
./gradlew assembleRelease -Pkeystore.properties
```

---

## Installation

### Method 1: Direct APK Installation

1. **Enable Unknown Sources** on your Android device:
   - Go to **Settings** → **Security** → **Unknown Sources** (enable)
   - Or **Settings** → **Apps** → **Special access** → **Install unknown apps**

2. **Transfer APK** to your device:
   - USB cable transfer
   - Email attachment
   - Cloud storage download
   - ADB push: `adb install app-debug.apk`

3. **Install APK**:
   - Open file manager on device
   - Navigate to APK location
   - Tap the APK file
   - Tap **Install**
   - Wait for installation to complete
   - Tap **Open** to launch

### Method 2: Android Studio Installation

1. Connect Android device via USB (enable USB debugging)
2. In Android Studio, click **Run** (green play button)
3. Select your device from the list
4. App will install and launch automatically

### Method 3: ADB Installation

```bash
# Install debug APK
adb install app/build/outputs/apk/debug/app-debug.apk

# Install release APK
adb install app/build/outputs/apk/release/app-release.apk

# Install and replace existing
adb install -r app/build/outputs/apk/release/app-release.apk

# Install to specific device (if multiple connected)
adb -s DEVICE_ID install app-debug.apk
```

### Troubleshooting Installation Issues

| Issue | Solution |
|-------|----------|
| "App not installed" | Uninstall existing version first, check storage space |
| "Blocked by Play Protect" | Tap "Install anyway" or temporarily disable Play Protect |
| "Parse error" | Ensure APK is not corrupted, download again |
| "Incompatible with your device" | Check Android version (requires 7.0+) |

---

## Usage Guide

### Getting Started

#### First Launch
1. Open AeroVPN from your app drawer
2. Grant VPN permission when prompted (required for all VPN apps)
3. Grant notification permission (for connection status)
4. You'll see the main home screen with a large **Connect** button

### Adding a VPN Server

#### Method 1: Manual Entry

1. Go to **Servers** tab (bottom navigation)
2. Tap **[+ Add Server]** button
3. Fill in server details:
   - **Name**: Friendly name (e.g., "Singapore Premium")
   - **Protocol**: Choose from WireGuard, V2Ray, SSH, etc.
   - **Hostname**: Server address (e.g., `sg.example.com`)
   - **Port**: Server port (e.g., `51820` for WireGuard)
   - **Credentials**: Username/password or keys based on protocol

4. Tap **Save** to add server

#### Method 2: Import Configuration

1. Obtain configuration file from your provider (`.conf`, `.json`, or link)
2. Go to **Config** tab
3. Tap **Import Config**
4. Select from:
   - **File**: Browse to config file location
   - **Clipboard**: Paste config text
   - **QR Code**: Scan QR code (for some protocols)
   - **Link**: Paste subscription URL

5. Tap **Import** to add

### Connecting to a VPN Server

#### Quick Connect

1. On **Home** screen, tap server card to select
2. Tap large **Connect** button
3. Wait for connection (status changes to **Connected**)
4. VPN icon appears in status bar
5. Internet traffic now routes through VPN

#### Connection Options

- **Auto-Connect**: Enable in Settings to connect on app launch
- **Last Server**: App remembers last used server
- **Favorite Servers**: Mark frequently used servers as favorites (★)

### Using Advanced Tools

Access tools from **Tools** tab or floating action button:

#### IP Hunter
- Shows your current public IP address
- Displays location, ISP, and VPN status
- Refresh button to update
- Copy IP to clipboard

#### Payload Generator
- Generate custom HTTP payloads for SSH tunneling
- Configure method (GET/POST)
- Add custom headers
- Set random intervals
- Save and apply to SSH configs

#### DNS Checker
- Test connectivity to DNS servers (Google, Cloudflare, etc.)
- Measure response time
- Identify fastest DNS
- Set custom DNS servers

#### Host Checker
- Verify server host accessibility
- Test port connectivity
- Check SSL certificate validity
- View certificate details

#### Slow DNS Detector
- Test DNS tunnel performance
- Measure DNS resolution time
- Detect DNS-based restrictions
- Optimize DNS settings

#### TCP No Delay
- Toggle TCP_NODELAY socket option
- Reduce latency for real-time applications
- Enable for gaming, disable for streaming

#### App Filter (Split Tunneling)
- Select which apps bypass VPN
- Exclude bandwidth-heavy apps
- Force specific apps through VPN
- System apps hidden by default

#### Ping Tool
- Test latency to servers
- Continuous ping mode
- Packet loss statistics
- Historical ping graph

#### Export/Import
- **Export**: Backup all configurations to encrypted file
- **Import**: Restore from backup file
- **Share**: Send config via messengers securely

#### Connection Share
- Share VPN connection via WiFi hotspot
- USB tethering support
- Bluetooth tethering (if supported)
- Configure sharing settings

### Managing Settings

Access via **Settings** tab:

#### General Settings
- **Auto-connect on startup**: Connect to last server automatically
- **Show notification**: Display persistent VPN status notification
- **Dark theme**: Force dark mode or system default
- **Language**: App language selection

#### Connection Settings
- **Reconnect on network change**: Auto-reconnect when switching networks
- **Kill switch**: Block internet when VPN disconnects
- **Exclude private networks**: Bypass VPN for local network devices
- **MTU size**: Configure Maximum Transmission Unit

#### Protocol-Specific Settings
Each protocol has dedicated settings:
- **WireGuard**: Keepalive interval, peer configuration
- **V2Ray**: Transport type, encryption, mux settings
- **SSH**: Connection timeout, compression, SSH version

#### Advanced Settings
- **Log level**: Verbose, Info, Warning, Error
- **Export logs**: Share debug information
- **Clear cache**: Remove temporary files
- **Reset settings**: Restore all settings to default
- **About**: Version info, licenses, privacy policy

---

## Protocol Support

AeroVPN supports multiple VPN and tunneling protocols:

### 1. WireGuard

**Overview**: Modern, high-performance VPN protocol using state-of-the-art cryptography.

**Features**:
- Fast connection speeds
- Low battery consumption
- Simple configuration
- Perfect forward secrecy
- No connection logging

**Best For**: General VPN use, streaming, browsing

**Configuration Requirements**:
- Private key
- Server public key
- Server endpoint (IP:port)
- Allowed IPs (usually `0.0.0.0/0`)
- Pre-shared key (optional)

**Ports**: Typically `51820` (UDP)

### 2. V2Ray / Xray

**Overview**: Advanced protocol suite supporting multiple transport methods and encryption.

**Supported Protocols**:
- **VMess**: Original V2Ray protocol with AEAD encryption
- **VLess**: Lightweight, faster than VMess
- **Trojan**: TLS-based, highly stealthy
- **Shadowsocks**: Simple SOCKS5 proxy with encryption

**Features**:
- Multiple transport options (TCP, mKCP, WebSocket, HTTP/2)
- TLS encryption support
- Domain fronting capability
- Multipath support
- Traffic obfuscation

**Best For**: Bypassing restrictions, high-security needs

**Ports**: Varies (commonly `443`, `8443`, `8080`)

### 3. SSH Tunnel

**Overview**: Secure Shell tunnel with optional HTTP proxy and SSL wrapping.

**Features**:
- Standard SSH authentication
- HTTP proxy support (for corporate networks)
- SSL/TLS wrapping (stunnel)
- WebSocket support
- Compression options
- Port forwarding

**Best For**: Corporate networks, restricted environments

**Authentication**:
- Password-based
- Public key (RSA, ECDSA, Ed25519)
- Certificate-based

**Ports**: Typically `22` (can be any port)

### 4. Shadowsocks

**Overview**: Lightweight SOCKS5 proxy with encryption, designed for circumvention.

**Encryption Methods**:
- AES-128-GCM, AES-256-GCM
- CHACHA20-IETF-POLY1305
- XCHACHA20-IETF-POLY1305

**Features**:
- Simple configuration
- Low overhead
- Plugin support (v2ray-plugin, obfs-local)
- UDP relay support

**Best For**: Simple proxy needs, low-resource devices

**Ports**: Typically `8388` or custom

### 5. Custom UDP Tunnel

**Overview**: User-defined UDP tunneling for specialized use cases.

**Features**:
- Custom packet format
- Configurable endpoints
- Encryption options
- Keepalive settings

**Best For**: Custom implementations, gaming, VoIP

**Ports**: User-defined

---

## Configuration Examples

### WireGuard Configuration

#### Configuration File (`wireguard.conf`)
```ini
[Interface]
PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
Address = 10.0.0.2/24
DNS = 1.1.1.1, 8.8.8.8

[Peer]
PublicKey = xTIBA5rboUvnH4htodjb6nd69Qvj86m7TczkggSB3eM=
Endpoint = 192.168.1.100:51820
AllowedIPs = 0.0.0.0/0
PersistentKeepalive = 25
```

#### Importing WireGuard Config
1. Save config as `.conf` file or copy to clipboard
2. Open AeroVPN → **Config** → **Import Config**
3. Select **File** or **Clipboard**
4. Paste or select the configuration
5. Tap **Import**
6. Name the configuration (e.g., "Home WireGuard")
7. Tap **Connect**

### V2Ray Configuration

#### JSON Configuration
```json
{
  "inbounds": [{
    "port": 10808,
    "protocol": "socks",
    "settings": {
      "auth": "noauth",
      "udp": true,
      "userLevel": 8
    },
    "tag": "socks"
  }],
  "outbounds": [{
    "tag": "proxy",
    "protocol": "vmess",
    "settings": {
      "vnext": [{
        "address": "v2ray.example.com",
        "port": 443,
        "users": [{
          "id": "b831381d-6324-4d53-ad4f-8cda48b30811",
          "alterId": 0,
          "email": "user@example.com",
          "security": "auto"
        }]
      }]
    },
    "streamSettings": {
      "network": "ws",
      "security": "tls",
      "tlsSettings": {
        "allowInsecure": false,
        "serverName": "v2ray.example.com"
      },
      "wsSettings": {
        "path": "/ray",
        "headers": {
          "Host": "v2ray.example.com"
        }
      }
    },
    "mux": {
      "enabled": true,
      "concurrency": 8
    }
  }],
  "routing": {
    "domainStrategy": "AsIs"
  }
}
```

#### VMess Link Format
```
vmess://eyJhZGQiOiJ2MnJheS5leGFtcGxlLmNvbSIsImFpZCI6IjAiLCJ
ob3N0IjoidjJyYXkuZXhhbXBsZS5jb20iLCJpZCI6ImI4MzEzODFkLT
YzMjQtNGQ1My1hZDRmLThjZGE0OGIzMDgxMSIsIm5ldCI6IndzIiwi
cGF0aCI6Ii9yYXkiLCJwb3J0IjoiNDQzIiwicHMiOiJFeGFtcGxlIF
ZNU2VzIiwidGxzIjoidGxzIiwidHlwZSI6Im5vbmUiLCJ2IjoiMiJ9
```

### SSH Configuration

#### Manual SSH Setup
```yaml
Host: ssh.example.com
Port: 22
Username: myuser
Password: ***********  # Not saved in config
Protocol: SSH
# Advanced settings:
Compression: true
Timeout: 30s
SSH Version: 2
Proxy: none
SSL Wrapping: false
```

#### SSH with HTTP Proxy
```yaml
Host: ssh.example.com
Port: 22
Username: myuser
Protocol: SSH
HTTP Proxy:
  Enabled: true
  Host: proxy.corporate.com
  Port: 8080
  Username: proxyuser
  Password: ***********
SSL Wrapping: true
SSL Host: secure.example.com
SSL Port: 443
```

#### SSH with Payload (for DPI bypass)
```yaml
Host: ssh.example.com
Port: 443
Username: myuser
Protocol: SSH
Payload:
  Method: GET
  Request: |
    GET / HTTP/1.1[crlf]
    Host: www.google.com[crlf]
    User-Agent: [ua][crlf]
    X-Online-Host: www.google.com[crlf]
    X-Forward-For: www.google.com[crlf][crlf]
  Interval: 60
```

### Shadowsocks Configuration

#### Simple Config
```json
{
  "server": "ss.example.com",
  "server_port": 8388,
  "password": "mysecretpassword",
  "method": "chacha20-ietf-poly1305",
  "plugin": "",
  "remarks": "Example SS Server"
}
```

#### With v2ray-plugin
```json
{
  "server": "ss.example.com",
  "server_port": 443,
  "password": "mysecretpassword",
  "method": "chacha20-ietf-poly1305",
  "plugin": "v2ray-plugin",
  "plugin_opts": "server=ss.example.com;tls;host=cdn.example.com;path=/websocket"
}
```

#### Shadowsocks URI
```
ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpteXNlY3JldHBhc3N3b3Jk
@ss.example.com:8388/?plugin=v2ray-plugin%3Bserver%3Dss.example.com%3Btls
%3Bhost%3Dcdn.example.com#ExampleSS
```

### Trojan Configuration

```json
{
  "server": "trojan.example.com",
  "server_port": 443,
  "password": "your_password_here",
  "ssl": {
    "sni": "trojan.example.com",
    "allow_insecure": false,
    "verify": true
  },
  "transport": {
    "type": "tcp"
  }
}
```

#### Trojan URI
```
trojan://your_password_here@trojan.example.com:443?sni=trojan.example.com&allow_insecure=false#TrojanServer
```

---

## Troubleshooting

### Connection Issues

#### Cannot Connect to Server
**Possible Causes**:
- Incorrect server address or port
- Firewall blocking connection
- Server is offline
- Wrong credentials

**Solutions**:
1. Verify server address and port
2. Use **Host Checker** tool to test connectivity
3. Try alternative server
4. Check credentials are correct
5. Restart router/firewall
6. Contact server administrator

#### Connection Drops Frequently
**Possible Causes**:
- Unstable internet connection
- Server overload
- Keepalive interval too long
- Network switching

**Solutions**:
1. Enable **Reconnect on network change**
2. Reduce Keepalive interval (WireGuard: try 15-20s)
3. Use **Ping Tool** to check server stability
4. Switch to closer server
5. Enable **Kill switch** to prevent data leaks

#### Very Slow Connection Speed
**Possible Causes**:
- Server overload
- Long distance to server
- Protocol inefficiency
- Device performance

**Solutions**:
1. Switch to closer server
2. Change protocol (try WireGuard for speed)
3. Use **Ping Tool** to find fastest server
4. Enable **TCP No Delay** for SSH
5. Reduce encryption complexity
6. Close background apps consuming bandwidth

### Protocol-Specific Issues

#### WireGuard Connection Fails
**Check**:
- Private and public keys are correct
- Server endpoint is reachable
- Allowed IPs includes `0.0.0.0/0`
- Firewall allows UDP on configured port
- MTU setting is appropriate (try 1280-1420)

**Solution**:
```ini
# Try these MTU values if connection fails
MTU = 1280  # Most conservative
MTU = 1420  # Standard for WireGuard
MTU = 1500  # Maximum (may fragment)
```

#### V2Ray Connection Fails
**Check**:
- UUID is correct (no extra spaces)
- Host header matches SNI
- WebSocket path is correct
- TLS certificate is valid
- Transport settings match server

**Solution**:
1. Validate JSON config syntax
2. Test with **Host Checker**
3. Try `allowInsecure: true` for self-signed certs (not recommended)
4. Verify WebSocket path starts with `/`
5. Check server supports requested transport

#### SSH Connection Times Out
**Check**:
- SSH server is running on target port
- SSH version compatibility (2.0 recommended)
- Proxy settings are correct
- Compression enabled for slow connections

**Solution**:
1. Increase timeout to 60s
2. Enable compression
3. Use SSH version 2 only
4. Simplify payload (remove if not needed)
5. Try without HTTP proxy first

### App Issues

#### App Crashes on Launch
**Solutions**:
1. Clear app cache: Settings → Apps → AeroVPN → Storage → Clear Cache
2. Reinstall app
3. Check Android version (requires 7.0+)
4. Disable battery optimization for app
5. Check available storage space
6. Report crash logs via **Export Logs**

#### VPN Icon Not Showing
**Solutions**:
1. Grant VPN permission: Settings → Apps → AeroVPN → Permissions
2. Enable **Show notification** in app settings
3. Check notification permission is granted
4. Reboot device
5. Reconnect to VPN

#### Battery Drain
**Solutions**:
1. Use WireGuard protocol (most efficient)
2. Increase keepalive interval
3. Disable unused features
4. Exclude battery-heavy apps via **App Filter**
5. Enable battery optimization except for app itself

#### Config Import Fails
**Solutions**:
1. Verify config format matches protocol
2. Check for encoding issues (UTF-8)
3. Remove extra whitespace/formatting
4. Try manual entry
5. Update app to latest version
6. Contact config provider for updated format

### Common Error Messages

| Error Message | Meaning | Solution |
|--------------|---------|----------|
| "Permission denied" | VPN permission not granted | Grant permission in system settings |
| "Server unreachable" | Cannot connect to server | Check address, port, firewall |
| "Authentication failed" | Wrong credentials | Verify username/password/keys |
| "Invalid configuration" | Config format error | Check JSON/INI syntax |
| "Connection timed out" | No response from server | Try different server, check network |
| "TUN device busy" | Another VPN active | Disconnect other VPN apps |
| "Certificate invalid" | SSL/TLS cert problem | Use correct SNI, check cert expiry |

---

## FAQ

### General Questions

**Q: Is AeroVPN completely free?**
A: Yes, AeroVPN is 100% free and open-source. There are no subscriptions, premium features, or hidden costs.

**Q: Does AeroVPN keep logs?**
A: No. AeroVPN does not collect, store, or transmit any user activity logs, connection logs, or metadata. All configuration is stored locally on your device.

**Q: Is AeroVPN safe to use?**
A: Yes. AeroVPN uses industry-standard encryption protocols, has no tracking or ads, and is open-source (source code is publicly auditable). However, always use trusted VPN servers.

**Q: Do I need to create an account?**
A: No account is required. AeroVPN works completely offline and locally. You manage your own server configurations.

**Q: Does AeroVPN work on all Android devices?**
A: AeroVPN requires Android 7.0 (API 24) or higher. It is compatible with phones and tablets from most manufacturers.

### Technical Questions

**Q: What is the minimum APK size?**
A: With full R8 optimization, the release APK is typically under 15MB. The exact size depends on included protocols and features.

**Q: Can I use AeroVPN on rooted devices?**
A: Yes, AeroVPN works on both rooted and non-rooted devices. Root access is not required for any functionality.

**Q: Does AeroVPN support IPv6?**
A: Yes. Configure IPv6 addresses in your server configuration. WireGuard and V2Ray both support IPv6 routing.

**Q: Can I run AeroVPN alongside other VPN apps?**
A: No. Android allows only one active VPN connection at a time. You must disconnect other VPNs before using AeroVPN.

**Q: Why does the notification stay on?**
A: Android requires a persistent notification while VPN is active. This is a system requirement, not optional. AeroVPN minimizes the notification to reduce distraction.

**Q: Does AeroVPN support split tunneling?**
A: Yes. Use the **App Filter** tool to select which apps bypass the VPN tunnel. This is protocol-independent.

### Performance Questions

**Q: Which protocol is fastest?**
A: WireGuard is generally the fastest protocol with lowest latency and best throughput. V2Ray with mKCP can match it in some conditions.

**Q: Will using AeroVPN drain my battery?**
A: All VPN apps increase battery usage. WireGuard has the lowest impact (5-10% additional drain). SSH and V2Ray consume more (10-20%) depending on encryption.

**Q: Why is my speed slower with VPN?**
A: VPN adds encryption overhead and routing distance. Expect 10-30% speed reduction normally. Use WireGuard and nearby servers to minimize impact.

**Q: Can I use AeroVPN for gaming?**
A: Yes, but choose servers close to game servers. WireGuard protocol has lowest latency. Enable **TCP No Delay** for SSH connections.

### Security Questions

**Q: Is my data encrypted?**
A: Yes. All traffic is encrypted using the selected protocol's encryption (WireGuard uses ChaCha20, V2Ray uses AES-256-GCM, etc.).

**Q: What encryption does AeroVPN use?**
A: Encryption depends on protocol:
   - WireGuard: ChaCha20-Poly1305
   - V2Ray: AES-256-GCM, ChaCha20-Poly1305
   - SSH: AES-256-CTR, ChaCha20-Poly1305
   - Shadowsocks: Configurable (recommended: ChaCha20-IETF-Poly1305)

**Q: Does AeroVPN have a kill switch?**
A: Yes. Enable **Kill Switch** in Settings to block all internet traffic if VPN disconnects unexpectedly.

**Q: Can the government track my usage?**
A: With proper protocol selection (WireGuard, V2Ray with TLS), your traffic appears encrypted. However, sophisticated actors may detect VPN usage patterns. No VPN is 100% undetectable.

**Q: Are exported configs encrypted?**
A: Yes. Exported configuration files are encrypted using AES-256-GCM. Keep backups secure and never share them.

---

## Contributing

Contributions are welcome! Here's how you can help:

### Ways to Contribute

1. **Report Bugs**: Found a bug? Open an issue on GitHub
2. **Suggest Features**: Have an idea? Create a feature request
3. **Improve Documentation**: Fix typos, add examples, improve clarity
4. **Submit Code**: Fix bugs, add features, improve performance
5. **Translate**: Help localize AeroVPN to your language
6. **Test Builds**: Test pre-release versions and provide feedback

### Development Setup

1. **Fork the repository**
   ```bash
   git clone https://github.com/YOUR_USERNAME/AeroVPN.git
   cd AeroVPN
   ```

2. **Create a branch**
   ```bash
   git checkout -b feature/your-feature-name
   ```

3. **Make your changes**
   - Follow Kotlin style guide
   - Add tests for new features
   - Update documentation

4. **Commit your changes**
   ```bash
   git commit -m "Add: concise description of changes"
   ```

5. **Push to your fork**
   ```bash
   git push origin feature/your-feature-name
   ```

6. **Open a Pull Request**
   - Describe your changes
   - Link to related issues
   - Wait for review

### Code Guidelines

#### Kotlin Style
- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use meaningful variable and function names
- Add comments for complex logic
- Keep functions small and focused

#### Architecture
- Use MVVM (Model-View-ViewModel) pattern
- Separate UI from business logic
- Use dependency injection (Hilt)
- Write unit tests for ViewModels

#### Commit Messages
Format: `[TYPE]: Brief description`

Types:
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `style`: Code style changes (formatting)
- `refactor`: Code refactoring
- `perf`: Performance improvements
- `test`: Adding or updating tests
- `chore`: Build/config changes

Examples:
```
feat: Add TCP No Delay toggle in settings
fix: Resolve crash when importing invalid JSON config
docs: Update README with VLess configuration example
refactor: Extract protocol handler into separate class
```

### Pull Request Process

1. Ensure code compiles and tests pass
2. Update README if behavior changes
3. Add/update unit tests
4. Request review from maintainers
5. Address review feedback
6. Merge after approval

### Reporting Issues

When creating an issue, include:

**Bug Reports**:
- Steps to reproduce
- Expected behavior
- Actual behavior
- Device model and Android version
- AeroVPN version
- Screenshots/videos if applicable
- Logs (Settings → Export Logs)

**Feature Requests**:
- Clear description
- Use case / motivation
- Suggested implementation (optional)
- Examples from other apps (optional)

### Testing

Before submitting:
- [ ] Debug build works
- [ ] Release build compiles
- [ ] No new linting errors
- [ ] Unit tests pass
- [ ] App tested on physical device
- [ ] No memory leaks (check with Profiler)
- [ ] Battery impact is reasonable

### Community Guidelines

- Be respectful and constructive
- Help others in issues/discussions
- Follow code of conduct
- No spam or self-promotion
- Credit original authors

---

## License

AeroVPN is released under the **MIT License**.

```
MIT License

Copyright (c) 2026 AeroVPN Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### License Summary

✅ **You can**:
- Use AeroVPN for personal or commercial purposes
- Modify the source code
- Distribute copies
- Use in proprietary software
- Sublicense or sell

❌ **You cannot**:
- Hold authors liable for damages
- Use contributors' names for endorsement without permission

📝 **You must**:
- Include copyright notice
- Include license text
- State significant changes made

**Full License**: [https://opensource.org/licenses/MIT](https://opensource.org/licenses/MIT)

---

## Support

### Need Help?

- **Documentation**: You're reading it! 📖
- **Issues**: [GitHub Issues](https://github.com/0xgetz/AeroVPN/issues)
- **Discussions**: [GitHub Discussions](https://github.com/0xgetz/AeroVPN/discussions)
- **Email**: Support inquiries (check repository for contact)

### Resources

- **Android VPN API**: [https://developer.android.com/reference/android/net/VpnService](https://developer.android.com/reference/android/net/VpnService)
- **WireGuard Docs**: [https://www.wireguard.com](https://www.wireguard.com)
- **V2Ray Guide**: [https://www.v2ray.com](https://www.v2ray.com)
- **Kotlin Lang**: [https://kotlinlang.org](https://kotlinlang.org)
- **Jetpack Compose**: [https://developer.android.com/jetpack/compose](https://developer.android.com/jetpack/compose)

---

**Developed with ❤️ for privacy and freedom**

Made in Android with Kotlin & Jetpack Compose

**Version**: 1.0.0  
**Last Updated**: April 2026
**Repository**: [https://github.com/0xgetz/AeroVPN](https://github.com/0xgetz/AeroVPN)
