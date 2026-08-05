package engine.forBoard;

import engine.Alliance;
import engine.forPiece.Pawn;
import engine.forPiece.King;
import engine.forPiece.Piece;
import engine.forPiece.Piece.PieceType;

import java.util.Random;

/**
 * The ZobristHashing class implements the Zobrist hashing algorithm for chess positions.
 * This algorithm generates unique hash codes for board positions that can be efficiently updated
 * when moves are made, without recalculating the entire hash. This approach is particularly valuable
 * for transposition tables, detecting repeated positions, and other performance optimizations.
 * <p>
 * The class maintains a set of pre-generated random numbers for each piece type, position, alliance
 * combination, as well as additional numbers for side to move, castling rights, and en passant
 * possibilities. These random numbers are XORed together to create unique position signatures.
 * This class is designed as a non-instantiable utility class with static methods to calculate
 * and update Zobrist hash values for chess board positions.
 *
 * @author Aaron Ho
 */
public class ZobristHashing {

  /**
   * Random number table for each piece type, on each square, for each alliance.
   * The first dimension represents the alliance (0=WHITE, 1=BLACK),
   * the second dimension represents the piece type (0=PAWN, 1=KNIGHT, etc.),
   * and the third dimension represents the board position (0-63).
   */
  private static final long[][][] PIECE_POSITION_TABLE = new long[2][6][64];

  /**
   * Random number for the side to move when it is black's turn.
   * This value is XORed into the hash when the side to move is BLACK.
   */
  private static final long SIDE_TO_MOVE;

  /**
   * Random numbers for castling rights.
   * Index 0: White kingside castling
   * Index 1: White queenside castling
   * Index 2: Black kingside castling
   * Index 3: Black queenside castling
   */
  private static final long[] CASTLING_RIGHTS = new long[4];

  /**
   * Random numbers for en passant file.
   * When en passant is possible, the value corresponding to the file (0-7)
   * is XORed into the hash.
   */
  private static final long[] EN_PASSANT_FILE = new long[8];

  /**
   * Static initializer that populates the random number tables with predetermined
   * random values. A fixed seed is used to ensure reproducibility of hash values
   * across different instances of the application.
   */
  static {
    final Random random = new Random(0);
    for (int alliance = 0; alliance < 2; alliance++) {
      for (int pieceType = 0; pieceType < 6; pieceType++) {
        for (int position = 0; position < 64; position++) {
          PIECE_POSITION_TABLE[alliance][pieceType][position] = random.nextLong();
        }
      }
    }
    SIDE_TO_MOVE = random.nextLong();
    for (int i = 0; i < 4; i++) {
      CASTLING_RIGHTS[i] = random.nextLong();
    }
    for (int i = 0; i < 8; i++) {
      EN_PASSANT_FILE[i] = random.nextLong();
    }
  }

  /**
   * Private constructor to prevent instantiation of this utility class.
   *
   * @throws RuntimeException Always thrown to prevent instantiation.
   */
  private ZobristHashing() {
    throw new RuntimeException("Not instantiatable!");
  }

  /**
   * Calculates the Zobrist hash for a given board position.
   * This method computes the hash from scratch by considering all pieces on the board,
   * the side to move, castling rights, and en passant possibilities.
   *
   * @param board The current chess board.
   * @return The Zobrist hash value representing the board position.
   */
  public static long calculateBoardHash(final Board board) {
    long hash = 0;
    for (final Piece piece : board.getAllPieces()) {
      hash ^= getPieceHash(piece);
    }
    if (board.currentPlayer().getAlliance() == Alliance.BLACK) {
      hash ^= SIDE_TO_MOVE;
    }
    if (canCastle(board, true, true)) {
      hash ^= CASTLING_RIGHTS[0];
    }
    if (canCastle(board, true, false)) {
      hash ^= CASTLING_RIGHTS[1];
    }
    if (canCastle(board, false, true)) {
      hash ^= CASTLING_RIGHTS[2];
    }
    if (canCastle(board, false, false)) {
      hash ^= CASTLING_RIGHTS[3];
    }
    final Pawn enPassantPawn = board.getEnPassantPawn();
    if (enPassantPawn != null) {
      final int enPassantFile = enPassantPawn.getPiecePosition() % 8;
      hash ^= EN_PASSANT_FILE[enPassantFile];
    }
    return hash;
  }

  public static boolean canCastle(final Board board, final boolean isWhite, final boolean kingSide) {
    final King king = isWhite ? board.whitePlayer().getPlayerKing() : board.blackPlayer().getPlayerKing();
    if (!king.isFirstMove()) {
      return false;
    }
    final int rookPosition = isWhite ? (kingSide ? 7 : 0) : (kingSide ? 63 : 56);
    final Piece rook = board.getPiece(rookPosition);
    return rook != null && rook.getPieceType() == Piece.PieceType.ROOK &&
            rook.getPieceAllegiance() == king.getPieceAllegiance() && rook.isFirstMove();
  }

