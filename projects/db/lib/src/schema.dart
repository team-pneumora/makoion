import 'package:meta/meta.dart';

/// Versioned SQLite schema for MobileClaw Phase 0.
@immutable
final class MobileClawSchema {
  const MobileClawSchema._();

  static const int latestVersion = 1;

  static const String conversationsTable = 'conversations';
  static const String messagesTable = 'messages';
  static const String tasksTable = 'tasks';
  static const String taskCheckpointsTable = 'task_checkpoints';
  static const String devicesTable = 'devices';
  static const String fileNodesTable = 'file_nodes';
  static const String fileTagsTable = 'file_tags';
  static const String fileEmbeddingsTable = 'file_embeddings';
  static const String approvalRequestsTable = 'approval_requests';
  static const String auditEventsTable = 'audit_events';
  static const String memoryItemsTable = 'memory_items';
  static const String syncStateTable = 'sync_state';

  static const List<String> applicationTables = [
    conversationsTable,
    messagesTable,
    tasksTable,
    taskCheckpointsTable,
    devicesTable,
    fileNodesTable,
    fileTagsTable,
    fileEmbeddingsTable,
    approvalRequestsTable,
    auditEventsTable,
    memoryItemsTable,
    syncStateTable,
  ];

  static const List<String> createStatementsV1 = [
    '''
    CREATE TABLE conversations (
      id TEXT PRIMARY KEY,
      title TEXT,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    )
    ''',
    '''
    CREATE TABLE messages (
      id TEXT PRIMARY KEY,
      conversation_id TEXT NOT NULL,
      role TEXT NOT NULL,
      content TEXT NOT NULL,
      created_at TEXT NOT NULL,
      model_output_json TEXT,
      FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
    )
    ''',
    '''
    CREATE TABLE tasks (
      id TEXT PRIMARY KEY,
      status TEXT NOT NULL,
      description TEXT NOT NULL,
      risk TEXT NOT NULL,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL,
      error TEXT,
      delegated_to TEXT,
      intent_action TEXT,
      intent_args_json TEXT
    )
    ''',
    '''
    CREATE TABLE task_checkpoints (
      task_id TEXT NOT NULL,
      step_id TEXT NOT NULL,
      status_at_checkpoint TEXT NOT NULL,
      state_json TEXT NOT NULL,
      created_at TEXT NOT NULL,
      PRIMARY KEY (task_id, step_id, created_at),
      FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
    )
    ''',
    '''
    CREATE TABLE devices (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      role TEXT NOT NULL,
      platform TEXT NOT NULL,
      capabilities_json TEXT NOT NULL,
      connectivity_mode TEXT NOT NULL,
      last_seen_at TEXT NOT NULL,
      is_paired INTEGER NOT NULL
    )
    ''',
    '''
    CREATE TABLE file_nodes (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      type TEXT NOT NULL,
      source_type TEXT NOT NULL,
      source_path TEXT NOT NULL,
      source_device_id TEXT,
      mime_type TEXT,
      size_bytes INTEGER,
      modified_at TEXT,
      created_at TEXT NOT NULL,
      metadata_json TEXT NOT NULL,
      parent_id TEXT,
      sync_cursor_value TEXT,
      sync_cursor_synced_at TEXT,
      FOREIGN KEY (parent_id) REFERENCES file_nodes(id) ON DELETE SET NULL
    )
    ''',
    '''
    CREATE TABLE file_tags (
      file_node_id TEXT NOT NULL,
      category TEXT NOT NULL,
      value TEXT NOT NULL,
      confidence REAL NOT NULL,
      PRIMARY KEY (file_node_id, category, value),
      FOREIGN KEY (file_node_id) REFERENCES file_nodes(id) ON DELETE CASCADE
    )
    ''',
    '''
    CREATE TABLE file_embeddings (
      file_node_id TEXT NOT NULL,
      embedding_model TEXT NOT NULL,
      embedding_json TEXT NOT NULL,
      created_at TEXT NOT NULL,
      PRIMARY KEY (file_node_id, embedding_model),
      FOREIGN KEY (file_node_id) REFERENCES file_nodes(id) ON DELETE CASCADE
    )
    ''',
    '''
    CREATE TABLE approval_requests (
      id TEXT PRIMARY KEY,
      task_id TEXT NOT NULL,
      intent_action TEXT NOT NULL,
      intent_args_json TEXT NOT NULL,
      assessed_risk TEXT NOT NULL,
      status TEXT NOT NULL,
      explanation TEXT,
      created_at TEXT NOT NULL,
      decided_at TEXT,
      FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
    )
    ''',
    '''
    CREATE TABLE audit_events (
      id TEXT PRIMARY KEY,
      task_id TEXT,
      action TEXT NOT NULL,
      capability TEXT,
      result TEXT NOT NULL,
      reversible INTEGER NOT NULL,
      details_json TEXT,
      created_at TEXT NOT NULL,
      FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE SET NULL
    )
    ''',
    '''
    CREATE TABLE memory_items (
      id TEXT PRIMARY KEY,
      content TEXT NOT NULL,
      category TEXT,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL,
      embedding_json TEXT
    )
    ''',
    '''
    CREATE TABLE sync_state (
      source_type TEXT NOT NULL,
      source_path TEXT NOT NULL,
      device_id TEXT NOT NULL,
      cursor_value TEXT NOT NULL,
      synced_at TEXT NOT NULL,
      PRIMARY KEY (source_type, source_path, device_id)
    )
    ''',
    'CREATE INDEX idx_messages_conversation_id ON messages(conversation_id)',
    'CREATE INDEX idx_tasks_status ON tasks(status)',
    'CREATE INDEX idx_task_checkpoints_task_id ON task_checkpoints(task_id)',
    'CREATE INDEX idx_file_nodes_parent_id ON file_nodes(parent_id)',
    'CREATE INDEX idx_file_nodes_source ON file_nodes(source_type, source_path)',
    'CREATE INDEX idx_file_tags_category_value ON file_tags(category, value)',
    'CREATE INDEX idx_approval_requests_status ON approval_requests(status)',
    'CREATE INDEX idx_audit_events_task_id ON audit_events(task_id)',
    'CREATE INDEX idx_memory_items_category ON memory_items(category)',
  ];

  static List<String> statementsForVersion(int version) {
    switch (version) {
      case 1:
        return createStatementsV1;
    }
    throw ArgumentError.value(version, 'version', 'Unsupported schema version');
  }
}
