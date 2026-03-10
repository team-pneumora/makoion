import 'package:meta/meta.dart';

import '../domain/enums.dart';
import '../domain/file_node.dart';
import '../domain/ids.dart';
import '../support/json_utils.dart';

/// The 10 core file operations in the Unified File Graph.
///
/// All file sources (local, cloud, companion) are unified behind
/// this single API. Implementations live in the platform layer.
abstract interface class FileGraphApi {
  /// Search files across all sources.
  Future<List<FileNode>> search(FileSearchQuery query);

  /// Preview a file's content (text excerpt, image thumbnail, etc.).
  Future<FilePreview> preview(FileNodeId id);

  /// Summarize one or more files using AI.
  Future<FileSummary> summarize(List<FileNodeId> ids);

  /// Generate an organization plan (rename, folder structure, etc.).
  Future<OrganizePlan> organize(OrganizeRequest request);

  /// Move files to a new location.
  Future<void> move(MoveRequest request);

  /// Copy files to a new location.
  Future<void> copy(CopyRequest request);

  /// Share files with other apps or contacts.
  Future<void> share(ShareRequest request);

  /// Find and report duplicate files.
  Future<DedupeResult> dedupe(DedupeRequest request);

  /// Send files to another device.
  Future<void> sendToDevice(SendToDeviceRequest request);

  /// Request files from another device.
  Future<void> requestFromDevice(RequestFromDeviceRequest request);
}

// ── Query & Request types ──────────────────────────────────────────

@immutable
class FileSearchQuery {
  final String? textQuery;
  final String? mimeType;
  final String? sourceType;
  final DeviceId? deviceId;
  final List<String>? tags;
  final DateTime? modifiedAfter;
  final DateTime? modifiedBefore;
  final int limit;

  const FileSearchQuery({
    this.textQuery,
    this.mimeType,
    this.sourceType,
    this.deviceId,
    this.tags,
    this.modifiedAfter,
    this.modifiedBefore,
    this.limit = 20,
  });

  Map<String, dynamic> toJson() => {
        'textQuery': textQuery,
        'mimeType': mimeType,
        'sourceType': sourceType,
        'deviceId': deviceId?.value,
        'tags': tags,
        'modifiedAfter': modifiedAfter?.toIso8601String(),
        'modifiedBefore': modifiedBefore?.toIso8601String(),
        'limit': limit,
      };

  factory FileSearchQuery.fromJson(Map<String, dynamic> json) =>
      FileSearchQuery(
        textQuery: json['textQuery'] as String?,
        mimeType: json['mimeType'] as String?,
        sourceType: json['sourceType'] as String?,
        deviceId: json['deviceId'] == null
            ? null
            : DeviceId.fromJson(json['deviceId']),
        tags: json['tags'] == null
            ? null
            : castJsonList(json['tags']).cast<String>(),
        modifiedAfter: parseNullableDateTime(json['modifiedAfter']),
        modifiedBefore: parseNullableDateTime(json['modifiedBefore']),
        limit: json['limit'] as int? ?? 20,
      );
}

@immutable
class FilePreview {
  final FileNodeId fileId;
  final String previewType; // 'text', 'image', 'metadata'
  final String content;
  final Map<String, dynamic>? metadata;

  const FilePreview({
    required this.fileId,
    required this.previewType,
    required this.content,
    this.metadata,
  });

  Map<String, dynamic> toJson() => {
        'fileId': fileId.value,
        'previewType': previewType,
        'content': content,
        'metadata': metadata,
      };

  factory FilePreview.fromJson(Map<String, dynamic> json) => FilePreview(
        fileId: FileNodeId.fromJson(json['fileId']),
        previewType: json['previewType'] as String,
        content: json['content'] as String,
        metadata:
            json['metadata'] == null ? null : castJsonMap(json['metadata']),
      );
}

@immutable
class FileSummary {
  final List<FileNodeId> fileIds;
  final String summary;
  final Map<String, String>? perFileSummary;

  const FileSummary({
    required this.fileIds,
    required this.summary,
    this.perFileSummary,
  });

  Map<String, dynamic> toJson() => {
        'fileIds': fileIds.map((id) => id.value).toList(),
        'summary': summary,
        'perFileSummary': perFileSummary,
      };

  factory FileSummary.fromJson(Map<String, dynamic> json) => FileSummary(
        fileIds: castJsonList(json['fileIds'])
            .map(FileNodeId.fromJson)
            .toList(growable: false),
        summary: json['summary'] as String,
        perFileSummary: json['perFileSummary'] == null
            ? null
            : Map<String, String>.from(json['perFileSummary'] as Map),
      );
}

