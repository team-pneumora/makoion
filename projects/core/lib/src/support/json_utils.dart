T enumByName<T extends Enum>(Iterable<T> values, Object? name) {
  final value = name as String;
  return values.firstWhere((candidate) => candidate.name == value);
}

DateTime parseDateTime(Object? value) => DateTime.parse(value as String);

DateTime? parseNullableDateTime(Object? value) {
  if (value == null) {
    return null;
  }
  return DateTime.parse(value as String);
}

Map<String, dynamic> castJsonMap(Object? value) {
  if (value == null) {
    return <String, dynamic>{};
  }
  return Map<String, dynamic>.from(value as Map);
}

Map<String, String> castStringMap(Object? value) {
  if (value == null) {
    return <String, String>{};
  }
  return Map<String, String>.from(value as Map);
}

List<dynamic> castJsonList(Object? value) {
  if (value == null) {
    return <dynamic>[];
  }
  return List<dynamic>.from(value as List);
}
