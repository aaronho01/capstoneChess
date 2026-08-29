package engine.forTesting;

import engine.forBoard.Board;
import engine.forBoard.BoardUtils;
import engine.forBoard.Move;
import engine.forPlayer.forAI.AlphaBeta;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The TacticalSuite class runs the engine's search against positions whose winning move is known
 * and reports whether the engine chose it. This tests the evaluation, move ordering, and pruning
 * layers that a perft cannot reach, since a perft counts nodes and is blind to which move is picked.
 * <p>
 * Each position is searched to a fixed depth rather than for a fixed time, so a run does not depend
 * on how fast the host is. Every position is searched by a freshly constructed engine that is shut
 * down once the position is finished, because the transposition table, evaluation cache, history
 * heuristic, and countermove table all survive a search and would otherwise make a result depend on
 * the order the positions are listed in.
 * <p>
 * Positions are searched on several worker threads at once, each running a single-threaded engine.
 * Nothing is shared between those engines, so a run reports the same result for every position
 * whatever order the workers happen to finish in. The engines themselves are single-threaded
 * because a parallel search reaches different results on different runs of the same position.
 * <p>
 * This class is designed to be run from the command line and its entry point returns a non-zero
 * exit status when any position is failed.
 *
 * @author Aaron Ho
 */
@SuppressWarnings("JavaPrintToLogpoint")
public class TacticalSuite {

  /** The size of the transposition table given to each engine, in megabytes. */
  private static final int TABLE_SIZE_MB = 64;

  /**
   * The number of search threads given to each engine. This is one so that a run is reproducible:
   * a parallel search reaches different results on different runs of the same position.
   */
  private static final int SEARCH_THREADS = 1;

  /**
   * The number of positions searched at once when no worker count is given. Each worker holds a
   * transposition table for as long as its position is running, so raising this raises peak memory
   * in proportion.
   */
  private static final int DEFAULT_WORKER_THREADS = 4;

  /** The command line flag prefix requesting a number of positions to search at once. */
  private static final String WORKERS_FLAG_PREFIX = "--workers=";

  /** The command line flag requesting that the engine's own search output be left on screen. */
  private static final String VERBOSE_FLAG = "--verbose";

  /** The command line flag requesting usage information. */
  private static final String HELP_FLAG = "--help";

  /** The depth override meaning that every position is searched to its own recorded depth. */
  private static final int NO_DEPTH_OVERRIDE = 0;

  /** The long algebraic notation of a move with no origin square, matching the null move convention. */
  private static final String NULL_MOVE_NOTATION = "0000";

  /** The number of nanoseconds in one second, used to convert elapsed times for reporting. */
  private static final double NANOSECONDS_PER_SECOND = 1_000_000_000.0;

