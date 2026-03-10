import 'package:equatable/equatable.dart';
import 'package:meta/meta.dart';

import 'ids.dart';
import '../support/json_utils.dart';

/// Snapshot metadata for one revision of a file.
@immutable
class FileVersion extends Equatable {
  final FileVersionId id;
  final FileNodeId fileId;
  final String revision;
  final int sizeBytes;
  final String? checksum;
  final String? sourcePath;
  final DateTime createdAt;

  const FileVersion({
    required this.id,
    required this.fileId,
    required this.revision,
    required this.sizeBytes,
    required this.createdAt,
    this.checksum,
    this.sourcePath,
  });

  Map<String, dynamic> toJson() => {
        'id': id.value,
        'fileId': fileId.value,
        'revision': revision,
        'sizeBytes': sizeBytes,
        'checksum': checksum,
        'sourcePath': sourcePath,
        'createdAt': createdAt.toIso8601String(),
      };

  factory FileVersion.fromJson(Map<String, dynamic> json) => FileVersion(
        id: FileVersionId.fromJson(json['id']),
        fileId: FileNodeId.fromJson(json['fileId']),
        revision: json['revision'] as String,
        sizeBytes: json['sizeBytes'] as int,
        checksum: json['checksum'] as String?,
        sourcePath: json['sourcePath'] as String?,
        createdAt: parseDateTime(json['createdAt']),
      );

  @override
  List<Object?> get props => [
        id,
        fileId,
        revision,
        sizeBytes,
        checksum,
        sourcePath,
        createdAt,
      ];
}
