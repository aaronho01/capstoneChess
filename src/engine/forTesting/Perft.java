package engine.forTesting;

import engine.forBoard.Board;
import engine.forBoard.BoardUtils;
import engine.forBoard.Move;
import engine.forBoard.MoveTransition;
import engine.forBoard.ZobristHashing;
import engine.forPiece.Piece;

import java.util.Map;
import java.util.TreeMap;

/**
 * The Perft class counts the leaf nodes reachable from a position within a fixed number of plies.
 * A performance test, universally abbreviated to perft, walks the full legal move tree to a given
 * depth and counts only the positions at that depth. Comparing those counts against published
 * reference values proves whether move generation, move execution, and the special rules for
 * castling, en passant, and promotion are implemented correctly, since a single missing or spurious
 * move changes the count in a way that no amount of testing by inspection reliably reveals.
 * <p>
 * The class also provides a divide operation, which reports the node count contributed by each move
 * available at the root. Divide is the standard debugging tool for a failing perft: comparing the
 * per move subtotals against a reference engine identifies which branch contains the fault, and
 * repeating the process inside that branch narrows the fault to a single position. A Zobrist
 * consistency walk is provided alongside these, since the incrementally updated hash carried by each
 * board can be checked against a hash recalculated from scratch at every node of the same tree.
 * <p>
 * {@link #perftMakeUnmake(Board, int)} walks the same tree through {@link Board#makeMove(Move)} and
 * {@link Board#unmakeMove()} on a single mutated board rather than building a new board per move.
 * It exists to validate that mutating path: running it alongside {@link #perft(Board, int)} on the
 * same position and depth, and any difference in node count means the mutating path is leaving the
 * board in a different state than the immutable path would have produced.
 * This class is designed as a non-instantiable utility class with static methods.
 *
 * @author Aaron Ho
 */
public class Perft {

  /** The long algebraic notation used for a move that has no origin square, matching the null move convention. */
  private static final String NULL_MOVE_NOTATION = "0000";

  /**
   * Private constructor to prevent instantiation of this utility class.
   *
   * @throws RuntimeException Always thrown to prevent instantiation.
   */
  private Perft() {
    throw new RuntimeException("Not instantiatable!");
  }

  /**
   * Counts the leaf nodes reachable from the given position at exactly the given depth.
   * Positions at intermediate depths are not counted, and terminal positions reached before the
   * requested depth contribute nothing, which matches the convention used by published reference
   * counts. A depth of zero counts the given position itself as a single node.
   * <p>
   * Legality is established by executing each generated move and keeping only those transitions
   * that complete, because the engine generates moves that may leave the moving player in check.
   *
   * @param board The position from which to count.
   * @param depth The number of plies to walk before counting.
   * @return The number of leaf nodes at the requested depth.
   */
  public static long perft(final Board board, final int depth) {
    if (depth <= 0) {
      return 1L;
    }
    long nodes = 0L;
    for (final Move move : board.currentPlayer().getLegalMoves()) {
      final MoveTransition transition = board.currentPlayer().makeMove(move);
      if (!transition.moveStatus().isDone()) {
        continue;
      }
      nodes += depth == 1 ? 1L : perft(transition.toBoard(), depth - 1);
    }
    return nodes;
  }

  /**
   * Counts the leaf nodes reachable from the given position at exactly the given depth, the same
   * as {@link #perft(Board, int)}, but by mutating a single board in place through
   * {@link Board#makeMove(Move)} and {@link Board#unmakeMove()} instead of building a new board
   * for every move. A depth of zero counts the given position itself as a single node.
   * <p>
   * Legality is established the same way {@link BoardUtils#kingThreat(Move)} establishes it
   * elsewhere in this engine: a move is applied, and then rejected if it leaves the player who
   * just moved in check. The given board is restored to its original state before this method
   * returns, whether it completes normally or throws, since the unmake happens in a finally block.
   *
   * @param board The position from which to count. Mutated during the walk and restored before
   *              this method returns.
   * @param depth The number of plies to walk before counting.
   * @return The number of leaf nodes at the requested depth.
   */
  public static long perftMakeUnmake(final Board board, final int depth) {
    if (depth <= 0) {
      return 1L;
    }
    long nodes = 0L;
    for (final Move move : board.currentPlayer().getLegalMoves()) {
      board.makeMove(move);
      try {
        if (!board.currentPlayer().getOpponent().isInCheck()) {
          nodes += depth == 1 ? 1L : perftMakeUnmake(board, depth - 1);
        }
      } finally {
        board.unmakeMove();
      }
    }
    return nodes;
  }

