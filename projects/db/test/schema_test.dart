import 'dart:convert';

import 'package:mobileclaw_db/db.dart';
import 'package:sqlite3/sqlite3.dart';
import 'package:test/test.dart';

void main() {
  group('MobileClawMigrations', () {
    late Database database;

    setUp(() {
      database = sqlite3.openInMemory();
    });

    tearDown(() {
      database.dispose();
    });

    test('creates all application tables and sets user_version', () {
      MobileClawMigrations.migrate(database);

      final tables = database
          .select(
            '''
            SELECT name
            FROM sqlite_master
            WHERE type = 'table' AND name NOT LIKE 'sqlite_%'
            ORDER BY name
            ''',
          )
          .map((row) => row['name'] as String)
          .toSet();

      expect(tables, containsAll(MobileClawSchema.applicationTables));
      expect(
        MobileClawMigrations.readUserVersion(database),
        MobileClawSchema.latestVersion,
      );
    });

    test('is idempotent when already on the latest version', () {
      MobileClawMigrations.migrate(database);
      MobileClawMigrations.migrate(database);

      final version = MobileClawMigrations.readUserVersion(database);
      final taskIndexes = database.select(
        '''
        SELECT name
        FROM sqlite_master
        WHERE type = 'index' AND name = 'idx_tasks_status'
        ''',
      );

      expect(version, MobileClawSchema.latestVersion);
      expect(taskIndexes, hasLength(1));
    });

    test('supports basic CRUD across the phase 0 schema', () {
      MobileClawMigrations.migrate(database);
      final now = DateTime.utc(2026, 3, 9).toIso8601String();

      database.execute(
        '''
        INSERT INTO conversations (id, title, created_at, updated_at)
        VALUES (?, ?, ?, ?)
        ''',
        ['conv-1', 'Inbox', now, now],
      );
      database.execute(
        '''
        INSERT INTO messages (id, conversation_id, role, content, created_at, model_output_json)
        VALUES (?, ?, ?, ?, ?, ?)
        ''',
        [
          'msg-1',
          'conv-1',
          'user',
          'hello',
          now,
          jsonEncode({'type': 'answer'}),
        ],
      );
      database.execute(
        '''
        INSERT INTO tasks (id, status, description, risk, created_at, updated_at, error, delegated_to, intent_action, intent_args_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ''',
        [
          'task-1',
          'queued',
          'Organize files',
          'medium',
          now,
          now,
          null,
          null,
          'files.organize',
          jsonEncode({'dryRun': true}),
        ],
      );
      database.execute(
        '''
        INSERT INTO task_checkpoints (task_id, step_id, status_at_checkpoint, state_json, created_at)
        VALUES (?, ?, ?, ?, ?)
        ''',
        [
          'task-1',
          'scan-1',
          'running',
          jsonEncode({'cursor': 1}),
          now
        ],
      );
      database.execute(
        '''
        INSERT INTO devices (id, name, role, platform, capabilities_json, connectivity_mode, last_seen_at, is_paired)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ''',
        [
          'device-1',
          'Phone Hub',
          'phoneHub',
          'android',
          jsonEncode({'files.list': 'supported'}),
          'directLocal',
          now,
          1,
        ],
      );
      database.execute(
        '''
        INSERT INTO file_nodes (id, name, type, source_type, source_path, source_device_id, mime_type, size_bytes, modified_at, created_at, metadata_json, parent_id, sync_cursor_value, sync_cursor_synced_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ''',
        [
          'file-1',
          'contract.pdf',
          'file',
          'local',
          '/documents/contract.pdf',
          'device-1',
          'application/pdf',
          1024,
          now,
          now,
          jsonEncode({'project': 'acme'}),
          null,
          'cursor-1',
          now,
        ],
      );
      database.execute(
        '''
        INSERT INTO file_tags (file_node_id, category, value, confidence)
        VALUES (?, ?, ?, ?)
        ''',
        ['file-1', 'project', 'acme', 0.95],
      );
      database.execute(
        '''
        INSERT INTO file_embeddings (file_node_id, embedding_model, embedding_json, created_at)
        VALUES (?, ?, ?, ?)
        ''',
        [
          'file-1',
          'text-embedding-3-small',
          jsonEncode([0.1, 0.2]),
          now
        ],
      );
      database.execute(
        '''
        INSERT INTO approval_requests (id, task_id, intent_action, intent_args_json, assessed_risk, status, explanation, created_at, decided_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ''',
        [
          'approval-1',
          'task-1',
          'files.share',
          jsonEncode({'target': 'drive'}),
          'high',
          'pending',
          'Need approval',
          now,
          null,
        ],
      );
      database.execute(
        '''
        INSERT INTO audit_events (id, task_id, action, capability, result, reversible, details_json, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ''',
        [
          'audit-1',
          'task-1',
          'files.organize',
          'files.list',
          'planned',
          1,
          jsonEncode({'dryRun': true}),
          now,
        ],
      );
      database.execute(
        '''
        INSERT INTO memory_items (id, content, category, created_at, updated_at, embedding_json)
        VALUES (?, ?, ?, ?, ?, ?)
        ''',
        [
          'memory-1',
          'Customer prefers PDF exports',
          'preference',
          now,
          now,
          jsonEncode([0.3, 0.4]),
        ],
      );
      database.execute(
        '''
        INSERT INTO sync_state (source_type, source_path, device_id, cursor_value, synced_at)
        VALUES (?, ?, ?, ?, ?)
        ''',
        ['local', '/documents', 'device-1', 'cursor-99', now],
      );

      expect(_count(database, MobileClawSchema.conversationsTable), 1);
      expect(_count(database, MobileClawSchema.messagesTable), 1);
      expect(_count(database, MobileClawSchema.tasksTable), 1);
      expect(_count(database, MobileClawSchema.taskCheckpointsTable), 1);
      expect(_count(database, MobileClawSchema.devicesTable), 1);
      expect(_count(database, MobileClawSchema.fileNodesTable), 1);
      expect(_count(database, MobileClawSchema.fileTagsTable), 1);
      expect(_count(database, MobileClawSchema.fileEmbeddingsTable), 1);
      expect(_count(database, MobileClawSchema.approvalRequestsTable), 1);
      expect(_count(database, MobileClawSchema.auditEventsTable), 1);
      expect(_count(database, MobileClawSchema.memoryItemsTable), 1);
      expect(_count(database, MobileClawSchema.syncStateTable), 1);

      final taskRow = database.select(
        'SELECT status, intent_action FROM tasks WHERE id = ?',
        ['task-1'],
      ).single;
      final syncRow = database.select(
        '''
        SELECT cursor_value
        FROM sync_state
        WHERE source_type = ? AND source_path = ? AND device_id = ?
        ''',
        ['local', '/documents', 'device-1'],
      ).single;

      expect(taskRow['status'], 'queued');
      expect(taskRow['intent_action'], 'files.organize');
      expect(syncRow['cursor_value'], 'cursor-99');
    });

    test('cascades related records when parent rows are deleted', () {
      MobileClawMigrations.migrate(database);
      final now = DateTime.utc(2026, 3, 9).toIso8601String();

      database.execute(
        '''
        INSERT INTO tasks (id, status, description, risk, created_at, updated_at, intent_args_json)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ''',
        ['task-1', 'queued', 'Task', 'low', now, now, '{}'],
      );
      database.execute(
        '''
        INSERT INTO task_checkpoints (task_id, step_id, status_at_checkpoint, state_json, created_at)
        VALUES (?, ?, ?, ?, ?)
        ''',
        ['task-1', 'step-1', 'queued', '{}', now],
      );
      database.execute(
        '''
        INSERT INTO approval_requests (id, task_id, intent_action, intent_args_json, assessed_risk, status, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ''',
        ['approval-1', 'task-1', 'files.search', '{}', 'low', 'pending', now],
      );
      database.execute(
        '''
        INSERT INTO file_nodes (id, name, type, source_type, source_path, created_at, metadata_json)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ''',
        ['file-1', 'doc.txt', 'file', 'local', '/doc.txt', now, '{}'],
      );
      database.execute(
        '''
        INSERT INTO file_tags (file_node_id, category, value, confidence)
        VALUES (?, ?, ?, ?)
        ''',
        ['file-1', 'project', 'acme', 1.0],
      );
      database.execute(
        '''
        INSERT INTO file_embeddings (file_node_id, embedding_model, embedding_json, created_at)
        VALUES (?, ?, ?, ?)
        ''',
        ['file-1', 'model-a', '[]', now],
      );

      database.execute('DELETE FROM tasks WHERE id = ?', ['task-1']);
      database.execute('DELETE FROM file_nodes WHERE id = ?', ['file-1']);

      expect(_count(database, MobileClawSchema.taskCheckpointsTable), 0);
      expect(_count(database, MobileClawSchema.approvalRequestsTable), 0);
      expect(_count(database, MobileClawSchema.fileTagsTable), 0);
      expect(_count(database, MobileClawSchema.fileEmbeddingsTable), 0);
    });
  });
}

int _count(Database database, String tableName) {
  final result = database.select('SELECT COUNT(*) AS count FROM $tableName');
  return result.single['count'] as int;
}
