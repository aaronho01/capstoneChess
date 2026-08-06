package engine.forPiece;

import engine.Alliance;
import engine.forBoard.Board;
import engine.forBoard.BoardUtils;
import engine.forBoard.Move;
import engine.forBoard.MovePool;

import java.util.*;

import static engine.forBoard.BoardUtils.isValidTileCoordinate;
import static engine.forBoard.MoveUtils.Line;

/**
 * The Rook class represents a rook chess piece. It extends the Piece class and defines the specific properties and
 * behaviors of a rook on the chess board. Rooks move horizontally and vertically across the board, able to travel
 * any number of squares in these directions until blocked by another piece or the edge of the board.
 * <p>
 * This class provides methods for calculating legal moves for a rook, including horizontal and vertical moves,
 * as well as capturing opponent pieces. It precomputes valid move lines for each tile on the board to optimize
 * move generation performance during gameplay.
 *
 * @author Aaron Ho
 */
public final class Rook extends Piece {

  /** An array of possible movement offsets for a rook, representing vertical and horizontal directions. */
  private static final int[] CANDIDATE_MOVE_COORDINATES = { -8, -1, 1, 8 };

  /** A map that stores precomputed legal move lines for each tile on the board for rook movement patterns. */
  private static final Map<Integer, Line[]> PRECOMPUTED_CANDIDATES = computeCandidates();

  /** An empty array used as a default value when creating arrays of Line objects. */
  private static final Line[] EMPTY_LINES_ARRAY = new Line[0];

  /**
   * Constructs a rook chess piece with the given alliance, starting position, and number of moves.
   * The rook is marked as having made its first move.
   *
   * @param alliance The alliance (color) of the rook (WHITE or BLACK).
   * @param piecePosition The position of the rook on the chessboard.
   * @param numMoves The number of moves made by the rook.
   */
  public Rook(final Alliance alliance, final int piecePosition, final int numMoves) {
    super(PieceType.ROOK, alliance, piecePosition, true, numMoves);
  }

  /**
   * Constructs a rook chess piece with the given alliance, starting position, first move status, and number of moves.
   *
   * @param alliance The alliance (color) of the rook (WHITE or BLACK).
   * @param piecePosition The position of the rook on the chessboard.
   * @param isFirstMove Indicates whether this is the rook's first move.
   * @param numMoves The number of moves made by the rook.
   */
  public Rook(final Alliance alliance,
              final int piecePosition,
              final boolean isFirstMove,
              final int numMoves) {
    super(PieceType.ROOK, alliance, piecePosition, isFirstMove, numMoves);
  }

  /**
   * Computes and precomputes the legal move lines for each tile on the board for rook movement.
   * This method generates all possible horizontal and vertical movement patterns for rooks,
   * taking into account board boundaries and edge exclusions.
   *
   * @return A map containing the precomputed legal move lines for each tile on the board.
   */
  private static Map<Integer, Line[]> computeCandidates() {
    Map<Integer, Line[]> candidates = new HashMap<>();
    for (int position = 0; position < BoardUtils.NUM_TILES; position++) {
      List<Line> lines = new ArrayList<>();
      for (int offset : CANDIDATE_MOVE_COORDINATES) {
        int destination = position;
        Line line = new Line();
        while (isValidTileCoordinate(destination)) {
          if (isColumnExclusion(destination, offset)) {
            break;
          }
          destination += offset;
          if (isValidTileCoordinate(destination)) {
            line.addCoordinate(destination);
          }
        }
        if (!line.isEmpty()) {
          lines.add(line);
        }
      }
      if (!lines.isEmpty()) {
        candidates.put(position, lines.toArray(EMPTY_LINES_ARRAY != null ? EMPTY_LINES_ARRAY : new Line[0]));
      }
    }
    return Collections.unmodifiableMap(candidates);
  }

  /**
   * Calculates the legal moves that the rook can make on the given chess board. A rook can move horizontally or
   * vertically across the board, capturing opponent pieces along the way until blocked by a piece or board edge.
   *
   * @param board The current state of the chess board.
   * @return A collection of legal moves that the rook can make.
   */
  @Override
  public Collection<Move> calculateLegalMoves(final Board board) {
    final List<Move> legalMoves = new ArrayList<>();
    for (final Line line : PRECOMPUTED_CANDIDATES.get(this.piecePosition)) {
      for (final int candidateDestinationCoordinate : line.getLineCoordinates()) {
        final Piece pieceAtDestination = board.getPiece(candidateDestinationCoordinate);
        if (pieceAtDestination == null) {
          legalMoves.add(MovePool.INSTANCE.getMajorMove(board, this, candidateDestinationCoordinate));
        } else {
          final Alliance pieceAlliance = pieceAtDestination.getPieceAllegiance();
          if (this.pieceAlliance != pieceAlliance) {
            legalMoves.add(MovePool.INSTANCE.getMajorAttackMove(board, this, candidateDestinationCoordinate, pieceAtDestination));
          }
          break;
        }
      }
    }
    return Collections.unmodifiableList(legalMoves);
  }
  /**
   * Determines whether this rook attacks the given square, walking the same precomputed
   * lines as {@link #calculateLegalMoves(Board)} but without allocating any moves.
   *
   * @param targetSquare The square to test.
   * @param board The current board.
   * @return True if this rook attacks targetSquare, false otherwise.
   */
  @Override
  public boolean attacksSquare(final int targetSquare, final Board board) {
    final Line[] lines = PRECOMPUTED_CANDIDATES.get(this.piecePosition);
    if (lines == null) {
      return false;
    }
    for (final Line line : lines) {
      for (final int candidateDestinationCoordinate : line.getLineCoordinates()) {
        final Piece pieceAtDestination = board.getPiece(candidateDestinationCoordinate);
        if (pieceAtDestination == null) {
          if (candidateDestinationCoordinate == targetSquare) {
            return true;
          }
        } else {
          if (candidateDestinationCoordinate == targetSquare) {
            return pieceAtDestination.getPieceAllegiance() != this.pieceAlliance;
          }
          break;
        }
      }
    }
    return false;
  }

  /**
   * Provides a location bonus value for the rook piece on the board. The bonus is determined based on the rook's
   * current position and alliance, contributing to the evaluation of the piece's strategic importance.
   *
   * @param board The current state of the chess board.
   * @return The location bonus value for the rook.
   */
  @Override
  public int locationBonus(final Board board) {
    return this.pieceAlliance.rookBonus(this.piecePosition, board);
  }

  /**
   * Creates a new rook piece after a given move is made. This method is used to update the piece's position after a
   * legal move is executed on the board.
   *
   * @param move The move that is being made.
   * @return A new rook piece after the specified move is made on the chessboard.
   */
  @Override
  public Rook movePiece(final Move move) {
    return PieceUtils.Instance.getMovedRook(move.getMovedPiece().getPieceAllegiance(), move.getDestinationCoordinate());
  }

  /**
   * Returns a string representation of the rook piece.
   *
   * @return A string representation of the rook piece.
   */
  @Override
  public String toString() {
    return this.pieceType.toString();
  }

  /**
   * Checks if a given position is at the edge of a column on the chessboard and if the given offset would
   * result in an invalid horizontal movement for the rook.
   *
   * @param position The position to check.
   * @param offset The offset value for the movement.
   * @return True if the position is at the column edge and the offset is invalid for horizontal movement, false otherwise.
   */
  private static boolean isColumnExclusion(final int position, final int offset) {
    return (BoardUtils.Instance.FirstColumn.get(position) && (offset == -1)) ||
            (BoardUtils.Instance.EighthColumn.get(position) && (offset == 1));
  }
}