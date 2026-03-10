import 'package:meta/meta.dart';

import 'auth_profile.dart';
import 'failover.dart';
import 'provider.dart';

abstract interface class ModelRouter {
  Future<ModelResponse> route(ModelRequest request);
}

/// Details about a failed provider attempt.
@immutable
class FailedAttempt {
  final String providerId;
  final String model;
  final String credentialId;
  final Object error;

  const FailedAttempt({
    required this.providerId,
    required this.model,
    required this.credentialId,
    required this.error,
  });
}

/// Raised when all routing attempts fail.
class ModelRoutingException implements Exception {
  final String message;
  final List<FailedAttempt> attempts;

  ModelRoutingException({
    required this.message,
    this.attempts = const [],
  });

  @override
  String toString() => 'ModelRoutingException: $message';
}

/// In-memory router with provider failover and credential cooldowns.
class RoutedModelRouter implements ModelRouter {
  final Map<String, ModelProvider> _providers;
  final Map<String, AuthProfile> _authProfiles;
  final FailoverStrategy _strategy;
  final Duration cooldownAfterFailure;
  final DateTime Function() _clock;

  RoutedModelRouter({
    required List<ModelProvider> providers,
    required List<AuthProfile> authProfiles,
    FailoverStrategy strategy = const DefaultFailoverStrategy(),
    this.cooldownAfterFailure = const Duration(minutes: 1),
    DateTime Function()? clock,
  })  : _providers = {
          for (final provider in providers) provider.id: provider,
        },
        _authProfiles = {
          for (final profile in authProfiles) profile.providerId: profile,
        },
        _strategy = strategy,
        _clock = clock ?? DateTime.now;

  @override
  Future<ModelResponse> route(ModelRequest request) async {
    final attempts = _strategy.plan(
      request,
      _providers.values.toList(growable: false),
      Map<String, AuthProfile>.unmodifiable(_authProfiles),
      _clock(),
    );

    if (attempts.isEmpty) {
      throw ModelRoutingException(
        message: 'No provider attempts available for the given request.',
      );
    }

    final failures = <FailedAttempt>[];
    for (final attempt in attempts) {
      final provider = _providers[attempt.providerId];
      if (provider == null) {
        continue;
      }
      final activeCredentials =
          _authProfiles[attempt.providerId]?.activeCredentials(_clock()) ??
              const [];
      final credentialStillActive = activeCredentials.any((credential) {
        return credential.id == attempt.credential.id;
      });
      if (!credentialStillActive) {
        continue;
      }

      try {
        final response = await provider.generate(
          request.copyWith(preferredModel: attempt.model),
          attempt.credential,
        );
        final profile = _authProfiles[attempt.providerId];
        if (profile != null) {
          _authProfiles[attempt.providerId] =
              profile.recordSuccess(attempt.credential.id);
        }
        return response;
      } catch (error) {
        failures.add(
          FailedAttempt(
            providerId: attempt.providerId,
            model: attempt.model,
            credentialId: attempt.credential.id,
            error: error,
          ),
        );
        final profile = _authProfiles[attempt.providerId];
        if (profile != null) {
          _authProfiles[attempt.providerId] = profile.recordFailure(
            attempt.credential.id,
            _clock().add(cooldownAfterFailure),
          );
        }
      }
    }

    throw ModelRoutingException(
      message: 'All provider attempts failed.',
      attempts: failures,
    );
  }

  /// Snapshot the current auth state for inspection or persistence.
  Map<String, AuthProfile> snapshotAuthProfiles() {
    return Map<String, AuthProfile>.unmodifiable(_authProfiles);
  }
}
