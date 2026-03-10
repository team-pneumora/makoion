import 'package:mobileclaw_core/core.dart';
import 'package:test/test.dart';

void main() {
  group('Device pairing protocol', () {
    test('round-trips a pairing session', () {
      final session = DevicePairingSession(
        id: PairingSessionId('pairing-1'),
        hubDeviceId: DeviceId('device-phone'),
        requestedRole: DeviceRole.companionDesktop,
        status: PairingStatus.pending,
        qrSecret: 'secret-123',
        requestedCapabilities: const [
          Capabilities.filesTransfer,
          Capabilities.appOpen,
        ],
        createdAt: DateTime.utc(2026, 3, 9, 12),
        expiresAt: DateTime.utc(2026, 3, 9, 12, 5),
      );

      final decoded = DevicePairingSession.fromJson(session.toJson());

      expect(decoded, session);
    });

    test('round-trips a trusted pairing response', () {
      final response = DevicePairingResponse(
        sessionId: PairingSessionId('pairing-1'),
        remoteDevice: Device(
          id: DeviceId('device-desktop'),
          name: 'Workstation',
          role: DeviceRole.companionDesktop,
          platform: DevicePlatform.windows,
          lastSeenAt: DateTime.utc(2026, 3, 9, 12),
          capabilities: const {
            Capabilities.filesTransfer: CapabilityStatus.supported,
          },
          isPaired: true,
        ),
        accepted: true,
        respondedAt: DateTime.utc(2026, 3, 9, 12, 1),
        grantedCapabilities: const [Capabilities.filesTransfer],
      );

      final decoded = DevicePairingResponse.fromJson(response.toJson());

      expect(decoded, response);
    });
  });
}
