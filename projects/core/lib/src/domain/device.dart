import 'package:equatable/equatable.dart';
import 'package:meta/meta.dart';

import 'enums.dart';
import 'ids.dart';
import '../support/json_utils.dart';

/// A device in the MobileClaw ecosystem.
///
/// Devices are either the Phone Hub (source of truth) or
/// Companion Nodes (PC, Mac, iPad, tablet).
@immutable
class Device extends Equatable {
  final DeviceId id;
  final String name;
  final DeviceRole role;
  final DevicePlatform platform;
  final Map<String, CapabilityStatus> capabilities;
  final ConnectivityMode connectivityMode;
  final DateTime lastSeenAt;
  final bool isPaired;

  const Device({
    required this.id,
    required this.name,
    required this.role,
    required this.platform,
    required this.lastSeenAt,
    this.capabilities = const {},
    this.connectivityMode = ConnectivityMode.offlineQueue,
    this.isPaired = false,
  });

  Map<String, dynamic> toJson() => {
        'id': id.value,
        'name': name,
        'role': role.name,
        'platform': platform.name,
        'capabilities': capabilities.map(
          (key, value) => MapEntry(key, value.name),
        ),
        'connectivityMode': connectivityMode.name,
        'lastSeenAt': lastSeenAt.toIso8601String(),
        'isPaired': isPaired,
      };

  factory Device.fromJson(Map<String, dynamic> json) {
    final capabilityJson = castJsonMap(json['capabilities']);
    return Device(
      id: DeviceId.fromJson(json['id']),
      name: json['name'] as String,
      role: enumByName(DeviceRole.values, json['role']),
      platform: enumByName(DevicePlatform.values, json['platform']),
      capabilities: capabilityJson.map(
        (key, value) => MapEntry(
          key,
          enumByName(CapabilityStatus.values, value),
        ),
      ),
      connectivityMode: enumByName(
        ConnectivityMode.values,
        json['connectivityMode'],
      ),
      lastSeenAt: parseDateTime(json['lastSeenAt']),
      isPaired: json['isPaired'] as bool? ?? false,
    );
  }

  bool get isPhoneHub => role == DeviceRole.phoneHub;
  bool get isCompanion =>
      role == DeviceRole.companionDesktop || role == DeviceRole.companionTablet;
  bool get isOnline => connectivityMode != ConnectivityMode.offlineQueue;

  Device copyWith({
    String? name,
    Map<String, CapabilityStatus>? capabilities,
    ConnectivityMode? connectivityMode,
    DateTime? lastSeenAt,
    bool? isPaired,
  }) {
    return Device(
      id: id,
      name: name ?? this.name,
      role: role,
      platform: platform,
      capabilities: capabilities ?? this.capabilities,
      connectivityMode: connectivityMode ?? this.connectivityMode,
      lastSeenAt: lastSeenAt ?? this.lastSeenAt,
      isPaired: isPaired ?? this.isPaired,
    );
  }

  @override
  List<Object?> get props => [
        id,
        name,
        role,
        platform,
        capabilities,
        connectivityMode,
        lastSeenAt,
        isPaired,
      ];
}
