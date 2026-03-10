import 'dart:async';

import '../domain/approval_request.dart';
import '../domain/enums.dart';
import '../domain/ids.dart';
import '../protocol/action_intent.dart';
import 'policy_engine.dart';

/// Manages the approval workflow for risky actions.
///
/// When [PolicyEngine] determines an action requires approval,
/// the system creates an [ApprovalRequest] and waits for the user's response.
abstract interface class ApprovalService {
  /// Create a new approval request.
  Future<ApprovalRequest> requestApproval(
    ActionIntent intent,
    PolicyDecision decision,
    TaskId taskId,
  );

  /// Wait for the user to approve or deny a request.
  Future<ApprovalResponse> waitForApproval(ApprovalRequestId requestId);

  /// Approve a pending request.
  Future<void> approve(ApprovalRequestId requestId);

  /// Deny a pending request with an optional reason.
  Future<void> deny(ApprovalRequestId requestId, {String? reason});

  /// Get a pending request by ID.
  Future<ApprovalRequest?> getRequest(ApprovalRequestId requestId);

  /// List all pending approval requests.
  Future<List<ApprovalRequest>> listPending();
}

/// In-memory approval workflow for tests and local orchestration.
class InMemoryApprovalService implements ApprovalService {
  final DateTime Function() _clock;
  final ApprovalRequestId Function() _idFactory;
  final Map<ApprovalRequestId, ApprovalRequest> _requests = {};
  final Map<ApprovalRequestId, Completer<ApprovalResponse>> _waiters = {};
  final Map<ApprovalRequestId, ApprovalResponse> _responses = {};

  InMemoryApprovalService({
    DateTime Function()? clock,
    ApprovalRequestId Function()? idFactory,
  })  : _clock = clock ?? DateTime.now,
        _idFactory = idFactory ?? ApprovalRequestId.generate;

  @override
  Future<ApprovalRequest> requestApproval(
    ActionIntent intent,
    PolicyDecision decision,
    TaskId taskId,
  ) async {
    final request = ApprovalRequest(
      id: _idFactory(),
      taskId: taskId,
      intentAction: intent.action,
      intentArgs: intent.args,
      assessedRisk: decision.assessedRisk,
      status: ApprovalStatus.pending,
      explanation: decision.reason ?? intent.explanation,
      createdAt: _clock(),
    );
    _requests[request.id] = request;
    _waiters[request.id] = Completer<ApprovalResponse>();
    return request;
  }

  @override
  Future<ApprovalResponse> waitForApproval(ApprovalRequestId requestId) {
    final existingResponse = _responses[requestId];
    if (existingResponse != null) {
      return Future.value(existingResponse);
    }
    final waiter = _waiters[requestId];
    if (waiter == null) {
      return Future.error(
        StateError('No approval request found for ${requestId.value}'),
      );
    }
    return waiter.future;
  }

  @override
  Future<void> approve(ApprovalRequestId requestId) async {
    _resolveRequest(requestId, approved: true);
  }

  @override
  Future<void> deny(ApprovalRequestId requestId, {String? reason}) async {
    _resolveRequest(requestId, approved: false, reason: reason);
  }

  @override
  Future<ApprovalRequest?> getRequest(ApprovalRequestId requestId) async {
    return _requests[requestId];
  }

  @override
  Future<List<ApprovalRequest>> listPending() async {
    final pending = _requests.values.where((request) {
      return request.status == ApprovalStatus.pending;
    }).toList()
      ..sort((left, right) => right.createdAt.compareTo(left.createdAt));
    return pending;
  }

  void _resolveRequest(
    ApprovalRequestId requestId, {
    required bool approved,
    String? reason,
  }) {
    final request = _requests[requestId];
    final waiter = _waiters[requestId];
    if (request == null || waiter == null) {
      throw StateError('No approval request found for ${requestId.value}');
    }
    if (request.status != ApprovalStatus.pending) {
      throw StateError(
          'Approval request ${requestId.value} is already resolved');
    }

    final decidedAt = _clock();
    final nextRequest = request.copyWith(
      status: approved ? ApprovalStatus.approved : ApprovalStatus.denied,
      decidedAt: decidedAt,
    );
    final response = ApprovalResponse(
      requestId: requestId,
      approved: approved,
      reason: reason,
      respondedAt: decidedAt,
    );

    _requests[requestId] = nextRequest;
    _responses[requestId] = response;
    waiter.complete(response);
  }
}
