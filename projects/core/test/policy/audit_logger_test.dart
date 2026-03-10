import 'package:mobileclaw_core/core.dart';
import 'package:test/test.dart';

void main() {
  group('InMemoryAuditLogger', () {
    test('stores events and filters queries', () async {
      final logger = InMemoryAuditLogger();
      final first = AuditEvent(
        id: const AuditEventId('audit-1'),
        taskId: const TaskId('task-1'),
        action: 'files.search',
        capability: Capabilities.filesList,
        result: 'ok',
        reversible: false,
        createdAt: DateTime.utc(2026, 3, 9, 12),
      );
      final second = AuditEvent(
        id: const AuditEventId('audit-2'),
        taskId: const TaskId('task-2'),
        action: 'files.share',
        capability: Capabilities.filesTransfer,
        result: 'pending',
        reversible: false,
        createdAt: DateTime.utc(2026, 3, 9, 13),
      );

      await logger.log(first);
      await logger.log(second);

      final shareEvents = await logger.query(
        const AuditQuery(action: 'files.share'),
      );
      final byId = await logger.getById(const AuditEventId('audit-1'));

      expect(shareEvents, [second]);
      expect(byId, first);
    });
  });
}
