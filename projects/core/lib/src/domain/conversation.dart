import 'package:equatable/equatable.dart';
import 'package:meta/meta.dart';

import 'ids.dart';
import '../support/json_utils.dart';

@immutable
class Conversation extends Equatable {
  final ConversationId id;
  final String? title;
  final DateTime createdAt;
  final DateTime updatedAt;

  const Conversation({
    required this.id,
    required this.createdAt,
    required this.updatedAt,
    this.title,
  });

  Map<String, dynamic> toJson() => {
        'id': id.value,
        'title': title,
        'createdAt': createdAt.toIso8601String(),
        'updatedAt': updatedAt.toIso8601String(),
      };

  factory Conversation.fromJson(Map<String, dynamic> json) => Conversation(
        id: ConversationId.fromJson(json['id']),
        title: json['title'] as String?,
        createdAt: parseDateTime(json['createdAt']),
        updatedAt: parseDateTime(json['updatedAt']),
      );

  @override
  List<Object?> get props => [id, title, createdAt, updatedAt];
}

@immutable
class Message extends Equatable {
  final MessageId id;
  final ConversationId conversationId;
  final String role; // 'user', 'assistant', 'system'
  final String content;
  final DateTime createdAt;
  final String? modelOutputJson;

  const Message({
    required this.id,
    required this.conversationId,
    required this.role,
    required this.content,
    required this.createdAt,
    this.modelOutputJson,
  });

  Map<String, dynamic> toJson() => {
        'id': id.value,
        'conversationId': conversationId.value,
        'role': role,
        'content': content,
        'createdAt': createdAt.toIso8601String(),
        'modelOutputJson': modelOutputJson,
      };

  factory Message.fromJson(Map<String, dynamic> json) => Message(
        id: MessageId.fromJson(json['id']),
        conversationId: ConversationId.fromJson(json['conversationId']),
        role: json['role'] as String,
        content: json['content'] as String,
        createdAt: parseDateTime(json['createdAt']),
        modelOutputJson: json['modelOutputJson'] as String?,
      );

  @override
  List<Object?> get props => [
        id,
        conversationId,
        role,
        content,
        createdAt,
        modelOutputJson,
      ];
}
