import 'package:equatable/equatable.dart';
import 'package:meta/meta.dart';

import '../capability/capabilities.dart';
import '../capability/capability_registry.dart';
import '../domain/device.dart';
import '../domain/enums.dart';
import '../protocol/action_intent.dart';

/// Evaluates action intents against security policies.
///
/// The PolicyEngine sits between the AI model output and the executor:
/// ```
/// Model → ActionIntent → PolicyEngine.evaluate() → [ApprovalService] → Executor
/// ```
///
/// It determines whether an action is allowed, what risk level it has,
/// and whether user approval is required before execution.
abstract interface class PolicyEngine {
  /// Evaluate an action intent in the context of the executing device.
  ///
  /// Returns a [PolicyDecision] indicating whether the action is allowed
  /// and what conditions apply.
  PolicyDecision evaluate(ActionIntent intent, Device device);
}

/// Reference implementation of the policy rules used in Phase 0.
///
/// The engine is intentionally conservative:
/// - unsupported actions are denied
/// - missing capabilities are denied
/// - high-risk or explicitly confirmed actions require approval
class DefaultPolicyEngine implements PolicyEngine {
  final CapabilityRegistry? capabilityRegistry;
  final Map<String, List<String>> capabilityRequirements;
  final Map<String, RiskLevel> minimumRiskByAction;
  final Set<String> actionsRequiringApproval;

  DefaultPolicyEngine({
    this.capabilityRegistry,
    Map<String, List<String>>? capabilityRequirements,
    Map<String, RiskLevel>? minimumRiskByAction,
    Set<String>? actionsRequiringApproval,
  })  : capabilityRequirements =
            capabilityRequirements ?? _defaultCapabilityRequirements,
        minimumRiskByAction =
            minimumRiskByAction ?? _defaultMinimumRiskByAction,
        actionsRequiringApproval =
            actionsRequiringApproval ?? _defaultActionsRequiringApproval;

  @override
  PolicyDecision evaluate(ActionIntent intent, Device device) {
    final requiredCapabilities = capabilityRequirements[intent.action];
    if (requiredCapabilities == null) {
      return PolicyDecision(
        allowed: false,
        requiresApproval: false,
        assessedRisk: RiskLevel.high,
        reason: 'Unsupported action: ${intent.action}',
        requiredCapabilities: const [],
      );
    }

    final assessedRisk = _resolveRisk(intent);
    final unavailableCapabilities = requiredCapabilities
        .where((capability) => !_lookupCapability(device, capability).isUsable)
        .toList(growable: false);

    if (unavailableCapabilities.isNotEmpty) {
      return PolicyDecision(
        allowed: false,
        requiresApproval: false,
        assessedRisk: assessedRisk,
        reason:
            'Device ${device.name} is missing required capabilities: ${unavailableCapabilities.join(', ')}',
        requiredCapabilities: requiredCapabilities,
      );
    }

    final requiresApproval = assessedRisk.requiresApprovalByDefault ||
        actionsRequiringApproval.contains(intent.action) ||
        intent.requiresConfirmation;

    return PolicyDecision(
      allowed: true,
      requiresApproval: requiresApproval,
      assessedRisk: assessedRisk,
      reason: requiresApproval ? _approvalReason(intent, assessedRisk) : null,
      requiredCapabilities: requiredCapabilities,
    );
  }

  CapabilityStatus _lookupCapability(Device device, String capability) {
    final deviceStatus = device.capabilities[capability];
    if (deviceStatus != null) {
      return deviceStatus;
    }
    if (capabilityRegistry != null) {
      return capabilityRegistry!.getStatus(device.id, capability);
    }
    return CapabilityStatus.unavailable;
  }

  RiskLevel _resolveRisk(ActionIntent intent) {
    final minimumRisk = minimumRiskByAction[intent.action];
    if (minimumRisk == null) {
      return intent.risk;
    }
    return _riskOrder(intent.risk) >= _riskOrder(minimumRisk)
        ? intent.risk
        : minimumRisk;
  }

  int _riskOrder(RiskLevel risk) {
    switch (risk) {
      case RiskLevel.low:
        return 0;
      case RiskLevel.medium:
        return 1;
      case RiskLevel.high:
        return 2;
    }
  }

  String _approvalReason(ActionIntent intent, RiskLevel assessedRisk) {
    if (assessedRisk == RiskLevel.high) {
      return 'High-risk action requires approval: ${intent.action}';
    }
    if (intent.requiresConfirmation) {
      return 'Model requested confirmation before executing ${intent.action}';
    }
    return 'Policy requires approval before executing ${intent.action}';
  }

