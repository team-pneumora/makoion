import 'package:equatable/equatable.dart';
import 'package:meta/meta.dart';

import '../domain/enums.dart';
import '../domain/task.dart';

/// Result of attempting a task state transition.
@immutable
class TaskTransitionResult extends Equatable {
  /// Whether the transition was successful.
  final bool success;

  /// The updated task (only meaningful if [success] is true).
  final Task task;

  /// Human-readable error (only if [success] is false).
  final String? error;

  const TaskTransitionResult._({
    required this.success,
    required this.task,
    this.error,
  });

  factory TaskTransitionResult.ok(Task task) =>
      TaskTransitionResult._(success: true, task: task);

  factory TaskTransitionResult.rejected(Task task, String error) =>
      TaskTransitionResult._(success: false, task: task, error: error);

  @override
  List<Object?> get props => [success, task, error];
}

/// A guard condition that must be satisfied before a transition is allowed.
typedef TransitionGuard = String? Function(Task task, TaskStatus newStatus);

/// Built-in guard: delegated requires a target device.
String? delegatedRequiresDevice(Task task, TaskStatus newStatus) {
  if (newStatus == TaskStatus.delegated && task.delegatedTo == null) {
    return 'Cannot delegate without a target device.';
  }
  return null;
}

/// Built-in guard: failed should carry an error message.
String? failedRequiresError(Task task, TaskStatus newStatus) {
  if (newStatus == TaskStatus.failed && task.error == null) {
    return 'Failed transition should include an error message.';
  }
  return null;
}
