/// Standard capability identifiers for the MobileClaw ecosystem.
///
/// Capabilities follow a `domain.verb` naming convention.
/// A device declares its support for each capability via
/// [CapabilityRegistry].
abstract final class Capabilities {
  // ── Common (all devices) ──────────────────────────────────────

  /// List files on the device.
  static const filesList = 'files.list';

  /// Read file metadata (name, size, modified date).
  static const filesReadMetadata = 'files.read_metadata';

  /// Transfer files between devices.
  static const filesTransfer = 'files.transfer';

  /// Open an app by package name or URL.
  static const appOpen = 'app.open';

  /// Heartbeat ping for connectivity checks.
  static const sessionPing = 'session.ping';

  // ── Desktop (Companion) ───────────────────────────────────────

  /// Focus a specific application window.
  static const windowFocus = 'window.focus';

  /// Execute a predefined workflow/automation.
  static const workflowRun = 'workflow.run';

  /// Stream the screen for preview.
  static const screenStream = 'screen.stream';

  /// Read/write clipboard content.
  static const clipboardReadWrite = 'clipboard.read_write';

  // ── Tablet (Companion) ────────────────────────────────────────

  /// Open a drawing/note canvas.
  static const canvasOpen = 'canvas.open';

  /// Start a handoff from phone to tablet.
  static const handoffStart = 'handoff.start';

  /// Display a preview on the tablet screen.
  static const previewDisplay = 'preview.display';

  // ── Mobile (Phone Hub) ────────────────────────────────────────

  /// Access camera for photos/scanning.
  static const camera = 'camera';

  /// Read/write calendar events.
  static const calendar = 'calendar';

  /// Access contacts.
  static const contacts = 'contacts';

  /// Access device location.
  static const location = 'location';

  /// Voice input/output.
  static const voice = 'voice';

  /// Push notifications.
  static const notifications = 'notifications';

  /// All standard capability identifiers.
  static const all = [
    filesList,
    filesReadMetadata,
    filesTransfer,
    appOpen,
    sessionPing,
    windowFocus,
    workflowRun,
    screenStream,
    clipboardReadWrite,
    canvasOpen,
    handoffStart,
    previewDisplay,
    camera,
    calendar,
    contacts,
    location,
    voice,
    notifications,
  ];
}
