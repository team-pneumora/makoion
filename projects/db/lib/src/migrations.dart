import 'package:meta/meta.dart';
import 'package:sqlite3/sqlite3.dart';

import 'schema.dart';

/// A single migration step between two schema versions.
@immutable
class MigrationStep {
  final int fromVersion;
  final int toVersion;
  final List<String> statements;

  const MigrationStep({
    required this.fromVersion,
    required this.toVersion,
    required this.statements,
  });

  void apply(Database database) {
    for (final statement in statements) {
      database.execute(statement);
    }
    database.execute('PRAGMA user_version = $toVersion');
  }
}

/// Applies schema migrations using SQLite's `user_version` pragma.
final class MobileClawMigrations {
  const MobileClawMigrations._();

  static final List<MigrationStep> steps = [
    MigrationStep(
      fromVersion: 0,
      toVersion: 1,
      statements: MobileClawSchema.statementsForVersion(1),
    ),
  ];

  static int get latestVersion => MobileClawSchema.latestVersion;

  static int readUserVersion(Database database) {
    final result = database.select('PRAGMA user_version');
    return result.single.columnAt(0) as int;
  }

  static void migrate(Database database, {int? targetVersion}) {
    final desiredVersion = targetVersion ?? latestVersion;
    if (desiredVersion < 0 || desiredVersion > latestVersion) {
      throw ArgumentError.value(
        desiredVersion,
        'targetVersion',
        'Target version must be between 0 and $latestVersion',
      );
    }

    database.execute('PRAGMA foreign_keys = ON');
    var currentVersion = readUserVersion(database);
    if (currentVersion == desiredVersion) {
      return;
    }
    if (currentVersion > desiredVersion) {
      throw StateError(
        'Database version $currentVersion is newer than supported version $desiredVersion',
      );
    }

    database.execute('BEGIN');
    try {
      while (currentVersion < desiredVersion) {
        final step = steps.where((candidate) {
          return candidate.fromVersion == currentVersion;
        }).firstOrNull;
        if (step == null) {
          throw StateError('No migration found from version $currentVersion');
        }
        step.apply(database);
        currentVersion = step.toVersion;
      }
      database.execute('COMMIT');
    } catch (_) {
      database.execute('ROLLBACK');
      rethrow;
    }
  }
}
