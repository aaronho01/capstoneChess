package engine.forTesting;

import engine.forBoard.Board;
import engine.forBoard.Move;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The SelfPlayMatch class plays one game between two chess engines running as separate processes
 * and reports the result. It is the arbiter as well as the runner: it holds the only board that
 * counts, decides the outcome, and accepts a move only after resolving it against its own legal
 * moves, so an engine cannot end a game by claiming one.
 * <p>
 * Both engines are told the position as the standard starting position followed by every move
 * played so far, including the moves of the opening, rather than as a position in
 * Forsyth-Edwards Notation. Replaying the whole game is what gives each engine the repetition
 * history a game actually has, which its own draw detection depends on.
 * <p>
 * Search threads are fixed at one for both engines. A node limit is only a meaningful measure of
 * equal effort while one thread is searching, since helper threads contribute nodes that the limit
 * does not account for.
 * <p>
 * The arbiter uses the move generation of the build it was compiled from, not that of either
 * engine, so a move generation fault shared by both engines would be invisible here. That is what
 * {@link PerftSuite} covers.
 *
 * @author Aaron Ho
 */
public class SelfPlayMatch {

  /** The node limit used for each move when no other limit is given. */
  private static final long DEFAULT_NODE_LIMIT = 100_000;

  /** The transposition table size in megabytes given to each engine when no other size is given. */
  private static final int DEFAULT_TABLE_SIZE_MB = 64;

  /** The largest number of plies a game may reach before it is stopped, when no other cap is given. */
  private static final int DEFAULT_PLY_CAP = 300;

  /** The time an engine is allowed to take to answer, in seconds, when no other time is given. */
  private static final long DEFAULT_TIMEOUT_SECONDS = 60;

  /** The directory the engine logs are written to when no other directory is given. */
  private static final String DEFAULT_LOG_DIRECTORY = "out/match-logs";

  /** The search thread count both engines are held to. */
  private static final int SEARCH_THREADS = 1;

  /** The long algebraic notation an engine reports when it has no move to make. */
  private static final String NULL_MOVE_NOTATION = "0000";

  /** The exit status used when the game ended in a failure rather than a result. */
  private static final int FAILURE_STATUS = 1;

  /** The exit status used when the command line could not be read. */
  private static final int USAGE_STATUS = 2;

  /**
   * Private constructor to prevent instantiation of this utility class.
   *
   * @throws RuntimeException Always thrown to prevent instantiation.
   */
  private SelfPlayMatch() {
    throw new RuntimeException("Not instantiatable!");
  }

  /**
   * Plays one game from the command line and reports the result. The exit status is zero if the
   * game reached a result, one if it ended in a failure, and two if the command line could not be
   * read.
   *
   * @param args The command line arguments, as described by the usage text.
   */
  public static void main(final String[] args) {
    final Settings settings;
    try {
      settings = Settings.read(args);
    } catch (final IllegalArgumentException exception) {
      System.out.println(exception.getMessage());
      printUsage();
      System.exit(USAGE_STATUS);
      return;
    }
    if (settings == null) {
      printUsage();
      return;
    }

    final OpeningBook.Opening opening;
    try {
      opening = openingOf(settings);
    } catch (final IOException exception) {
      System.out.println("The opening book at " + settings.bookPath() + " could not be read: " +
              exception.getMessage());
      System.exit(USAGE_STATUS);
      return;
    } catch (final IllegalArgumentException exception) {
      System.out.println(exception.getMessage());
      System.exit(USAGE_STATUS);
      return;
    }

    printHeader(settings, opening);
    final Result result = play(settings, opening);
    System.out.println();
    System.out.println("Result: " + result.outcome() + ", " + result.reason());
    System.out.println("Plies: " + result.moves().size());
    System.out.println("Moves: " + String.join(" ", result.moves()));
    if (result.outcome() == Outcome.FAILURE) {
      System.exit(FAILURE_STATUS);
    }
  }

