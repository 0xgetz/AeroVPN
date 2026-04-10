# Contributing to AeroVPN

Thank you for your interest in contributing to AeroVPN! This document provides guidelines and instructions for contributing to the project. We welcome all forms of contribution — code, documentation, bug reports, feature requests, and translations.

---

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [How to Contribute](#how-to-contribute)
- [Development Setup](#development-setup)
- [Project Structure](#project-structure)
- [Coding Standards](#coding-standards)
- [Commit Guidelines](#commit-guidelines)
- [Pull Request Process](#pull-request-process)
- [Reporting Bugs](#reporting-bugs)
- [Requesting Features](#requesting-features)
- [Security Vulnerabilities](#security-vulnerabilities)
- [Translations](#translations)

---

## Code of Conduct

By participating in this project, you agree to maintain a respectful and constructive environment. We expect all contributors to:

- Be respectful and considerate in all interactions
- Accept constructive feedback gracefully
- Focus on what is best for the community and project
- Show empathy towards other community members
- Refrain from harassment, discrimination, or offensive language

Violations of these standards may result in removal from the project.

---

## Getting Started

### Prerequisites

Before contributing, ensure you have the following installed:

| Tool | Version | Purpose |
|------|---------|---------|
| Android Studio | Hedgehog (2023.1.1)+ | IDE |
| JDK | 17+ | Build toolchain |
| Android SDK | API 34 | Target platform |
| Git | 2.30+ | Version control |
| Kotlin | 1.9.22+ | Primary language |

### Fork and Clone

1. **Fork** the repository by clicking the Fork button on GitHub
2. **Clone** your fork locally:
   ```bash
   git clone https://github.com/YOUR_USERNAME/AeroVPN.git
   cd AeroVPN
   ```
3. **Add the upstream remote** to keep your fork in sync:
   ```bash
   git remote add upstream https://github.com/0xgetz/AeroVPN.git
   ```
4. **Verify remotes**:
   ```bash
   git remote -v
   # origin    https://github.com/YOUR_USERNAME/AeroVPN.git (fetch)
   # origin    https://github.com/YOUR_USERNAME/AeroVPN.git (push)
   # upstream  https://github.com/0xgetz/AeroVPN.git (fetch)
   # upstream  https://github.com/0xgetz/AeroVPN.git (push)
   ```

---

## How to Contribute

### Types of Contributions

| Type | Description | Skill Level |
|------|-------------|-------------|
| Bug fixes | Fix reported issues | Beginner+ |
| Documentation | Improve README, comments, wiki | Beginner |
| Translations | Localize the app to new languages | Beginner |
| UI improvements | Polish the Jetpack Compose UI | Intermediate |
| New protocol support | Add new VPN/tunneling protocols | Advanced |
| Performance | Optimize speed, battery, memory | Advanced |
| Security | Harden encryption, fix vulnerabilities | Expert |
| Tests | Add unit/integration tests | Intermediate |

### Finding Issues to Work On

- Browse [open issues](https://github.com/0xgetz/AeroVPN/issues) on GitHub
- Look for issues labeled `good first issue` for beginner-friendly tasks
- Issues labeled `help wanted` are prioritized for community contributions
- Check the [project board](https://github.com/0xgetz/AeroVPN/projects) for planned work

**Before starting work**, comment on the issue to let maintainers know you're working on it. This avoids duplicate effort.

---

## Development Setup

### 1. Open the Project

1. Launch Android Studio
2. Select **File** → **Open**
3. Navigate to the cloned `AeroVPN` directory
4. Click **OK** and wait for Gradle sync

### 2. Create a Feature Branch

Always branch off `main` for new work:

```bash
# Sync your fork with upstream
git fetch upstream
git checkout main
git merge upstream/main

# Create your feature branch
git checkout -b feature/your-feature-name
# or for bug fixes:
git checkout -b fix/issue-description
# or for documentation:
git checkout -b docs/what-you-changed
```

**Branch naming conventions:**

| Prefix | Use for |
|--------|---------|
| `feature/` | New features |
| `fix/` | Bug fixes |
| `docs/` | Documentation only |
| `refactor/` | Code restructuring |
| `perf/` | Performance improvements |
| `test/` | Adding or fixing tests |
| `chore/` | Build/config changes |

### 3. Run the App

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Run all checks
./gradlew check

# Run lint
./gradlew lint
```

### 4. Verify Your Changes

Before submitting, ensure:
- [ ] App builds successfully (`./gradlew assembleDebug`)
- [ ] No new lint warnings (`./gradlew lint`)
- [ ] App tested on a physical device or emulator
- [ ] No sensitive data (API keys, passwords) hardcoded in code
- [ ] New features have appropriate comments

---

## Project Structure

```
AeroVPN/
├── app/
│   └── src/
│       └── main/
│           ├── java/com/aerovpn/
│           │   ├── AeroVPNApplication.kt      # Application class
│           │   ├── config/
│           │   │   └── VpnConfig.kt           # VPN configuration models
│           │   ├── receiver/
│           │   │   ├── BootReceiver.kt        # Auto-start on boot
│           │   │   ├── NetworkStateReceiver.kt # Network change detection
│           │   │   ├── PackageUpdateReceiver.kt# App install/uninstall events
│           │   │   └── PowerStateReceiver.kt  # Power state events
│           │   ├── service/
│           │   │   ├── AeroVpnService.kt      # Core VPN service
│           │   │   └── protocol/
│           │   │       ├── ProtocolHandler.kt # Protocol abstraction
│           │   │       ├── SSHProtocol.kt     # SSH tunneling
│           │   │       ├── ShadowsocksProtocol.kt
│           │   │       ├── UdpTunnelProtocol.kt
│           │   │       ├── V2RayProtocol.kt   # V2Ray/Xray
│           │   │       └── WireGuardProtocol.kt
│           │   ├── tools/
│           │   │   ├── AppsFilterTool.kt      # Split tunneling
│           │   │   ├── CustomDnsTool.kt       # DNS configuration
│           │   │   ├── ExportImportTool.kt    # Config backup/restore
│           │   │   ├── HostCheckerTool.kt     # Host connectivity test
│           │   │   ├── IpHunterTool.kt        # Public IP lookup
│           │   │   ├── PayloadGeneratorTool.kt# HTTP payload for tunnels
│           │   │   ├── PingTool.kt            # Latency test
│           │   │   ├── ShareConnectionTool.kt # Hotspot/tethering
│           │   │   ├── SlowDnsCheckerTool.kt  # DNS tunnel test
│           │   │   └── TcpNoDelayTool.kt      # TCP optimization
│           │   └── ui/
│           │       ├── MainActivity.kt        # Entry point activity
│           │       ├── navigation/            # Navigation graph
│           │       ├── screens/               # Compose screens
│           │       │   ├── HomeScreen.kt
│           │       │   ├── ServerListScreen.kt
│           │       │   ├── ToolsScreen.kt
│           │       │   ├── ConfigScreen.kt
│           │       │   └── SettingsScreen.kt
│           │       └── theme/                 # Material 3 theming
│           └── res/
│               ├── drawable/                  # Icons and graphics
│               ├── values/                    # Strings, colors, themes
│               └── xml/                       # Network security config
├── gradle/                                    # Gradle wrapper
├── .github/                                   # CI/CD workflows
├── LICENSE
├── README.md
├── CONTRIBUTING.md
├── .gitignore
├── build.gradle
├── settings.gradle
└── gradle.properties
```

### Architecture Overview

AeroVPN follows **MVVM (Model-View-ViewModel)** architecture:

```
UI Layer (Compose Screens)
        ↕
ViewModel Layer (State management)
        ↕
Domain Layer (Business logic, Protocol handlers)
        ↕
Data Layer (VpnConfig, DataStore, Room)
        ↕
System Layer (Android VpnService, Network APIs)
```

Key architectural decisions:
- **Jetpack Compose** for all UI — no XML layouts
- **Kotlin Coroutines** for async operations
- **Android VpnService** as the foundation for all protocols
- **DataStore** for preferences over SharedPreferences
- **BuildConfig** fields for any environment-specific values (never hardcoded)

---

## Coding Standards

### Kotlin Style

Follow the [official Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html). Key rules:

```kotlin
// Good: descriptive names, single responsibility
fun connectToServer(config: VpnConfig): Result<Unit> {
    // ...
}

// Bad: cryptic names, multiple responsibilities
fun doStuff(c: Any): Boolean {
    // ...
}
```

**Formatting:**
- 4-space indentation (no tabs)
- Max line length: 120 characters
- Opening braces on the same line
- Trailing commas in multi-line expressions

**Naming conventions:**

| Element | Convention | Example |
|---------|------------|---------|
| Classes | PascalCase | `VpnConfig`, `AeroVpnService` |
| Functions | camelCase | `connectToServer()` |
| Properties | camelCase | `isConnected`, `serverAddress` |
| Constants | UPPER_SNAKE_CASE | `MAX_RETRY_COUNT` |
| Private fields | camelCase (no underscore prefix) | `connectionState` |

### Compose UI Guidelines

```kotlin
// Good: stateless composable with hoisted state
@Composable
fun ConnectButton(
    isConnected: Boolean,
    onConnectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onConnectClick,
        modifier = modifier
    ) {
        Text(if (isConnected) "Disconnect" else "Connect")
    }
}

// Bad: state inside composable (hard to test/preview)
@Composable
fun ConnectButton() {
    var isConnected by remember { mutableStateOf(false) }
    Button(onClick = { isConnected = !isConnected }) {
        Text(if (isConnected) "Disconnect" else "Connect")
    }
}
```

**Compose best practices:**
- Keep composables small and focused on a single responsibility
- Hoist state as high as necessary, no higher
- Use `modifier` parameter in every composable for flexibility
- Preview composables with `@Preview` annotations
- Avoid side effects in composable bodies — use `LaunchedEffect`, `SideEffect`, etc.

### Protocol Handler Guidelines

When adding a new protocol or modifying an existing one:

1. Implement the `ProtocolHandler` interface
2. Handle all connection lifecycle events: `connect()`, `disconnect()`, `reconnect()`
3. Properly release all resources in `disconnect()`
4. Never store credentials beyond the active session
5. Log connection events at appropriate levels (INFO for state changes, DEBUG for internals)
6. Return descriptive error messages in `Result.failure()`

```kotlin
// Template for a new protocol
class MyProtocol : ProtocolHandler {
    override suspend fun connect(config: VpnConfig): Result<Unit> {
        return try {
            // Implementation
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun disconnect(): Result<Unit> {
        // Always clean up resources
        return Result.success(Unit)
    }
}
```

### Security Guidelines

**Never hardcode sensitive values:**
```kotlin
// BAD - never do this
val apiKey = "sk-abc123xyz"
val serverPassword = "mypassword123"

// GOOD - use BuildConfig or runtime input
val apiKey = BuildConfig.API_KEY  // set via gradle, not in code
// Or better: receive from user input at runtime, never store in source
```

**Handle user credentials safely:**
- Never log passwords, keys, or tokens
- Clear sensitive data from memory when no longer needed
- Use Android Keystore for storing cryptographic keys
- Validate all imported configuration files before use

---

## Commit Guidelines

We follow the [Conventional Commits](https://www.conventionalcommits.org/) specification.

### Format

```
<type>(<scope>): <short description>

[optional body]

[optional footer(s)]
```

### Types

| Type | Description |
|------|-------------|
| `feat` | New feature |
| `fix` | Bug fix |
| `docs` | Documentation changes only |
| `style` | Code style changes (formatting, no logic change) |
| `refactor` | Code change that neither fixes a bug nor adds a feature |
| `perf` | Performance improvement |
| `test` | Adding or updating tests |
| `chore` | Build process, dependency updates, tooling |
| `ci` | CI/CD configuration changes |
| `revert` | Reverts a previous commit |

### Scope (optional)

Use the affected component: `service`, `ui`, `protocol`, `tools`, `config`, `build`

### Examples

```bash
feat(protocol): add VLESS protocol support with XTLS

fix(service): resolve crash when switching protocols during active connection

docs(readme): add VLESS configuration example and URI format

perf(wireguard): reduce CPU usage by batching keepalive packets

refactor(tools): extract IP geolocation logic into dedicated class

chore(deps): bump OkHttp from 4.11.0 to 4.12.0

fix(ui): correct dark theme colors on ServerListScreen

feat(tools): add QR code scanner for config import
```

### Short Description Rules

- Use imperative mood: "add" not "added" or "adds"
- No capital letter at start
- No period at the end
- Maximum 72 characters
- Be specific: "fix WireGuard reconnect on network switch" not "fix bug"

---

## Pull Request Process

### Before Opening a PR

1. **Sync with upstream** to avoid conflicts:
   ```bash
   git fetch upstream
   git rebase upstream/main
   ```

2. **Test your changes** thoroughly on a real device if possible

3. **Run the full check suite:**
   ```bash
   ./gradlew check lint assembleDebug
   ```

4. **Self-review** your diff before submitting — check for debug code, TODOs, or accidental changes

### PR Title and Description

Use the same format as commit messages for the PR title.

The PR description should include:

```markdown
## Summary
Brief explanation of what this PR does and why.

## Changes
- List of specific changes made
- ...

## Testing
- [ ] Tested on physical device (Android X.X)
- [ ] Tested on emulator (API XX)
- [ ] Existing features not broken
- [ ] New feature works as expected

## Screenshots (if UI changes)
Before | After
-------|------
[img]  | [img]

## Related Issues
Fixes #123
Closes #456
```

### Review Process

1. A maintainer will review your PR within a few days
2. Address any requested changes promptly
3. Keep the PR focused — one feature/fix per PR
4. Don't force-push after review has started (use new commits instead)
5. PRs are merged with squash-merge to keep history clean

### Checklist Before Submitting

- [ ] Branch is up to date with `main`
- [ ] Code follows the project's style guidelines
- [ ] No hardcoded secrets, API keys, or passwords
- [ ] New features are documented
- [ ] Existing README sections updated if behavior changed
- [ ] Build passes (`./gradlew assembleDebug`)
- [ ] Lint passes (`./gradlew lint`)
- [ ] App tested on device/emulator
- [ ] Commit messages follow the convention
- [ ] PR description is complete

---

## Reporting Bugs

Use the [GitHub Issues](https://github.com/0xgetz/AeroVPN/issues) page to report bugs. Before opening a new issue:

1. **Search existing issues** to avoid duplicates
2. **Try the latest version** — the bug may already be fixed
3. **Reproduce the bug** consistently

### Bug Report Template

```markdown
**Bug Description**
A clear and concise description of what the bug is.

**Steps to Reproduce**
1. Go to '...'
2. Tap on '...'
3. See error

**Expected Behavior**
What you expected to happen.

**Actual Behavior**
What actually happened.

**Environment**
- AeroVPN version: [e.g., 1.0.0]
- Android version: [e.g., Android 13]
- Device model: [e.g., Samsung Galaxy S21]
- Protocol used: [e.g., WireGuard]

**Logs**
If applicable, export logs from Settings → Export Logs and attach here
(redact any personal information first).

**Screenshots**
If applicable, add screenshots to help explain the problem.
```

---

## Requesting Features

Feature requests are welcome! Use [GitHub Issues](https://github.com/0xgetz/AeroVPN/issues) with the `enhancement` label.

### Feature Request Template

```markdown
**Feature Description**
A clear description of the feature you'd like to see.

**Problem it Solves**
What problem does this feature address? Who would benefit?

**Proposed Solution**
If you have ideas about implementation, describe them here.

**Alternatives Considered**
Any alternative approaches you've considered.

**Additional Context**
Screenshots, mockups, or references to similar features in other apps.
```

---

## Security Vulnerabilities

**Do not open a public GitHub issue for security vulnerabilities.**

If you discover a security vulnerability in AeroVPN, please report it responsibly:

1. Go to the repository's **Security** tab on GitHub
2. Click **Report a vulnerability**
3. Fill in the details of the vulnerability

Or contact the maintainers directly through the contact information in the repository.

**What to include:**
- Description of the vulnerability
- Steps to reproduce
- Potential impact
- Suggested fix (if you have one)

We aim to respond to security reports within 48 hours and will credit reporters in our security advisories (unless you prefer to remain anonymous).

---

## Translations

AeroVPN aims to support multiple languages. To add or improve a translation:

1. Navigate to `app/src/main/res/`
2. Create a new values directory for your language:
   - `values-id/` for Indonesian (Bahasa Indonesia)
   - `values-de/` for German
   - `values-fr/` for French
   - `values-ja/` for Japanese
   - etc. (use [BCP 47 language tags](https://tools.ietf.org/html/bcp47))
3. Copy `values/strings.xml` into the new directory
4. Translate the string values (keep the string names unchanged)
5. Submit a PR with the new translation file

**Translation guidelines:**
- Translate the values, not the keys
- Keep format specifiers (`%1$s`, `%d`) in the correct position
- Maintain the same tone (concise, technical but accessible)
- If unsure about a term, keep the English term with a local transliteration
- Mark any strings you're unsure about with `<!-- TODO: verify translation -->`

---

## Questions?

If you have questions not covered in this guide:

- Check the [README](README.md) for project overview and documentation
- Open a [GitHub Discussion](https://github.com/0xgetz/AeroVPN/discussions) for general questions
- Browse [closed issues](https://github.com/0xgetz/AeroVPN/issues?q=is%3Aissue+is%3Aclosed) for past answers

---

**Thank you for contributing to AeroVPN!**

Every contribution, no matter how small, helps make privacy and internet freedom more accessible to everyone.
