package engine.forTesting;

import engine.Alliance;
import engine.forBoard.Board;
import engine.forBoard.BoardUtils;
import engine.forPiece.Bishop;
import engine.forPiece.King;
import engine.forPiece.Knight;
import engine.forPiece.Pawn;
import engine.forPiece.Queen;
import engine.forPiece.Rook;

import java.util.HashMap;
import java.util.Map;

/**
 * The FenParser class converts Forsyth-Edwards Notation strings into Board instances.
 * It exists so that arbitrary test positions can be loaded without playing a game up to them,
 * which is a prerequisite for move generation testing against published node counts.
 * The parser reads the piece placement, side to move, castling rights, and en passant fields,
 * and ignores the halfmove clock and fullmove number because the engine does not track them.
 * <p>
 * Castling rights and pawn double-step rights are expressed in this engine through the first
 * move status of the pieces involved rather than through a dedicated rights field, so the parser
 * translates the FEN castling field into the first move status of each king and rook, and marks
 * a pawn as unmoved only when it stands on its home rank. This class is designed as a
 * non-instantiable utility class with static methods.
 *
 * @author Aaron Ho
 */
public class FenParser {

  /** The Forsyth-Edwards Notation string describing the standard chess starting position. */
  public static final String STARTING_POSITION_FEN =
          "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

  /** The number of ranks described by the piece placement field of a Forsyth-Edwards Notation string. */
  private static final int NUM_RANKS = 8;

  /** The number of files described by each rank of a Forsyth-Edwards Notation string. */
  private static final int NUM_FILES = 8;

  /** The minimum number of whitespace separated fields a usable Forsyth-Edwards Notation string must contain. */
  private static final int MINIMUM_FIELD_COUNT = 4;

  /** The board coordinate of the square on which the white king side rook begins the game (h1). */
  private static final int WHITE_KING_SIDE_ROOK_TILE = 63;

  /** The board coordinate of the square on which the white queen side rook begins the game (a1). */
  private static final int WHITE_QUEEN_SIDE_ROOK_TILE = 56;

  /** The board coordinate of the square on which the black king side rook begins the game (h8). */
  private static final int BLACK_KING_SIDE_ROOK_TILE = 7;

  /** The board coordinate of the square on which the black queen side rook begins the game (a8). */
  private static final int BLACK_QUEEN_SIDE_ROOK_TILE = 0;

  /**
   * Private constructor to prevent instantiation of this utility class.
   *
   * @throws RuntimeException Always thrown to prevent instantiation.
   */
  private FenParser() {
    throw new RuntimeException("Not instantiatable!");
  }

  /**
   * Parses a Forsyth-Edwards Notation string and builds the board it describes.
   * The halfmove clock and fullmove number fields are optional and are ignored when present,
   * since the engine does not maintain either counter.
   *
   * @param fen The Forsyth-Edwards Notation string to parse.
   * @return The board described by the given notation string.
   * @throws IllegalArgumentException If the notation string is malformed or describes an impossible position.
   */
  public static Board parse(final String fen) {
    final String[] fields = fen.trim().split("\\s+");
    if (fields.length < MINIMUM_FIELD_COUNT) {
      throw new IllegalArgumentException("A FEN string requires at least four fields, found " +
              fields.length + ": " + fen);
    }
    final Alliance nextMoveMaker = parseMoveMaker(fields[1]);
    final Board.Builder builder = new Board.Builder();
    final Map<Integer, Pawn> placedPawns = new HashMap<>();
    parsePiecePlacement(fields[0], fields[2], builder, placedPawns);
    if (!"-".equals(fields[3])) {
      builder.setEnPassantPawn(resolveEnPassantPawn(fields[3], nextMoveMaker, placedPawns));
    }
    builder.setMoveMaker(nextMoveMaker);
    return builder.build();
  }

