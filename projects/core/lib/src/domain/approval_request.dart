import 'package:equatable/equatable.dart';
import 'package:meta/meta.dart';

import 'enums.dart';
import 'ids.dart';
import '../support/json_utils.dart';

/// A request for user approval of a risky action.
///
/// Created by [PolicyEngine] when an [ActionIntent] requires user confirmation.
/// The user can approve, deny, or let the request expire.
@immutable
class ApprovalRequest extends Equatable {
  final ApprovalRequestId id;
  final TaskId taskId;
  final String intentAction;
  final Map<String, dynamic> intentArgs;
  final RiskLevel assessedRisk;
  final ApprovalStatus status;
  final String? explanation;
  final DateTime createdAt;
  final DateTime? decidedAt;

  const ApprovalRequest({
    required this.id,
    required this.taskId,
    required this.intentAction,
    required this.intentArgs,
    required this.assessedRisk,
    required this.status,
    required this.createdAt,
    this.explanation,
    this.decidedAt,
  });

  Map<String, dynamic> toJson() => {
        'id': id.value,
        'taskId': taskId.value,
        'intentAction': intentAction,
        'intentArgs': intentArgs,
        'assessedRisk': assessedRisk.name,
        'status': status.name,
        'explanation': explanation,
        'createdAt': createdAt.toIso8601String(),
        'decidedAt': decidedAt?.toIso8601String(),
      };

  factory ApprovalRequest.fromJson(Map<String, dynamic> json) =>
      ApprovalRequest(
        id: ApprovalRequestId.fromJson(json['id']),
        taskId: TaskId.fromJson(json['taskId']),
        intentAction: json['intentAction'] as String,
        intentArgs: castJsonMap(json['intentArgs']),
        assessedRisk: enumByName(RiskLevel.values, json['assessedRisk']),
        status: enumByName(ApprovalStatus.values, json['status']),
        explanation: json['explanation'] as String?,
        createdAt: parseDateTime(json['createdAt']),
        decidedAt: parseNullableDateTime(json['decidedAt']),
      );

  ApprovalRequest copyWith({
    ApprovalStatus? status,
    DateTime? decidedAt,
  }) {
    return ApprovalRequest(
      id: id,
      taskId: taskId,
      intentAction: intentAction,
      intentArgs: intentArgs,
      assessedRisk: assessedRisk,
      status: status ?? this.status,
      createdAt: createdAt,
      explanation: explanation,
      decidedAt: decidedAt ?? this.decidedAt,
    );
  }

  @override
  List<Object?> get props => [
        id,
        taskId,
        intentAction,
        intentArgs,
        assessedRisk,
        status,
        explanation,
        createdAt,
        decidedAt,
      ];
}

/// User's response to an approval request.
@immutable
class ApprovalResponse extends Equatable {
  final ApprovalRequestId requestId;
  final bool approved;
  final String? reason;
  final DateTime respondedAt;

  const ApprovalResponse({
    required this.requestId,
    required this.approved,
    required this.respondedAt,
    this.reason,
  });

  Map<String, dynamic> toJson() => {
        'requestId': requestId.value,
        'approved': approved,
        'reason': reason,
        'respondedAt': respondedAt.toIso8601String(),
      };

  factory ApprovalResponse.fromJson(Map<String, dynamic> json) =>
      ApprovalResponse(
        requestId: ApprovalRequestId.fromJson(json['requestId']),
        approved: json['approved'] as bool,
        reason: json['reason'] as String?,
        respondedAt: parseDateTime(json['respondedAt']),
      );

  @override
  List<Object?> get props => [requestId, approved, reason, respondedAt];
}
