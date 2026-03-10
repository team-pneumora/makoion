import 'package:mobileclaw_core/core.dart';
import 'package:test/test.dart';

void main() {
  test('file organize flow goes through intent, policy, approval, and audit',
      () async {
    final device = Device(
      id: DeviceId('device-phone'),
      name: 'Phone Hub',
      role: DeviceRole.phoneHub,
      platform: DevicePlatform.android,
      lastSeenAt: DateTime.utc(2026, 3, 9, 12),
      capabilities: const {
        Capabilities.filesList: CapabilityStatus.supported,
        Capabilities.filesReadMetadata: CapabilityStatus.supported,
      },
      isPaired: true,
    );
    final policyEngine = DefaultPolicyEngine();
    final approvalService = InMemoryApprovalService(
      clock: () => DateTime.utc(2026, 3, 9, 12),
      idFactory: () => const ApprovalRequestId('approval-1'),
    );
    final auditLogger = InMemoryAuditLogger();
    final intent = const ActionIntent(
      action: 'files.organize',
      args: {
        'targets': ['source://photos/recent'],
        'strategy': 'group_by_project_and_date',
        'dryRun': true,
      },
      risk: RiskLevel.medium,
      requiresConfirmation: true,
      explanation: 'Plan organization before applying changes.',
    );

    final decision = policyEngine.evaluate(intent, device);
    expect(decision.allowed, isTrue);
    expect(decision.requiresApproval, isTrue);

    final approvalRequest = await approvalService.requestApproval(
      intent,
      decision,
      const TaskId('task-1'),
    );
    final approvalFuture = approvalService.waitForApproval(approvalRequest.id);

    await approvalService.approve(approvalRequest.id);
    final approvalResponse = await approvalFuture;
    expect(approvalResponse.approved, isTrue);

    await auditLogger.log(
      AuditEvent(
        id: const AuditEventId('audit-1'),
        taskId: const TaskId('task-1'),
        action: intent.action,
        capability: Capabilities.filesList,
        result: 'planned',
        reversible: true,
        details: {
          'dryRun': true,
          'approvalRequestId': approvalRequest.id.value,
        },
        createdAt: DateTime.utc(2026, 3, 9, 12, 1),
      ),
    );

    final auditEvents = await auditLogger.query(
      const AuditQuery(taskId: TaskId('task-1')),
    );

    expect(auditEvents, hasLength(1));
    expect(auditEvents.single.action, 'files.organize');
    expect(auditEvents.single.details?['approvalRequestId'], 'approval-1');
  });
}
