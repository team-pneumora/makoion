import 'package:equatable/equatable.dart';
import 'package:meta/meta.dart';

import 'enums.dart';
import 'ids.dart';
import '../support/json_utils.dart';

/// A unit of work in MobileClaw.
///
/// Tasks are managed by [TaskStateMachine] and follow the lifecycle:
/// queued → ready → running → succeeded/failed/cancelled
///
/// Tasks are checkpointed so they can be recovered if the app is killed.
@immutable
class Task extends Equatable {
  final TaskId id;
  final TaskStatus status;
  final String description;
  final RiskLevel risk;
  final DateTime createdAt;
  final DateTime updatedAt;
  final List<TaskCheckpoint> checkpoints;
  final String? error;
  final DeviceId? delegatedTo;
  final String? intentAction;
  final Map<String, dynamic>? intentArgs;

  const Task({
    required this.id,
    required this.status,
    required this.description,
    required this.risk,
    required this.createdAt,
    required this.updatedAt,
    this.checkpoints = const [],
    this.error,
    this.delegatedTo,
    this.intentAction,
    this.intentArgs,
  });

  Map<String, dynamic> toJson() => {
        'id': id.value,
        'status': status.name,
        'description': description,
        'risk': risk.name,
        'createdAt': createdAt.toIso8601String(),
        'updatedAt': updatedAt.toIso8601String(),
        'checkpoints':
            checkpoints.map((checkpoint) => checkpoint.toJson()).toList(),
        'error': error,
        'delegatedTo': delegatedTo?.value,
        'intentAction': intentAction,
        'intentArgs': intentArgs,
      };

  factory Task.fromJson(Map<String, dynamic> json) => Task(
        id: TaskId.fromJson(json['id']),
        status: enumByName(TaskStatus.values, json['status']),
        description: json['description'] as String,
        risk: enumByName(RiskLevel.values, json['risk']),
        createdAt: parseDateTime(json['createdAt']),
        updatedAt: parseDateTime(json['updatedAt']),
        checkpoints: castJsonList(json['checkpoints'])
            .map((item) => TaskCheckpoint.fromJson(castJsonMap(item)))
            .toList(growable: false),
        error: json['error'] as String?,
        delegatedTo: json['delegatedTo'] == null
            ? null
            : DeviceId.fromJson(json['delegatedTo']),
        intentAction: json['intentAction'] as String?,
        intentArgs:
            json['intentArgs'] == null ? null : castJsonMap(json['intentArgs']),
      );

  Task copyWith({
    TaskStatus? status,
    String? description,
    RiskLevel? risk,
    DateTime? updatedAt,
    List<TaskCheckpoint>? checkpoints,
    String? error,
    DeviceId? delegatedTo,
    String? intentAction,
    Map<String, dynamic>? intentArgs,
  }) {
    return Task(
      id: id,
      status: status ?? this.status,
      description: description ?? this.description,
      risk: risk ?? this.risk,
      createdAt: createdAt,
      updatedAt: updatedAt ?? this.updatedAt,
      checkpoints: checkpoints ?? this.checkpoints,
      error: error ?? this.error,
      delegatedTo: delegatedTo ?? this.delegatedTo,
      intentAction: intentAction ?? this.intentAction,
      intentArgs: intentArgs ?? this.intentArgs,
    );
  }

  @override
  List<Object?> get props => [
        id,
        status,
        description,
        risk,
        createdAt,
        updatedAt,
        checkpoints,
        error,
        delegatedTo,
        intentAction,
        intentArgs,
      ];
}

/// A checkpoint saved during task execution for recovery.
@immutable
class TaskCheckpoint extends Equatable {
  final String stepId;
  final TaskStatus statusAtCheckpoint;
  final Map<String, dynamic> state;
  final DateTime createdAt;

  const TaskCheckpoint({
    required this.stepId,
    required this.statusAtCheckpoint,
    required this.state,
    required this.createdAt,
  });

  Map<String, dynamic> toJson() => {
        'stepId': stepId,
        'statusAtCheckpoint': statusAtCheckpoint.name,
        'state': state,
        'createdAt': createdAt.toIso8601String(),
      };

  factory TaskCheckpoint.fromJson(Map<String, dynamic> json) => TaskCheckpoint(
        stepId: json['stepId'] as String,
        statusAtCheckpoint: enumByName(
          TaskStatus.values,
          json['statusAtCheckpoint'],
        ),
        state: castJsonMap(json['state']),
        createdAt: parseDateTime(json['createdAt']),
      );

  @override
  List<Object?> get props => [stepId, statusAtCheckpoint, state, createdAt];
}
