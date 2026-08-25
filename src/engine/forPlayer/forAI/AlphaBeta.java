package engine.forPlayer.forAI;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ComparisonChain;
import engine.forBoard.Board;
import engine.forBoard.BoardUtils;
import engine.forBoard.Move;
import engine.forPiece.Piece;
import engine.forPlayer.Player;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static engine.forBoard.Move.MoveFactory;

/**
 * The AlphaBeta class implements a chess engine using the alpha-beta search algorithm
 * with Lazy SMP parallel search optimization. This implementation employs advanced chess
 * engine techniques including iterative deepening, aspiration windows, transposition tables,
 * quiescence search, null move pruning, late move reductions, and various move ordering
 * heuristics to achieve high-performance chess move selection.
 * <p>
 * The engine uses multiple threads with the Young Brothers Wait concept to improve search
 * efficiency while maintaining search quality. It incorporates modern pruning techniques
 * and evaluation caching to reduce the search space and improve performance.
 *
 * @author Aaron Ho
 */
public class AlphaBeta extends Observable implements MoveStrategy {

  /** The evaluator used to assess board positions, selected at the start of each search. */
  private volatile BoardEvaluator evaluator;

  /** The depth used by {@link #execute(Board)} when a caller supplies no depth of its own. */
  private final int maxDepth;

  /** The count of boards evaluated during the search process. */
  private final AtomicLong boardsEvaluated = new AtomicLong(0);

  /** Thread-local search statistics for tracking per-thread performance metrics. */
  private final ThreadLocal<SearchStats> threadStats = new ThreadLocal<>();

  /** The number of threads to use for parallel search operations. */
  private final int threadCount;

  /** Flag indicating whether the search should be stopped. */
  private volatile boolean searchStopped;

  /** Thread pool executor for managing search worker threads. */
  private final ExecutorService searchThreadPool;

  /** Thread-safe transposition table for storing previously evaluated positions. */
  private final StripedTranspositionTable transpositionTable;

  /** History heuristic table for move ordering, indexed by origin and destination square. */
  private final int[][] historyHeuristic = new int[64][64];

  /** Killer moves table storing good non-capture moves for each search ply. */
  private final ThreadLocal<Move[][]> killerMoves = ThreadLocal.withInitial(() ->
          new Move[2][MAX_SEARCH_DEPTH]);

  /** Countermove table for storing responses to opponent moves for improved move ordering. */
  @SuppressWarnings("unchecked")
  private final AtomicReference<Move>[][] counterMoves = new AtomicReference[64][64];

  /** The maximum number of quiescence search nodes allowed per search. */
  private static final int MAX_QUIESCENCE = 300000;

  /** Maximum search depth supported by data structures. */
  private static final int MAX_SEARCH_DEPTH = 100;

  /** The transposition table size in megabytes used when a caller does not specify one. */
  private static final int DEFAULT_TABLE_SIZE_MB = 256;

  /** The depth threshold for applying futility pruning. */
  private static final int FUTILITY_PRUNING_DEPTH = 3;

  /** The move count threshold for applying late move reductions. */
  private static final int LMR_THRESHOLD = 9;

  /** The reduction scale factor used in late move reductions. */
  private static final double LMR_SCALE = 0.9;

  /** The evaluation margin for delta pruning in quiescence search. */
  private static final double DELTA_PRUNING_VALUE = 5;

  /** The evaluation margin for razoring pruning technique. */
  private static final double RAZOR_MARGIN = 150;

  /** The size of the aspiration window for aspiration search. */
  private static final double ASPIRATION_WINDOW = 40;

  /** The material threshold for delta pruning in quiescence search. */
  private static final double DELTA_MATERIAL = 100;

  /** The static exchange evaluation threshold for pruning bad captures. */
  private static final int SEE_PRUNING_THRESHOLD = -20;

  /** The highest evaluation value seen during aspiration search.
   * Reset at the start of each search. */
  private double highestSeenValue = Double.NEGATIVE_INFINITY;

  /** The lowest evaluation value seen during aspiration search.
   * Reset at the start of each search. */
  private double lowestSeenValue = Double.POSITIVE_INFINITY;

  /** Reference to the static exchange evaluator for move evaluation. */
  private final StaticExchangeEvaluator seeEvaluator = StaticExchangeEvaluator.get();

  /**
   * The MoveSorter enumeration defines different strategies for ordering moves
   * to improve alpha-beta search efficiency. Different sorting strategies are
   * used based on the search context and depth.
   */
  private enum MoveSorter {

