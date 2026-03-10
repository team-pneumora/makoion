import 'package:equatable/equatable.dart';
import 'package:meta/meta.dart';

import 'enums.dart';
import 'ids.dart';
import '../support/json_utils.dart';

/// Structured capability record for a device.
@immutable
class DeviceCapability extends Equatable {
  final DeviceId deviceId;
  final String capability;
  final CapabilityStatus status;
  final DateTime updatedAt;
  final String? detail;

  const DeviceCapability({
    required this.deviceId,
    required this.capability,
    required this.status,
    required this.updatedAt,
    this.detail,
  });

  Map<String, dynamic> toJson() => {
        'deviceId': deviceId.value,
        'capability': capability,
        'status': status.name,
        'updatedAt': updatedAt.toIso8601String(),
        'detail': detail,
      };

  factory DeviceCapability.fromJson(Map<String, dynamic> json) =>
      DeviceCapability(
        deviceId: DeviceId.fromJson(json['deviceId']),
        capability: json['capability'] as String,
        status: enumByName(CapabilityStatus.values, json['status']),
        updatedAt: parseDateTime(json['updatedAt']),
        detail: json['detail'] as String?,
      );

  @override
  List<Object?> get props => [deviceId, capability, status, updatedAt, detail];
}