  static const Map<String, List<String>> _defaultCapabilityRequirements = {
    'files.search': [Capabilities.filesList],
    'files.preview': [Capabilities.filesReadMetadata],
    'files.summarize': [Capabilities.filesReadMetadata],
    'files.organize': [
      Capabilities.filesList,
      Capabilities.filesReadMetadata,
    ],
    'files.move': [Capabilities.filesTransfer],
    'files.copy': [Capabilities.filesTransfer],
    'files.share': [Capabilities.filesTransfer],
    'files.dedupe': [
      Capabilities.filesList,
      Capabilities.filesReadMetadata,
    ],
    'files.send_to_device': [Capabilities.filesTransfer],
    'files.request_from_device': [Capabilities.filesTransfer],
    'app.open': [Capabilities.appOpen],
    'session.ping': [Capabilities.sessionPing],
    'window.focus': [Capabilities.windowFocus],
    'workflow.run': [Capabilities.workflowRun],
    'screen.stream': [Capabilities.screenStream],
    'clipboard.read_write': [Capabilities.clipboardReadWrite],
    'canvas.open': [Capabilities.canvasOpen],
    'handoff.start': [Capabilities.handoffStart],
    'preview.display': [Capabilities.previewDisplay],
    'camera.capture': [Capabilities.camera],
    'calendar.create': [Capabilities.calendar],
    'contacts.lookup': [Capabilities.contacts],
    'location.get': [Capabilities.location],
    'voice.transcribe': [Capabilities.voice],
    'notifications.send': [Capabilities.notifications],
  };

  static const Map<String, RiskLevel> _defaultMinimumRiskByAction = {
    'files.search': RiskLevel.low,
    'files.preview': RiskLevel.low,
    'files.summarize': RiskLevel.low,
    'files.organize': RiskLevel.medium,
    'files.move': RiskLevel.medium,
    'files.copy': RiskLevel.medium,
    'files.share': RiskLevel.high,
    'files.dedupe': RiskLevel.high,
    'files.send_to_device': RiskLevel.medium,
    'files.request_from_device': RiskLevel.medium,
    'app.open': RiskLevel.low,
    'session.ping': RiskLevel.low,
    'window.focus': RiskLevel.medium,
    'workflow.run': RiskLevel.high,
    'screen.stream': RiskLevel.high,
    'clipboard.read_write': RiskLevel.high,
    'canvas.open': RiskLevel.low,
    'handoff.start': RiskLevel.medium,
    'preview.display': RiskLevel.low,
    'camera.capture': RiskLevel.medium,
    'calendar.create': RiskLevel.medium,
    'contacts.lookup': RiskLevel.low,
    'location.get': RiskLevel.medium,
    'voice.transcribe': RiskLevel.low,
    'notifications.send': RiskLevel.low,
  };

  static const Set<String> _defaultActionsRequiringApproval = {
    'files.share',
    'files.dedupe',
    'workflow.run',
    'screen.stream',
    'clipboard.read_write',
  };
}

/// The result of a policy evaluation.
@immutable
class PolicyDecision extends Equatable {
  /// Whether the action is allowed at all.
  final bool allowed;

  /// Whether user approval is required before execution.
  final bool requiresApproval;

  /// The risk level as assessed by the policy engine.
  final RiskLevel assessedRisk;

  /// Human-readable reason if denied or if approval is required.
  final String? reason;

  /// Device capabilities required to execute this action.
  final List<String> requiredCapabilities;

  const PolicyDecision({
    required this.allowed,
    required this.requiresApproval,
    required this.assessedRisk,
    this.reason,
    this.requiredCapabilities = const [],
  });

  /// Convenience: action is allowed without user approval.
  factory PolicyDecision.allow(RiskLevel risk) => PolicyDecision(
        allowed: true,
        requiresApproval: false,
        assessedRisk: risk,
      );

  /// Convenience: action needs user approval.
  factory PolicyDecision.needsApproval(RiskLevel risk, {String? reason}) =>
      PolicyDecision(
        allowed: true,
        requiresApproval: true,
        assessedRisk: risk,
        reason: reason,
      );

  /// Convenience: action is denied.
  factory PolicyDecision.deny(String reason) => PolicyDecision(
        allowed: false,
        requiresApproval: false,
        assessedRisk: RiskLevel.high,
        reason: reason,
      );

  @override
  List<Object?> get props => [
        allowed,
        requiresApproval,
        assessedRisk,
        reason,
        requiredCapabilities,
      ];
}
