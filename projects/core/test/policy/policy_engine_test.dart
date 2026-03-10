import 'package:mobileclaw_core/core.dart';
import 'package:test/test.dart';

void main() {
  group('DefaultPolicyEngine', () {
    test('allows low-risk file search when capability is present', () {
      final engine = DefaultPolicyEngine();
      final decision = engine.evaluate(
        const ActionIntent(
          action: 'files.search',
          args: {'query': 'contract'},
          risk: RiskLevel.low,
        ),
        _device(capabilities: const {
          Capabilities.filesList: CapabilityStatus.supported,
        }),
      );

      expect(decision.allowed, isTrue);
      expect(decision.requiresApproval, isFalse);
      expect(decision.assessedRisk, RiskLevel.low);
      expect(decision.requiredCapabilities, [Capabilities.filesList]);
    });

    test('denies execution when a required capability is missing', () {
      final engine = DefaultPolicyEngine();
      final decision = engine.evaluate(
        const ActionIntent(
          action: 'files.preview',
          args: {'id': 'file-1'},
          risk: RiskLevel.low,
        ),
        _device(capabilities: const {}),
      );

      expect(decision.allowed, isFalse);
      expect(decision.reason, contains(Capabilities.filesReadMetadata));
    });

    test('upgrades high-risk actions and requires approval', () {
      final engine = DefaultPolicyEngine();
      final decision = engine.evaluate(
        const ActionIntent(
          action: 'files.share',
          args: {'id': 'file-1'},
          risk: RiskLevel.low,
        ),
        _device(capabilities: const {
          Capabilities.filesTransfer: CapabilityStatus.supported,
        }),
      );

      expect(decision.allowed, isTrue);
      expect(decision.assessedRisk, RiskLevel.high);
      expect(decision.requiresApproval, isTrue);
    });

    test('uses the capability registry as a fallback source', () {
      final registry = InMemoryCapabilityRegistry();
      final device = _device(capabilities: const {});
      registry.register(device.id, {
        Capabilities.calendar: CapabilityStatus.supported,
      });
      final engine = DefaultPolicyEngine(capabilityRegistry: registry);

      final decision = engine.evaluate(
        const ActionIntent(
          action: 'calendar.create',
          args: {'title': 'Meeting'},
          risk: RiskLevel.low,
        ),
        device,
      );

      expect(decision.allowed, isTrue);
      expect(decision.assessedRisk, RiskLevel.medium);
    });

    test('denies unsupported actions by default', () {
      final engine = DefaultPolicyEngine();
      final decision = engine.evaluate(
        const ActionIntent(
          action: 'files.delete_everything',
          args: {},
          risk: RiskLevel.high,
        ),
        _device(capabilities: const {
          Capabilities.filesTransfer: CapabilityStatus.supported,
        }),
      );

      expect(decision.allowed, isFalse);
      expect(decision.reason, contains('Unsupported action'));
    });

    test('honors model confirmation requests for medium-risk actions', () {
      final engine = DefaultPolicyEngine();
      final decision = engine.evaluate(
        const ActionIntent(
          action: 'files.move',
          args: {
            'ids': ['file-1']
          },
          risk: RiskLevel.medium,
          requiresConfirmation: true,
        ),
        _device(capabilities: const {
          Capabilities.filesTransfer: CapabilityStatus.supported,
        }),
      );

      expect(decision.allowed, isTrue);
      expect(decision.requiresApproval, isTrue);
      expect(decision.reason, contains('confirmation'));
    });
  });
}

Device _device({
  required Map<String, CapabilityStatus> capabilities,
}) {
  return Device(
    id: DeviceId('device-1'),
    name: 'Phone Hub',
    role: DeviceRole.phoneHub,
    platform: DevicePlatform.android,
    lastSeenAt: DateTime.utc(2026, 3, 9),
    capabilities: capabilities,
  );
}