    /**
     * Standard move sorting strategy using history heuristic, killer moves,
     * countermoves, and static exchange evaluation for move ordering.
     */
    STANDARD {
      @Override
      Collection<Move> sort(final Collection<Move> moves, final Board board,
                            final AlphaBeta engine, final int ply) {
        List<Move> sortedMoves = new ArrayList<>(moves);
        Move[][] killers = engine.killerMoves.get();
        Move lastMove = board.getTransitionMove();

        Map<Move, Integer> seeScores = new HashMap<>();
        Map<Move, Boolean> isUndefendedMap = new HashMap<>();
        Map<Move, Boolean> isKillerMap = new HashMap<>();
        Map<Move, Boolean> isCounterMap = new HashMap<>();

        for (Move move : sortedMoves) {
          if (move.isAttack()) {
            seeScores.put(move, engine.seeEvaluator.evaluate(board, move));
            Piece attackedPiece = move.getAttackedPiece();
            isUndefendedMap.put(move, attackedPiece != null &&
                    !engine.seeEvaluator.isPieceDefended(attackedPiece, board));
          }

          isKillerMap.put(move, move.equals(killers[0][ply]) || move.equals(killers[1][ply]));

          boolean isCounter = false;
          if (lastMove != null && lastMove != MoveFactory.getNullMove() &&
                  lastMove.getCurrentCoordinate() >= 0 && lastMove.getDestinationCoordinate() >= 0 &&
                  lastMove.getCurrentCoordinate() < 64 && lastMove.getDestinationCoordinate() < 64) {
            isCounter = move.equals(engine.counterMoves[lastMove.getCurrentCoordinate()][lastMove.getDestinationCoordinate()].get());
          }
          isCounterMap.put(move, isCounter);
        }

        sortedMoves.sort((move1, move2) -> {
          boolean isUndefendedCapture1 = isUndefendedMap.getOrDefault(move1, false);
          boolean isUndefendedCapture2 = isUndefendedMap.getOrDefault(move2, false);

          if (isUndefendedCapture1 != isUndefendedCapture2) {
            return isUndefendedCapture1 ? -1 : 1;
          }

          boolean isKiller1 = isKillerMap.getOrDefault(move1, false);
          boolean isKiller2 = isKillerMap.getOrDefault(move2, false);

          if (isKiller1 != isKiller2) {
            return isKiller1 ? -1 : 1;
          }

          boolean isCounter1 = isCounterMap.getOrDefault(move1, false);
          boolean isCounter2 = isCounterMap.getOrDefault(move2, false);

          if (isCounter1 != isCounter2) {
            return isCounter1 ? -1 : 1;
          }

          boolean isCapture1 = move1.isAttack();
          boolean isCapture2 = move2.isAttack();

          if (isCapture1 && isCapture2) {
            int score1 = seeScores.getOrDefault(move1, 0);
            int score2 = seeScores.getOrDefault(move2, 0);
            return Integer.compare(score2, score1);
          } else if (isCapture1 != isCapture2) {
            if (isCapture1) {
              int seeValue = seeScores.getOrDefault(move1, 0);
              return seeValue >= 0 ? -1 : 1;
            } else {
              int seeValue = seeScores.getOrDefault(move2, 0);
              return seeValue >= 0 ? 1 : -1;
            }
          }

          int score1 = 0;
          int score2 = 0;

          if (isValidPosition(move1)) {
            score1 = engine.historyHeuristic[move1.getCurrentCoordinate()][move1.getDestinationCoordinate()];
          } if (isValidPosition(move2)) {
            score2 = engine.historyHeuristic[move2.getCurrentCoordinate()][move2.getDestinationCoordinate()];
          }

          return Integer.compare(score2, score1);
        });
        return sortedMoves;
      }

      /**
       * Validates that a move has coordinates within the valid range for history heuristic access.
       *
       * @param move The move to validate.
       * @return True if the move has valid coordinates, false otherwise.
       */
      private boolean isValidPosition(Move move) {
        if (move == null) return false;
        int current = move.getCurrentCoordinate();
        int dest = move.getDestinationCoordinate();
        return current >= 0 && current < 64 && dest >= 0 && dest < 64;
      }
    },

    /**
     * Expensive move sorting strategy used for root moves that performs
     * comprehensive evaluation including threat analysis and detailed move scoring.
     */
    EXPENSIVE {
      @Override
      Collection<Move> sort(final Collection<Move> moves, final Board board,
                            final AlphaBeta engine, final int ply) {
        List<Move> sortedMoves = new ArrayList<>(moves);

        Map<Move, Integer> seeScores = new HashMap<>();
        for (Move move : sortedMoves) {
          if (move.isAttack()) {
            seeScores.put(move, engine.seeEvaluator.evaluate(board, move));
          }
        }

        // Whether a move gives check is resolved once per move here rather than inside the
        // comparator, which would ask the same question O(n log n) times. It is resolved against a
        // private copy of the position because kingThreat mutates the board it is handed, and the
        // board this sorter is given at the root is the game board the rest of the application is
        // reading.
        final Board probeBoard = board.copy();
        Map<Move, Boolean> givesCheck = new HashMap<>();
        for (Move move : sortedMoves) {
          givesCheck.put(move, BoardUtils.kingThreat(move, probeBoard));
        }

        sortedMoves.sort((move1, move2) -> ComparisonChain.start()
                .compareTrueFirst(givesCheck.getOrDefault(move1, false),
                        givesCheck.getOrDefault(move2, false))
                .compareTrueFirst(move1.isCastlingMove(), move2.isCastlingMove())
                .compare(
                        move1.isAttack() ? seeScores.getOrDefault(move1, 0) : -1000,
                        move2.isAttack() ? seeScores.getOrDefault(move2, 0) : -1000
                )
                .compare(
                    isValidPosition(move1) ?
                        engine.historyHeuristic[move1.getCurrentCoordinate()][move1.getDestinationCoordinate()] : 0,
                   isValidPosition(move2) ?
                        engine.historyHeuristic[move2.getCurrentCoordinate()][move2.getDestinationCoordinate()] : 0
                )
                .result());
        return sortedMoves;
      }

      /**
       * Validates that a move has coordinates within the valid range for history heuristic access.
       *
       * @param move The move to validate.
       * @return True if the move has valid coordinates, false otherwise.
       */
      private boolean isValidPosition(Move move) {
        int current = move.getCurrentCoordinate();
        int dest = move.getDestinationCoordinate();
        return current >= 0 && current < 64 && dest >= 0 && dest < 64;
      }
    };

    /**
     * Sorts the given collection of moves according to the strategy's ordering criteria.
     *
     * @param moves The collection of moves to sort.
     * @param board The current board position.
     * @param engine The engine instance for accessing move ordering data.
     * @param ply The current search ply for accessing ply-specific data.
     * @return A sorted collection of moves.
     */
    abstract Collection<Move> sort(Collection<Move> moves, final Board board,
                                   final AlphaBeta engine, final int ply);
  }

