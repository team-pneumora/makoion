import 'package:mobileclaw_core/core.dart';
import 'package:test/test.dart';

void main() {
  group('Domain serialization', () {
    test('round-trips conversations and messages', () {
      final conversation = Conversation(
        id: ConversationId('conv-1'),
        title: 'Inbox',
        createdAt: DateTime.utc(2026, 3, 9),
        updatedAt: DateTime.utc(2026, 3, 10),
      );
      final message = Message(
        id: MessageId('msg-1'),
        conversationId: conversation.id,
        role: 'assistant',
        content: 'hello',
        createdAt: DateTime.utc(2026, 3, 10, 12),
        modelOutputJson: '{"type":"answer"}',
      );

      expect(Conversation.fromJson(conversation.toJson()), conversation);
      expect(Message.fromJson(message.toJson()), message);
    });

    test('round-trips task and checkpoints', () {
      final task = Task(
        id: TaskId('task-1'),
        status: TaskStatus.running,
        description: 'Organize files',
        risk: RiskLevel.medium,
        createdAt: DateTime.utc(2026, 3, 9),
        updatedAt: DateTime.utc(2026, 3, 10),
        checkpoints: [
          TaskCheckpoint(
            stepId: 'scan-1',
            statusAtCheckpoint: TaskStatus.running,
            state: const {'cursor': 3},
            createdAt: DateTime.utc(2026, 3, 10, 1),
          ),
        ],
        error: 'transient',
        delegatedTo: DeviceId('device-1'),
        intentAction: 'files.organize',
        intentArgs: const {'dryRun': true},
      );

      expect(Task.fromJson(task.toJson()), task);
    });

    test('round-trips file nodes and sync cursor', () {
      final fileNode = FileNode(
        id: FileNodeId('file-1'),
        name: 'contract.pdf',
        type: FileNodeType.file,
        source: const FileSource(
          sourceType: FileSource.typeLocal,
          sourcePath: '/docs/contract.pdf',
          deviceId: DeviceId('device-1'),
        ),
        mimeType: 'application/pdf',
        sizeBytes: 1024,
        modifiedAt: DateTime.utc(2026, 3, 8),
        createdAt: DateTime.utc(2026, 3, 7),
        metadata: const {'project': 'acme'},
        tags: const [
          SemanticTag(category: 'project', value: 'acme', confidence: 0.9),
        ],
        parentId: FileNodeId('parent-1'),
        syncCursor: SyncCursor(
          cursorValue: 'cursor-1',
          syncedAt: DateTime.utc(2026, 3, 9),
        ),
      );

      expect(FileNode.fromJson(fileNode.toJson()), fileNode);
    });

    test('round-trips device capability and file revision metadata', () {
      final capability = DeviceCapability(
        deviceId: DeviceId('device-1'),
        capability: Capabilities.filesTransfer,
        status: CapabilityStatus.supported,
        updatedAt: DateTime.utc(2026, 3, 9),
        detail: 'paired and available',
      );
      final version = FileVersion(
        id: FileVersionId('version-1'),
        fileId: FileNodeId('file-1'),
        revision: 'r42',
        sizeBytes: 2048,
        checksum: 'abc123',
        sourcePath: '/docs/file.txt',
        createdAt: DateTime.utc(2026, 3, 9, 1),
      );
      final embedding = FileEmbedding(
        fileId: FileNodeId('file-1'),
        model: 'text-embedding-3-small',
        vector: const [0.1, 0.2, 0.3],
        createdAt: DateTime.utc(2026, 3, 9, 2),
      );

      expect(DeviceCapability.fromJson(capability.toJson()), capability);
      expect(FileVersion.fromJson(version.toJson()), version);
      expect(FileEmbedding.fromJson(embedding.toJson()), embedding);
    });

    test('round-trips devices and user profile', () {
      final device = Device(
        id: DeviceId('device-1'),
        name: 'Phone',
        role: DeviceRole.phoneHub,
        platform: DevicePlatform.android,
        lastSeenAt: DateTime.utc(2026, 3, 9),
        capabilities: const {
          Capabilities.filesList: CapabilityStatus.supported,
          Capabilities.camera: CapabilityStatus.limited,
        },
        connectivityMode: ConnectivityMode.directLocal,
        isPaired: true,
      );
      final user = UserProfile(
        id: UserId('user-1'),
        name: 'Mina',
        createdAt: DateTime.utc(2026, 3, 9),
      );

      expect(Device.fromJson(device.toJson()), device);
      expect(UserProfile.fromJson(user.toJson()), user);
    });

    test('round-trips approval, audit, memory, and sync state', () {
      final request = ApprovalRequest(
        id: ApprovalRequestId('approval-1'),
        taskId: TaskId('task-1'),
        intentAction: 'files.share',
        intentArgs: const {'target': 'drive'},
        assessedRisk: RiskLevel.high,
        status: ApprovalStatus.pending,
        explanation: 'Need approval',
        createdAt: DateTime.utc(2026, 3, 9),
      );
      final response = ApprovalResponse(
        requestId: request.id,
        approved: false,
        reason: 'denied',
        respondedAt: DateTime.utc(2026, 3, 9, 1),
      );
      final auditEvent = AuditEvent(
        id: AuditEventId('audit-1'),
        taskId: TaskId('task-1'),
        action: 'files.share',
        capability: Capabilities.filesTransfer,
        result: 'blocked',
        reversible: false,
        details: const {'reason': 'approval denied'},
        createdAt: DateTime.utc(2026, 3, 9, 2),
      );
      final memoryItem = MemoryItem(
        id: MemoryItemId('memory-1'),
        content: 'Prefers PDFs',
        category: 'preference',
        createdAt: DateTime.utc(2026, 3, 9),
        updatedAt: DateTime.utc(2026, 3, 10),
      );
      final syncState = SyncState(
        sourceType: 'local',
        sourcePath: '/docs',
        deviceId: DeviceId('device-1'),
        cursorValue: 'cursor-42',
        syncedAt: DateTime.utc(2026, 3, 9, 3),
      );
      final remoteSession = RemoteSession(
        id: RemoteSessionId('session-1'),
        localDeviceId: DeviceId('device-phone'),
        remoteDeviceId: DeviceId('device-desktop'),
        status: RemoteSessionStatus.active,
        connectivityMode: ConnectivityMode.directLocal,
        grantedCapabilities: const [Capabilities.filesTransfer],
        createdAt: DateTime.utc(2026, 3, 9, 4),
        updatedAt: DateTime.utc(2026, 3, 9, 5),
      );

      expect(ApprovalRequest.fromJson(request.toJson()), request);
      expect(ApprovalResponse.fromJson(response.toJson()), response);
      expect(AuditEvent.fromJson(auditEvent.toJson()), auditEvent);
      expect(MemoryItem.fromJson(memoryItem.toJson()), memoryItem);
      expect(SyncState.fromJson(syncState.toJson()), syncState);
      expect(RemoteSession.fromJson(remoteSession.toJson()), remoteSession);
    });
  });
}
