import 'package:mobileclaw_core/core.dart';
import 'package:test/test.dart';

void main() {
  group('TaskStateMachine', () {
    late TaskStateMachine stateMachine;

    setUp(() {
      stateMachine = TaskStateMachine();
    });

    test('allows a valid transition', () {
      final task = _task(status: TaskStatus.queued);

      final result = stateMachine.transition(task, TaskStatus.ready);

      expect(result.success, isTrue);
      expect(result.task.status, TaskStatus.ready);
      expect(result.error, isNull);
    });

    test('rejects an invalid transition', () {
      final task = _task(status: TaskStatus.queued);

      final result = stateMachine.transition(task, TaskStatus.succeeded);

      expect(result.success, isFalse);
      expect(result.task.status, TaskStatus.queued);
      expect(result.error, contains('Invalid transition'));
    });

    test('requires a target device before delegating', () {
      final task = _task(status: TaskStatus.running);

      final result = stateMachine.transition(task, TaskStatus.delegated);

      expect(result.success, isFalse);
      expect(result.error, contains('target device'));
    });

    test('requires an error message before failing', () {
      final task = _task(status: TaskStatus.running);

      final rejected = stateMachine.transition(task, TaskStatus.failed);
      final accepted = stateMachine.transition(
        task.copyWith(error: 'network timeout'),
        TaskStatus.failed,
      );

      expect(rejected.success, isFalse);
      expect(accepted.success, isTrue);
      expect(accepted.task.status, TaskStatus.failed);
    });

    test('creates checkpoints and recovers to the checkpoint status', () {
      final runningTask = _task(status: TaskStatus.running);
      final checkpointed = stateMachine.checkpoint(
        runningTask,
        'scan-1',
        const {'cursor': 42},
      );
      final waitingTask =
          checkpointed.copyWith(status: TaskStatus.waitingNetwork);

      final recovered = stateMachine.recover(
        waitingTask,
        checkpointed.checkpoints.single,
      );

      expect(checkpointed.checkpoints, hasLength(1));
      expect(checkpointed.checkpoints.single.stepId, 'scan-1');
      expect(recovered.status, TaskStatus.running);
    });
  });
}

Task _task({
  required TaskStatus status,
}) {
  final now = DateTime.utc(2026, 3, 9);
  return Task(
    id: TaskId('task-1'),
    status: status,
    description: 'test task',
    risk: RiskLevel.low,
    createdAt: now,
    updatedAt: now,
  );
}
