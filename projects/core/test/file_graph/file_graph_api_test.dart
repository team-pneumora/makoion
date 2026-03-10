import 'package:mobileclaw_core/core.dart';
import 'package:test/test.dart';

void main() {
  group('FileGraph contract serialization', () {
    test('round-trips search and preview types', () {
      final query = FileSearchQuery(
        textQuery: 'contract',
        mimeType: 'application/pdf',
        sourceType: 'local',
        deviceId: DeviceId('device-1'),
        tags: const ['project:acme'],
        modifiedAfter: DateTime.utc(2026, 3, 1),
        modifiedBefore: DateTime.utc(2026, 3, 9),
        limit: 50,
      );
      final preview = FilePreview(
        fileId: FileNodeId('file-1'),
        previewType: 'text',
        content: 'Preview text',
        metadata: const {'pageCount': 3},
      );
      final summary = FileSummary(
        fileIds: const [FileNodeId('file-1')],
        summary: 'Summary text',
        perFileSummary: const {'file-1': 'Per-file summary'},
      );

      final decodedQuery = FileSearchQuery.fromJson(query.toJson());
      final decodedPreview = FilePreview.fromJson(preview.toJson());
      final decodedSummary = FileSummary.fromJson(summary.toJson());

      expect(decodedQuery.textQuery, query.textQuery);
      expect(decodedQuery.deviceId, query.deviceId);
      expect(decodedPreview.fileId, preview.fileId);
      expect(decodedPreview.metadata, preview.metadata);
      expect(decodedSummary.fileIds, summary.fileIds);
      expect(decodedSummary.perFileSummary, summary.perFileSummary);
    });

    test('round-trips organize, move, copy, and share requests', () {
      final organizeRequest = OrganizeRequest(
        fileIds: const [FileNodeId('file-1')],
        targetFolder: '/archive',
        strategy: 'by_project',
      );
      final organizePlan = OrganizePlan(
        actions: const [
          OrganizeAction(
            fileId: FileNodeId('file-1'),
            actionType: 'move',
            from: '/inbox',
            to: '/archive',
          ),
        ],
        explanation: 'Move into archive folder.',
        risk: RiskLevel.medium,
      );
      final moveRequest = MoveRequest(
        fileIds: const [FileNodeId('file-1')],
        destinationPath: '/archive',
        destinationDevice: DeviceId('device-2'),
      );
      final copyRequest = CopyRequest(
        fileIds: const [FileNodeId('file-1')],
        destinationPath: '/backup',
      );
      final shareRequest = ShareRequest(
        fileIds: const [FileNodeId('file-1')],
        shareTarget: 'drive',
        message: 'Please review',
      );

      expect(
        OrganizeRequest.fromJson(organizeRequest.toJson()).strategy,
        organizeRequest.strategy,
      );
      expect(
        OrganizePlan.fromJson(organizePlan.toJson()).actions.single.to,
        '/archive',
      );
      expect(
        MoveRequest.fromJson(moveRequest.toJson()).destinationDevice,
        moveRequest.destinationDevice,
      );
      expect(
        CopyRequest.fromJson(copyRequest.toJson()).destinationPath,
        copyRequest.destinationPath,
      );
      expect(
        ShareRequest.fromJson(shareRequest.toJson()).message,
        shareRequest.message,
      );
    });

    test('round-trips dedupe and cross-device transfer requests', () {
      final dedupeRequest = DedupeRequest(
        scope: '/docs',
        deviceId: DeviceId('device-1'),
        dryRun: false,
      );
      final dedupeResult = DedupeResult(
        groups: const [
          DuplicateGroup(
            fileIds: [FileNodeId('file-1'), FileNodeId('file-2')],
            suggestedKeep: FileNodeId('file-1'),
            wastedBytes: 2048,
          ),
        ],
        totalDuplicates: 1,
        totalSavingsBytes: 2048,
      );
      final sendRequest = SendToDeviceRequest(
        fileIds: const [FileNodeId('file-1')],
        targetDevice: DeviceId('device-2'),
        destinationPath: '/incoming',
      );
      final requestFromDevice = RequestFromDeviceRequest(
        sourceDevice: DeviceId('device-3'),
        sourcePath: '/exports',
        query: 'latest report',
      );

      expect(
        DedupeRequest.fromJson(dedupeRequest.toJson()).dryRun,
        isFalse,
      );
      expect(
        DedupeResult.fromJson(dedupeResult.toJson()).groups.single.wastedBytes,
        2048,
      );
      expect(
        SendToDeviceRequest.fromJson(sendRequest.toJson()).targetDevice,
        sendRequest.targetDevice,
      );
      expect(
        RequestFromDeviceRequest.fromJson(requestFromDevice.toJson()).query,
        requestFromDevice.query,
      );
    });
  });
}
