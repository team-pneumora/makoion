import 'package:mobileclaw_core/core.dart';
import 'package:test/test.dart';

void main() {
  group('ActionIntent serialization', () {
    test('round-trips action intent with explanation', () {
      final intent = const ActionIntent(
        action: 'files.organize',
        args: {
          'targets': ['file-1', 'file-2'],
          'dryRun': true,
        },
        risk: RiskLevel.medium,
        requiresConfirmation: true,
        explanation: 'Plan file reorganization before applying changes.',
      );

      final decoded = ActionIntent.fromJson(intent.toJson());

      expect(decoded, intent);
      expect(decoded.domain, 'files');
      expect(decoded.verb, 'organize');
    });

    test('round-trips model output with embedded action intent', () {
      final output = ModelOutput.action(
        const ActionIntent(
          action: 'files.share',
          args: {'target': 'drive'},
          risk: RiskLevel.high,
        ),
      );

      final decoded = ModelOutput.fromJson(output.toJson());

      expect(decoded, output);
      expect(decoded.intent?.action, 'files.share');
    });
  });
}
