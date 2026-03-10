import 'dart:async';

import 'package:mobileclaw_core/core.dart';
import 'package:test/test.dart';

void main() {
  group('InMemoryApprovalService', () {
    test('creates pending requests and resolves approvals', () async {
      final timestamps = [
        DateTime.utc(2026, 3, 9, 12),
        DateTime.utc(2026, 3, 9, 12, 1),
      ];
      var cursor = 0;
      final service = InMemoryApprovalService(
        clock: () => timestamps[cursor++],
        idFactory: () => const ApprovalRequestId('approval-1'),
      );

      final request = await service.requestApproval(
        const ActionIntent(
          action: 'files.share',
          args: {'target': 'drive'},
          risk: RiskLevel.high,
        ),
        PolicyDecision.needsApproval(
          RiskLevel.high,
          reason: 'High-risk action requires approval',
        ),
        const TaskId('task-1'),
      );
      final pending = await service.listPending();
      final waiter = service.waitForApproval(request.id);

      await service.approve(request.id);
      final response = await waiter;
      final updated = await service.getRequest(request.id);

      expect(pending, hasLength(1));
      expect(response.approved, isTrue);
      expect(updated?.status, ApprovalStatus.approved);
    });

    test('supports denial with a reason', () async {
      final service = InMemoryApprovalService(
        clock: () => DateTime.utc(2026, 3, 9, 12),
        idFactory: () => const ApprovalRequestId('approval-2'),
      );

      final request = await service.requestApproval(
        const ActionIntent(
          action: 'workflow.run',
          args: {'name': 'cleanup'},
          risk: RiskLevel.high,
        ),
        PolicyDecision.needsApproval(RiskLevel.high),
        const TaskId('task-2'),
      );

      unawaited(service.deny(request.id, reason: 'Denied by user'));
      final response = await service.waitForApproval(request.id);

      expect(response.approved, isFalse);
      expect(response.reason, 'Denied by user');
    });
  });
}
