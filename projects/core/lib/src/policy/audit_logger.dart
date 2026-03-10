import 'package:meta/meta.dart';

import '../domain/audit_event.dart';
import '../domain/ids.dart';

/// Logs all actions taken by the system for traceability.
///
/// Every action that passes through the executor is recorded as an
/// [AuditEvent], allowing users to review the full history of what
/// the AI did and why.
abstract interface class AuditLogger {
  /// Log an audit event.
  Future<void> log(AuditEvent event);

  /// Query audit events matching the given criteria.
  Future<List<AuditEvent>> query(AuditQuery query);

  /// Get a single event by ID.
  Future<AuditEvent?> getById(AuditEventId id);
}

/// In-memory audit log for tests and local flows.
class InMemoryAuditLogger implements AuditLogger {
  final List<AuditEvent> _events = [];

  @override
  Future<void> log(AuditEvent event) async {
    _events.add(event);
  }

  @override
  Future<List<AuditEvent>> query(AuditQuery query) async {
    final filtered = _events.where((event) {
      if (query.taskId != null && event.taskId != query.taskId) {
        return false;
      }
      if (query.action != null && event.action != query.action) {
        return false;
      }
      if (query.capability != null && event.capability != query.capability) {
        return false;
      }
      if (query.after != null && event.createdAt.isBefore(query.after!)) {
        return false;
      }
      if (query.before != null && event.createdAt.isAfter(query.before!)) {
        return false;
      }
      return true;
    }).toList()
      ..sort((left, right) => right.createdAt.compareTo(left.createdAt));
    if (filtered.length <= query.limit) {
      return filtered;
    }
    return filtered.take(query.limit).toList(growable: false);
  }

  @override
  Future<AuditEvent?> getById(AuditEventId id) async {
    for (final event in _events) {
      if (event.id == id) {
        return event;
      }
    }
    return null;
  }
}

/// Query criteria for filtering audit events.
@immutable
class AuditQuery {
  final TaskId? taskId;
  final String? action;
  final String? capability;
  final DateTime? after;
  final DateTime? before;
  final int limit;

  const AuditQuery({
    this.taskId,
    this.action,
    this.capability,
    this.after,
    this.before,
    this.limit = 50,
  });
}