  /**
   * Parses the piece placement field of a Forsyth-Edwards Notation string and adds every piece
   * it describes to the given builder. Ranks are listed from the eighth rank to the first,
   * which matches the coordinate ordering used by the engine, where coordinate zero is a8.
   *
   * @param placement The piece placement field of the notation string.
   * @param castlingRights The castling rights field of the notation string.
   * @param builder The builder collecting the pieces described by the notation string.
   * @param placedPawns A map recording every placed pawn by coordinate for later en passant resolution.
   * @throws IllegalArgumentException If a rank is missing, overfull, or contains an unrecognised symbol.
   */
  private static void parsePiecePlacement(final String placement,
                                          final String castlingRights,
                                          final Board.Builder builder,
                                          final Map<Integer, Pawn> placedPawns) {
    final String[] ranks = placement.split("/");
    if (ranks.length != NUM_RANKS) {
      throw new IllegalArgumentException("A FEN piece placement field requires eight ranks, found " +
              ranks.length + ": " + placement);
    }
    for (int rank = 0; rank < NUM_RANKS; rank++) {
      int file = 0;
      for (final char symbol : ranks[rank].toCharArray()) {
        if (Character.isDigit(symbol)) {
          file += Character.getNumericValue(symbol);
          continue;
        }
        if (file >= NUM_FILES) {
          throw new IllegalArgumentException("Rank " + (NUM_RANKS - rank) +
                  " describes more than eight files: " + ranks[rank]);
        }
        placePiece(symbol, (rank * NUM_FILES) + file, castlingRights, builder, placedPawns);
        file++;
      }
      if (file != NUM_FILES) {
        throw new IllegalArgumentException("Rank " + (NUM_RANKS - rank) +
                " describes " + file + " files rather than eight: " + ranks[rank]);
      }
    }
  }

  /**
   * Creates the piece denoted by a single placement symbol and adds it to the given builder.
   * Uppercase symbols denote white pieces and lowercase symbols denote black pieces. Rooks and
   * kings are created with the first move status implied by the castling rights field, and pawns
   * are created with the first move status implied by the rank they stand on.
   *
   * @param symbol The placement symbol describing the piece.
   * @param coordinate The board coordinate the piece occupies.
   * @param castlingRights The castling rights field of the notation string.
   * @param builder The builder collecting the pieces described by the notation string.
   * @param placedPawns A map recording every placed pawn by coordinate for later en passant resolution.
   * @throws IllegalArgumentException If the placement symbol does not denote a chess piece.
   */
  private static void placePiece(final char symbol,
                                 final int coordinate,
                                 final String castlingRights,
                                 final Board.Builder builder,
                                 final Map<Integer, Pawn> placedPawns) {
    final Alliance alliance = Character.isUpperCase(symbol) ? Alliance.WHITE : Alliance.BLACK;
    switch (Character.toLowerCase(symbol)) {
      case 'p' -> {
        final boolean isUnmoved = isOnPawnHomeRank(alliance, coordinate);
        final Pawn pawn = new Pawn(alliance, coordinate, isUnmoved, isUnmoved ? 0 : 1);
        placedPawns.put(coordinate, pawn);
        builder.setPiece(pawn);
      }
      case 'n' -> builder.setPiece(new Knight(alliance, coordinate, 0));
      case 'b' -> builder.setPiece(new Bishop(alliance, coordinate, 0));
      case 'r' -> {
        final boolean retainsRight = retainsRookCastlingRight(alliance, coordinate, castlingRights);
        builder.setPiece(new Rook(alliance, coordinate, retainsRight, retainsRight ? 0 : 1));
      }
      case 'q' -> builder.setPiece(new Queen(alliance, coordinate, 0));
      case 'k' -> {
        final boolean kingSide = castlingRights.indexOf(alliance.isWhite() ? 'K' : 'k') >= 0;
        final boolean queenSide = castlingRights.indexOf(alliance.isWhite() ? 'Q' : 'q') >= 0;
        builder.setPiece(new King(alliance, coordinate, kingSide || queenSide, false, kingSide, queenSide));
      }
      default -> throw new IllegalArgumentException("Unrecognised piece symbol in FEN: " + symbol);
    }
  }

