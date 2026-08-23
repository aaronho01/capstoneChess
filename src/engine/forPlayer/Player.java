package engine.forPlayer;

import engine.Alliance;
import engine.forBoard.Board;
import engine.forBoard.Move;
import engine.forPiece.King;
import engine.forPiece.Piece;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static engine.forPiece.Piece.PieceType.KING;
import static java.util.stream.Collectors.collectingAndThen;

/**
 * The Player class represents an abstract chess player, providing the foundation for player-specific
 * operations and game state management. Each player is associated with a board position and maintains
 * their king piece, legal moves, and check status. The class answers state queries including check,
 * checkmate, stalemate, and castling status. Applying a move is not a player's responsibility;
 * moves are applied to a board in place through {@link Board#makeMove(Move)}. Concrete subclasses
 * must implement alliance-specific behavior for piece management and castling calculations.
 *
 * @author Aaron Ho
 * @author dareTo81
 */
public abstract class Player {

  /** The chessboard associated with this player. */
  protected final Board board;

  /** The king piece belonging to this player. */
  protected final King playerKing;

  /** The opponent's active pieces, used to test check and castling safety. */
  protected final Collection<Piece> opponentPieces;

  /**
   * The collection of legal moves available to this player on the current board state. Null
   * until {@link #getLegalMoves()} is first called, at which point it is computed once and
   * cached here.
   */
  protected Collection<Move> legalMoves;

  /** Flag indicating whether this player is currently in check. */
  protected final boolean isInCheck;

  /**
   * Constructs a Player with the specified board and the opponent's active pieces. Establishes
   * the player's king and determines check status directly against the opponent's pieces. This
   * player's legal moves, including castling, are not computed here; they are computed lazily by
   * {@link #getLegalMoves()} the first time something actually asks for them.
   *
   * @param board The chessboard associated with this player.
   * @param opponentPieces The opponent's active pieces, used to test check and castling safety.
   */
  Player(final Board board, final Collection<Piece> opponentPieces) {
    this.board = board;
    this.opponentPieces = opponentPieces;
    this.playerKing = establishKing();
    this.isInCheck = isSquareAttacked(this.playerKing.getPiecePosition(), opponentPieces, board);
  }

  /**
   * Determines whether this player is currently in check.
   *
   * @return True if the player is in check, false otherwise.
   */
  public boolean isInCheck() {
    return this.isInCheck;
  }

  /**
   * Determines whether this player is in checkmate.
   * A player is in checkmate if they are in check and have no legal escape moves.
   *
   * @return True if the player is in checkmate, false otherwise.
   */
  public boolean isInCheckMate() {
    return this.isInCheck && !hasEscapeMoves();
  }

  /**
   * Determines whether this player is in stalemate.
   * A player is in stalemate if they are not in check but have no legal moves.
   *
   * @return True if the player is in stalemate, false otherwise.
   */
  public boolean isInStaleMate() {
    return !this.isInCheck && !hasEscapeMoves();
  }

  /**
   * Determines whether this player has castled.
   *
   * @return True if the player has castled, false otherwise.
   */
  public boolean isCastled() {
    return this.playerKing.isCastled();
  }

  /**
   * Retrieves this player's king piece.
   *
   * @return The king piece belonging to this player.
   */
  public King getPlayerKing() {
    return this.playerKing;
  }

  /**
   * Establishes the king piece for this player by locating it among active pieces.
   *
   * @return The king piece belonging to this player.
   * @throws RuntimeException If no king piece is found among active pieces.
   */
  private King establishKing() {
    return (King) getActivePieces().stream()
            .filter(piece -> piece.getPieceType() == KING)
            .findAny()
            .orElseThrow(RuntimeException::new);
  }

