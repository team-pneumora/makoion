import 'package:equatable/equatable.dart';
import 'package:meta/meta.dart';

import 'enums.dart';
import 'ids.dart';
import '../support/json_utils.dart';

/// A node in the Unified File Graph.
///
/// Represents a file, folder, or collection from any source
/// (local, Google Drive, OneDrive, Dropbox, Companion device).
@immutable
class FileNode extends Equatable {
  final FileNodeId id;
  final String name;
  final FileNodeType type;
  final FileSource source;
  final String? mimeType;
  final int? sizeBytes;
  final DateTime? modifiedAt;
  final DateTime createdAt;
  final Map<String, String> metadata;
  final List<SemanticTag> tags;
  final FileNodeId? parentId;
  final SyncCursor? syncCursor;

  const FileNode({
    required this.id,
    required this.name,
    required this.type,
    required this.source,
    required this.createdAt,
    this.mimeType,
    this.sizeBytes,
    this.modifiedAt,
    this.metadata = const {},
    this.tags = const [],
    this.parentId,
    this.syncCursor,
  });

  Map<String, dynamic> toJson() => {
        'id': id.value,
        'name': name,
        'type': type.name,
        'source': source.toJson(),
        'mimeType': mimeType,
        'sizeBytes': sizeBytes,
        'modifiedAt': modifiedAt?.toIso8601String(),
        'createdAt': createdAt.toIso8601String(),
        'metadata': metadata,
        'tags': tags.map((tag) => tag.toJson()).toList(),
        'parentId': parentId?.value,
        'syncCursor': syncCursor?.toJson(),
      };

  factory FileNode.fromJson(Map<String, dynamic> json) => FileNode(
        id: FileNodeId.fromJson(json['id']),
        name: json['name'] as String,
        type: enumByName(FileNodeType.values, json['type']),
        source: FileSource.fromJson(castJsonMap(json['source'])),
        mimeType: json['mimeType'] as String?,
        sizeBytes: json['sizeBytes'] as int?,
        modifiedAt: parseNullableDateTime(json['modifiedAt']),
        createdAt: parseDateTime(json['createdAt']),
        metadata: castStringMap(json['metadata']),
        tags: castJsonList(json['tags'])
            .map((item) => SemanticTag.fromJson(castJsonMap(item)))
            .toList(growable: false),
        parentId: json['parentId'] == null
            ? null
            : FileNodeId.fromJson(json['parentId']),
        syncCursor: json['syncCursor'] == null
            ? null
            : SyncCursor.fromJson(castJsonMap(json['syncCursor'])),
      );

  bool get isFile => type == FileNodeType.file;
  bool get isFolder => type == FileNodeType.folder;
  bool get isCollection => type == FileNodeType.collection;

  @override
  List<Object?> get props => [
        id,
        name,
        type,
        source,
        mimeType,
        sizeBytes,
        modifiedAt,
        createdAt,
        metadata,
        tags,
        parentId,
        syncCursor,
      ];
}

/// Where a file physically resides.
@immutable
class FileSource extends Equatable {
  final String sourceType;
  final String sourcePath;
  final DeviceId? deviceId;

  const FileSource({
    required this.sourceType,
    required this.sourcePath,
    this.deviceId,
  });

  Map<String, dynamic> toJson() => {
        'sourceType': sourceType,
        'sourcePath': sourcePath,
        'deviceId': deviceId?.value,
      };

  factory FileSource.fromJson(Map<String, dynamic> json) => FileSource(
        sourceType: json['sourceType'] as String,
        sourcePath: json['sourcePath'] as String,
        deviceId: json['deviceId'] == null
            ? null
            : DeviceId.fromJson(json['deviceId']),
      );

  static const typeLocal = 'local';
  static const typeGdrive = 'gdrive';
  static const typeOnedrive = 'onedrive';
  static const typeDropbox = 'dropbox';
  static const typeCompanion = 'companion';
  static const typeIcloud = 'icloud';

  bool get isLocal => sourceType == typeLocal;
  bool get isCloud =>
      sourceType == typeGdrive ||
      sourceType == typeOnedrive ||
      sourceType == typeDropbox ||
      sourceType == typeIcloud;
  bool get isCompanion => sourceType == typeCompanion;

  @override
  List<Object?> get props => [sourceType, sourcePath, deviceId];
}

/// A semantic tag attached to a file node.
@immutable
class SemanticTag extends Equatable {
  final String category;
  final String value;
  final double confidence;

  const SemanticTag({
    required this.category,
    required this.value,
    this.confidence = 1.0,
  });

  Map<String, dynamic> toJson() => {
        'category': category,
        'value': value,
        'confidence': confidence,
      };

  factory SemanticTag.fromJson(Map<String, dynamic> json) => SemanticTag(
        category: json['category'] as String,
        value: json['value'] as String,
        confidence: (json['confidence'] as num?)?.toDouble() ?? 1.0,
      );

  static const categoryDate = 'date';
  static const categoryLocation = 'location';
  static const categoryProject = 'project';
  static const categoryPerson = 'person';
  static const categoryDocType = 'docType';

  @override
  List<Object?> get props => [category, value, confidence];
}

/// Tracks synchronization state for a file source.
@immutable
class SyncCursor extends Equatable {
  final String cursorValue;
  final DateTime syncedAt;

  const SyncCursor({
    required this.cursorValue,
    required this.syncedAt,
  });

  Map<String, dynamic> toJson() => {
        'cursorValue': cursorValue,
        'syncedAt': syncedAt.toIso8601String(),
      };

  factory SyncCursor.fromJson(Map<String, dynamic> json) => SyncCursor(
        cursorValue: json['cursorValue'] as String,
        syncedAt: parseDateTime(json['syncedAt']),
      );

  @override
  List<Object?> get props => [cursorValue, syncedAt];
}
