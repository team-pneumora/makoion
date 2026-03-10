/// MobileClaw Core — Domain models, interfaces, and contracts.
///
/// Phone-first ubiquitous AI hub core contracts.
library;

// Domain - Enums & Value Objects
export 'src/domain/enums.dart';
export 'src/domain/ids.dart';

// Domain - Entities
export 'src/domain/task.dart';
export 'src/domain/file_node.dart';
export 'src/domain/device.dart';
export 'src/domain/device_capability.dart';
export 'src/domain/conversation.dart';
export 'src/domain/user_profile.dart';
export 'src/domain/memory_item.dart';
export 'src/domain/approval_request.dart';
export 'src/domain/audit_event.dart';
export 'src/domain/sync_state.dart';
export 'src/domain/file_version.dart';
export 'src/domain/file_embedding.dart';
export 'src/domain/remote_session.dart';

// Protocol
export 'src/protocol/action_intent.dart';
export 'src/protocol/file_graph_actions.dart';
export 'src/protocol/device_pairing.dart';

// Task Engine
export 'src/task_engine/task_state_machine.dart';
export 'src/task_engine/task_transition.dart';

// Policy
export 'src/policy/policy_engine.dart';
export 'src/policy/approval_service.dart';
export 'src/policy/audit_logger.dart';

// Capability
export 'src/capability/capability_registry.dart';
export 'src/capability/capabilities.dart';

// Memory
export 'src/memory/memory_store.dart';
