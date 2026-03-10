import 'package:equatable/equatable.dart';
import 'package:meta/meta.dart';

import 'ids.dart';
import '../support/json_utils.dart';

@immutable
class MemoryItem extends Equatable {
  final MemoryItemId id;
  final String content;
  final String? category;
  final DateTime createdAt;
  final DateTime updatedAt;

  const MemoryItem({
    required this.id,
    required this.content,
    required this.createdAt,
    required this.updatedAt,
    this.category,
  });

  Map<String, dynamic> toJson() => {
        'id': id.value,
        'content': content,
        'category': category,
        'createdAt': createdAt.toIso8601String(),
        'updatedAt': updatedAt.toIso8601String(),
      };

  factory MemoryItem.fromJson(Map<String, dynamic> json) => MemoryItem(
        id: MemoryItemId.fromJson(json['id']),
        content: json['content'] as String,
        category: json['category'] as String?,
        createdAt: parseDateTime(json['createdAt']),
        updatedAt: parseDateTime(json['updatedAt']),
      );

  @override
  List<Object?> get props => [id, content, category, createdAt, updatedAt];
}
