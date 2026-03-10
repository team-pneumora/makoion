/// Core enumerations for MobileClaw domain model.

/// Task lifecycle states.
///
/// ```
/// queued → ready → running → succeeded (terminal)
///   ↓       ↓       ├→ failed (terminal)
///   ↓       ↓       ├→ cancelled (terminal)
///   ↓       ↓       ├→ waitingUser → running / cancelled
///   ↓       ↓       ├→ waitingDevice → running / failed / cancelled
///   ↓       ↓       ├→ waitingNetwork → running / failed / cancelled
///   ↓       ↓       └→ delegated → running / succeeded / failed
///   ↓       └→ cancelled (terminal)
///   └→ cancelled (terminal)
/// ```
enum TaskStatus {
  queued,
  ready,
  running,
  waitingUser,
  waitingDevice,
  waitingNetwork,
  delegated,
  succeeded,
  failed,
  cancelled;

  bool get isTerminal =>
      this == succeeded || this == failed || this == cancelled;

  bool get isWaiting =>
      this == waitingUser || this == waitingDevice || this == waitingNetwork;
}

/// Risk level for action intents.
///
/// - [low]: search, summarize, draft
/// - [medium]: calendar, file move suggestion, folder classify
/// - [high]: delete, bulk change, external send, remote control
enum RiskLevel {
  low,
  medium,
  high;

  bool get requiresApprovalByDefault => this == high;
}

/// Capability status of a device for a specific feature.
enum CapabilityStatus {
  supported,
  limited,
  denied,
  unavailable,
  needsPairing;

  bool get isUsable => this == supported || this == limited;
}

/// Type of output the AI model can produce.
///
/// The model never calls OS APIs directly.
/// It produces one of these output types, which are then
/// routed through PolicyEngine → Executor → AuditLogger.
enum ActionType {
  answer,
  question,
  draft,
  actionIntent,
  escalation,
}

/// Connectivity mode between Phone Hub and Companion Nodes.
enum ConnectivityMode {
  /// Same LAN / nearby direct connection.
  directLocal,

  /// Remote connection via managed relay (push, NAT traversal, mailbox).
  managedRelay,

  /// Device offline — queue tasks and resume when online.
  offlineQueue,
}

/// Role of a device in the MobileClaw ecosystem.
enum DeviceRole {
  /// The primary phone hub (source of truth).
  phoneHub,

  /// Desktop companion node (PC, Mac, Linux).
  companionDesktop,

  /// Tablet companion node (iPad, Android tablet).
  companionTablet,
}

/// Type of a node in the Unified File Graph.
enum FileNodeType {
  file,
  folder,
  collection,
}

/// Status of an approval request.
enum ApprovalStatus {
  pending,
  approved,
  denied,
  expired,
}

/// Pairing workflow status between phone hub and companion nodes.
enum PairingStatus {
  pending,
  trusted,
  denied,
  expired,
}

/// Platform identifier for devices.
enum DevicePlatform {
  android,
  ios,
  macos,
  windows,
  linux;

  bool get isMobile => this == android || this == ios;
  bool get isDesktop => this == macos || this == windows || this == linux;
}

/// Status of an active remote session between paired devices.
enum RemoteSessionStatus {
  pending,
  active,
  suspended,
  closed,
  failed,
}
