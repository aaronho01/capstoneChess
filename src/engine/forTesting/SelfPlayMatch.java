package engine.forTesting;

import engine.forBoard.Board;
import engine.forBoard.Move;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The SelfPlayMatch class plays a match between two chess engines running as separate processes
 * and reports the result. It is the arbiter as well as the runner: it holds the only board that
 * counts, decides the outcome, and accepts a move only after resolving it against its own legal
 * moves, so an engine cannot end a game by claiming one.
 * <p>
 * Every opening is played twice, once with each engine as White, which is what a pair is. Both
 * engines are started once for the whole match and reused, and a new game is started on each of
 * them before every game, so the transposition table and the heuristic tables an engine holds
 * never carry from one game into the next.
 * <p>
 * Openings are drawn from a shuffle of the book seeded from the command line, since the book is
 * grouped by opening classification and taking its lines in order would play a match out of one
 * corner of it. The same seed draws the same openings, so a match can be repeated.
 * <p>
 * Both engines are told the position as the standard starting position followed by every move
 * played so far, including the moves of the opening, rather than as a position in
 * Forsyth-Edwards Notation. Replaying the whole game is what gives each engine the repetition
 * history a game actually has, which its own draw detection depends on.
 * <p>
 * Each engine searches under its own node limit, which the command line sets for both engines at
 * once or for one engine at a time.
 * <p>
 * Search threads are fixed at one for both engines. A node limit is only a meaningful measure of
 * equal effort while one thread is searching, since helper threads contribute nodes that the limit
 * does not account for.
 * <p>
 * A game that the engines agree is decided, or agree is level, is adjudicated rather than played
 * out, on the rules {@link Adjudicator} holds. A position that has already ended is judged on the
 * board before it is judged on the scores, so a checkmate is a checkmate rather than an
 * adjudicated win.
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

  /** The score a side must be ahead by for a ply to count towards a win, by default. */
  private static final int DEFAULT_RESIGN_SCORE = 800;

  /** The number of consecutive plies at the resign score that win a game, by default. */
  private static final int DEFAULT_RESIGN_PLIES = 8;

  /** The score a position must be within for a ply to count towards a draw, by default. */
  private static final int DEFAULT_DRAW_SCORE = 10;

  /** The number of consecutive plies within the draw score that draw a game, by default. */
  private static final int DEFAULT_DRAW_PLIES = 8;

  /** The ply a game must have reached before it may be drawn on score, by default. */
  private static final int DEFAULT_DRAW_AFTER = 80;

  /** The time an engine is allowed to take to answer, in seconds, when no other time is given. */
  private static final long DEFAULT_TIMEOUT_SECONDS = 60;

  /** The directory the engine logs are written to when no other directory is given. */
  private static final String DEFAULT_LOG_DIRECTORY = "out/match-logs";

  /** The number of openings played when no other number is given. */
  private static final int DEFAULT_PAIRS = 1;

  /** The seed the book shuffle uses when no other seed is given. */
  private static final long DEFAULT_SEED = 1;

  /** The number of games played from each opening, one with each engine as White. */
  private static final int GAMES_PER_PAIR = 2;

  /** The search thread count both engines are held to. */
  private static final int SEARCH_THREADS = 1;

  /** The long algebraic notation an engine reports when it has no move to make. */
  private static final String NULL_MOVE_NOTATION = "0000";

  /** The name the first engine is reported and logged under. */
  private static final String ENGINE_A_NAME = "A";

  /** The name the second engine is reported and logged under. */
  private static final String ENGINE_B_NAME = "B";

  /** The exit status used when the match stopped in a failure rather than reaching its end. */
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
   * Plays a match from the command line and reports the result. The exit status is zero if every
   * game reached a result, one if the match stopped on a failure, and two if the command line or
   * the opening book could not be read.
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

    final List<BookLine> lines;
    try {
      lines = selectLines(settings);
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

    printHeader(settings, lines);
    final Tally tally = runMatch(settings, lines);
    printTally(tally, lines.size());
    if (tally.failure() != null) {
      System.out.println();
      System.out.println("The match stopped: " + tally.failure());
      System.exit(FAILURE_STATUS);
    }
  }

  /**
   * Plays every pair of the match on one pair of engine processes, reporting each game as it ends.
   * The match stops at the first failure, and the games played before it are still counted.
   *
   * @param settings The settings the match is played under.
   * @param lines The openings the match is played from, each of them played twice.
   * @return What the games came to and what stopped the match, if anything did.
   */
  private static Tally runMatch(final Settings settings, final List<BookLine> lines) {
    final List<String> commandA;
    final List<String> commandB;
    try {
      commandA = EngineProcess.tokenize(settings.engineACommand());
      commandB = EngineProcess.tokenize(settings.engineBCommand());
    } catch (final IllegalArgumentException exception) {
      return new Tally(0, 0, 0, 0, 0, exception.getMessage());
    }

    final Path logDirectory = Path.of(settings.logDirectory());
    final long timeoutMillis = settings.timeoutSeconds() * 1_000L;
    int engineAWins = 0;
    int engineBWins = 0;
    int draws = 0;
    int played = 0;
    int adjudicated = 0;

    try (EngineProcess engineA = new EngineProcess(ENGINE_A_NAME, commandA,
            logDirectory.resolve("engine-a.log"), timeoutMillis);
         EngineProcess engineB = new EngineProcess(ENGINE_B_NAME, commandB,
                 logDirectory.resolve("engine-b.log"), timeoutMillis)) {
      prepare(engineA, settings);
      prepare(engineB, settings);

      for (int pair = 0; pair < lines.size(); pair++) {
        final BookLine line = lines.get(pair);
        printPair(pair + 1, line);
        for (int game = 0; game < GAMES_PER_PAIR; game++) {
          final boolean engineAIsWhite = game == 0;
          final EngineProcess white = engineAIsWhite ? engineA : engineB;
          final EngineProcess black = engineAIsWhite ? engineB : engineA;
          final long whiteNodeLimit =
                  engineAIsWhite ? settings.nodeLimitA() : settings.nodeLimitB();
          final long blackNodeLimit =
                  engineAIsWhite ? settings.nodeLimitB() : settings.nodeLimitA();
          final Result result = play(settings, line.opening(), white, black, whiteNodeLimit,
                  blackNodeLimit);
          printGame(game + 1, engineAIsWhite, result, settings.verbose());
          if (result.outcome() == Outcome.FAILURE) {
            return new Tally(engineAWins, engineBWins, draws, played, adjudicated,
                    result.reason());
          }
          played++;
          if (result.adjudicated()) {
            adjudicated++;
          }
          if (result.outcome() == Outcome.DRAW) {
            draws++;
          } else if ((result.outcome() == Outcome.WHITE_WINS) == engineAIsWhite) {
            engineAWins++;
          } else {
            engineBWins++;
          }
        }
      }
    } catch (final EngineProcess.Fault fault) {
      return new Tally(engineAWins, engineBWins, draws, played, adjudicated, fault.getMessage());
    }
    return new Tally(engineAWins, engineBWins, draws, played, adjudicated, null);
  }

  /**
   * Plays one game between two engine processes, starting a new game on each of them first.
   *
   * @param settings The settings the game is played under.
   * @param opening The opening the game starts from, or null to start from the standard starting
   *                position.
   * @param white The engine playing White.
   * @param black The engine playing Black.
   * @param whiteNodeLimit The largest number of nodes a search by White may visit for one move.
   * @param blackNodeLimit The largest number of nodes a search by Black may visit for one move.
   * @return The outcome of the game, why it ended, and the moves it held.
   */
  public static Result play(final Settings settings, final OpeningBook.Opening opening,
                            final EngineProcess white, final EngineProcess black,
                            final long whiteNodeLimit, final long blackNodeLimit) {
    final List<String> moves = new ArrayList<>();
    final Board board = opening == null ? Board.createStandardBoard() : OpeningBook.play(opening);
    if (opening != null) {
      moves.addAll(opening.moves());
    }
    try {
      white.newGame();
      black.newGame();
      return playMoves(settings, board, moves, white, black, whiteNodeLimit, blackNodeLimit);
    } catch (final EngineProcess.Fault fault) {
      return new Result(Outcome.FAILURE, fault.getMessage(), moves);
    }
  }

  /**
   * Carries out the handshake with one engine and sets its options. The engine reads the options
   * when a new game is started, so this is done once for the match.
   *
   * @param engine The engine to prepare.
   * @param settings The settings the match is played under.
   * @throws EngineProcess.Fault If the engine does not answer.
   */
  private static void prepare(final EngineProcess engine, final Settings settings) {
    engine.handshake();
    engine.setOption("Hash", settings.tableSizeMB());
    engine.setOption("Threads", SEARCH_THREADS);
  }

  /**
   * Plays moves until the game reaches a result or fails.
   *
   * @param settings The settings the game is played under.
   * @param board The position the game is played on, which this method advances.
   * @param moves The moves played so far, which this method appends to.
   * @param white The engine playing White.
   * @param black The engine playing Black.
   * @param whiteNodeLimit The largest number of nodes a search by White may visit for one move.
   * @param blackNodeLimit The largest number of nodes a search by Black may visit for one move.
   * @return The outcome of the game, why it ended, and the moves it held.
   * @throws EngineProcess.Fault If an engine does not answer.
   */
  private static Result playMoves(final Settings settings, final Board board,
                                  final List<String> moves, final EngineProcess white,
                                  final EngineProcess black, final long whiteNodeLimit,
                                  final long blackNodeLimit) {
    final Adjudicator adjudicator = new Adjudicator(settings.adjudicate(),
            settings.resignScore(), settings.resignPlies(), settings.drawScore(),
            settings.drawPlies(), settings.drawAfter());
    Adjudicator.Verdict verdict = Adjudicator.Verdict.NONE;

    while (true) {
      final Result terminal = terminalResult(board, moves);
      if (terminal != null) {
        return terminal;
      }
      if (verdict != Adjudicator.Verdict.NONE) {
        return new Result(outcomeOf(verdict), verdict.getReason(), moves, true);
      }
      if (moves.size() >= settings.plyCap()) {
        return new Result(Outcome.DRAW, "the ply cap of " + settings.plyCap() + " was reached",
                moves);
      }

      final boolean whiteToMove = board.currentPlayer().getAlliance().isWhite();
      final EngineProcess mover = whiteToMove ? white : black;
      final String moverName = "the " + mover.getName() + " engine, playing " +
              (whiteToMove ? "White" : "Black") + ",";
      mover.setPosition(moves);
      final EngineProcess.Reply reply = mover.go(whiteToMove ? whiteNodeLimit : blackNodeLimit);
      if (NULL_MOVE_NOTATION.equals(reply.move())) {
        return new Result(Outcome.FAILURE, moverName + " reported no move in a position the " +
                "arbiter did not find terminal", moves);
      }
      final Move move = OpeningBook.resolve(board, reply.move());
      if (move == null) {
        return new Result(Outcome.FAILURE, moverName + " reported the illegal move " +
                reply.move(), moves);
      }
      board.makeMove(move);
      moves.add(reply.move());
      if (settings.verbose()) {
        printMove(moves.size(), whiteToMove, reply);
      }
      verdict = adjudicator.judge(reply, whiteToMove, moves.size());
    }
  }

  /**
   * Names the outcome a verdict awards.
   *
   * @param verdict What the reported scores settled.
   * @return The outcome of the game.
   * @throws IllegalArgumentException If the verdict settled nothing.
   */
  private static Outcome outcomeOf(final Adjudicator.Verdict verdict) {
    return switch (verdict) {
      case WHITE_WINS -> Outcome.WHITE_WINS;
      case BLACK_WINS -> Outcome.BLACK_WINS;
      case DRAW -> Outcome.DRAW;
      case NONE -> throw new IllegalArgumentException("A game that was not adjudicated has no " +
              "adjudicated outcome");
    };
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
   * Chooses the openings the match is played from. Without a book every pair starts from the
   * standard starting position, a named line is the only line played, and otherwise the lines are
   * drawn from a shuffle of the book seeded from the settings.
   *
   * @param settings The settings the match is played under.
   * @return The openings the match is played from, one for each pair.
   * @throws IOException If the book file cannot be read.
   * @throws IllegalArgumentException If the book holds no openings, holds no line at the named
   *                                  index, or holds fewer lines than the match asks for.
   */
  private static List<BookLine> selectLines(final Settings settings) throws IOException {
    final List<BookLine> lines = new ArrayList<>();
    if (!settings.useBook()) {
      for (int pair = 0; pair < settings.pairs(); pair++) {
        lines.add(new BookLine(-1, null));
      }
      return lines;
    }

    final List<OpeningBook.Opening> book = OpeningBook.load(Path.of(settings.bookPath()));
    if (book.isEmpty()) {
      throw new IllegalArgumentException("The book at " + settings.bookPath() +
              " holds no openings");
    }
    if (settings.openingIndex() >= 0) {
      if (settings.openingIndex() >= book.size()) {
        throw new IllegalArgumentException("The book holds " + book.size() +
                " openings, so there is none at index " + settings.openingIndex());
      }
      lines.add(new BookLine(settings.openingIndex(), book.get(settings.openingIndex())));
      return lines;
    }
    if (settings.pairs() > book.size()) {
      throw new IllegalArgumentException("The book holds " + book.size() + " openings, so " +
              settings.pairs() + " pairs cannot be played without repeating one");
    }

    final List<Integer> order = new ArrayList<>(book.size());
    for (int index = 0; index < book.size(); index++) {
      order.add(index);
    }
    Collections.shuffle(order, new Random(settings.seed()));
    for (int pair = 0; pair < settings.pairs(); pair++) {
      final int index = order.get(pair);
      lines.add(new BookLine(index, book.get(index)));
    }
    return lines;
  }

  /**
   * Reports the settings the match is played under.
   *
   * @param settings The settings the match is played under.
   * @param lines The openings the match is played from.
   */
  private static void printHeader(final Settings settings, final List<BookLine> lines) {
    System.out.println("Engine A: " + settings.engineACommand());
    System.out.println("Engine B: " + settings.engineBCommand());
    final String nodes = settings.nodeLimitA() == settings.nodeLimitB() ?
            String.valueOf(settings.nodeLimitA()) :
            settings.nodeLimitA() + " for " + ENGINE_A_NAME + " and " + settings.nodeLimitB() +
                    " for " + ENGINE_B_NAME;
    System.out.println("Nodes: " + nodes + " per move, hash " + settings.tableSizeMB() + " MB, " +
            SEARCH_THREADS + " search thread");
    if (!settings.useBook()) {
      System.out.println("Openings: the standard starting position");
    } else if (settings.openingIndex() >= 0) {
      System.out.println("Openings: line " + settings.openingIndex() + " of " +
              settings.bookPath());
    } else {
      System.out.println("Openings: drawn from " + settings.bookPath() + " with seed " +
              settings.seed());
    }
    System.out.println("Games: " + lines.size() * GAMES_PER_PAIR + " over " + lines.size() +
            " pairs, ply cap " + settings.plyCap() + ", timeout " + settings.timeoutSeconds() +
            "s");
    if (settings.adjudicate()) {
      System.out.println("Adjudication: a win at " + settings.resignScore() + " over " +
              settings.resignPlies() + " plies, a draw within " + settings.drawScore() + " over " +
              settings.drawPlies() + " plies from ply " + settings.drawAfter());
    } else {
      System.out.println("Adjudication: off");
    }
    System.out.println();
  }

  /**
   * Reports the opening a pair is about to be played from.
   *
   * @param pair The number of the pair, counting from one.
   * @param line The opening the pair is played from.
   */
  private static void printPair(final int pair, final BookLine line) {
    if (line.opening() == null) {
      System.out.println("Pair " + pair + ": the standard starting position");
      return;
    }
    System.out.println("Pair " + pair + ": line " + line.index() + ", " + line.opening().eco() +
            " " + line.opening().name() + ", " + String.join(" ", line.opening().moves()));
  }

  /**
   * Reports one game as it ends.
   *
   * @param game The number of the game within its pair, counting from one.
   * @param engineAIsWhite True if engine A played White.
   * @param result How the game ended.
   * @param verbose True to report the moves the game held.
   */
  private static void printGame(final int game, final boolean engineAIsWhite, final Result result,
                                final boolean verbose) {
    System.out.printf("  game %d  White %s  %-7s  %3d plies  %s%n", game,
            engineAIsWhite ? ENGINE_A_NAME : ENGINE_B_NAME, scoreOf(result.outcome()),
            result.moves().size(), result.reason());
    if (verbose) {
      System.out.println("  moves: " + String.join(" ", result.moves()));
    }
  }

  /**
   * Reports one move as it is played. The score column holds the score from White's point of
   * view, not from the point of view of the engine that reported it.
   *
   * @param ply The number of the ply just played, counting the moves of the opening.
   * @param whiteMoved True if White played the move.
   * @param reply What the engine reported.
   */
  private static void printMove(final int ply, final boolean whiteMoved,
                                final EngineProcess.Reply reply) {
    System.out.printf("%6d  %s  %-6s  %9s  %7.2fs%n", ply, whiteMoved ? "W" : "B", reply.move(),
            scoreText(reply, whiteMoved), reply.elapsedMillis() / 1000.0);
  }

  /**
   * Names what an engine reported about the position it moved from, written from White's point
   * of view. Engines report scores from the point of view of the side to move, so a score
   * reported with a Black move is negated here.
   *
   * @param reply What the engine reported.
   * @param whiteMoved True if White played the move the score was reported with.
   * @return The distance to mate, the score in centipawns, or a dash if the engine reported
   *         neither, each from White's point of view.
   */
  private static String scoreText(final EngineProcess.Reply reply, final boolean whiteMoved) {
    if (reply.mateIn() != null) {
      return "mate " + (whiteMoved ? reply.mateIn() : -reply.mateIn());
    }
    if (reply.score() != null) {
      return "cp " + (whiteMoved ? reply.score() : -reply.score());
    }
    return "-";
  }

  /**
   * Reports what the games of the match came to and the Elo difference they measure.
   *
   * @param tally What the games came to.
   * @param pairs The number of pairs the match was to hold.
   */
  private static void printTally(final Tally tally, final int pairs) {
    final double points = tally.engineAWins() + tally.draws() / 2.0;
    System.out.println();
    System.out.println("Played: " + tally.played() + " of " + pairs * GAMES_PER_PAIR + " games");
    System.out.println("A wins " + tally.engineAWins() + ", B wins " + tally.engineBWins() +
            ", drawn " + tally.draws());
    if (tally.adjudicated() > 0) {
      System.out.println("Adjudicated: " + tally.adjudicated() + " of " + tally.played() +
              " games");
    }
    if (tally.played() > 0) {
      System.out.printf("Score for A: %.1f of %d, %.2f percent%n", points, tally.played(),
              100.0 * points / tally.played());
      final MatchStatistics statistics = new MatchStatistics(tally.engineAWins(),
              tally.engineBWins(), tally.draws());
      for (final String line : statistics.report(ENGINE_A_NAME)) {
        System.out.println(line);
      }
    }
  }

  /**
   * Names an outcome as a score from White's point of view.
   *
   * @param outcome How a game ended.
   * @return The score the outcome is written as.
   */
  private static String scoreOf(final Outcome outcome) {
    return switch (outcome) {
      case WHITE_WINS -> "1-0";
      case BLACK_WINS -> "0-1";
      case DRAW -> "1/2-1/2";
      case FAILURE -> "failed";
    };
  }

  /**
   * Reports how this class is run.
   */
  private static void printUsage() {
    System.out.println("Usage: SelfPlayMatch --engine-a <command> --engine-b <command> [options]");
    System.out.println();
    System.out.println("  --engine-a <command> the command line starting the first engine");
    System.out.println("  --engine-b <command> the command line starting the second engine");
    System.out.println("  --pairs <count>      openings to play, each of them played twice with " +
            "the colours reversed, " + DEFAULT_PAIRS + " by default");
    System.out.println("  --seed <number>      the seed the book shuffle uses, " + DEFAULT_SEED +
            " by default");
    System.out.println("  --opening <index>    play only this book line, as one pair");
    System.out.println("  --nobook             play from the standard starting position");
    System.out.println("  --nodes <count>      nodes per move for both engines, " +
            DEFAULT_NODE_LIMIT + " by default");
    System.out.println("  --nodes-a <count>    nodes per move for the first engine, overriding " +
            "--nodes");
    System.out.println("  --nodes-b <count>    nodes per move for the second engine, overriding " +
            "--nodes");
    System.out.println("  --hash <megabytes>   transposition table size, " + DEFAULT_TABLE_SIZE_MB +
            " by default");
    System.out.println("  --book <path>        the opening book, " + OpeningBook.DEFAULT_BOOK_PATH +
            " by default");
    System.out.println("  --plycap <count>     the longest game allowed, " + DEFAULT_PLY_CAP +
            " by default");
    System.out.println("  --no-adjudication    play every game out on the board");
    System.out.println("  --resign-score <cp>  the lead a ply counts as decisive at, " +
            DEFAULT_RESIGN_SCORE + " by default");
    System.out.println("  --resign-plies <n>   decisive plies that win a game, " +
            DEFAULT_RESIGN_PLIES + " by default");
    System.out.println("  --draw-score <cp>    the score a ply counts as level within, " +
            DEFAULT_DRAW_SCORE + " by default");
    System.out.println("  --draw-plies <n>     level plies that draw a game, " +
            DEFAULT_DRAW_PLIES + " by default");
    System.out.println("  --draw-after <ply>   the ply a draw may first be given at, " +
            DEFAULT_DRAW_AFTER + " by default");
    System.out.println("  --timeout <seconds>  the longest wait for an answer, " +
            DEFAULT_TIMEOUT_SECONDS + " by default");
    System.out.println("  --logs <directory>   where the engine logs are written, " +
            DEFAULT_LOG_DIRECTORY + " by default");
    System.out.println("  --verbose            report every move and the moves of every game");
    System.out.println("  --help               print this message");
    System.out.println();
    System.out.println("Engine A plays White in the first game of every pair. An engine command");
    System.out.println("line is one argument. Whitespace inside it separates its arguments except");
    System.out.println("inside double quotes, so a path holding a space can be written as");
    System.out.println("\"--engine-a\" \"java -jar \"\"C:\\My Builds\\engine.jar\"\"\".");
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
   * @param adjudicated True if the game ended on the reported scores rather than on the board.
   */
  public record Result(Outcome outcome, String reason, List<String> moves, boolean adjudicated) {

    /**
     * Constructs a result holding a copy of the moves given.
     */
    public Result {
      moves = List.copyOf(moves);
    }

    /**
     * Constructs a result for a game that ended on the board.
     *
     * @param outcome How the game ended from White's point of view.
     * @param reason Why the game ended.
     * @param moves The moves the game held, including the moves of the opening.
     */
    public Result(final Outcome outcome, final String reason, final List<String> moves) {
      this(outcome, reason, moves, false);
    }
  }

  /**
   * The Tally record holds what the games of a match came to.
   *
   * @param engineAWins The games engine A won.
   * @param engineBWins The games engine B won.
   * @param draws The games neither engine won.
   * @param played The games that reached a result.
   * @param adjudicated The games that ended on the reported scores rather than on the board.
   * @param failure What stopped the match, or null if every game reached a result.
   */
  public record Tally(int engineAWins, int engineBWins, int draws, int played, int adjudicated,
                      String failure) {
  }

  /**
   * The BookLine record pairs an opening with the place it was read from in the book.
   *
   * @param index The position of the opening in the book, or minus one for the standard starting
   *              position.
   * @param opening The opening, or null for the standard starting position.
   */
  private record BookLine(int index, OpeningBook.Opening opening) {
  }

  /**
   * The Settings record holds what a match is played under.
   *
   * @param engineACommand The command line starting the first engine.
   * @param engineBCommand The command line starting the second engine.
   * @param nodeLimitA The largest number of nodes a search by the first engine may visit for one
   *                   move.
   * @param nodeLimitB The largest number of nodes a search by the second engine may visit for one
   *                   move.
   * @param tableSizeMB The transposition table size in megabytes given to each engine.
   * @param bookPath The path of the opening book.
   * @param pairs The number of openings played, each of them played twice.
   * @param seed The seed the book shuffle uses.
   * @param openingIndex The only book line to play, or minus one to draw lines from the shuffle.
   * @param useBook False to play every game from the standard starting position.
   * @param plyCap The largest number of plies a game may reach.
   * @param adjudicate True to end a game the engines agree is decided or is level.
   * @param resignScore The score a side must be ahead by for a ply to count towards a win.
   * @param resignPlies The number of consecutive plies at the resign score that win a game.
   * @param drawScore The score a position must be within for a ply to count towards a draw.
   * @param drawPlies The number of consecutive plies within the draw score that draw a game.
   * @param drawAfter The ply a game must have reached before it may be drawn on score.
   * @param timeoutSeconds The longest an engine may take to answer.
   * @param logDirectory The directory the engine logs are written to.
   * @param verbose True to report every move and the moves of every game.
   */
  public record Settings(String engineACommand, String engineBCommand, long nodeLimitA,
                         long nodeLimitB, int tableSizeMB, String bookPath, int pairs, long seed,
                         int openingIndex, boolean useBook, int plyCap, boolean adjudicate,
                         int resignScore, int resignPlies, int drawScore, int drawPlies,
                         int drawAfter, long timeoutSeconds, String logDirectory,
                         boolean verbose) {

    /**
     * Reads settings from command line arguments. The argument --nodes sets the node limit of both
     * engines, and --nodes-a and --nodes-b each set the limit of one engine and override --nodes
     * whatever order they are given in. Adjudication is on unless --no-adjudication is given.
     *
     * @param args The command line arguments, as described by the usage text.
     * @return The settings the arguments describe, or null if they asked for the usage text.
     * @throws IllegalArgumentException If an argument is unrecognised, names no value, holds a
     *                                  value that is not a number, if either engine command line
     *                                  is missing, if fewer than one pair is asked for, if
     *                                  arguments naming different openings are given together, if
     *                                  an adjudication value is out of range, or if an
     *                                  adjudication value is given with --no-adjudication.
     */
    public static Settings read(final String[] args) {
      String engineACommand = null;
      String engineBCommand = null;
      Long nodeLimitBoth = null;
      Long nodeLimitA = null;
      Long nodeLimitB = null;
      int tableSizeMB = DEFAULT_TABLE_SIZE_MB;
      String bookPath = OpeningBook.DEFAULT_BOOK_PATH;
      int pairs = DEFAULT_PAIRS;
      boolean pairsGiven = false;
      long seed = DEFAULT_SEED;
      int openingIndex = -1;
      boolean useBook = true;
      int plyCap = DEFAULT_PLY_CAP;
      boolean adjudicate = true;
      int resignScore = DEFAULT_RESIGN_SCORE;
      int resignPlies = DEFAULT_RESIGN_PLIES;
      int drawScore = DEFAULT_DRAW_SCORE;
      int drawPlies = DEFAULT_DRAW_PLIES;
      int drawAfter = DEFAULT_DRAW_AFTER;
      String tuningArgument = null;
      long timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
      String logDirectory = DEFAULT_LOG_DIRECTORY;
      boolean verbose = false;

      for (int index = 0; index < args.length; index++) {
        final String argument = args[index];
        if ("--help".equals(argument)) {
          return null;
        }
        if ("--nobook".equals(argument)) {
          useBook = false;
          continue;
        }
        if ("--no-adjudication".equals(argument)) {
          adjudicate = false;
          continue;
        }
        if ("--verbose".equals(argument)) {
          verbose = true;
          continue;
        }
        final String value = valueOf(args, index, argument);
        switch (argument) {
          case "--engine-a" -> engineACommand = value;
          case "--engine-b" -> engineBCommand = value;
          case "--nodes" -> nodeLimitBoth = number(value, argument);
          case "--nodes-a" -> nodeLimitA = number(value, argument);
          case "--nodes-b" -> nodeLimitB = number(value, argument);
          case "--hash" -> tableSizeMB = (int) number(value, argument);
          case "--book" -> bookPath = value;
          case "--pairs" -> {
            pairs = (int) number(value, argument);
            pairsGiven = true;
          }
          case "--seed" -> seed = number(value, argument);
          case "--opening" -> openingIndex = (int) number(value, argument);
          case "--plycap" -> plyCap = (int) number(value, argument);
          case "--resign-score" -> {
            resignScore = (int) number(value, argument);
            tuningArgument = argument;
          }
          case "--resign-plies" -> {
            resignPlies = (int) number(value, argument);
            tuningArgument = argument;
          }
          case "--draw-score" -> {
            drawScore = (int) number(value, argument);
            tuningArgument = argument;
          }
          case "--draw-plies" -> {
            drawPlies = (int) number(value, argument);
            tuningArgument = argument;
          }
          case "--draw-after" -> {
            drawAfter = (int) number(value, argument);
            tuningArgument = argument;
          }
          case "--timeout" -> timeoutSeconds = number(value, argument);
          case "--logs" -> logDirectory = value;
          default -> throw new IllegalArgumentException("Unrecognised argument: " + argument);
        }
        index++;
      }

      if (engineACommand == null || engineBCommand == null) {
        throw new IllegalArgumentException("Both --engine-a and --engine-b must name a command " +
                "line");
      }
      if (pairs < 1) {
        throw new IllegalArgumentException("The value of --pairs must be at least one");
      }
      if (openingIndex >= 0) {
        if (!useBook) {
          throw new IllegalArgumentException("The argument --opening names a book line, so it " +
                  "cannot be given with --nobook");
        }
        if (pairsGiven && pairs != 1) {
          throw new IllegalArgumentException("The argument --opening names the only line to " +
                  "play, so it cannot be given with --pairs " + pairs);
        }
        pairs = 1;
      }
      if (!adjudicate && tuningArgument != null) {
        throw new IllegalArgumentException("The argument " + tuningArgument + " tunes " +
                "adjudication, so it cannot be given with --no-adjudication");
      }
      if (resignScore < 0 || drawScore < 0 || drawAfter < 0) {
        throw new IllegalArgumentException("The values of --resign-score, --draw-score and " +
                "--draw-after cannot be negative");
      }
      if (resignPlies < 1 || drawPlies < 1) {
        throw new IllegalArgumentException("The values of --resign-plies and --draw-plies must " +
                "be at least one");
      }
      final long shared = nodeLimitBoth == null ? DEFAULT_NODE_LIMIT : nodeLimitBoth;
      final long limitA = nodeLimitA == null ? shared : nodeLimitA;
      final long limitB = nodeLimitB == null ? shared : nodeLimitB;
      return new Settings(engineACommand, engineBCommand, limitA, limitB, tableSizeMB, bookPath,
              pairs, seed, openingIndex, useBook, plyCap, adjudicate, resignScore, resignPlies,
              drawScore, drawPlies, drawAfter, timeoutSeconds, logDirectory, verbose);
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