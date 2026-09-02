package engine.forPlayer.forAI;

import engine.Alliance;
import engine.forBoard.Board;
import engine.forBoard.Move;
import engine.forPiece.Piece;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The StaticExchangeEvaluator class implements Static Exchange Evaluation (SEE) for accurate assessment
 * of capture sequences in chess positions. SEE recursively evaluates the best possible outcome of a
 * capture sequence for each side, providing critical information for move ordering and pruning decisions
 * in search algorithms. This evaluation helps distinguish between profitable and unprofitable exchanges.
 * <p>
 * The class follows the singleton pattern to ensure consistent evaluation across the chess engine.
 * It uses simplified piece values optimized for exchange calculations and considers factors such as
 * piece defense and attack sequences to determine the material outcome of captures.
 * <p>
 * Both the attacker search and the defense test are answered through
 * {@link Piece#defendsSquare(int, Board)} rather than through generated move lists. Move generation
 * is pseudo-legal and never emits a move onto a square held by the moving piece's own alliance, so
 * a move list cannot report that a piece is defended, and disregarding occupancy is what an
 * exchange sequence requires.
 *
 * @author Aaron Ho
 */
public class StaticExchangeEvaluator {

  /** The singleton instance of the StaticExchangeEvaluator for global access. */
  private static final StaticExchangeEvaluator INSTANCE = new StaticExchangeEvaluator();

  /**
   * Simplified piece values used for SEE calculations, indexed by piece type ordinal.
   * Values are optimized for exchange evaluation rather than full position assessment.
   */
  private static final int[] SEE_PIECE_VALUES = {
          100,
          320,
          330,
          500,
          900,
          20000
  };

  /**
   * Constructs a new StaticExchangeEvaluator instance.
   * Private constructor enforces the singleton pattern.
   */
  private StaticExchangeEvaluator() {}

  /**
   * Returns the singleton instance of the StaticExchangeEvaluator.
   *
   * @return The singleton StaticExchangeEvaluator instance.
   */
  public static StaticExchangeEvaluator get() {
    return INSTANCE;
  }

  /**
   * Evaluates the material gain or loss from a capture move using static exchange evaluation.
   * The method simulates the optimal capture sequence for both sides and returns the expected
   * material outcome. Non-capture moves return a value of zero.
   *
   * @param board The current chess board state.
   * @param move The capture move to evaluate.
   * @return The estimated material gain or loss, positive values favor the side making the initial capture.
   */
  public int evaluate(final Board board, final Move move) {
    if (!move.isAttack()) {
      return 0;
    }

    final int targetSquare = move.getDestinationCoordinate();
    final Piece attackingPiece = move.getMovedPiece();
    final int attackerValue = getPieceValue(attackingPiece.getPieceType());
    final Piece capturedPiece = move.getAttackedPiece();
    final int capturedValue = getPieceValue(capturedPiece.getPieceType());

    if (move instanceof Move.PawnEnPassantAttack) {
      return SEE_PIECE_VALUES[0];
    }

    if (!isPieceDefended(capturedPiece, board)) {
      return capturedValue;
    }

    final Alliance opponentSide = board.currentPlayer().getOpponent().getAlliance();
    final List<Piece> attackers = findAttackers(board, targetSquare);

    int gain = capturedValue;

    if (attackers.isEmpty()) {
      return gain;
    }

    List<Piece> remainingAttackers = new ArrayList<>(attackers);
    remainingAttackers.removeIf(p -> p.equals(attackingPiece));

    Alliance side = opponentSide;
    int lastAttackerValue = attackerValue;

    while (!remainingAttackers.isEmpty()) {
      Piece nextAttacker = findLeastValuableAttacker(remainingAttackers, side);

      if (nextAttacker == null) {
        break;
      }

      gain = -gain + lastAttackerValue;

      if (gain < 0) {
        break;
      }

      lastAttackerValue = getPieceValue(nextAttacker.getPieceType());
      remainingAttackers.remove(nextAttacker);
      side = side.equals(Alliance.WHITE) ? Alliance.BLACK : Alliance.WHITE;
    }

    return gain;
  }

  /**
   * Determines whether a piece is defended by any other piece of the same alliance. A defender is
   * a friendly piece bearing on the piece's square, whether or not it could legally move there.
   *
   * @param piece The piece to check for defense.
   * @param board The current chess board state.
   * @return True if the piece is defended by a friendly piece, false otherwise.
   */
  public boolean isPieceDefended(final Piece piece, final Board board) {
    if (piece == null) {
      return false;
    }

    final int piecePosition = piece.getPiecePosition();
    final Collection<Piece> friendlyPieces = piece.getPieceAllegiance().isWhite() ?
            board.getWhitePieces() : board.getBlackPieces();

    for (final Piece otherPiece : friendlyPieces) {
      if (otherPiece.getPiecePosition() == piecePosition) {
        continue;
      }
      if (otherPiece.defendsSquare(piecePosition, board)) {
        return true;
      }
    }

    return false;
  }

  /**
   * Finds every piece on the board that bears on a square, of either alliance. Occupancy of the
   * square is disregarded, so the result holds both the pieces that could capture on the square
   * and the pieces defending whatever stands there.
   *
   * @param board The current chess board state.
   * @param targetSquare The square coordinate to check for attackers.
   * @return A list of pieces that bear on the target square.
   */
  private List<Piece> findAttackers(final Board board, final int targetSquare) {
    final List<Piece> attackers = new ArrayList<>();

    for (final Piece piece : board.getWhitePieces()) {
      if (piece.defendsSquare(targetSquare, board)) {
        attackers.add(piece);
      }
    }

    for (final Piece piece : board.getBlackPieces()) {
      if (piece.defendsSquare(targetSquare, board)) {
        attackers.add(piece);
      }
    }

    return attackers;
  }

  /**
   * Finds the least valuable attacking piece of a given alliance from the list of attackers.
   * This method implements the principle of using the lowest-value piece for exchanges.
   *
   * @param attackers The list of pieces that can attack the target square.
   * @param side The alliance of the attacking side.
   * @return The least valuable attacker of the specified side, or null if none found.
   */
  private Piece findLeastValuableAttacker(List<Piece> attackers, Alliance side) {
    Piece leastValuableAttacker = null;
    int leastValue = Integer.MAX_VALUE;

    for (Piece attacker : attackers) {
      if (attacker.getPieceAllegiance() == side) {
        int value = getPieceValue(attacker.getPieceType());
        if (value < leastValue) {
          leastValue = value;
          leastValuableAttacker = attacker;
        }
      }
    }

    return leastValuableAttacker;
  }

  /**
   * Returns the SEE-specific value for a given piece type.
   * These values are optimized for exchange evaluation calculations.
   *
   * @param pieceType The type of piece to evaluate.
   * @return The SEE value for the piece type.
   */
  private int getPieceValue(Piece.PieceType pieceType) {
    return switch (pieceType) {
      case PAWN -> SEE_PIECE_VALUES[0];
      case KNIGHT -> SEE_PIECE_VALUES[1];
      case BISHOP -> SEE_PIECE_VALUES[2];
      case ROOK -> SEE_PIECE_VALUES[3];
      case QUEEN -> SEE_PIECE_VALUES[4];
      case KING -> SEE_PIECE_VALUES[5];
    };
  }
}