import 'package:equatable/equatable.dart';
import 'package:meta/meta.dart';

import 'ids.dart';
import '../support/json_utils.dart';

/// An immutable record of an action taken by the system.
///
/// Every action that passes through the Intent → Policy → Executor pipeline
/// is logged as an AuditEvent. This ensures full traceability and allows
/// users to review what the AI did and why.
@immutable
class AuditEvent extends Equatable {
  final AuditEventId id;
  final TaskId? taskId;
  final String action;
  final String? capability;
  final String result;
  final bool reversible;
  final Map<String, dynamic>? details;
  final DateTime createdAt;

  const AuditEvent({
    required this.id,
    required this.action,
    required this.result,
    required this.reversible,
    required this.createdAt,
    this.taskId,
    this.capability,
    this.details,
  });

  Map<String, dynamic> toJson() => {
        'id': id.value,
        'taskId': taskId?.value,
        'action': action,
        'capability': capability,
        'result': result,
        'reversible': reversible,
        'details': details,
        'createdAt': createdAt.toIso8601String(),
      };

  factory AuditEvent.fromJson(Map<String, dynamic> json) => AuditEvent(
        id: AuditEventId.fromJson(json['id']),
        taskId: json['taskId'] == null ? null : TaskId.fromJson(json['taskId']),
        action: json['action'] as String,
        capability: json['capability'] as String?,
        result: json['result'] as String,
        reversible: json['reversible'] as bool,
        details: json['details'] == null ? null : castJsonMap(json['details']),
        createdAt: parseDateTime(json['createdAt']),
      );

  @override
  List<Object?> get props => [
        id,
        taskId,
        action,
        capability,
        result,
        reversible,
        details,
        createdAt,
      ];
}
