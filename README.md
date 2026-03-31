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

Then I realized there is one device that is already always on, always connected, and already holds your most personal data: **your smartphone**.

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

> Yes, giving an AI agent full access to your phone is risky. But that is also what makes it exciting.

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

## Getting Started

> Makoion is in early development. Things will break.

### Prerequisites

- Android device or emulator
- Node.js 18+

### Installation

```bash
git clone https://github.com/team-pneumora/makoion.git
cd makoion
npm install
```

### Run

```bash
npm run dev
```

Detailed setup guides for the Android app and desktop companion are still being refined.

## Roadmap

- [x] Project setup and architecture design
- [ ] Chat UI prototype
- [ ] Local file system agent
- [ ] MCP tool integration
- [ ] Cross-device pairing
- [ ] Background service (always-on daemon)
- [ ] Plugin system for community tools
- [ ] iOS support

## Development Credit

The implementation work in this repository has been carried out by Codex. Pneumora provided product direction, requirements, and guidance, but did not write the implementation itself.

## Contributing

Makoion is built solo right now, but contributions are very welcome.

### How To Contribute

1. Fork the repo
2. Create a feature branch (`git checkout -b feat/your-feature`)
3. Commit your changes (`git commit -m "Add some feature"`)
4. Push to the branch (`git push origin feat/your-feature`)
5. Open a pull request

### Good Places To Start

- Look for issues labeled `good first issue`
- Suggest new MCP tools or integrations
- Improve documentation
- Report bugs

### Areas Where Help Is Needed

- Android native development
- MCP server and tool development
- Security and sandboxing
- UI/UX design
- Testing on various devices

## Name Origin

**Makoion** = *Makom* (Hebrew: "the place that exists everywhere") + *Aion* (Greek: "eternal, boundless time")

> Your server should be wherever you are, always.

## License

MIT. See [LICENSE](LICENSE).

## Support

If you think this project is worth exploring:

- Star the repo
- Open an issue with ideas or bugs
- Start a discussion
- Support the project at [GitHub Sponsors](https://github.com/sponsors/team-pneumora)

---

<p align="center">
  Built for Pneumora with Codex.
</p>
