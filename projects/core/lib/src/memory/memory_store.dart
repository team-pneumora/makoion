import '../domain/ids.dart';
import '../domain/memory_item.dart';

/// Persistent memory store for the AI assistant.
///
/// Stores user preferences, learned patterns, and contextual information
/// that persists across conversations. Supports both keyword search
/// and vector similarity search for semantic retrieval.
abstract interface class MemoryStore {
  /// Save or update a memory item.
  Future<void> save(MemoryItem item);

  /// Search memory items by text query.
  Future<List<MemoryItem>> search(String query, {int limit = 10});

  /// Get a memory item by ID.
  Future<MemoryItem?> getById(MemoryItemId id);

  /// Delete a memory item.
  Future<void> delete(MemoryItemId id);

  /// Store an embedding vector for a memory item (for similarity search).
  Future<void> addEmbedding(MemoryItemId itemId, List<double> embedding);

  /// Find memory items similar to the given embedding vector.
  Future<List<MemoryItem>> similaritySearch(
    List<double> queryEmbedding, {
    int limit = 5,
  });

  /// List all memory items, optionally filtered by category.
  Future<List<MemoryItem>> listByCategory(String category, {int limit = 50});
}
