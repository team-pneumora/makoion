import 'package:equatable/equatable.dart';
import 'package:meta/meta.dart';

import 'ids.dart';
import '../support/json_utils.dart';

/// Tracks synchronization progress between file sources and the local DB.
///
/// Each combination of (sourceType, sourcePath, deviceId) has its own cursor
/// so we know where we left off when resuming an incremental sync.
@immutable
class SyncState extends Equatable {
  final String sourceType;
  final String sourcePath;
  final DeviceId deviceId;
  final String cursorValue;
  final DateTime syncedAt;

  const SyncState({
    required this.sourceType,
    required this.sourcePath,
    required this.deviceId,
    required this.cursorValue,
    required this.syncedAt,
  });

  Map<String, dynamic> toJson() => {
        'sourceType': sourceType,
        'sourcePath': sourcePath,
        'deviceId': deviceId.value,
        'cursorValue': cursorValue,
        'syncedAt': syncedAt.toIso8601String(),
      };

  factory SyncState.fromJson(Map<String, dynamic> json) => SyncState(
        sourceType: json['sourceType'] as String,
        sourcePath: json['sourcePath'] as String,
        deviceId: DeviceId.fromJson(json['deviceId']),
        cursorValue: json['cursorValue'] as String,
        syncedAt: parseDateTime(json['syncedAt']),
      );

  SyncState copyWith({
    String? cursorValue,
    DateTime? syncedAt,
  }) {
    return SyncState(
      sourceType: sourceType,
      sourcePath: sourcePath,
      deviceId: deviceId,
      cursorValue: cursorValue ?? this.cursorValue,
      syncedAt: syncedAt ?? this.syncedAt,
    );
  }

  @override
  List<Object?> get props => [
        sourceType,
        sourcePath,
        deviceId,
        cursorValue,
        syncedAt,
      ];
}