@immutable
class OrganizeRequest {
  final List<FileNodeId> fileIds;
  final String? targetFolder;
  final String? strategy; // 'by_date', 'by_type', 'by_project', 'custom'

  const OrganizeRequest({
    required this.fileIds,
    this.targetFolder,
    this.strategy,
  });

  Map<String, dynamic> toJson() => {
        'fileIds': fileIds.map((id) => id.value).toList(),
        'targetFolder': targetFolder,
        'strategy': strategy,
      };

  factory OrganizeRequest.fromJson(Map<String, dynamic> json) =>
      OrganizeRequest(
        fileIds: castJsonList(json['fileIds'])
            .map(FileNodeId.fromJson)
            .toList(growable: false),
        targetFolder: json['targetFolder'] as String?,
        strategy: json['strategy'] as String?,
      );
}

@immutable
class OrganizePlan {
  final List<OrganizeAction> actions;
  final String explanation;
  final RiskLevel risk;

  const OrganizePlan({
    required this.actions,
    required this.explanation,
    required this.risk,
  });

  Map<String, dynamic> toJson() => {
        'actions': actions.map((action) => action.toJson()).toList(),
        'explanation': explanation,
        'risk': risk.name,
      };

  factory OrganizePlan.fromJson(Map<String, dynamic> json) => OrganizePlan(
        actions: castJsonList(json['actions'])
            .map((item) => OrganizeAction.fromJson(castJsonMap(item)))
            .toList(growable: false),
        explanation: json['explanation'] as String,
        risk: enumByName(RiskLevel.values, json['risk']),
      );
}

@immutable
class OrganizeAction {
  final FileNodeId fileId;
  final String actionType; // 'move', 'rename', 'tag'
  final String? from;
  final String? to;

  const OrganizeAction({
    required this.fileId,
    required this.actionType,
    this.from,
    this.to,
  });

  Map<String, dynamic> toJson() => {
        'fileId': fileId.value,
        'actionType': actionType,
        'from': from,
        'to': to,
      };

  factory OrganizeAction.fromJson(Map<String, dynamic> json) => OrganizeAction(
        fileId: FileNodeId.fromJson(json['fileId']),
        actionType: json['actionType'] as String,
        from: json['from'] as String?,
        to: json['to'] as String?,
      );
}

@immutable
class MoveRequest {
  final List<FileNodeId> fileIds;
  final String destinationPath;
  final DeviceId? destinationDevice;

  const MoveRequest({
    required this.fileIds,
    required this.destinationPath,
    this.destinationDevice,
  });

  Map<String, dynamic> toJson() => {
        'fileIds': fileIds.map((id) => id.value).toList(),
        'destinationPath': destinationPath,
        'destinationDevice': destinationDevice?.value,
      };

  factory MoveRequest.fromJson(Map<String, dynamic> json) => MoveRequest(
        fileIds: castJsonList(json['fileIds'])
            .map(FileNodeId.fromJson)
            .toList(growable: false),
        destinationPath: json['destinationPath'] as String,
        destinationDevice: json['destinationDevice'] == null
            ? null
            : DeviceId.fromJson(json['destinationDevice']),
      );
}

@immutable
class CopyRequest {
  final List<FileNodeId> fileIds;
  final String destinationPath;
  final DeviceId? destinationDevice;

  const CopyRequest({
    required this.fileIds,
    required this.destinationPath,
    this.destinationDevice,
  });

  Map<String, dynamic> toJson() => {
        'fileIds': fileIds.map((id) => id.value).toList(),
        'destinationPath': destinationPath,
        'destinationDevice': destinationDevice?.value,
      };

  factory CopyRequest.fromJson(Map<String, dynamic> json) => CopyRequest(
        fileIds: castJsonList(json['fileIds'])
            .map(FileNodeId.fromJson)
            .toList(growable: false),
        destinationPath: json['destinationPath'] as String,
        destinationDevice: json['destinationDevice'] == null
            ? null
            : DeviceId.fromJson(json['destinationDevice']),
      );
}

@immutable
class ShareRequest {
  final List<FileNodeId> fileIds;
  final String shareTarget; // app package name, contact id, etc.
  final String? message;

  const ShareRequest({
    required this.fileIds,
    required this.shareTarget,
    this.message,
  });