  /**
   * Updates a hash value when a piece is moved on the board.
   * This method removes the piece from its original position and adds it
   * at the new position in the hash calculation.
   *
   * @param hash The current hash value to update.
   * @param piece The piece being moved.
   * @param fromPosition The starting position of the move.
   * @param toPosition The destination position of the move.
   * @return The updated hash value.
   */
  public static long updateHashPieceMove(long hash,
                                         final Piece piece,
                                         final int fromPosition,
                                         final int toPosition) {
    hash ^= getPieceHash(piece, fromPosition);
    hash ^= getPieceHash(piece, toPosition);
    return hash;
  }

  /**
   * Updates a hash value when a piece is captured on the board.
   * This method removes the captured piece from the hash calculation.
   *
   * @param hash The current hash value to update.
   * @param capturedPiece The piece being captured.
   * @return The updated hash value.
   */
  public static long updateHashPieceCapture(long hash, final Piece capturedPiece) {
    return hash ^ getPieceHash(capturedPiece);
  }

  /**
   * Updates a hash value for a castling move.
   * This method updates the hash by moving both the king and rook to their
   * respective destinations.
   *
   * @param hash The current hash value to update.
   * @param king The king being moved.
   * @param rook The rook being moved.
   * @param kingFrom The king's starting position.
   * @param kingTo The king's destination position.
   * @param rookFrom The rook's starting position.
   * @param rookTo The rook's destination position.
   * @return The updated hash value.
   */
  public static long updateHashCastle(long hash,
                                      final Piece king,
                                      final Piece rook,
                                      final int kingFrom,
                                      final int kingTo,
                                      final int rookFrom,
                                      final int rookTo) {
    hash ^= getPieceHash(king, kingFrom);
    hash ^= getPieceHash(king, kingTo);
    hash ^= getPieceHash(rook, rookFrom);
    hash ^= getPieceHash(rook, rookTo);
    return hash;
  }

  /**
   * Updates a hash value for a pawn promotion.
   * This method removes the pawn from the hash and adds the promoted piece.
   *
   * @param hash The current hash value to update.
   * @param pawn The pawn being promoted.
   * @param promotedPiece The piece to which the pawn is promoted.
   * @param position The position of the promotion.
   * @return The updated hash value.
   */
  public static long updateHashPromotion(long hash,
                                         final Piece pawn,
                                         final Piece promotedPiece,
                                         final int position) {
    hash ^= getPieceHash(pawn, position);
    hash ^= getPieceHash(promotedPiece, position);
    return hash;
  }

  /**
   * Updates a hash value to reflect a change in the side to move.
   * This method toggles the SIDE_TO_MOVE bit in the hash.
   *
   * @param hash The current hash value to update.
   * @return The updated hash value.
   */
  public static long updateHashSideToMove(long hash) {
    return hash ^ SIDE_TO_MOVE;
  }

  /**
   * Updates a hash value for a change in en passant possibility.
   * This method adds or removes an en passant possibility on the specified file.
   *
   * @param hash The current hash value to update.
   * @param enPassantFile The file (0-7) where en passant is possible.
   * @return The updated hash value.
   */
  public static long updateHashEnPassant(long hash, final int enPassantFile) {
    if (enPassantFile >= 0 && enPassantFile < 8) {
      hash ^= EN_PASSANT_FILE[enPassantFile];
    }
    return hash;
  }

  /**
   * Updates a hash value for a change in castling rights.
   * This method adds or removes a specific castling right from the hash.
   *
   * @param hash The current hash value to update.
   * @param castlingRightIndex The index of the castling right (0-3).
   * @return The updated hash value.
   */
  public static long updateHashCastlingRight(long hash, final int castlingRightIndex) {
    if (castlingRightIndex >= 0 && castlingRightIndex < 4) {
      hash ^= CASTLING_RIGHTS[castlingRightIndex];
    }
    return hash;
  }

  /**
   * Gets the hash value for a piece at its current position.
   * This is a convenience method that calls getPieceHash with the piece's current position.
   *
   * @param piece The piece to hash.
   * @return The hash value for the piece at its position.
   */
  private static long getPieceHash(final Piece piece) {
    return getPieceHash(piece, piece.getPiecePosition());
  }

  /**
   * Gets the hash value for a piece at a specified position.
   * This method looks up the pre-generated random number for the given piece type,
   * alliance, and position combination.
   *
   * @param piece The piece to hash.
   * @param position The position to consider.
   * @return The hash value for the piece at the position.
   */
  private static long getPieceHash(final Piece piece, final int position) {
    final int allianceIndex = piece.getPieceAllegiance().ordinal();
    final int pieceTypeIndex = convertPieceTypeToIndex(piece.getPieceType());
    return PIECE_POSITION_TABLE[allianceIndex][pieceTypeIndex][position];
  }

  /**
   * Converts a PieceType enum value to an index for the hash table.
   * This method maps each piece type to a zero-based index used in the PIECE_POSITION_TABLE.
   *
   * @param pieceType The type of piece.
   * @return The index for the hash table (0-5).
   */
  private static int convertPieceTypeToIndex(final PieceType pieceType) {
    return switch (pieceType) {
      case PAWN -> 0;
      case KNIGHT -> 1;
      case BISHOP -> 2;
      case ROOK -> 3;
      case QUEEN -> 4;
      case KING -> 5;
    };
  }
}