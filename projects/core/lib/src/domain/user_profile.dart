import 'package:equatable/equatable.dart';
import 'package:meta/meta.dart';

import 'ids.dart';
import '../support/json_utils.dart';

@immutable
class UserProfile extends Equatable {
  final UserId id;
  final String name;
  final DateTime createdAt;

  const UserProfile({
    required this.id,
    required this.name,
    required this.createdAt,
  });

  Map<String, dynamic> toJson() => {
        'id': id.value,
        'name': name,
        'createdAt': createdAt.toIso8601String(),
      };

  factory UserProfile.fromJson(Map<String, dynamic> json) => UserProfile(
        id: UserId.fromJson(json['id']),
        name: json['name'] as String,
        createdAt: parseDateTime(json['createdAt']),
      );

  @override
  List<Object?> get props => [id, name, createdAt];
}
