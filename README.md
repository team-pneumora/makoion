<p align="center">
  <h1 align="center">Makoion</h1>
  <p align="center">
    <strong>Your smartphone, your server, your agent.</strong>
  </p>
  <p align="center">
    An open-source, chat-based AI agent that turns your mobile device into a personal server.
  </p>
  <p align="center">
    <a href="https://github.com/team-pneumora/makoion/blob/main/LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License: MIT"></a>
    <a href="https://github.com/team-pneumora/makoion/stargazers"><img src="https://img.shields.io/github/stars/team-pneumora/makoion.svg?style=social" alt="GitHub Stars"></a>
    <a href="https://github.com/sponsors/team-pneumora"><img src="https://img.shields.io/badge/sponsor-%E2%99%A1-ff69b4.svg" alt="Sponsor"></a>
  </p>
</p>

---

## Why Makoion?

Self-hosting has always had an annoying tradeoff:

- **A dedicated PC (Mac Mini, NUC, etc.)**: always on, but eats electricity 24/7
- **Cloud VPS**: always available, but your data lives on someone else's machine. It's never truly yours.

Then there is the device that is already always on, always connected, and already holds your most personal data: **your smartphone**.

Makoion turns your mobile device into a personal server that you fully own and control, not as a simple file server, but as a **chat-based AI agent** that manages everything for you.

## What Is Makoion?

Makoion is an **agentic mobile platform**: a chat-based AI agent app that lives on your phone and acts on your behalf.

```text
You: "Back up my photos to the NAS"
Makoion: Done. 1,247 photos synced to /photos/2026-03.

You: "What's eating my storage?"
Makoion: Top 3: Videos (12.4GB), Downloads (8.1GB), Cache (3.2GB). Want me to clean up cache?
```

### Core Capabilities

- Chat-based interface: talk to your device and let it act
- File and folder management: access, organize, and sync through conversation
- MCP (Model Context Protocol): extensible tool integration
- Cross-device orchestration: connect and control your other devices
- Privacy-first ownership: no mandatory cloud dependency, no third-party control plane

### The Philosophy

> Yes, giving an AI agent full access to your phone is risky. But that is also what makes it interesting.

Makoion embraces the tension between autonomy and risk. The goal is real control over your own infrastructure, not a watered-down, permission-gated toy.

## Architecture

```text
┌──────────────────────────────────────┐
│           Makoion Agent              │
│  ┌────────────┐  ┌───────────────┐   │
│  │  Chat UI   │  │  Agent Core   │   │
│  │            │↔ │  (LLM-based)  │   │
│  └────────────┘  └───────┬───────┘   │
│                          │           │
│  ┌───────────────────────┼────────┐  │
│  │        Tool Layer (MCP)        │  │
│  ├────────┬────────┬──────────────┤  │
│  │ Files  │ Device │  External    │  │
│  │ Access │ Control│  Services    │  │
│  └────────┴────────┴──────────────┘  │
│                                      │
│  Your Smartphone                     │
└──────────────────────────────────────┘
         ↕ Cross-device connection
      PC  |  NAS  |  Tablet
```

## Repository Layout

```text
projects/
  apps/
    android/            Android app and agent runtime
    desktop-companion/  Desktop companion and MCP bridge
docs/
  architecture/         Current status, plans, and checklists
```

## Getting Started

> Makoion is in active development. Expect rough edges.

### Prerequisites

- Android Studio with Android SDK / emulator
- JDK 17+
- A desktop environment for the companion app

### Clone

```bash
git clone https://github.com/team-pneumora/makoion.git
cd makoion
```

### Android App

```bash
cd projects/apps/android
./gradlew :app:assembleDebug
```

### Desktop Companion

```bash
cd projects/apps/desktop-companion
javac -d build/tmp/desktop-companion-classes src/io/makoion/desktopcompanion/Main.java
java -cp build/tmp/desktop-companion-classes io.makoion.desktopcompanion.Main
```

### Current Development Focus

- Chat-driven task execution on Android
- Attachment-aware chat flow
- Companion-backed MCP bridge discovery
- Companion-backed browser page access for direct URL fetch and extraction

## Roadmap

- [x] Project setup and architecture design
- [x] Chat UI prototype
- [x] Cross-device pairing foundation
- [x] MCP bridge discovery
- [x] Basic browser page access through companion MCP tool calls
- [ ] Local file system agent expansion
- [ ] Background service hardening
- [ ] Plugin system for community tools
- [ ] iOS support

## Contributing

Contributions are welcome.

1. Fork the repository
2. Create a feature branch
3. Commit your changes
4. Push the branch
5. Open a pull request

Areas where help is especially useful:

- Android native development
- MCP server and tool development
- Security and sandboxing
- UI/UX design
- Device compatibility testing

## Name Origin

**Makoion** = *Makom* (Hebrew: "the place that exists everywhere") + *Aion* (Greek: "eternal, boundless time")

> Your server should be wherever you are, always.

## License

MIT. See [LICENSE](LICENSE).

## Support

If the project is interesting to you:

- Star the repository
- Open an issue with ideas or bugs
- Start or join a discussion
- Sponsor the project at [GitHub Sponsors](https://github.com/sponsors/team-pneumora)

---

<p align="center">
  Built by <a href="https://github.com/team-pneumora">Pneumora</a>
</p>