  /**
   * Constructs an AlphaBeta chess engine with the given default search depth and a transposition
   * table of the default size.
   *
   * @param maxDepth The depth used by {@link #execute(Board)} when no depth is supplied per search.
   */
  public AlphaBeta(final int maxDepth) {
    this(maxDepth, DEFAULT_TABLE_SIZE_MB);
  }

  /**
   * Constructs an AlphaBeta chess engine with the given default search depth and transposition
   * table size. The table is allocated once here and serves every search this engine runs.
   *
   * @param maxDepth The depth used by {@link #execute(Board)} when no depth is supplied per search.
   * @param tableSizeMB The size of the transposition table in megabytes, at least one.
   * @throws IllegalArgumentException If the requested table size is less than one megabyte.
   */
  public AlphaBeta(final int maxDepth, final int tableSizeMB) {
    if (tableSizeMB < 1) {
      throw new IllegalArgumentException(
              "The transposition table needs at least one megabyte, requested " + tableSizeMB);
    }
    this.maxDepth = maxDepth;
    this.threadCount = Runtime.getRuntime().availableProcessors();
    this.searchThreadPool = Executors.newFixedThreadPool(threadCount);
    this.transpositionTable = new StripedTranspositionTable(tableSizeMB);

    for (int i = 0; i < 64; i++) {
      for (int j = 0; j < 64; j++) {
        counterMoves[i][j] = new AtomicReference<>();
      }
    }
  }

  /**
   * Returns a string representation of this chess engine.
   *
   * @return A string identifying this engine implementation.
   */
  @Override
  public String toString() {
    return "StockAB with Lazy SMP";
  }

  /**
   * Executes the alpha-beta search to this engine's default depth.
   *
   * @param board The current chess board position.
   * @return The best move determined by the search algorithm.
   */
  @Override
  public Move execute(final Board board) {
    return execute(board, this.maxDepth);
  }

  /**
   * Executes the alpha-beta search algorithm with iterative deepening and parallel search to find
   * the best move for the current player, to the given depth.
   * <p>
   * The thread pool, transposition table, history heuristic, and countermove table survive this
   * call and carry into the next search. A caller that is finished with this engine must call
   * {@link #shutdown()}.
   *
   * @param board The current chess board position.
   * @param searchDepth The maximum depth for iterative deepening on this search.
   * @return The best move determined by the search algorithm.
   */
  public Move execute(final Board board, final int searchDepth) {
    final long startTime = System.currentTimeMillis();
    Move bestMove = MoveFactory.getNullMove();

    this.searchStopped = false;
    this.boardsEvaluated.set(0);
    this.transpositionTable.incrementAge();
    this.evaluator = determineGameState(board);
    this.highestSeenValue = Double.NEGATIVE_INFINITY;
    this.lowestSeenValue = Double.POSITIVE_INFINITY;

    EvaluationCache.get().clear();

    for (int currentDepth = 1; currentDepth <= searchDepth && !searchStopped; currentDepth++) {
      if (currentDepth >= 3) {
        bestMove = searchRootAspirationWindow(board, currentDepth, bestMove);
      } else {
        bestMove = searchRootParallel(board, currentDepth, -Double.MAX_VALUE, Double.MAX_VALUE, bestMove);
      }

      updateHistoryHeuristic(bestMove, currentDepth);

      final long evaluatedPositions = this.boardsEvaluated.get();
      final long executionTime = System.currentTimeMillis() - startTime;
      final String result = String.format(
              "%s | depth = %d | boards evaluated = %d | time = %.2f sec | nps = %.2f | %s",
              bestMove, currentDepth, evaluatedPositions,
              executionTime / 1000.0,
              evaluatedPositions / (executionTime / 1000.0),
              EvaluationCache.get().getStats());

      System.out.println(result);
      setChanged();
      notifyObservers(result);
    }

    return bestMove;
  }

  /**
   * Raises the stop flag and shuts this engine's search thread pool down. The engine cannot
   * search after this returns.
   */
  public void shutdown() {
    this.searchStopped = true;
    this.searchThreadPool.shutdown();
  }

  /**
   * Retrieves a cached board evaluation or computes a new evaluation if not found in cache.
   *
   * @param board The board position to evaluate.
   * @param depth The search depth for the evaluation.
   * @return The evaluation score for the board position.
   */
  private double getCachedEvaluation(Board board, int depth) {
    Double cachedScore = EvaluationCache.get().probe(board, depth);
    if (cachedScore != null) {
      return cachedScore;
    }

    double score = this.evaluator.evaluate(board, depth);
    EvaluationCache.get().store(board, depth, score);
    return score;
  }

  /**
   * Implements aspiration window search for deeper iterations to reduce search time
   * by using narrow alpha-beta windows based on previous search results.
   *
   * @param board The root board position.
   * @param depth The current search depth.
   * @param previousBestMove The best move from the previous iteration.
   * @return The best move found with aspiration window search.
   */
  private Move searchRootAspirationWindow(final Board board, final int depth, final Move previousBestMove) {
    double alpha = depth > 3 ? highestSeenValue - ASPIRATION_WINDOW : -Double.MAX_VALUE;
    double beta = depth > 3 ? lowestSeenValue + ASPIRATION_WINDOW : Double.MAX_VALUE;
    Move bestMove;

    bestMove = searchRootParallel(board, depth, alpha, beta, previousBestMove);

    if ((board.currentPlayer().getAlliance().isWhite() && highestSeenValue <= alpha) ||
            (!board.currentPlayer().getAlliance().isWhite() && lowestSeenValue >= beta)) {
      System.out.println("Aspiration window failed, re-searching with full window");
      bestMove = searchRootParallel(board, depth, -Double.MAX_VALUE, Double.MAX_VALUE, previousBestMove);
    }

    return bestMove;
  }

