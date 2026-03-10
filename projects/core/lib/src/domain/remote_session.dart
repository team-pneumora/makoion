import 'package:equatable/equatable.dart';
import 'package:meta/meta.dart';

import 'enums.dart';
import 'ids.dart';
import '../support/json_utils.dart';

/// Active or pending session between the phone hub and a companion device.
@immutable
class RemoteSession extends Equatable {
  final RemoteSessionId id;
  final DeviceId localDeviceId;
  final DeviceId remoteDeviceId;
  final RemoteSessionStatus status;
  final ConnectivityMode connectivityMode;
  final List<String> grantedCapabilities;
  final DateTime createdAt;
  final DateTime updatedAt;
  final String? lastError;

  const RemoteSession({
    required this.id,
    required this.localDeviceId,
    required this.remoteDeviceId,
    required this.status,
    required this.connectivityMode,
    required this.createdAt,
    required this.updatedAt,
    this.grantedCapabilities = const [],
    this.lastError,
  });

  Map<String, dynamic> toJson() => {
        'id': id.value,
        'localDeviceId': localDeviceId.value,
        'remoteDeviceId': remoteDeviceId.value,
        'status': status.name,
        'connectivityMode': connectivityMode.name,
        'grantedCapabilities': grantedCapabilities,
        'createdAt': createdAt.toIso8601String(),
        'updatedAt': updatedAt.toIso8601String(),
        'lastError': lastError,
      };

  factory RemoteSession.fromJson(Map<String, dynamic> json) => RemoteSession(
        id: RemoteSessionId.fromJson(json['id']),
        localDeviceId: DeviceId.fromJson(json['localDeviceId']),
        remoteDeviceId: DeviceId.fromJson(json['remoteDeviceId']),
        status: enumByName(RemoteSessionStatus.values, json['status']),
        connectivityMode: enumByName(
          ConnectivityMode.values,
          json['connectivityMode'],
        ),
        grantedCapabilities:
            castJsonList(json['grantedCapabilities']).cast<String>(),
        createdAt: parseDateTime(json['createdAt']),
        updatedAt: parseDateTime(json['updatedAt']),
        lastError: json['lastError'] as String?,
      );

  @override
  List<Object?> get props => [
        id,
        localDeviceId,
        remoteDeviceId,
        status,
        connectivityMode,
        grantedCapabilities,
        createdAt,
        updatedAt,
        lastError,
      ];
}
