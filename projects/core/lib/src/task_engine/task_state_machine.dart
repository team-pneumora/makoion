import '../domain/enums.dart';
import '../domain/task.dart';
import 'task_transition.dart';

/// Manages task lifecycle state transitions.
///
/// All state changes go through [transition] which validates
/// against the transition map and guard conditions.
///
/// Tasks can be checkpointed for crash recovery via [checkpoint]
/// and restored via [recover].
class TaskStateMachine {
  /// Valid transitions from each state.
  static const Map<TaskStatus, Set<TaskStatus>> validTransitions = {
    TaskStatus.queued: {TaskStatus.ready, TaskStatus.cancelled},
    TaskStatus.ready: {TaskStatus.running, TaskStatus.cancelled},
    TaskStatus.running: {
      TaskStatus.waitingUser,
      TaskStatus.waitingDevice,
      TaskStatus.waitingNetwork,
      TaskStatus.delegated,
      TaskStatus.succeeded,
      TaskStatus.failed,
      TaskStatus.cancelled,
    },
    TaskStatus.waitingUser: {TaskStatus.running, TaskStatus.cancelled},
    TaskStatus.waitingDevice: {
      TaskStatus.running,
      TaskStatus.failed,
      TaskStatus.cancelled,
    },
    TaskStatus.waitingNetwork: {
      TaskStatus.running,
      TaskStatus.failed,
      TaskStatus.cancelled,
    },
    TaskStatus.delegated: {
      TaskStatus.running,
      TaskStatus.succeeded,
      TaskStatus.failed,
    },
    // Terminal states have no outgoing transitions.
    TaskStatus.succeeded: {},
    TaskStatus.failed: {},
    TaskStatus.cancelled: {},
  };

  /// Guard conditions evaluated before each transition.
  final List<TransitionGuard> _guards;

  TaskStateMachine({List<TransitionGuard>? guards})
      : _guards = guards ??
            [
              delegatedRequiresDevice,
              failedRequiresError,
            ];

  /// Attempt to transition [task] to [newStatus].
  ///
  /// Returns [TaskTransitionResult.ok] with the updated task on success,
  /// or [TaskTransitionResult.rejected] with an error message on failure.
  TaskTransitionResult transition(Task task, TaskStatus newStatus) {
    // Check if current state allows this transition.
    final allowed = validTransitions[task.status];
    if (allowed == null || !allowed.contains(newStatus)) {
      return TaskTransitionResult.rejected(
        task,
        'Invalid transition: ${task.status.name} → ${newStatus.name}',
      );
    }

    // Run guard conditions.
    for (final guard in _guards) {
      final error = guard(task, newStatus);
      if (error != null) {
        return TaskTransitionResult.rejected(task, error);
      }
    }

    final updated = task.copyWith(
      status: newStatus,
      updatedAt: DateTime.now(),
    );
    return TaskTransitionResult.ok(updated);
  }

  /// Create a checkpoint for crash recovery.
  Task checkpoint(Task task, String stepId, Map<String, dynamic> state) {
    final cp = TaskCheckpoint(
      stepId: stepId,
      statusAtCheckpoint: task.status,
      state: state,
      createdAt: DateTime.now(),
    );
    return task.copyWith(
      checkpoints: [...task.checkpoints, cp],
      updatedAt: DateTime.now(),
    );
  }

  /// Recover a task from a checkpoint.
  ///
  /// Restores the task to the checkpoint's status and returns it.
  Task recover(Task task, TaskCheckpoint checkpoint) {
    return task.copyWith(
      status: checkpoint.statusAtCheckpoint,
      updatedAt: DateTime.now(),
    );
  }

  /// Check if a transition is valid without performing it.
  bool canTransition(TaskStatus from, TaskStatus to) {
    final allowed = validTransitions[from];
    return allowed != null && allowed.contains(to);
  }
}