  /**
   * Searches the root position using parallel Lazy SMP search with Young Brothers Wait protocol.
   * Distributes move searching across multiple threads for improved performance.
   *
   * @param board The root board position.
   * @param depth The current search depth.
   * @param alpha The alpha bound for alpha-beta search.
   * @param beta The beta bound for alpha-beta search.
   * @return The best move found by the parallel search.
   */
  private Move searchRootParallel(final Board board, final int depth, double alpha, double beta,
                                  final Move previousBestMove) {
    final List<Move> allMoves = new ArrayList<>(
        MoveSorter.EXPENSIVE.sort(board.currentPlayer().getLegalMoves(), board, this, 0));

    if (allMoves.isEmpty()) {
      return MoveFactory.getNullMove();
    }

    if (previousBestMove != null && previousBestMove != MoveFactory.getNullMove() &&
        allMoves.contains(previousBestMove)) {
      allMoves.remove(previousBestMove);
      allMoves.add(0, previousBestMove);
    }

    final AtomicReference<Move> globalBestMove = new AtomicReference<>(allMoves.get(0));
    final AtomicReference<Double> globalBestScore = new AtomicReference<>(
        board.currentPlayer().getAlliance().isWhite() ? alpha : beta);

    final AtomicInteger moveIndex = new AtomicInteger(0);

    final CountDownLatch firstMoveLatch = new CountDownLatch(1);

    List<Future<?>> futures = new ArrayList<>();
    for (int threadId = 0; threadId < threadCount; threadId++) {
      final int id = threadId;
      futures.add(searchThreadPool.submit(() -> {
        SearchStats stats = new SearchStats();
        threadStats.set(stats);

        if (killerMoves.get() == null) {
          killerMoves.set(new Move[2][MAX_SEARCH_DEPTH]);
        }

        final Board threadBoard = board.copy();
        final long rootHash = threadBoard.getZobristHash();

        try {
          if (id == 0) {
            try {
              searchMove(threadBoard, allMoves.get(0), depth,
                  globalBestMove, globalBestScore, alpha, beta);
            } finally {
              firstMoveLatch.countDown();
            }
          } else {
            firstMoveLatch.await();
          }

          int idx;
          while ((idx = moveIndex.getAndIncrement()) < allMoves.size() && !searchStopped) {
            if (idx == 0) continue;

            int threadDepth = depth - (id % 2);
            if (threadDepth < 1) threadDepth = 1;

            searchMove(threadBoard, allMoves.get(idx), threadDepth,
                globalBestMove, globalBestScore, alpha, beta);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          boardsEvaluated.addAndGet(stats.boardsEvaluated);
          assert threadBoard.getZobristHash() == rootHash :
              "Thread board not restored to the root position: unbalanced make/unmake.";
        }
      }));
    }

    for (Future<?> future : futures) {
      try {
        future.get();
      } catch (Exception e) {
        System.err.println("Search worker thread failed: " + e);
      }
    }

    if (board.currentPlayer().getAlliance().isWhite()) {
      highestSeenValue = globalBestScore.get();
    } else {
      lowestSeenValue = globalBestScore.get();
    }

    return globalBestMove.get();
  }

  /**
   * Applies the given root move to the calling thread's private board, searches the resulting
   * position, unmakes the move, and then updates the shared best move and score if this move
   * improved on them.
   * <p>
   * The score comparison and the countermove record deliberately happen after the unmake, because
   * {@link #recordCounterMove} reads the board's transition move and must see the opponent's
   * previous move rather than the move just searched.
   *
   * @param board The calling thread's private board, in the root position.
   * @param move The root move to search.
   * @param depth The search depth.
   * @param bestMove Reference to the current best move.
   * @param bestScore Reference to the current best score.
   * @param alpha The alpha bound.
   * @param beta The beta bound.
   */
  private void searchMove(final Board board, final Move move, final int depth,
                          final AtomicReference<Move> bestMove, final AtomicReference<Double> bestScore,
                          final double alpha, final double beta) {
    final boolean rootIsWhite = board.currentPlayer().getAlliance().isWhite();

    board.makeMove(move);
    if (board.currentPlayer().getOpponent().isInCheck()) {
      board.unmakeMove();
      return;
    }

    double score;
    try {
      score = rootIsWhite ?
          min(board, depth - 1, alpha, beta, 1) :
          max(board, depth - 1, alpha, beta, 1);
    } finally {
      board.unmakeMove();
    }

    if (rootIsWhite) {
      if (score > bestScore.get()) {
        synchronized (bestScore) {
          if (score > bestScore.get()) {
            bestScore.set(score);
            bestMove.set(move);
            recordCounterMove(board, move);
          }
        }
      }
    } else {
      if (score < bestScore.get()) {
        synchronized (bestScore) {
          if (score < bestScore.get()) {
            bestScore.set(score);
            bestMove.set(move);
            recordCounterMove(board, move);
          }
        }
      }
    }
  }

  /**
   * Records a move as a countermove response to the last opponent move for move ordering.
   *
   * @param board The current board position.
   * @param move The move to record as a countermove.
   */
  private void recordCounterMove(Board board, Move move) {
    Move lastMove = board.getTransitionMove();
    if (lastMove != null && lastMove != MoveFactory.getNullMove()) {
      counterMoves[lastMove.getCurrentCoordinate()][lastMove.getDestinationCoordinate()].set(move);
    }
  }

  /**
   * Implements the maximizing player portion of the alpha-beta search algorithm
   * with various pruning techniques and search extensions.
   *
   * @param board The current board position.
   * @param depth The remaining search depth.
   * @param alpha The alpha bound.
   * @param beta The beta bound.
   * @param ply The current search ply.
   * @return The best evaluation score for the maximizing player.
   */
  private double max(final Board board, int depth, double alpha, double beta, int ply) {
    SearchStats stats = threadStats.get();
    stats.boardsEvaluated++;

    if (searchStopped) {
      return depth <= 0 ? quiescenceSearch(board, alpha, beta, ply, true) :
          getCachedEvaluation(board, depth);
    }

    if (depth <= 0) {
      return quiescenceSearch(board, alpha, beta, ply, true);
    }

    long zobristHash = board.getZobristHash();
    TranspositionTable.Entry entry = transpositionTable.get(zobristHash);
    if (entry != null && entry.depth >= depth) {
      if (entry.nodeType == TranspositionTable.EXACT) {
        return entry.score;
      } else if (entry.nodeType == TranspositionTable.LOWERBOUND) {
        alpha = Math.max(alpha, entry.score);
      } else if (entry.nodeType == TranspositionTable.UPPERBOUND) {
        beta = Math.min(beta, entry.score);
      }
      if (alpha >= beta) {
        return entry.score;
      }
    }

    if (BoardUtils.isEndOfGame(board)) {
      return getCachedEvaluation(board, depth);
    }

    final boolean inCheckAtNode = board.currentPlayer().isInCheck();

    if (depth == 1) {
      double eval = getCachedEvaluation(board, depth);
      if (eval + RAZOR_MARGIN < alpha) {
        return quiescenceSearch(board, alpha, beta, ply, true);
      }
    }

    if (depth < FUTILITY_PRUNING_DEPTH && !inCheckAtNode) {
      double eval = getCachedEvaluation(board, depth);
      if (eval >= beta + (depth * 100)) {
        return eval;
      }
    }

    Move ttMove = entry != null ? entry.move : null;
    if (ttMove == null && depth >= 4) {
      max(board, depth - 2, alpha, beta, ply);
      entry = transpositionTable.get(zobristHash);
      if (entry != null) {
        ttMove = entry.move;
      }
    }

    if (depth >= 3 && !inCheckAtNode && hasNonPawnMaterial(board.currentPlayer())) {
      final int R = 2 + depth / 6;
      boolean nullMovePrunes = false;
      board.makeNullMove();
      try {
        double nullMoveScore = min(board, depth - 1 - R, alpha, beta, ply + 1);
        if (nullMoveScore >= beta) {
          nullMovePrunes = depth < 6 || min(board, depth - 4, alpha, beta, ply + 1) >= beta;
        }
      } finally {
        board.unmakeNullMove();
      }
      if (nullMovePrunes) {
        return beta;
      }
    }

    double currentAlpha = alpha;
    boolean firstMove = true;
    Move bestFoundMove = null;
    Move[][] killers = killerMoves.get();
    int movesSearched = 0;

    Collection<Move> sortedMoves = MoveSorter.STANDARD.sort(board.currentPlayer().getLegalMoves(), board, this, ply);

    if (ttMove != null) {
      List<Move> reorderedMoves = new ArrayList<>();
      for (Move move : sortedMoves) {
        if (move.equals(ttMove)) {
          reorderedMoves.add(0, move);
        } else {
          reorderedMoves.add(move);
        }
      }
      sortedMoves = reorderedMoves;
    }

    for (final Move move : sortedMoves) {
      if (move.isAttack() && depth < 3 && movesSearched > 2) {
        int seeScore = seeEvaluator.evaluate(board, move);
        if (seeScore < SEE_PRUNING_THRESHOLD) {
          continue;
        }
      }

      board.makeMove(move);
      if (board.currentPlayer().getOpponent().isInCheck()) {
        board.unmakeMove();
        continue;
      }

      double currentValue;
      try {
        int newDepth = depth - 1;
        if (board.currentPlayer().isInCheck()) {
          newDepth++;
        }

        if (firstMove) {
          currentValue = min(board, newDepth, currentAlpha, beta, ply + 1);
        } else {
          int reduction = 0;
          if (depth >= 3 && movesSearched >= 4 && !move.isAttack() && !inCheckAtNode) {
            reduction = 1 + (movesSearched / 6);
            if (reduction > 3) reduction = 3;
          }

          currentValue = min(board, newDepth - reduction, currentAlpha, currentAlpha + 0.1, ply + 1);

          if (currentValue > currentAlpha && currentValue < beta) {
            currentValue = min(board, newDepth, currentAlpha, beta, ply + 1);
          }
        }
      } finally {
        board.unmakeMove();
      }

      if (currentValue > currentAlpha) {
        currentAlpha = currentValue;
        bestFoundMove = move;

        if (!move.isAttack()) {
          if (!move.equals(killers[0][ply])) {
            killers[1][ply] = killers[0][ply];
            killers[0][ply] = move;
          }
        }

        recordCounterMove(board, move);

        if (currentAlpha >= beta) {
          if (!move.isAttack()) {
            historyHeuristic[move.getCurrentCoordinate()][move.getDestinationCoordinate()] += depth * depth;
          }

          transpositionTable.store(zobristHash, beta, depth, TranspositionTable.LOWERBOUND, bestFoundMove);
          return beta;
        }
      }

      firstMove = false;
      movesSearched++;
    }

    byte nodeType = TranspositionTable.EXACT;
    if (currentAlpha <= alpha) {
      nodeType = TranspositionTable.UPPERBOUND;
    } else if (currentAlpha >= beta) {
      nodeType = TranspositionTable.LOWERBOUND;
    }
    transpositionTable.store(zobristHash, currentAlpha, depth, nodeType, bestFoundMove);

    return currentAlpha;
  }

  /**
   * Implements the minimizing player portion of the alpha-beta search algorithm
   * with various pruning techniques and search extensions.
   *
   * @param board The current board position.
   * @param depth The remaining search depth.
   * @param alpha The alpha bound.
   * @param beta The beta bound.
   * @param ply The current search ply.
   * @return The best evaluation score for the minimizing player.
   */
  private double min(final Board board, int depth, double alpha, double beta, int ply) {
    SearchStats stats = threadStats.get();
    stats.boardsEvaluated++;

    if (searchStopped) {
      return depth <= 0 ? quiescenceSearch(board, alpha, beta, ply, false) :
          getCachedEvaluation(board, depth);
    }

    if (depth <= 0) {
      return quiescenceSearch(board, alpha, beta, ply, false);
    }

    long zobristHash = board.getZobristHash();
    TranspositionTable.Entry entry = transpositionTable.get(zobristHash);
    if (entry != null && entry.depth >= depth) {
      if (entry.nodeType == TranspositionTable.EXACT) {
        return entry.score;
      } else if (entry.nodeType == TranspositionTable.LOWERBOUND) {
        alpha = Math.max(alpha, entry.score);
      } else if (entry.nodeType == TranspositionTable.UPPERBOUND) {
        beta = Math.min(beta, entry.score);
      }
      if (alpha >= beta) {
        return entry.score;
      }
    }

    if (BoardUtils.isEndOfGame(board)) {
      return getCachedEvaluation(board, depth);
    }

    final boolean inCheckAtNode = board.currentPlayer().isInCheck();

    if (depth == 1) {
      double eval = getCachedEvaluation(board, depth);
      if (eval - RAZOR_MARGIN > beta) {
        return quiescenceSearch(board, alpha, beta, ply, false);
      }
    }

    if (depth < FUTILITY_PRUNING_DEPTH && !inCheckAtNode) {
      double eval = getCachedEvaluation(board, depth);
      if (eval <= alpha - (depth * 100)) {
        return eval;
      }
    }

    Move ttMove = entry != null ? entry.move : null;
    if (ttMove == null && depth >= 4) {
      min(board, depth - 2, alpha, beta, ply);
      entry = transpositionTable.get(zobristHash);
      if (entry != null) {
        ttMove = entry.move;
      }
    }

    if (depth >= 3 && !inCheckAtNode && hasNonPawnMaterial(board.currentPlayer())) {
      final int R = 2 + depth / 6;
      boolean nullMovePrunes = false;
      board.makeNullMove();
      try {
        double nullMoveScore = max(board, depth - 1 - R, alpha, beta, ply + 1);
        if (nullMoveScore <= alpha) {
          nullMovePrunes = depth < 6 || max(board, depth - 4, alpha, beta, ply + 1) <= alpha;
        }
      } finally {
        board.unmakeNullMove();
      }
      if (nullMovePrunes) {
        return alpha;
      }
    }

    double currentBeta = beta;
    boolean firstMove = true;
    Move bestFoundMove = null;
    Move[][] killers = killerMoves.get();
    int movesSearched = 0;

    Collection<Move> sortedMoves = MoveSorter.STANDARD.sort(board.currentPlayer().getLegalMoves(), board, this, ply);

    if (ttMove != null) {
      List<Move> reorderedMoves = new ArrayList<>();
      for (Move move : sortedMoves) {
        if (move.equals(ttMove)) {
          reorderedMoves.add(0, move);
        } else {
          reorderedMoves.add(move);
        }
      }
      sortedMoves = reorderedMoves;
    }

    for (final Move move : sortedMoves) {
      if (move.isAttack() && depth < 3 && movesSearched > 2) {
        int seeScore = seeEvaluator.evaluate(board, move);
        if (seeScore < SEE_PRUNING_THRESHOLD) {
          continue;
        }
      }

      board.makeMove(move);
      if (board.currentPlayer().getOpponent().isInCheck()) {
        board.unmakeMove();
        continue;
      }

      double currentValue;
      try {
        int newDepth = depth - 1;
        if (board.currentPlayer().isInCheck()) {
          newDepth++;
        }

        if (firstMove) {
          currentValue = max(board, newDepth, alpha, currentBeta, ply + 1);
        } else {
          int reduction = 0;
          if (depth >= 3 && movesSearched >= 4 && !move.isAttack() && !inCheckAtNode) {
            reduction = 1 + (movesSearched / 6);
            if (reduction > 3) reduction = 3;
          }

          currentValue = max(board, newDepth - reduction, currentBeta - 0.1, currentBeta, ply + 1);

          if (currentValue < currentBeta && currentValue > alpha) {
            currentValue = max(board, newDepth, alpha, currentBeta, ply + 1);
          }
        }
      } finally {
        board.unmakeMove();
      }
        if (currentValue < currentBeta) {
          currentBeta = currentValue;
          bestFoundMove = move;

          if (!move.isAttack()) {
            if (!move.equals(killers[0][ply])) {
              killers[1][ply] = killers[0][ply];
              killers[0][ply] = move;
            }
          }

          recordCounterMove(board, move);

          if (currentBeta <= alpha) {
            if (!move.isAttack()) {
              historyHeuristic[move.getCurrentCoordinate()][move.getDestinationCoordinate()] += depth * depth;
            }

            transpositionTable.store(zobristHash, alpha, depth, TranspositionTable.UPPERBOUND, bestFoundMove);
            return alpha;
          }
        }

        firstMove = false;
        movesSearched++;
    }

    byte nodeType = TranspositionTable.EXACT;
    if (currentBeta <= alpha) {
      nodeType = TranspositionTable.UPPERBOUND;
    } else if (currentBeta >= beta) {
      nodeType = TranspositionTable.LOWERBOUND;
    }
    transpositionTable.store(zobristHash, currentBeta, depth, nodeType, bestFoundMove);

    return currentBeta;
  }

  /**
   * Implements quiescence search to handle tactical sequences involving captures
   * and checks to avoid the horizon effect in evaluation.
   *
   * @param board The current board position.
   * @param alpha The alpha bound.
   * @param beta The beta bound.
   * @param ply The current search ply.
   * @param maximizing True if this is a maximizing node, false for minimizing.
   * @return The quiescence search evaluation score.
   */
  private double quiescenceSearch(Board board, double alpha, double beta, int ply, boolean maximizing) {
    SearchStats stats = threadStats.get();
    stats.boardsEvaluated++;

    if (searchStopped) {
      return getCachedEvaluation(board, 0);
    }

    if (stats.quiescenceCount >= MAX_QUIESCENCE) {
      return getCachedEvaluation(board, 0);
    }
    stats.quiescenceCount++;

    long zobristHash = board.getZobristHash();
    TranspositionTable.Entry entry = transpositionTable.get(zobristHash);
    if (entry != null) {
      if (entry.nodeType == TranspositionTable.EXACT) {
        return entry.score;
      } else if (entry.nodeType == TranspositionTable.LOWERBOUND) {
        alpha = Math.max(alpha, entry.score);
      } else if (entry.nodeType == TranspositionTable.UPPERBOUND) {
        beta = Math.min(beta, entry.score);
      }
      if (alpha >= beta) {
        return entry.score;
      }
    }

    if (BoardUtils.isEndOfGame(board)) {
      return getCachedEvaluation(board, 0);
    }

    final double originalAlpha = alpha;
    final double originalBeta = beta;

    double standPat = getCachedEvaluation(board, 0);

    if (maximizing) {
      if (standPat >= beta) {
        transpositionTable.store(zobristHash, beta, 0, TranspositionTable.LOWERBOUND, null);
        return beta;
      }
      if (standPat > alpha) alpha = standPat;
    } else {
      if (standPat <= alpha) {
        transpositionTable.store(zobristHash, alpha, 0, TranspositionTable.UPPERBOUND, null);
        return alpha;
      }
      if (standPat < beta) beta = standPat;
    }

    if (maximizing && standPat < alpha - DELTA_MATERIAL) return alpha;
    if (!maximizing && standPat > beta + DELTA_MATERIAL) return beta;

    if (board.currentPlayer().isInCheck()) {
      return maximizing ?
          max(board, 1, alpha, beta, ply + 1) :
          min(board, 1, alpha, beta, ply + 1);
    }

    List<Move> captures = board.currentPlayer().getLegalMoves().stream()
        .filter(Move::isAttack)
        .sorted((m1, m2) -> {
          boolean isM1Undefended = m1.isAttack() && m1.getAttackedPiece() != null &&
              !seeEvaluator.isPieceDefended(m1.getAttackedPiece(), board);
          boolean isM2Undefended = m2.isAttack() && m2.getAttackedPiece() != null &&
              !seeEvaluator.isPieceDefended(m2.getAttackedPiece(), board);

          if (isM1Undefended != isM2Undefended) {
            return isM1Undefended ? -1 : 1;
          }

          int see1 = seeEvaluator.evaluate(board, m1);
          int see2 = seeEvaluator.evaluate(board, m2);
          return Integer.compare(see2, see1);
        })
        .toList();

    for (Move move : captures) {
      final int seeScore = seeEvaluator.evaluate(board, move);
      final boolean isUndefendedCapture = !seeEvaluator.isPieceDefended(move.getAttackedPiece(), board);

      board.makeMove(move);

      final boolean legal = !board.currentPlayer().getOpponent().isInCheck();
      final boolean givesCheck = legal && board.currentPlayer().isInCheck();
      final boolean prunedByExchange = seeScore < SEE_PRUNING_THRESHOLD &&
          !isUndefendedCapture && !givesCheck;

      if (!legal || prunedByExchange) {
        board.unmakeMove();
        continue;
      }

      double score;
      try {
        score = quiescenceSearch(board, alpha, beta, ply + 1, !maximizing);
      } finally {
        board.unmakeMove();
      }

      if (maximizing) {
        if (score > alpha) alpha = score;
      } else {
        if (score < beta) beta = score;
      }

      if (alpha >= beta) {
        transpositionTable.store(zobristHash, maximizing ? beta : alpha, 0,
            maximizing ? TranspositionTable.LOWERBOUND : TranspositionTable.UPPERBOUND, null);
        return maximizing ? beta : alpha;
      }
    }

    double finalScore = maximizing ? alpha : beta;
    byte nodeType = TranspositionTable.EXACT;
    if (finalScore <= originalAlpha) {
      nodeType = TranspositionTable.UPPERBOUND;
    } else if (finalScore >= originalBeta) {
      nodeType = TranspositionTable.LOWERBOUND;
    }
    transpositionTable.store(zobristHash, finalScore, 0, nodeType, null);
    return finalScore;
  }

  /**
   * Checks whether a player has non-pawn material remaining on the board.
   * Used to determine if null move pruning is safe to apply.
   *
   * @param player The player to check for non-pawn material.
   * @return True if the player has pieces other than pawns and king, false otherwise.
   */
  private boolean hasNonPawnMaterial(Player player) {
    for (Piece piece : player.getActivePieces()) {
      if (piece.getPieceType() != Piece.PieceType.PAWN &&
              piece.getPieceType() != Piece.PieceType.KING) {
        return true;
      }
    }
    return false;
  }

  /**
   * Updates the history heuristic table with information about a good move
   * to improve move ordering in future searches.
   *
   * @param move The move to record in the history heuristic.
   * @param depth The depth at which this move was found to be good.
   */
  private void updateHistoryHeuristic(Move move, int depth) {
    if (move != null && move != MoveFactory.getNullMove()) {
      historyHeuristic[move.getCurrentCoordinate()][move.getDestinationCoordinate()] += depth * depth;
    }
  }

  /**
   * Determines the appropriate board evaluator based on the current game state.
   * Uses game phase detection to select between opening, middlegame, and endgame evaluators.
   *
   * @param board The current board position.
   * @return The appropriate board evaluator for the game state.
   */
  @VisibleForTesting
  private BoardEvaluator determineGameState(final Board board) {
    return GameStateDetector.get().determineEvaluator(board);
  }

  /**
   * The SearchStats class tracks performance statistics for individual search threads
   * during parallel search operations.
   */
  private static class SearchStats {
    /** The number of board positions evaluated by this thread. */
    long boardsEvaluated;
    /** The number of quiescence search nodes explored by this thread. */
    int quiescenceCount;
  }

  /**
   * The StripedTranspositionTable class implements a thread-safe transposition table
   * using striped locking to reduce contention in parallel search operations.
   */
  private static class StripedTranspositionTable {
    /** The hash table storing transposition table entries. */
    private final TranspositionTable.Entry[] table;
    /** The bit mask for indexing into the hash table. */
    private final int mask;
    /** The current age counter for entry replacement decisions. */
    private volatile byte currentAge;
    /** Array of read-write locks for striped locking. */
    private final ReadWriteLock[] locks;
    /** The number of locks used for striped locking. */
    private static final int LOCK_COUNT = 1024;

    /**
     * Constructs a new striped transposition table with the specified size.
     *
     * @param sizeMB The size of the transposition table in megabytes.
     */
    public StripedTranspositionTable(int sizeMB) {
      long bytes = (long) sizeMB * 1024 * 1024;
      int entryCount = (int) (bytes / 24);
      int size = Integer.highestOneBit(entryCount);

      table = new TranspositionTable.Entry[size];
      mask = size - 1;
      currentAge = 0;

      locks = new ReentrantReadWriteLock[LOCK_COUNT];
      for (int i = 0; i < LOCK_COUNT; i++) {
        locks[i] = new ReentrantReadWriteLock();
      }

      for (int i = 0; i < size; i++) {
        table[i] = new TranspositionTable.Entry();
      }

      System.out.println("Transposition Table created with " + size +
              " entries (" + (size * 24 / (1024 * 1024)) + " MB)");
    }

    /**
     * Gets the appropriate lock for the given hash value using striped locking.
     *
     * @param hash The hash value to determine the lock.
     * @return The read-write lock for the hash value.
     */
    private ReadWriteLock getLock(long hash) {
      return locks[(int)(hash & (LOCK_COUNT - 1))];
    }

    /**
     * Increments the age counter for entry replacement decisions.
     */
    public synchronized void incrementAge() {
      currentAge++;
      if (currentAge == 0) {
        currentAge = 1;
      }
    }

    /**
     * Retrieves a transposition table entry for the given board hash.
     *
     * @param zobristHash The Zobrist hash of the board position.
     * @return The transposition table entry if found, null otherwise.
     */
    public TranspositionTable.Entry get(long zobristHash) {
      int index = (int) (zobristHash & mask);

      ReadWriteLock lock = getLock(zobristHash);
      lock.readLock().lock();
      try {
        TranspositionTable.Entry entry = table[index];
        if (entry.key == zobristHash && entry.key != 0) {
          entry.age = currentAge;
          return entry;
        }

        int index2 = (index ^ (int)(zobristHash >>> 32)) & mask;
        TranspositionTable.Entry entry2 = table[index2];
        if (entry2.key == zobristHash && entry2.key != 0) {
          entry2.age = currentAge;
          return entry2;
        }

        return null;
      } finally {
        lock.readLock().unlock();
      }
    }

    /**
     * Stores a transposition table entry for the given board position.
     *
     * @param zobristHash The Zobrist hash of the board position.
     * @param score The evaluation score for the position.
     * @param depth The search depth for the evaluation.
     * @param nodeType The type of node (exact, lower bound, upper bound).
     */
    public void store(long zobristHash, double score, int depth, byte nodeType, Move bestMove) {
      int index = (int) (zobristHash & mask);

      ReadWriteLock lock = getLock(zobristHash);
      lock.writeLock().lock();
      try {
        TranspositionTable.Entry entry = table[index];
        int index2 = (index ^ (int)(zobristHash >>> 32)) & mask;
        TranspositionTable.Entry entry2 = table[index2];

        boolean useFirst = shouldReplace(entry, entry2, depth, nodeType);
        TranspositionTable.Entry target = useFirst ? entry : entry2;

        target.key = zobristHash;
        target.score = score;
        target.depth = (short) depth;
        target.nodeType = nodeType;
        target.age = currentAge;
        target.move = bestMove;
      } finally {
        lock.writeLock().unlock();
      }
    }

    /**
     * Determines which transposition table entry should be replaced based on
     * depth, node type, and age criteria.
     *
     * @param entry1 The first entry candidate for replacement.
     * @param entry2 The second entry candidate for replacement.
     * @param depth The depth of the new entry.
     * @param nodeType The node type of the new entry.
     * @return True if the first entry should be replaced, false for the second.
     */
    private boolean shouldReplace(TranspositionTable.Entry entry1,
                                  TranspositionTable.Entry entry2,
                                  int depth, byte nodeType) {
      if (entry1.key == 0) return true;
      if (entry2.key == 0) return false;

      if (entry1.depth < entry2.depth) return true;
      if (entry1.depth > entry2.depth) return false;

      boolean isExact1 = entry1.nodeType == TranspositionTable.EXACT;
      boolean isExact2 = entry2.nodeType == TranspositionTable.EXACT;
      boolean newIsExact = nodeType == TranspositionTable.EXACT;

      if (!isExact1 && isExact2) return false;
      if (isExact1 && !isExact2) return true;
      if (!isExact1 && newIsExact) return true;

      return entry1.age <= entry2.age;
    }
  }
}