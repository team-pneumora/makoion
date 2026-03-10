import 'package:meta/meta.dart';

import 'auth_profile.dart';
import 'provider.dart';

/// One concrete provider/model/credential attempt planned by the router.
@immutable
class ProviderAttemptPlan {
  final String providerId;
  final String model;
  final ApiCredential credential;

  const ProviderAttemptPlan({
    required this.providerId,
    required this.model,
    required this.credential,
  });
}

/// Strategy for ordering routing attempts across providers and credentials.
abstract interface class FailoverStrategy {
  List<ProviderAttemptPlan> plan(
    ModelRequest request,
    List<ModelProvider> providers,
    Map<String, AuthProfile> authProfiles,
    DateTime now,
  );
}

/// Default provider ordering:
/// - honor allowedProviders when present
/// - try preferred provider first when possible
/// - prefer the requested model, then fallback models
/// - within a provider, try active credentials in profile rotation order
class DefaultFailoverStrategy implements FailoverStrategy {
  const DefaultFailoverStrategy();

  @override
  List<ProviderAttemptPlan> plan(
    ModelRequest request,
    List<ModelProvider> providers,
    Map<String, AuthProfile> authProfiles,
    DateTime now,
  ) {
    final allowedProviders = request.allowedProviders;
    final candidateProviders = providers.where((provider) {
      if (allowedProviders == null) {
        return true;
      }
      return allowedProviders.contains(provider.id);
    }).toList(growable: false);

    final orderedProviders = _orderProviders(
      candidateProviders,
      request.preferredProviderId,
    );
    final models = _modelsToTry(request, orderedProviders);
    final attempts = <ProviderAttemptPlan>[];

    if (request.preferredModel != null || request.fallbackModels.isNotEmpty) {
      for (final model in models) {
        for (final provider in orderedProviders) {
          final profile = authProfiles[provider.id];
          if (profile == null || !provider.supportsModel(model)) {
            continue;
          }
          for (final credential in profile.activeCredentials(now)) {
            attempts.add(
              ProviderAttemptPlan(
                providerId: provider.id,
                model: model,
                credential: credential,
              ),
            );
          }
        }
      }
      return attempts;
    }

    for (final provider in orderedProviders) {
      final profile = authProfiles[provider.id];
      if (profile == null) {
        continue;
      }
      for (final credential in profile.activeCredentials(now)) {
        attempts.add(
          ProviderAttemptPlan(
            providerId: provider.id,
            model: provider.defaultModel,
            credential: credential,
          ),
        );
      }
    }
    return attempts;
  }

  List<ModelProvider> _orderProviders(
    List<ModelProvider> providers,
    String? preferredProviderId,
  ) {
    if (preferredProviderId == null) {
      return providers;
    }

    final preferred = <ModelProvider>[];
    final others = <ModelProvider>[];
    for (final provider in providers) {
      if (provider.id == preferredProviderId) {
        preferred.add(provider);
      } else {
        others.add(provider);
      }
    }
    return [...preferred, ...others];
  }

  List<String> _modelsToTry(
    ModelRequest request,
    List<ModelProvider> providers,
  ) {
    final models = <String>[];
    if (request.preferredModel != null) {
      models.add(request.preferredModel!);
    }
    for (final model in request.fallbackModels) {
      if (!models.contains(model)) {
        models.add(model);
      }
    }
    if (models.isNotEmpty) {
      return models;
    }

    final defaults = <String>[];
    for (final provider in providers) {
      if (!defaults.contains(provider.defaultModel)) {
        defaults.add(provider.defaultModel);
      }
    }
    return defaults;
  }
}
