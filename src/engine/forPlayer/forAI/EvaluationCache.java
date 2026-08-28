package engine.forPlayer.forAI;

import engine.forBoard.Board;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The EvaluationCache class provides a thread-safe cache for chess board evaluations to avoid redundant calculations
 * during search operations. It uses Zobrist hashing combined with search depth as the cache key for efficient and
 * accurate lookups. The cache automatically manages its size by evicting entries when it reaches capacity limits.
 * <p>
 * Each cache belongs to the engine that constructed it, so two engines running at the same time neither share entries
 * nor clear one another's. Cache statistics are maintained to monitor hit rates and performance characteristics.
 *
 * @author Aaron Ho
 */
public class EvaluationCache {

  /** The concurrent hash map storing cached evaluation scores indexed by cache keys. */
  private final ConcurrentHashMap<CacheKey, Double> cache;

  /** The maximum number of entries allowed in the cache before eviction occurs. */
  private static final int MAX_SIZE = 1_000_000;

  /** The number of successful cache lookups (hits). */
  private long hits = 0;

  /** The number of unsuccessful cache lookups (misses). */
  private long misses = 0;

  /**
   * Constructs a new empty EvaluationCache. The table grows on demand up to the maximum size.
   */
  public EvaluationCache() {
    this.cache = new ConcurrentHashMap<>();
  }

  /**
   * Stores an evaluation score in the cache for the specified board position and search depth.
   * If the cache is at capacity, entries are automatically evicted before storing the new value.
   *
   * @param board The chess board position being evaluated.
   * @param depth The search depth used for the evaluation.
   * @param score The evaluation score to cache.
   */
  public void store(Board board, int depth, double score) {
    if (cache.size() >= MAX_SIZE) {
      clearSomeEntries();
    }

    CacheKey key = new CacheKey(board.getZobristHash(), depth);
    cache.put(key, score);
  }

  /**
   * Retrieves a cached evaluation score for the specified board position and search depth.
   * Updates cache statistics based on whether the lookup was successful.
   *
   * @param board The chess board position to look up.
   * @param depth The search depth used for the evaluation.
   * @return The cached evaluation score, or null if not found in cache.
   */
  public Double probe(Board board, int depth) {
    CacheKey key = new CacheKey(board.getZobristHash(), depth);
    Double result = cache.get(key);

    if (result != null) {
      hits++;
    } else {
      misses++;
    }

    return result;
  }

  /**
   * Returns a formatted string containing cache performance statistics including entry count,
   * hit rate percentage, and total hits and misses.
   *
   * @return A string representation of cache statistics.
   */
  public String getStats() {
    long total = hits + misses;
    if (total == 0) return "No cache lookups yet";

    double hitRate = (double) hits / total * 100.0;
    return String.format("Cache: %d entries, %.2f%% hit rate (%d hits, %d misses)",
            cache.size(), hitRate, hits, misses);
  }

  /**
   * Clears all entries from the cache and resets hit and miss statistics to zero.
   */
  public void clear() {
    cache.clear();
    hits = 0;
    misses = 0;
  }

  /**
   * Removes approximately half of the cache entries to prevent excessive memory usage.
   * This method is called automatically when the cache reaches its maximum size.
   */
  private void clearSomeEntries() {
    int toRemove = MAX_SIZE / 2;

    int removed = 0;
    for (CacheKey key : cache.keySet()) {
      cache.remove(key);
      removed++;
      if (removed >= toRemove) break;
    }
  }

  /**
   * The CacheKey class represents a composite key for the evaluation cache, combining a board's
   * Zobrist hash value with the evaluation depth. This ensures that evaluations are only retrieved
   * for identical board positions at the same search depth.
   */
  private static class CacheKey {

    /** The Zobrist hash value of the board position. */
    private final long zobristHash;

    /** The search depth used for the evaluation. */
    private final int depth;

    /**
     * Constructs a new CacheKey with the specified Zobrist hash and search depth.
     *
     * @param zobristHash The Zobrist hash value of the board position.
     * @param depth The search depth used for the evaluation.
     */
    public CacheKey(long zobristHash, int depth) {
      this.zobristHash = zobristHash;
      this.depth = depth;
    }

    /**
     * Compares this CacheKey with another object for equality.
     * Two CacheKey objects are equal if they have the same Zobrist hash and depth.
     *
     * @param o The object to compare with this CacheKey.
     * @return True if the objects are equal, false otherwise.
     */
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      CacheKey cacheKey = (CacheKey) o;
      return zobristHash == cacheKey.zobristHash && depth == cacheKey.depth;
    }

    /**
     * Returns a hash code for this CacheKey based on the Zobrist hash and depth values.
     *
     * @return The hash code for this CacheKey.
     */
    @Override
    public int hashCode() {
      return Objects.hash(zobristHash, depth);
    }
  }
}