  /**
   * Plays one game between two engine processes.
   *
   * @param settings The settings the game is played under.
   * @param opening The opening the game starts from, or null to start from the standard starting
   *                position.
   * @return The outcome of the game, why it ended, and the moves it held.
   */
  public static Result play(final Settings settings, final OpeningBook.Opening opening) {
    final Path logDirectory = Path.of(settings.logDirectory());
    final long timeoutMillis = settings.timeoutSeconds() * 1_000L;
    final List<String> moves = new ArrayList<>();
    final Board board = opening == null ? Board.createStandardBoard() : OpeningBook.play(opening);
    if (opening != null) {
      moves.addAll(opening.moves());
    }

    try (EngineProcess white = new EngineProcess("white",
            EngineProcess.tokenize(settings.whiteCommand()), logDirectory.resolve("white.log"),
            timeoutMillis);
         EngineProcess black = new EngineProcess("black",
                 EngineProcess.tokenize(settings.blackCommand()), logDirectory.resolve("black.log"),
                 timeoutMillis)) {
      prepare(white, settings);
      prepare(black, settings);
      return playMoves(settings, board, moves, white, black);
    } catch (final EngineProcess.Fault fault) {
      return new Result(Outcome.FAILURE, fault.getMessage(), moves);
    }
  }

  /**
   * Carries out the handshake with one engine, sets its options, and starts a new game on it.
   *
   * @param engine The engine to prepare.
   * @param settings The settings the game is played under.
   * @throws EngineProcess.Fault If the engine does not answer.
   */
  private static void prepare(final EngineProcess engine, final Settings settings) {
    engine.handshake();
    engine.setOption("Hash", settings.tableSizeMB());
    engine.setOption("Threads", SEARCH_THREADS);
    engine.newGame();
  }

  /**
   * Plays moves until the game reaches a result or fails, reporting each move as it is played.
   *
   * @param settings The settings the game is played under.
   * @param board The position the game is played on, which this method advances.
   * @param moves The moves played so far, which this method appends to.
   * @param white The engine playing White.
   * @param black The engine playing Black.
   * @return The outcome of the game, why it ended, and the moves it held.
   * @throws EngineProcess.Fault If an engine does not answer.
   */
  private static Result playMoves(final Settings settings, final Board board,
                                  final List<String> moves, final EngineProcess white,
                                  final EngineProcess black) {
    while (true) {
      final Result terminal = terminalResult(board, moves);
      if (terminal != null) {
        return terminal;
      }
      if (moves.size() >= settings.plyCap()) {
        return new Result(Outcome.DRAW, "the ply cap of " + settings.plyCap() + " was reached",
                moves);
      }

      final boolean whiteToMove = board.currentPlayer().getAlliance().isWhite();
      final EngineProcess mover = whiteToMove ? white : black;
      mover.setPosition(moves);
      final EngineProcess.Reply reply = mover.go(settings.nodeLimit());
      if (NULL_MOVE_NOTATION.equals(reply.move())) {
        return new Result(Outcome.FAILURE, "the " + (whiteToMove ? "white" : "black") +
                " engine reported no move in a position the arbiter did not find terminal", moves);
      }
      final Move move = OpeningBook.resolve(board, reply.move());
      if (move == null) {
        return new Result(Outcome.FAILURE, "the " + (whiteToMove ? "white" : "black") +
                " engine reported the illegal move " + reply.move(), moves);
      }
      board.makeMove(move);
      moves.add(reply.move());
      printMove(moves.size(), whiteToMove, reply);
    }
  }

  /**
   * Decides whether the game has ended in the given position. Conditions are checked in the order
   * checkmate, stalemate, insufficient material, the fifty move rule, then threefold repetition,
   * so that a mate delivered on the fiftieth move is a mate.
   *
   * @param board The position to judge.
   * @param moves The moves played so far.
   * @return The result of the game, or null if the game has not ended.
   */
  private static Result terminalResult(final Board board, final List<String> moves) {
    final boolean whiteToMove = board.currentPlayer().getAlliance().isWhite();
    if (board.currentPlayer().isInCheckMate()) {
      return new Result(whiteToMove ? Outcome.BLACK_WINS : Outcome.WHITE_WINS, "checkmate", moves);
    }
    if (board.currentPlayer().isInStaleMate()) {
      return new Result(Outcome.DRAW, "stalemate", moves);
    }
    if (board.isInsufficientMaterial()) {
      return new Result(Outcome.DRAW, "insufficient material", moves);
    }
    if (board.isFiftyMoveRule()) {
      return new Result(Outcome.DRAW, "the fifty move rule", moves);
    }
    if (board.isThreefoldRepetition()) {
      return new Result(Outcome.DRAW, "threefold repetition", moves);
    }
    return null;
  }