  Map<String, dynamic> toJson() => {
        'fileIds': fileIds.map((id) => id.value).toList(),
        'shareTarget': shareTarget,
        'message': message,
      };

  factory ShareRequest.fromJson(Map<String, dynamic> json) => ShareRequest(
        fileIds: castJsonList(json['fileIds'])
            .map(FileNodeId.fromJson)
            .toList(growable: false),
        shareTarget: json['shareTarget'] as String,
        message: json['message'] as String?,
      );
}

@immutable
class DedupeRequest {
  final String? scope; // 'all', specific folder path, etc.
  final DeviceId? deviceId;
  final bool dryRun;

  const DedupeRequest({
    this.scope,
    this.deviceId,
    this.dryRun = true,
  });

  Map<String, dynamic> toJson() => {
        'scope': scope,
        'deviceId': deviceId?.value,
        'dryRun': dryRun,
      };

  factory DedupeRequest.fromJson(Map<String, dynamic> json) => DedupeRequest(
        scope: json['scope'] as String?,
        deviceId: json['deviceId'] == null
            ? null
            : DeviceId.fromJson(json['deviceId']),
        dryRun: json['dryRun'] as bool? ?? true,
      );
}

@immutable
class DedupeResult {
  final List<DuplicateGroup> groups;
  final int totalDuplicates;
  final int totalSavingsBytes;

  const DedupeResult({
    required this.groups,
    required this.totalDuplicates,
    required this.totalSavingsBytes,
  });

  Map<String, dynamic> toJson() => {
        'groups': groups.map((group) => group.toJson()).toList(),
        'totalDuplicates': totalDuplicates,
        'totalSavingsBytes': totalSavingsBytes,
      };

  factory DedupeResult.fromJson(Map<String, dynamic> json) => DedupeResult(
        groups: castJsonList(json['groups'])
            .map((item) => DuplicateGroup.fromJson(castJsonMap(item)))
            .toList(growable: false),
        totalDuplicates: json['totalDuplicates'] as int,
        totalSavingsBytes: json['totalSavingsBytes'] as int,
      );
}

@immutable
class DuplicateGroup {
  final List<FileNodeId> fileIds;
  final FileNodeId suggestedKeep;
  final int wastedBytes;

  const DuplicateGroup({
    required this.fileIds,
    required this.suggestedKeep,
    required this.wastedBytes,
  });

  Map<String, dynamic> toJson() => {
        'fileIds': fileIds.map((id) => id.value).toList(),
        'suggestedKeep': suggestedKeep.value,
        'wastedBytes': wastedBytes,
      };

  factory DuplicateGroup.fromJson(Map<String, dynamic> json) => DuplicateGroup(
        fileIds: castJsonList(json['fileIds'])
            .map(FileNodeId.fromJson)
            .toList(growable: false),
        suggestedKeep: FileNodeId.fromJson(json['suggestedKeep']),
        wastedBytes: json['wastedBytes'] as int,
      );
}

@immutable
class SendToDeviceRequest {
  final List<FileNodeId> fileIds;
  final DeviceId targetDevice;
  final String? destinationPath;

  const SendToDeviceRequest({
    required this.fileIds,
    required this.targetDevice,
    this.destinationPath,
  });

  Map<String, dynamic> toJson() => {
        'fileIds': fileIds.map((id) => id.value).toList(),
        'targetDevice': targetDevice.value,
        'destinationPath': destinationPath,
      };

  factory SendToDeviceRequest.fromJson(Map<String, dynamic> json) =>
      SendToDeviceRequest(
        fileIds: castJsonList(json['fileIds'])
            .map(FileNodeId.fromJson)
            .toList(growable: false),
        targetDevice: DeviceId.fromJson(json['targetDevice']),
        destinationPath: json['destinationPath'] as String?,
      );
}

@immutable
class RequestFromDeviceRequest {
  final DeviceId sourceDevice;
  final String? sourcePath;
  final String? query;

  const RequestFromDeviceRequest({
    required this.sourceDevice,
    this.sourcePath,
    this.query,
  });

  Map<String, dynamic> toJson() => {
        'sourceDevice': sourceDevice.value,
        'sourcePath': sourcePath,
        'query': query,
      };

  factory RequestFromDeviceRequest.fromJson(Map<String, dynamic> json) =>
      RequestFromDeviceRequest(
        sourceDevice: DeviceId.fromJson(json['sourceDevice']),
        sourcePath: json['sourcePath'] as String?,
        query: json['query'] as String?,
      );
}
