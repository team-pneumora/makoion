import 'package:meta/meta.dart';
import 'package:mobileclaw_core/core.dart';

import 'auth_profile.dart';

/// A provider capable of generating model output for a request.
abstract interface class ModelProvider {
  String get id;

  String get defaultModel;

  List<String> get supportedModels;

  bool supportsModel(String model) => supportedModels.contains(model);

  Future<ModelResponse> generate(
    ModelRequest request,
    ApiCredential credential,
  );
}

/// Input to the model router.
@immutable
class ModelRequest {
  final String prompt;
  final String? systemPrompt;
  final String? preferredProviderId;
  final String? preferredModel;
  final List<String> fallbackModels;
  final Set<String>? allowedProviders;
  final Map<String, Object?> metadata;

  const ModelRequest({
    required this.prompt,
    this.systemPrompt,
    this.preferredProviderId,
    this.preferredModel,
    this.fallbackModels = const [],
    this.allowedProviders,
    this.metadata = const {},
  });

  ModelRequest copyWith({
    String? prompt,
    String? systemPrompt,
    String? preferredProviderId,
    String? preferredModel,
    List<String>? fallbackModels,
    Set<String>? allowedProviders,
    Map<String, Object?>? metadata,
  }) {
    return ModelRequest(
      prompt: prompt ?? this.prompt,
      systemPrompt: systemPrompt ?? this.systemPrompt,
      preferredProviderId: preferredProviderId ?? this.preferredProviderId,
      preferredModel: preferredModel ?? this.preferredModel,
      fallbackModels: fallbackModels ?? this.fallbackModels,
      allowedProviders: allowedProviders ?? this.allowedProviders,
      metadata: metadata ?? this.metadata,
    );
  }
}

/// Normalized response from a provider.
@immutable
class ModelResponse {
  final String providerId;
  final String model;
  final String credentialId;
  final ModelOutput output;
  final int? promptTokens;
  final int? completionTokens;
  final Duration? latency;
  final Map<String, Object?> metadata;

  const ModelResponse({
    required this.providerId,
    required this.model,
    required this.credentialId,
    required this.output,
    this.promptTokens,
    this.completionTokens,
    this.latency,
    this.metadata = const {},
  });
}
