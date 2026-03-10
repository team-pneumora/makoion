import 'package:mobileclaw_core/core.dart';
import 'package:mobileclaw_model_router/model_router.dart';
import 'package:test/test.dart';

void main() {
  group('AuthProfile', () {
    test('returns active credentials in rotation order', () {
      final now = DateTime.utc(2026, 3, 9, 12);
      final profile = AuthProfile(
        providerId: 'openai',
        credentials: const [
          ApiCredential(id: 'key-1', secret: 'a'),
          ApiCredential(id: 'key-2', secret: 'b'),
          ApiCredential(id: 'key-3', secret: 'c'),
        ],
        rotationCursor: 1,
        cooldownUntil: {
          'key-2': DateTime.utc(2026, 3, 9, 12, 5),
        },
      );

      final active = profile.activeCredentials(now);

      expect(active.map((credential) => credential.id), ['key-3', 'key-1']);
    });

    test('records failures as cooldowns and rotates after success', () {
      final base = AuthProfile(
        providerId: 'anthropic',
        credentials: const [
          ApiCredential(id: 'key-1', secret: 'a'),
          ApiCredential(id: 'key-2', secret: 'b'),
        ],
      );

      final failed = base.recordFailure(
        'key-1',
        DateTime.utc(2026, 3, 9, 12, 30),
      );
      final succeeded = failed.recordSuccess('key-2');

      expect(failed.cooldownUntil['key-1'], isNotNull);
      expect(succeeded.cooldownUntil.containsKey('key-2'), isFalse);
      expect(succeeded.rotationCursor, 0);
    });
  });

  group('RoutedModelRouter', () {
    test('routes to the preferred provider when available', () async {
      final router = RoutedModelRouter(
        providers: [
          FakeProvider(
            id: 'openai',
            defaultModel: 'gpt-4.1-mini',
            supportedModels: const ['gpt-4.1-mini'],
            handler: (request, credential) async {
              return ModelResponse(
                providerId: 'openai',
                model: request.preferredModel ?? 'gpt-4.1-mini',
                credentialId: credential.id,
                output: const ModelOutput.answer('ok'),
              );
            },
          ),
          FakeProvider(
            id: 'anthropic',
            defaultModel: 'claude-3-5-haiku',
            supportedModels: const ['claude-3-5-haiku'],
            handler: (request, credential) async {
              throw StateError('should not be called');
            },
          ),
        ],
        authProfiles: [
          AuthProfile(
            providerId: 'openai',
            credentials: const [ApiCredential(id: 'key-1', secret: 'a')],
          ),
          AuthProfile(
            providerId: 'anthropic',
            credentials: const [ApiCredential(id: 'key-2', secret: 'b')],
          ),
        ],
        clock: () => DateTime.utc(2026, 3, 9, 12),
      );

      final response = await router.route(
        const ModelRequest(
          prompt: 'hello',
          preferredProviderId: 'openai',
          preferredModel: 'gpt-4.1-mini',
        ),
      );

      expect(response.providerId, 'openai');
      expect(response.model, 'gpt-4.1-mini');
    });

    test('fails over to the next provider after an error', () async {
      final failing = FakeProvider(
        id: 'openai',
        defaultModel: 'gpt-4.1-mini',
        supportedModels: const ['gpt-4.1-mini'],
        handler: (request, credential) async {
          throw StateError('rate limit');
        },
      );
      final backup = FakeProvider(
        id: 'anthropic',
        defaultModel: 'gpt-4.1-mini',
        supportedModels: const ['gpt-4.1-mini'],
        handler: (request, credential) async {
          return ModelResponse(
            providerId: 'anthropic',
            model: request.preferredModel ?? 'gpt-4.1-mini',
            credentialId: credential.id,
            output: const ModelOutput.answer('fallback'),
          );
        },
      );
      final router = RoutedModelRouter(
        providers: [failing, backup],
        authProfiles: [
          AuthProfile(
            providerId: 'openai',
            credentials: const [ApiCredential(id: 'key-1', secret: 'a')],
          ),
          AuthProfile(
            providerId: 'anthropic',
            credentials: const [ApiCredential(id: 'key-2', secret: 'b')],
          ),
        ],
        cooldownAfterFailure: const Duration(minutes: 10),
        clock: () => DateTime.utc(2026, 3, 9, 12),
      );

      final response = await router.route(
        const ModelRequest(
          prompt: 'hello',
          preferredModel: 'gpt-4.1-mini',
        ),
      );

      expect(response.providerId, 'anthropic');
      final authState = router.snapshotAuthProfiles();
      expect(authState['openai']!.cooldownUntil['key-1'], isNotNull);
    });

    test('tries the next credential on the same provider after failure',
        () async {
      final attempts = <String>[];
      final router = RoutedModelRouter(
        providers: [
          FakeProvider(
            id: 'openai',
            defaultModel: 'gpt-4.1-mini',
            supportedModels: const ['gpt-4.1-mini'],
            handler: (request, credential) async {
              attempts.add(credential.id);
              if (credential.id == 'key-1') {
                throw StateError('quota');
              }
              return ModelResponse(
                providerId: 'openai',
                model: request.preferredModel ?? 'gpt-4.1-mini',
                credentialId: credential.id,
                output: const ModelOutput.answer('ok'),
              );
            },
          ),
        ],
        authProfiles: [
          AuthProfile(
            providerId: 'openai',
            credentials: const [
              ApiCredential(id: 'key-1', secret: 'a'),
              ApiCredential(id: 'key-2', secret: 'b'),
            ],
          ),
        ],
        clock: () => DateTime.utc(2026, 3, 9, 12),
      );

      final response = await router.route(
        const ModelRequest(
          prompt: 'hello',
          preferredModel: 'gpt-4.1-mini',
        ),
      );

      expect(response.credentialId, 'key-2');
      expect(attempts, ['key-1', 'key-2']);
    });

    test('throws a routing exception when no attempt succeeds', () async {
      final router = RoutedModelRouter(
        providers: [
          FakeProvider(
            id: 'openai',
            defaultModel: 'gpt-4.1-mini',
            supportedModels: const ['gpt-4.1-mini'],
            handler: (request, credential) async {
              throw StateError('down');
            },
          ),
        ],
        authProfiles: [
          AuthProfile(
            providerId: 'openai',
            credentials: const [ApiCredential(id: 'key-1', secret: 'a')],
          ),
        ],
        clock: () => DateTime.utc(2026, 3, 9, 12),
      );

      expect(
        () => router.route(
          const ModelRequest(
            prompt: 'hello',
            preferredModel: 'gpt-4.1-mini',
          ),
        ),
        throwsA(
          isA<ModelRoutingException>().having(
            (error) => error.attempts.length,
            'attempt count',
            1,
          ),
        ),
      );
    });
  });
}

class FakeProvider implements ModelProvider {
  @override
  final String id;

  @override
  final String defaultModel;

  @override
  final List<String> supportedModels;

  final Future<ModelResponse> Function(
    ModelRequest request,
    ApiCredential credential,
  ) handler;

  FakeProvider({
    required this.id,
    required this.defaultModel,
    required this.supportedModels,
    required this.handler,
  });

  @override
  bool supportsModel(String model) => supportedModels.contains(model);

  @override
  Future<ModelResponse> generate(
    ModelRequest request,
    ApiCredential credential,
  ) {
    return handler(request, credential);
  }
}