  /**
   * The positions the suite tests, each paired with the moves that win and the depth to search to.
   * The depth recorded against a position is the shallowest one that keeps its tactic inside the
   * search horizon.
   */
  private static final List<TacticalPosition> STANDARD_POSITIONS = List.of(
          new TacticalPosition("Back rank mate",
                  "6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1", 2, "a1a8"),
          new TacticalPosition("Back rank mate by capture",
                  "2r3k1/5ppp/8/8/8/8/5PPP/2R3K1 w - - 0 1", 2, "c1c8"),
          new TacticalPosition("Scholar's mate",
                  "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4", 2, "h5f7"),
          new TacticalPosition("Queen sacrifice, mate in two",
                  "2rr3k/pp3pp1/1nnqbN1p/3pN3/2pP4/2P3Q1/PPB4P/R4RK1 w - - 0 1", 4, "g3g6"),
          new TacticalPosition("Bishop sacrifice, mate in two",
                  "r1bq2rk/pp3pbp/2p1p1pQ/7P/3P4/2PB1N2/PP3PPR/2KR4 w - - 0 1", 4, "h6h7"),
          new TacticalPosition("Queen check, mate in two",
                  "5k2/6pp/p1qN4/1p1p4/3P4/2PKP2Q/PP3r2/3R4 b - - 0 1", 4, "c6c4"),
          new TacticalPosition("Rook deflection, mate in two",
                  "6k1/pp4p1/2p5/2bp4/8/P5Pb/1P3rrP/2BRRN1K b - - 0 1", 4, "g2g1"),
          new TacticalPosition("Rook lift wins the queen",
                  "5rk1/1ppb3p/p1pb4/6q1/3P1p1r/2P1R2P/PP1BQ1P1/5RKN w - - 0 1", 6, "e3g3"),
          new TacticalPosition("Rook sacrifice wins the knight",
                  "2br2k1/2q3rn/p2NppQ1/2p1P3/Pp5R/4P3/1P3PPP/3R2K1 w - - 0 1", 6, "h4h7"),
          new TacticalPosition("Bishop capture wins a piece",
                  "r1b1kb1r/3q1ppp/pBp1pn2/8/Np3P2/5B2/PPP3PP/R2Q1RK1 w kq - 0 1", 6, "f3c6"),
          new TacticalPosition("Back rank threat wins the queen",
                  "3r1r1k/1p4pp/p4p2/8/1PQR4/6Pq/P3PP2/2R3K1 b - - 0 1", 6, "d8c8"),
          new TacticalPosition("Knight fork wins the exchange",
                  "8/p7/1ppk1n2/5ppp/P1PP4/2P1K1P1/5N1P/8 b - - 0 1", 6, "f6g4"),
          new TacticalPosition("Queen sacrifice, mate in four",
                  "r2rb1k1/pp1q1p1p/2n1p1p1/2bp4/5P2/PP1BPR1Q/1BPN2PP/R5K1 w - - 0 1", 8, "h3h7"),
          new TacticalPosition("Smothered mate in four",
                  "5r1k/6pp/8/6N1/8/1Q6/6PP/6K1 w - - 0 1", 8, "g5f7"),
          new TacticalPosition("Knight check, mate in four",
                  "rnbqkb1r/pppp1ppp/8/4P3/6n1/7P/PPPNPnP1/R1BQKBNR b KQkq - 0 1", 8, "f2d3"));

  /**
   * Runs the suite from the command line. With no arguments every position is searched to its own
   * recorded depth, a single numeric argument overrides that depth for every position, the workers
   * flag sets how many positions are searched at once, and the verbose flag leaves the engine's own
   * search output on screen.
   *
   * @param args The command line arguments, as described by the usage text.
   */
  public static void main(final String[] args) {
    int depthOverride = NO_DEPTH_OVERRIDE;
    int workerThreads = DEFAULT_WORKER_THREADS;
    boolean verbose = false;
    for (final String argument : args) {
      if (VERBOSE_FLAG.equals(argument)) {
        verbose = true;
      } else if (HELP_FLAG.equals(argument)) {
        printUsage();
        return;
      } else if (argument.startsWith(WORKERS_FLAG_PREFIX)) {
        try {
          workerThreads = Integer.parseInt(argument.substring(WORKERS_FLAG_PREFIX.length()));
        } catch (final NumberFormatException exception) {
          System.out.println("The worker count must be a number: " + argument);
          printUsage();
          return;
        }
      } else {
        try {
          depthOverride = Integer.parseInt(argument);
        } catch (final NumberFormatException exception) {
          System.out.println("Unrecognised argument: " + argument);
          printUsage();
          return;
        }
      }
    }
    if (depthOverride != NO_DEPTH_OVERRIDE && depthOverride < 1) {
      System.out.println("The depth override must be at least one.");
      printUsage();
      return;
    }
    if (workerThreads < 1) {
      System.out.println("The worker count must be at least one.");
      printUsage();
      return;
    }
    System.exit(run(depthOverride, verbose, workerThreads) ? 0 : 1);
  }

  /**
   * Searches every position in the suite on the default number of workers and prints a report of
   * the results.
   *
   * @param depthOverride The depth to search every position to, or zero to use each recorded depth.
   * @param verbose Whether to leave the engine's own search output on screen.
   * @return True if every position was solved, false otherwise.
   */
  public static boolean run(final int depthOverride, final boolean verbose) {
    return run(depthOverride, verbose, DEFAULT_WORKER_THREADS);
  }

