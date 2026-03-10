import '../domain/device.dart';
import '../domain/enums.dart';
import '../domain/ids.dart';

/// Registry of device capabilities.
///
/// Tracks what each device can do. Used by [PolicyEngine] to check
/// if a device supports the capabilities required by an [ActionIntent].
abstract interface class CapabilityRegistry {
  /// Register or update capabilities for a device.
  void register(DeviceId deviceId, Map<String, CapabilityStatus> capabilities);

  /// Get the status of a specific capability on a device.
  CapabilityStatus getStatus(DeviceId deviceId, String capability);

  /// Find all devices that support a given capability.
  List<Device> devicesWithCapability(String capability);

  /// Get all capabilities for a device.
  Map<String, CapabilityStatus> getAll(DeviceId deviceId);

  /// Check if a device supports all the given capabilities.
  bool hasAll(DeviceId deviceId, List<String> capabilities);
}

/// Simple in-memory registry for tests and local orchestration flows.
class InMemoryCapabilityRegistry implements CapabilityRegistry {
  final Map<DeviceId, Device> _devices;

  InMemoryCapabilityRegistry({Iterable<Device> devices = const []})
      : _devices = {
          for (final device in devices) device.id: device,
        };

  /// Add or replace a device snapshot.
  void upsertDevice(Device device) {
    _devices[device.id] = device;
  }

  @override
  void register(DeviceId deviceId, Map<String, CapabilityStatus> capabilities) {
    final existing = _devices[deviceId];
    if (existing != null) {
      _devices[deviceId] = existing.copyWith(
        capabilities: Map<String, CapabilityStatus>.unmodifiable(capabilities),
      );
      return;
    }

    _devices[deviceId] = Device(
      id: deviceId,
      name: 'device-${deviceId.value}',
      role: DeviceRole.companionDesktop,
      platform: DevicePlatform.windows,
      lastSeenAt: DateTime.fromMillisecondsSinceEpoch(0),
      capabilities: Map<String, CapabilityStatus>.unmodifiable(capabilities),
    );
  }

  @override
  CapabilityStatus getStatus(DeviceId deviceId, String capability) {
    final device = _devices[deviceId];
    if (device == null) {
      return CapabilityStatus.unavailable;
    }
    return device.capabilities[capability] ?? CapabilityStatus.unavailable;
  }

  @override
  List<Device> devicesWithCapability(String capability) {
    return _devices.values
        .where((device) => getStatus(device.id, capability).isUsable)
        .toList(growable: false);
  }

  @override
  Map<String, CapabilityStatus> getAll(DeviceId deviceId) {
    final device = _devices[deviceId];
    if (device == null) {
      return const {};
    }
    return Map<String, CapabilityStatus>.unmodifiable(device.capabilities);
  }

  @override
  bool hasAll(DeviceId deviceId, List<String> capabilities) {
    return capabilities.every((capability) {
      return getStatus(deviceId, capability).isUsable;
    });
  }
}