  /**
   * Determines whether this player has any legal escape moves available, by applying each of this
   * player's pseudo-legal moves to the board in place and keeping the first one that does not
   * leave this player's own king attacked.
   * <p>
   * This is only a meaningful question to ask of the side to move, since a move can only be played
   * from a position in which it is that side's turn. The previous implementation carried the same
   * assumption implicitly, through a board transition that read the board's current player rather
   * than this one, and simply returned a wrong answer if the assumption did not hold. Now that the
   * board is mutated in place instead, the same mistake would corrupt the board rather than merely
   * misreport, so it is checked rather than assumed.
   *
   * @return True if escape moves exist, false otherwise.
   * @throws IllegalStateException If this player is not the side to move on its own board.
   */
  private boolean hasEscapeMoves() {
    if (this.board.currentPlayer() != this) {
      throw new IllegalStateException(
              "Escape moves can only be tested for the side to move, not for " + getAlliance() + ".");
    }
    for (final Move move : getLegalMoves()) {
      if (this.board.isLegal(move)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Retrieves the collection of legal moves for this player, computing and caching it on the
   * first call. This is the expensive part of standing up a player, full pseudo-legal move
   * generation for every active piece plus castling, and most positions visited during search
   * only ever need one side's move list at a given node, so deferring it here rather than
   * computing it for both sides on every board mutation is the whole point.
   *
   * @return An unmodifiable collection of legal moves.
   */
  public Collection<Move> getLegalMoves() {
    if (this.legalMoves == null) {
      this.legalMoves = calculateLegalMoves();
    }
    return this.legalMoves;
  }

  /**
   * Computes this player's full legal move list against the current board: pseudo-legal moves
   * for every active piece, plus castling.
   *
   * @return An unmodifiable collection of this player's legal moves.
   */
  private Collection<Move> calculateLegalMoves() {
    final List<Move> playerLegals = new ArrayList<>(this.board.calculateLegalMoves(getActivePieces()));
    playerLegals.addAll(calculateKingCastles(playerLegals, this.opponentPieces));
    return Collections.unmodifiableList(playerLegals);
  }

  /**
   * Calculates all moves that attack a specific tile on the board.
   *
   * @param tile The tile coordinate to check for attacks.
   * @param moves The collection of moves to examine.
   * @return An unmodifiable collection of moves that attack the specified tile.
   */
  public static Collection<Move> calculateAttacksOnTile(final int tile, final Collection<Move> moves) {
    return moves.stream()
            .filter(move -> move.getDestinationCoordinate() == tile)
            .collect(collectingAndThen(Collectors.toList(), Collections::unmodifiableList));
  }

  /**
   * Determines whether any piece in the given collection attacks the given square on the given
   * board, using {@link Piece#attacksSquare(int, Board)} rather than generating and filtering a
   * full move list, since only a yes/no answer for one square is needed here.
   *
   * @param square The square to test.
   * @param attackers The candidate attacking pieces.
   * @param board The current board.
   * @return True if any piece in attackers attacks square, false otherwise.
   */
  public static boolean isSquareAttacked(final int square, final Collection<Piece> attackers, final Board board) {
    for (final Piece piece : attackers) {
      if (piece.attacksSquare(square, board)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Retrieves the collection of active pieces belonging to this player.
   * Must be implemented by concrete subclasses to return alliance-specific pieces.
   *
   * @return The collection of active pieces for this player.
   */
  public abstract Collection<Piece> getActivePieces();

  /**
   * Retrieves the alliance (color) of this player.
   * Must be implemented by concrete subclasses to return the appropriate alliance.
   *
   * @return The alliance of this player.
   */
  public abstract Alliance getAlliance();

  /**
   * Retrieves the opponent player.
   * Must be implemented by concrete subclasses to return the opposing player.
   *
   * @return The opponent player.
   */
  public abstract Player getOpponent();

  /**
   * Calculates and returns possible castling moves for this player's king.
   * Must be implemented by concrete subclasses to handle alliance-specific castling rules.
   *
   * @param playerLegals The legal moves available to this player.
   * @param opponentPieces The opposing player's pieces.
   * @return A collection of possible castling moves.
   */
  protected abstract Collection<Move> calculateKingCastles(Collection<Move> playerLegals, Collection<Piece> opponentPieces);

  /**
   * Determines whether this player has any castling opportunities available.
   * A player has castling opportunities if they are not in check, have not already castled,
   * and retain either kingside or queenside castling capabilities.
   *
   * @return True if castling opportunities exist, false otherwise.
   */
  protected boolean hasCastleOpportunities() {
    return !this.isInCheck || !this.playerKing.isCastled() ||
            (this.playerKing.isKingSideCastleCapable() && this.playerKing.isQueenSideCastleCapable());
  }
}