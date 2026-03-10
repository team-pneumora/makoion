import 'package:equatable/equatable.dart';
import 'package:uuid/uuid.dart';

const _uuid = Uuid();

/// Type-safe ID wrapper base class.
///
/// Prevents accidental mixing of different entity IDs
/// (e.g., passing a TaskId where a DeviceId is expected).
abstract class EntityId extends Equatable {
  final String value;

  const EntityId(this.value);

  @override
  List<Object?> get props => [value];

  @override
  String toString() => '$runtimeType($value)';

  String toJson() => value;
}

class TaskId extends EntityId {
  const TaskId(super.value);
  factory TaskId.generate() => TaskId(_uuid.v4());
  factory TaskId.fromJson(Object? json) => TaskId(json as String);
}

class DeviceId extends EntityId {
  const DeviceId(super.value);
  factory DeviceId.generate() => DeviceId(_uuid.v4());
  factory DeviceId.fromJson(Object? json) => DeviceId(json as String);
}

class FileNodeId extends EntityId {
  const FileNodeId(super.value);
  factory FileNodeId.generate() => FileNodeId(_uuid.v4());
  factory FileNodeId.fromJson(Object? json) => FileNodeId(json as String);
}

class FileVersionId extends EntityId {
  const FileVersionId(super.value);
  factory FileVersionId.generate() => FileVersionId(_uuid.v4());
  factory FileVersionId.fromJson(Object? json) => FileVersionId(json as String);
}

class ConversationId extends EntityId {
  const ConversationId(super.value);
  factory ConversationId.generate() => ConversationId(_uuid.v4());
  factory ConversationId.fromJson(Object? json) =>
      ConversationId(json as String);
}

class MessageId extends EntityId {
  const MessageId(super.value);
  factory MessageId.generate() => MessageId(_uuid.v4());
  factory MessageId.fromJson(Object? json) => MessageId(json as String);
}

class ApprovalRequestId extends EntityId {
  const ApprovalRequestId(super.value);
  factory ApprovalRequestId.generate() => ApprovalRequestId(_uuid.v4());
  factory ApprovalRequestId.fromJson(Object? json) =>
      ApprovalRequestId(json as String);
}

class AuditEventId extends EntityId {
  const AuditEventId(super.value);
  factory AuditEventId.generate() => AuditEventId(_uuid.v4());
  factory AuditEventId.fromJson(Object? json) => AuditEventId(json as String);
}

class MemoryItemId extends EntityId {
  const MemoryItemId(super.value);
  factory MemoryItemId.generate() => MemoryItemId(_uuid.v4());
  factory MemoryItemId.fromJson(Object? json) => MemoryItemId(json as String);
}

class RemoteSessionId extends EntityId {
  const RemoteSessionId(super.value);
  factory RemoteSessionId.generate() => RemoteSessionId(_uuid.v4());
  factory RemoteSessionId.fromJson(Object? json) =>
      RemoteSessionId(json as String);
}

class PairingSessionId extends EntityId {
  const PairingSessionId(super.value);
  factory PairingSessionId.generate() => PairingSessionId(_uuid.v4());
  factory PairingSessionId.fromJson(Object? json) =>
      PairingSessionId(json as String);
}

class UserId extends EntityId {
  const UserId(super.value);
  factory UserId.generate() => UserId(_uuid.v4());
  factory UserId.fromJson(Object? json) => UserId(json as String);
}
