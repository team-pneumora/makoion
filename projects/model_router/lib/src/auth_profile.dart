import 'package:meta/meta.dart';

/// Provider credential metadata.
@immutable
class ApiCredential {
  final String id;
  final String secret;
  final String? label;
  final Uri? baseUri;

  const ApiCredential({
    required this.id,
    required this.secret,
    this.label,
    this.baseUri,
  });
}

/// Authentication and cooldown state for one provider.
@immutable
class AuthProfile {
  final String providerId;
  final List<ApiCredential> credentials;
  final int rotationCursor;
  final Map<String, DateTime> cooldownUntil;

  const AuthProfile({
    required this.providerId,
    required this.credentials,
    this.rotationCursor = 0,
    this.cooldownUntil = const {},
  });

  bool hasActiveCredential(DateTime now) => activeCredentials(now).isNotEmpty;

  List<ApiCredential> activeCredentials(DateTime now) {
    if (credentials.isEmpty) {
      return const [];
    }

    final ordered = <ApiCredential>[];
    for (var offset = 0; offset < credentials.length; offset++) {
      final index = (rotationCursor + offset) % credentials.length;
      final credential = credentials[index];
      final cooldown = cooldownUntil[credential.id];
      if (cooldown == null || !cooldown.isAfter(now)) {
        ordered.add(credential);
      }
    }
    return ordered;
  }

  AuthProfile recordSuccess(String credentialId) {
    final nextCursor = _nextCursorFromCredential(credentialId);
    final nextCooldowns = Map<String, DateTime>.from(cooldownUntil)
      ..remove(credentialId);
    return AuthProfile(
      providerId: providerId,
      credentials: credentials,
      rotationCursor: nextCursor,
      cooldownUntil: Map<String, DateTime>.unmodifiable(nextCooldowns),
    );
  }

  AuthProfile recordFailure(String credentialId, DateTime until) {
    final nextCursor = _nextCursorFromCredential(credentialId);
    final nextCooldowns = Map<String, DateTime>.from(cooldownUntil)
      ..[credentialId] = until;
    return AuthProfile(
      providerId: providerId,
      credentials: credentials,
      rotationCursor: nextCursor,
      cooldownUntil: Map<String, DateTime>.unmodifiable(nextCooldowns),
    );
  }

  int _nextCursorFromCredential(String credentialId) {
    final index = credentials.indexWhere((credential) {
      return credential.id == credentialId;
    });
    if (index == -1 || credentials.isEmpty) {
      return rotationCursor;
    }
    return (index + 1) % credentials.length;
  }
}
