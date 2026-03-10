import 'package:mobileclaw_core/core.dart';
import 'package:test/test.dart';

void main() {
  group('InMemoryCapabilityRegistry', () {
    test('returns registered capability states', () {
      final registry = InMemoryCapabilityRegistry();
      final deviceId = DeviceId('device-1');

      registry.register(deviceId, {
        Capabilities.filesList: CapabilityStatus.supported,
        Capabilities.camera: CapabilityStatus.denied,
      });

      expect(
        registry.getStatus(deviceId, Capabilities.filesList),
        CapabilityStatus.supported,
      );
      expect(
        registry.getStatus(deviceId, Capabilities.camera),
        CapabilityStatus.denied,
      );
      expect(
        registry.getStatus(deviceId, Capabilities.voice),
        CapabilityStatus.unavailable,
      );
    });

    test('finds devices that support a capability', () {
      final desktop = Device(
        id: DeviceId('desktop-1'),
        name: 'Desktop',
        role: DeviceRole.companionDesktop,
        platform: DevicePlatform.windows,
        lastSeenAt: DateTime.utc(2026, 3, 9),
        capabilities: const {
          Capabilities.filesTransfer: CapabilityStatus.supported,
        },
      );
      final tablet = Device(
        id: DeviceId('tablet-1'),
        name: 'Tablet',
        role: DeviceRole.companionTablet,
        platform: DevicePlatform.android,
        lastSeenAt: DateTime.utc(2026, 3, 9),
        capabilities: const {
          Capabilities.filesTransfer: CapabilityStatus.denied,
        },
      );
      final registry = InMemoryCapabilityRegistry(devices: [desktop, tablet]);

      final devices =
          registry.devicesWithCapability(Capabilities.filesTransfer);

      expect(devices, hasLength(1));
      expect(devices.single.name, 'Desktop');
    });

    test('checks whether a device has all required capabilities', () {
      final registry = InMemoryCapabilityRegistry();
      final deviceId = DeviceId('device-2');
      registry.register(deviceId, {
        Capabilities.filesList: CapabilityStatus.supported,
        Capabilities.filesReadMetadata: CapabilityStatus.limited,
      });

      expect(
        registry.hasAll(
          deviceId,
          [Capabilities.filesList, Capabilities.filesReadMetadata],
        ),
        isTrue,
      );
      expect(
        registry.hasAll(
          deviceId,
          [Capabilities.filesList, Capabilities.filesTransfer],
        ),
        isFalse,
      );
    });
  });
}