  /**
   * Reads the opening the settings name.
   *
   * @param settings The settings the game is played under.
   * @return The opening the game starts from, or null if the settings name none.
   * @throws IOException If the book file cannot be read.
   * @throws IllegalArgumentException If the book holds no opening at the given index.
   */
  private static OpeningBook.Opening openingOf(final Settings settings) throws IOException {
    if (settings.openingIndex() < 0) {
      return null;
    }
    final List<OpeningBook.Opening> openings = OpeningBook.load(Path.of(settings.bookPath()));
    if (settings.openingIndex() >= openings.size()) {
      throw new IllegalArgumentException("The book holds " + openings.size() +
              " openings, so there is none at index " + settings.openingIndex());
    }
    return openings.get(settings.openingIndex());
  }

  /**
   * Reports the settings the game is played under.
   *
   * @param settings The settings the game is played under.
   * @param opening The opening the game starts from, or null if it starts from the standard
   *                starting position.
   */
  private static void printHeader(final Settings settings, final OpeningBook.Opening opening) {
    System.out.println("White: " + settings.whiteCommand());
    System.out.println("Black: " + settings.blackCommand());
    System.out.println("Nodes: " + settings.nodeLimit() + " per move, hash " +
            settings.tableSizeMB() + " MB, " + SEARCH_THREADS + " search thread");
    if (opening == null) {
      System.out.println("Opening: the standard starting position");
    } else {
      System.out.println("Opening: " + settings.openingIndex() + ", " + opening.eco() + " " +
              opening.name() + ", " + String.join(" ", opening.moves()));
    }
    System.out.println();
  }

  /**
   * Reports one move as it is played.
   *
   * @param ply The number of the ply just played, counting the moves of the opening.
   * @param whiteMoved True if White played the move.
   * @param reply What the engine reported.
   */
  private static void printMove(final int ply, final boolean whiteMoved,
                                final EngineProcess.Reply reply) {
    System.out.printf("%4d  %s  %-6s  %9s  %7.2fs%n", ply, whiteMoved ? "W" : "B", reply.move(),
            reply.score() == null ? "-" : "cp " + reply.score(), reply.elapsedMillis() / 1000.0);
  }

  /**
   * Reports how this class is run.
   */
  private static void printUsage() {
    System.out.println("Usage: SelfPlayMatch --white <command> --black <command> [options]");
    System.out.println();
    System.out.println("  --white <command>   the command line starting the engine playing White");
    System.out.println("  --black <command>   the command line starting the engine playing Black");
    System.out.println("  --nodes <count>     nodes per move, " + DEFAULT_NODE_LIMIT +
            " by default");
    System.out.println("  --hash <megabytes>  transposition table size, " + DEFAULT_TABLE_SIZE_MB +
            " by default");
    System.out.println("  --book <path>       the opening book, " + OpeningBook.DEFAULT_BOOK_PATH +
            " by default");
    System.out.println("  --opening <index>   the book line to start from, the standard starting " +
            "position by default");
    System.out.println("  --plycap <count>    the longest game allowed, " + DEFAULT_PLY_CAP +
            " by default");
    System.out.println("  --timeout <seconds> the longest wait for an answer, " +
            DEFAULT_TIMEOUT_SECONDS + " by default");
    System.out.println("  --logs <directory>  where the engine logs are written, " +
            DEFAULT_LOG_DIRECTORY + " by default");
    System.out.println("  --help              print this message");
    System.out.println();
    System.out.println("An engine command line is one argument. Whitespace inside it separates");
    System.out.println("its arguments except inside double quotes, so a path holding a space can");
    System.out.println("be written as \"--white\" \"java -jar \"\"C:\\My Builds\\engine.jar\"\"\".");
  }

  /**
   * The Outcome enum names how a game ended from White's point of view.
   */
  public enum Outcome {

    /** White won. */
    WHITE_WINS,

    /** Black won. */
    BLACK_WINS,

    /** Neither side won. */
    DRAW,

    /** An engine failed, so the game has no result. */
    FAILURE
  }