  /**
   * Counts the leaf nodes contributed by each legal move available in the given position.
   * The subtotals sum to the perft value of the same position at the same depth, so comparing
   * this breakdown against a reference engine identifies the branch responsible for a discrepancy.
   * Moves are keyed by long algebraic notation and ordered by that notation for readable output.
   *
   * @param board The position whose root moves are to be broken down.
   * @param depth The number of plies to walk before counting, which must be at least one.
   * @return An ordered map of long algebraic notation to the node count contributed by that move.
   */
  public static Map<String, Long> divide(final Board board, final int depth) {
    final Map<String, Long> subtotals = new TreeMap<>();
    if (depth <= 0) {
      return subtotals;
    }
    for (final Move move : board.currentPlayer().getLegalMoves()) {
      final MoveTransition transition = board.currentPlayer().makeMove(move);
      if (!transition.moveStatus().isDone()) {
        continue;
      }
      final long nodes = depth == 1 ? 1L : perft(transition.toBoard(), depth - 1);
      subtotals.merge(longAlgebraicNotation(move, transition.toBoard()), nodes, Long::sum);
    }
    return subtotals;
  }

  /**
   * Counts the legal moves available in the given position, which is the perft value at depth one.
   * This is provided separately because a wrong move count in the position under examination is the
   * most common cause of a failing perft, and reading it directly avoids a recursive walk.
   *
   * @param board The position whose legal moves are to be counted.
   * @return The number of legal moves available to the player about to move.
   */
  public static int countLegalMoves(final Board board) {
    int legalMoves = 0;
    for (final Move move : board.currentPlayer().getLegalMoves()) {
      if (board.currentPlayer().makeMove(move).moveStatus().isDone()) {
        legalMoves++;
      }
    }
    return legalMoves;
  }

  /**
   * Walks the legal move tree and returns the first position whose incrementally updated Zobrist
   * hash disagrees with a hash recalculated from scratch. The two values must agree at every node,
   * because the transposition table treats equal hashes as equal positions, and an incremental
   * update that drifts from the true hash silently corrupts every table lookup that follows.
   *
   * @param board The position from which to begin the walk.
   * @param depth The number of plies to walk.
   * @return The first position whose hash is inconsistent, or null if every position agrees.
   */
  public static Board findZobristDivergence(final Board board, final int depth) {
    if (board.getZobristHash() != ZobristHashing.calculateBoardHash(board)) {
      return board;
    }
    if (depth <= 0) {
      return null;
    }
    for (final Move move : board.currentPlayer().getLegalMoves()) {
      final MoveTransition transition = board.currentPlayer().makeMove(move);
      if (!transition.moveStatus().isDone()) {
        continue;
      }
      final Board divergentBoard = findZobristDivergence(transition.toBoard(), depth - 1);
      if (divergentBoard != null) {
        return divergentBoard;
      }
    }
    return null;
  }

  /**
   * Renders a move in the long algebraic notation used by reference engines and divide output,
   * consisting of the origin square, the destination square, and for a promotion the lowercase
   * letter of the promoted piece. The promoted piece is read from the resulting board rather than
   * from the move itself, so that every promotion variant produces a distinct notation.
   *
   * @param move The move to render.
   * @param resultingBoard The board produced by executing the move.
   * @return The long algebraic notation of the move.
   */
  public static String longAlgebraicNotation(final Move move, final Board resultingBoard) {
    final int origin = move.getCurrentCoordinate();
    if (!BoardUtils.isValidTileCoordinate(origin)) {
      return NULL_MOVE_NOTATION;
    }
    final int destination = move.getDestinationCoordinate();
    final String notation = BoardUtils.getPositionAtCoordinate(origin) +
            BoardUtils.getPositionAtCoordinate(destination);
    final Piece placedPiece = resultingBoard.getPiece(destination);
    if (move.getMovedPiece().getPieceType() == Piece.PieceType.PAWN &&
            placedPiece != null && placedPiece.getPieceType() != Piece.PieceType.PAWN) {
      return notation + placedPiece.getPieceType().toString().toLowerCase();
    }
    return notation;
  }
}