  /**
   * Searches every position in the suite and prints a report of the results. Overriding the depth
   * downwards is expected to fail positions whose tactic no longer fits inside the horizon. Results
   * are reported in the order the positions are listed in however the workers are scheduled, and a
   * verbose run uses a single worker so the engine's output stays readable.
   *
   * @param depthOverride The depth to search every position to, or zero to use each recorded depth.
   * @param verbose Whether to leave the engine's own search output on screen.
   * @param requestedWorkers The number of positions to search at once, at least one.
   * @return True if every position was solved, false otherwise.
   */
  public static boolean run(final int depthOverride, final boolean verbose,
                            final int requestedWorkers) {
    final int workers = verbose ? 1 : Math.min(requestedWorkers, STANDARD_POSITIONS.size());
    System.out.printf("Tactical suite: %d positions on %d worker%s%s%n%n",
            STANDARD_POSITIONS.size(), workers, workers == 1 ? "" : "s",
            depthOverride == NO_DEPTH_OVERRIDE ? "" : ", every one searched to depth " + depthOverride);

    final PrintStream originalOut = System.out;
    final ExecutorService workerPool = Executors.newFixedThreadPool(workers);
    final List<Future<PositionOutcome>> outcomes = new ArrayList<>(STANDARD_POSITIONS.size());
    for (int index = 0; index < STANDARD_POSITIONS.size(); index++) {
      outcomes.add(null);
    }

    int solved = 0;
    final long startTime = System.nanoTime();
    if (!verbose) {
      System.setOut(new PrintStream(OutputStream.nullOutputStream()));
    }
    try {
      for (final int index : submissionOrder(depthOverride)) {
        final TacticalPosition position = STANDARD_POSITIONS.get(index);
        outcomes.set(index, workerPool.submit(() -> runPosition(position, depthOverride)));
      }
      for (int index = 0; index < STANDARD_POSITIONS.size(); index++) {
        final PositionOutcome outcome = await(outcomes.get(index), STANDARD_POSITIONS.get(index));
        originalOut.print(outcome.report());
        if (outcome.solved()) {
          solved++;
        }
      }
    } finally {
      System.setOut(originalOut);
      workerPool.shutdown();
    }

    final double elapsedSeconds = (System.nanoTime() - startTime) / NANOSECONDS_PER_SECOND;
    System.out.printf("%d of %d positions solved in %.2fs%n",
            solved, STANDARD_POSITIONS.size(), elapsedSeconds);
    return solved == STANDARD_POSITIONS.size();
  }

  /**
   * Returns the indices of the positions in the order they are handed to the workers, deepest
   * first. Starting the longest searches first keeps a deep position from being picked up last and
   * running on alone after every other worker has finished.
   *
   * @param depthOverride The depth every position is searched to, or zero to use recorded depths.
   * @return The position indices, ordered by descending search depth.
   */
  private static List<Integer> submissionOrder(final int depthOverride) {
    final List<Integer> order = new ArrayList<>(STANDARD_POSITIONS.size());
    for (int index = 0; index < STANDARD_POSITIONS.size(); index++) {
      order.add(index);
    }
    if (depthOverride == NO_DEPTH_OVERRIDE) {
      order.sort(Comparator.comparingInt(
              (Integer index) -> STANDARD_POSITIONS.get(index).searchDepth()).reversed());
    }
    return order;
  }

  /**
   * Waits for a position's search to finish and returns its outcome, reporting a failure to
   * complete as a failed position rather than ending the run.
   *
   * @param outcome The pending outcome of the position's search.
   * @param position The position the search was run against.
   * @return The outcome of the search.
   */
  private static PositionOutcome await(final Future<PositionOutcome> outcome,
                                       final TacticalPosition position) {
    try {
      return outcome.get();
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
      return abandoned(position, "the search was interrupted");
    } catch (final ExecutionException exception) {
      return abandoned(position, "the search threw " + exception.getCause());
    }
  }

  /**
   * Builds the outcome of a position whose search did not produce a move.
   *
   * @param position The position the search was run against.
   * @param reason The reason no move was produced.
   * @return A failed outcome carrying a report of the reason.
   */
  private static PositionOutcome abandoned(final TacticalPosition position, final String reason) {
    return new PositionOutcome(false, String.format("%s%n  %s%n  FAIL  %s%n%n",
            position.name(), position.fen(), reason));
  }

