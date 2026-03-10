import 'package:mobileclaw_core/core.dart';

/// Base contract for repositories that persist a single aggregate type.
abstract interface class EntityRepository<T, I extends EntityId> {
  Future<void> put(T entity);

  Future<T?> getById(I id);

  Future<void> delete(I id);
}

abstract interface class ConversationRepository
    implements EntityRepository<Conversation, ConversationId> {
  Future<List<Conversation>> list({int limit = 50});

  Future<void> appendMessage(Message message);

  Future<List<Message>> listMessages(
    ConversationId conversationId, {
    int limit = 100,
  });
}

abstract interface class TaskRepository
    implements EntityRepository<Task, TaskId> {
  Future<List<Task>> listByStatus(TaskStatus status, {int limit = 50});

  Future<void> appendCheckpoint(TaskId taskId, TaskCheckpoint checkpoint);

  Future<List<TaskCheckpoint>> listCheckpoints(TaskId taskId);
}

abstract interface class DeviceRepository
    implements EntityRepository<Device, DeviceId> {
  Future<List<Device>> listPaired();
}

abstract interface class FileNodeRepository
    implements EntityRepository<FileNode, FileNodeId> {
  Future<List<FileNode>> searchByName(String query, {int limit = 50});

  Future<void> replaceTags(FileNodeId fileNodeId, List<SemanticTag> tags);
}

abstract interface class ApprovalRequestRepository
    implements EntityRepository<ApprovalRequest, ApprovalRequestId> {
  Future<List<ApprovalRequest>> listPending({int limit = 50});
}

abstract interface class AuditEventRepository
    implements EntityRepository<AuditEvent, AuditEventId> {
  Future<List<AuditEvent>> listForTask(TaskId taskId, {int limit = 100});
}

abstract interface class MemoryItemRepository
    implements EntityRepository<MemoryItem, MemoryItemId> {
  Future<List<MemoryItem>> listByCategory(String category, {int limit = 50});
}

abstract interface class SyncStateRepository {
  Future<void> put(SyncState state);

  Future<SyncState?> getBySource(
    String sourceType,
    String sourcePath,
    DeviceId deviceId,
  );

  Future<List<SyncState>> list({int limit = 100});
}
