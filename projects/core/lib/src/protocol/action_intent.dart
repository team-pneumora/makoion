import 'package:equatable/equatable.dart';
import 'package:meta/meta.dart';

import '../domain/enums.dart';
import '../support/json_utils.dart';

/// A structured action proposed by the AI model.
///
/// The model never calls OS APIs directly. Instead it produces an
/// [ActionIntent] that flows through:
/// ```
/// Model → ActionIntent → PolicyEngine → [Approval] → Executor → AuditLogger
/// ```
///
/// Actions follow the `domain.verb` convention, e.g.:
/// - `files.search`, `files.organize`, `files.move`
/// - `device.send_to`, `device.request_from`
/// - `calendar.create`, `contacts.lookup`
@immutable
class ActionIntent extends Equatable {
  /// Action identifier in `domain.verb` format.
  final String action;

  /// Action-specific arguments.
  final Map<String, dynamic> args;

  /// Risk level assessed by the model (may be overridden by PolicyEngine).
  final RiskLevel risk;

  /// Whether the model recommends user confirmation before execution.
  final bool requiresConfirmation;

  /// Human-readable explanation of what this action will do.
  final String? explanation;

  const ActionIntent({
    required this.action,
    required this.args,
    required this.risk,
    this.requiresConfirmation = false,
    this.explanation,
  });

  Map<String, dynamic> toJson() => {
        'action': action,
        'args': args,
        'risk': risk.name,
        'requiresConfirmation': requiresConfirmation,
        'explanation': explanation,
      };

  factory ActionIntent.fromJson(Map<String, dynamic> json) => ActionIntent(
        action: json['action'] as String,
        args: castJsonMap(json['args']),
        risk: enumByName(RiskLevel.values, json['risk']),
        requiresConfirmation: json['requiresConfirmation'] as bool? ?? false,
        explanation: json['explanation'] as String?,
      );

  /// The domain part of the action (e.g., `files` from `files.search`).
  String get domain => action.contains('.') ? action.split('.').first : action;

  /// The verb part of the action (e.g., `search` from `files.search`).
  String get verb =>
      action.contains('.') ? action.split('.').skip(1).join('.') : action;

  @override
  List<Object?> get props => [
        action,
        args,
        risk,
        requiresConfirmation,
        explanation,
      ];
}

/// The output produced by the AI model in response to a user request.
///
/// Each output has exactly one [ActionType]:
/// - [ActionType.answer]: Direct text response
/// - [ActionType.question]: Clarifying question
/// - [ActionType.draft]: Draft content for review
/// - [ActionType.actionIntent]: Structured action to execute
/// - [ActionType.escalation]: Model cannot handle, needs human
@immutable
class ModelOutput extends Equatable {
  final ActionType type;

  /// Text content for answer, question, or draft types.
  final String? text;

  /// Structured action for [ActionType.actionIntent].
  final ActionIntent? intent;

  /// Reason for escalation when [type] is [ActionType.escalation].
  final String? reason;

  const ModelOutput({
    required this.type,
    this.text,
    this.intent,
    this.reason,
  });

  Map<String, dynamic> toJson() => {
        'type': type.name,
        'text': text,
        'intent': intent?.toJson(),
        'reason': reason,
      };

  factory ModelOutput.fromJson(Map<String, dynamic> json) {
    final type = enumByName(ActionType.values, json['type']);
    return ModelOutput(
      type: type,
      text: json['text'] as String?,
      intent: json['intent'] == null
          ? null
          : ActionIntent.fromJson(castJsonMap(json['intent'])),
      reason: json['reason'] as String?,
    );
  }

  const ModelOutput.answer(String text)
      : type = ActionType.answer,
        text = text,
        intent = null,
        reason = null;

  const ModelOutput.question(String text)
      : type = ActionType.question,
        text = text,
        intent = null,
        reason = null;

  const ModelOutput.draft(String text)
      : type = ActionType.draft,
        text = text,
        intent = null,
        reason = null;

  const ModelOutput.action(ActionIntent intent)
      : type = ActionType.actionIntent,
        text = null,
        intent = intent,
        reason = null;

  const ModelOutput.escalation(String reason)
      : type = ActionType.escalation,
        text = null,
        intent = null,
        reason = reason;

  @override
  List<Object?> get props => [type, text, intent, reason];
}