  /**
   * Determines whether a pawn of the given alliance standing on the given coordinate is still
   * on its home rank. The engine permits a two square advance only for a pawn whose first move
   * status is set and which stands on its home rank, so a pawn placed anywhere else is created
   * as having already moved.
   *
   * @param alliance The alliance of the pawn being placed.
   * @param coordinate The board coordinate the pawn occupies.
   * @return True if the pawn stands on its home rank, false otherwise.
   */
  private static boolean isOnPawnHomeRank(final Alliance alliance, final int coordinate) {
    return alliance.isWhite() ? BoardUtils.SeventhRow.get(coordinate)
            : BoardUtils.Instance.SecondRow.get(coordinate);
  }

  /**
   * Determines whether a rook of the given alliance standing on the given coordinate retains
   * a castling right according to the castling rights field. Only a rook standing on its original
   * corner square can retain a right, and only when the corresponding character is present.
   *
   * @param alliance The alliance of the rook being placed.
   * @param coordinate The board coordinate the rook occupies.
   * @param castlingRights The castling rights field of the notation string.
   * @return True if the rook retains a castling right, false otherwise.
   */
  private static boolean retainsRookCastlingRight(final Alliance alliance,
                                                  final int coordinate,
                                                  final String castlingRights) {
    if (alliance.isWhite()) {
      return (coordinate == WHITE_KING_SIDE_ROOK_TILE && castlingRights.indexOf('K') >= 0) ||
              (coordinate == WHITE_QUEEN_SIDE_ROOK_TILE && castlingRights.indexOf('Q') >= 0);
    }
    return (coordinate == BLACK_KING_SIDE_ROOK_TILE && castlingRights.indexOf('k') >= 0) ||
            (coordinate == BLACK_QUEEN_SIDE_ROOK_TILE && castlingRights.indexOf('q') >= 0);
  }

  /**
   * Resolves the pawn that may be captured en passant from the en passant target square.
   * The target square names the square the capturing pawn moves to, so the vulnerable pawn stands
   * one rank beyond it, on the far side from the player about to move. The pawn instance returned
   * is the same instance placed on the board, since the engine compares en passant pawns by identity.
   *
   * @param targetSquare The en passant target square in algebraic notation.
   * @param nextMoveMaker The alliance of the player about to move.
   * @param placedPawns A map of every placed pawn by coordinate.
   * @return The pawn that may be captured en passant.
   * @throws IllegalArgumentException If the target square is unrecognised or no pawn stands beside it.
   */
  private static Pawn resolveEnPassantPawn(final String targetSquare,
                                           final Alliance nextMoveMaker,
                                           final Map<Integer, Pawn> placedPawns) {
    final int targetCoordinate = BoardUtils.ALGEBRAIC_NOTATION.indexOf(targetSquare.toLowerCase());
    if (targetCoordinate == -1) {
      throw new IllegalArgumentException("Unrecognised en passant target square in FEN: " + targetSquare);
    }
    final int pawnCoordinate = nextMoveMaker.isWhite() ? targetCoordinate + NUM_FILES
            : targetCoordinate - NUM_FILES;
    final Pawn enPassantPawn = placedPawns.get(pawnCoordinate);
    if (enPassantPawn == null) {
      throw new IllegalArgumentException("No pawn stands beyond the en passant target square " +
              targetSquare + " in FEN");
    }
    return enPassantPawn;
  }

  /**
   * Parses the side to move field of a Forsyth-Edwards Notation string.
   *
   * @param field The side to move field of the notation string.
   * @return The alliance of the player about to move.
   * @throws IllegalArgumentException If the field does not denote a side to move.
   */
  private static Alliance parseMoveMaker(final String field) {
    return switch (field.toLowerCase()) {
      case "w" -> Alliance.WHITE;
      case "b" -> Alliance.BLACK;
      default -> throw new IllegalArgumentException("Unrecognised side to move in FEN: " + field);
    };
  }
}
