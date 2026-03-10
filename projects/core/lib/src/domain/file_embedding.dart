import 'package:equatable/equatable.dart';
import 'package:meta/meta.dart';

import 'ids.dart';
import '../support/json_utils.dart';

/// Vector embedding associated with a file node.
@immutable
class FileEmbedding extends Equatable {
  final FileNodeId fileId;
  final String model;
  final List<double> vector;
  final DateTime createdAt;

  const FileEmbedding({
    required this.fileId,
    required this.model,
    required this.vector,
    required this.createdAt,
  });

  Map<String, dynamic> toJson() => {
        'fileId': fileId.value,
        'model': model,
        'vector': vector,
        'createdAt': createdAt.toIso8601String(),
      };

  factory FileEmbedding.fromJson(Map<String, dynamic> json) => FileEmbedding(
        fileId: FileNodeId.fromJson(json['fileId']),
        model: json['model'] as String,
        vector: castJsonList(json['vector'])
            .map((value) => (value as num).toDouble())
            .toList(growable: false),
        createdAt: parseDateTime(json['createdAt']),
      );

  @override
  List<Object?> get props => [fileId, model, vector, createdAt];
}