  /**
   * Searches a single position and builds a report of whether the engine chose a move the position
   * accepts. Nothing is printed from here, so that reports can be shown in the order the positions
   * are listed in rather than the order the workers finish in.
   *
   * @param position The position to search.
   * @param depthOverride The depth to search to, or zero to use the position's recorded depth.
   * @return The outcome of the search.
   */
  private static PositionOutcome runPosition(final TacticalPosition position,
                                             final int depthOverride) {
    final int depth = depthOverride == NO_DEPTH_OVERRIDE ? position.searchDepth() : depthOverride;
    final Board board;
    try {
      board = FenParser.parse(position.fen());
    } catch (final IllegalArgumentException exception) {
      return abandoned(position, "the position could not be parsed: " + exception.getMessage());
    }
    final long startTime = System.nanoTime();
    final Move chosenMove = search(board, depth);
    final double elapsedSeconds = (System.nanoTime() - startTime) / NANOSECONDS_PER_SECOND;
    final String chosenNotation = describe(board, chosenMove);
    final boolean solved = position.accepts(chosenNotation);
    final String report = String.format("%s%n  %s%n  depth %d  expected %-14s chose %-14s %8.2fs  %s%n%n",
            position.name(), position.fen(), depth,
            String.join(" or ", position.acceptedMoves()), chosenNotation, elapsedSeconds,
            solved ? "PASS" : "FAIL");
    return new PositionOutcome(solved, report);
  }

  /**
   * Searches the given position to the given depth with a single-threaded engine built for this
   * position alone. The engine is shut down before this returns, so nothing it learned reaches any
   * other position.
   *
   * @param board The position to search.
   * @param depth The depth to search to.
   * @return The move the engine chose.
   */
  private static Move search(final Board board, final int depth) {
    final AlphaBeta engine = new AlphaBeta(depth, TABLE_SIZE_MB, SEARCH_THREADS);
    try {
      return engine.execute(board, depth);
    } finally {
      engine.shutdown();
    }
  }

  /**
   * Renders the move the engine chose in long algebraic notation. The move is applied to the board
   * to name a promotion after the piece left on the destination square, and the board is restored
   * before this method returns.
   *
   * @param board The position the move was chosen in.
   * @param move The move the engine chose, which may be a null move.
   * @return The long algebraic notation of the move.
   */
  private static String describe(final Board board, final Move move) {
    if (move == null || !BoardUtils.isValidTileCoordinate(move.getCurrentCoordinate())) {
      return NULL_MOVE_NOTATION;
    }
    board.makeMove(move);
    try {
      return Perft.longAlgebraicNotation(move, board);
    } finally {
      board.unmakeMove();
    }
  }

  /**
   * Prints the usage text describing how the suite is run from the command line.
   */
  private static void printUsage() {
    System.out.println("""
            Usage:
              TacticalSuite [depth] [--workers=N] [--verbose]
              TacticalSuite --help                     print this message

            A depth argument overrides the depth recorded against every position. Positions are
            recorded at the shallowest depth that keeps the tactic inside the search horizon, so
            overriding downwards is expected to fail some of them.

            The workers flag sets how many positions are searched at once, four by default. Every
            worker holds a transposition table for as long as its position is running, so raising
            it raises peak memory in proportion. A verbose run uses one worker whatever is asked
            for, so that the engine's own output stays readable.

            The suite exits with a non-zero status when any position is failed.""");
  }

  /**
   * The PositionOutcome record pairs the result of a position's search with the report to print
   * for it.
   *
   * @param solved Whether the engine chose a move the position accepts.
   * @param report The text describing the position and the move that was chosen.
   */
  private record PositionOutcome(boolean solved, String report) { }

  /**
   * The TacticalPosition record pairs a position with the moves that solve it and the depth to
   * search it to. More than one move is accepted only where a position genuinely has more than one
   * winning move.
   *
   * @param name The tactic the position illustrates.
   * @param fen The Forsyth-Edwards Notation string describing the position.
   * @param searchDepth The depth this position is searched to when no override is given.
   * @param acceptedMoves The winning moves, in long algebraic notation.
   */
  public record TacticalPosition(String name, String fen, int searchDepth, List<String> acceptedMoves) {

    /**
     * Constructs a position whose accepted moves are given one after another.
     *
     * @param name The tactic the position illustrates.
     * @param fen The Forsyth-Edwards Notation string describing the position.
     * @param searchDepth The depth this position is searched to when no override is given.
     * @param acceptedMoves The winning moves, in long algebraic notation.
     */
    public TacticalPosition(final String name, final String fen, final int searchDepth,
                            final String... acceptedMoves) {
      this(name, fen, searchDepth, List.of(acceptedMoves));
    }

    /**
     * Reports whether the given move solves this position.
     *
     * @param moveNotation The long algebraic notation of the move the engine chose.
     * @return True if the move is one this position accepts, false otherwise.
     */
    public boolean accepts(final String moveNotation) {
      return this.acceptedMoves.contains(moveNotation);
    }
  }
}