  /**
   * The Result record holds how one game ended.
   *
   * @param outcome How the game ended from White's point of view.
   * @param reason Why the game ended.
   * @param moves The moves the game held, including the moves of the opening.
   */
  public record Result(Outcome outcome, String reason, List<String> moves) {

    /**
     * Constructs a result holding a copy of the moves given.
     */
    public Result {
      moves = List.copyOf(moves);
    }
  }

  /**
   * The Settings record holds what one game is played under.
   *
   * @param whiteCommand The command line starting the engine playing White.
   * @param blackCommand The command line starting the engine playing Black.
   * @param nodeLimit The largest number of nodes a search may visit for one move.
   * @param tableSizeMB The transposition table size in megabytes given to each engine.
   * @param bookPath The path of the opening book.
   * @param openingIndex The book line the game starts from, or minus one to start from the
   *                     standard starting position.
   * @param plyCap The largest number of plies the game may reach.
   * @param timeoutSeconds The longest an engine may take to answer.
   * @param logDirectory The directory the engine logs are written to.
   */
  public record Settings(String whiteCommand, String blackCommand, long nodeLimit, int tableSizeMB,
                         String bookPath, int openingIndex, int plyCap, long timeoutSeconds,
                         String logDirectory) {

    /**
     * Reads settings from command line arguments.
     *
     * @param args The command line arguments, as described by the usage text.
     * @return The settings the arguments describe, or null if they asked for the usage text.
     * @throws IllegalArgumentException If an argument is unrecognised, names no value, holds a
     *                                  value that is not a number, or if either engine command
     *                                  line is missing.
     */
    public static Settings read(final String[] args) {
      String whiteCommand = null;
      String blackCommand = null;
      long nodeLimit = DEFAULT_NODE_LIMIT;
      int tableSizeMB = DEFAULT_TABLE_SIZE_MB;
      String bookPath = OpeningBook.DEFAULT_BOOK_PATH;
      int openingIndex = -1;
      int plyCap = DEFAULT_PLY_CAP;
      long timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
      String logDirectory = DEFAULT_LOG_DIRECTORY;

      for (int index = 0; index < args.length; index++) {
        final String argument = args[index];
        if ("--help".equals(argument)) {
          return null;
        }
        final String value = valueOf(args, index, argument);
        switch (argument) {
          case "--white" -> whiteCommand = value;
          case "--black" -> blackCommand = value;
          case "--nodes" -> nodeLimit = number(value, argument);
          case "--hash" -> tableSizeMB = (int) number(value, argument);
          case "--book" -> bookPath = value;
          case "--opening" -> openingIndex = (int) number(value, argument);
          case "--plycap" -> plyCap = (int) number(value, argument);
          case "--timeout" -> timeoutSeconds = number(value, argument);
          case "--logs" -> logDirectory = value;
          default -> throw new IllegalArgumentException("Unrecognised argument: " + argument);
        }
        index++;
      }
      if (whiteCommand == null || blackCommand == null) {
        throw new IllegalArgumentException("Both --white and --black must name a command line");
      }
      return new Settings(whiteCommand, blackCommand, nodeLimit, tableSizeMB, bookPath,
              openingIndex, plyCap, timeoutSeconds, logDirectory);
    }

    /**
     * Reads the value following an argument.
     *
     * @param args The command line arguments.
     * @param index The position of the argument the value follows.
     * @param argument The argument the value follows, used in the fault message.
     * @return The value following the argument.
     * @throws IllegalArgumentException If the argument is the last one.
     */
    private static String valueOf(final String[] args, final int index, final String argument) {
      if (index + 1 >= args.length) {
        throw new IllegalArgumentException("The argument " + argument + " names no value");
      }
      return args[index + 1];
    }

    /**
     * Reads a whole number from a command line value.
     *
     * @param value The value to read.
     * @param argument The argument the value follows, used in the fault message.
     * @return The number the value holds.
     * @throws IllegalArgumentException If the value is not a whole number.
     */
    private static long number(final String value, final String argument) {
      try {
        return Long.parseLong(value);
      } catch (final NumberFormatException exception) {
        throw new IllegalArgumentException("The value of " + argument + " is not a number: " +
                value);
      }
    }
  }
}