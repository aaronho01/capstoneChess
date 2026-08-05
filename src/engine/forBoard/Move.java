package engine.forBoard;

import engine.forPiece.Pawn;
import engine.forPiece.Piece;
import engine.forPiece.Rook;

import java.util.Collection;
import java.util.Objects;

import static engine.forBoard.Board.Builder;

/**
 * The Move class represents a move in a game of chess.
 * This abstract class provides the base functionality for all types of chess moves,
 * including standard moves, attack moves, castling, en passant, and pawn promotion.
 * The class has been modified to support object pooling for better memory efficiency.
 * <p>
 * Different types of moves are represented by specialized subclasses, each implementing
 * the specific behavior and rules for that move type.
 *
 * @author Aaron Ho
 */
public abstract class Move {

  /** The current state of the chess board. */
  protected Board board;

  /** The destination coordinate of the move. */
  protected int destinationCoordinate;

  /** The piece that is being moved. */
  protected Piece movedPiece;

  /** Indicates whether it's the first move for the piece being moved. */
  protected boolean isFirstMove;

  /**
   * Creates a new Move object with the given board, moved piece, and destination coordinate.
   *
   * @param board The current state of the chess board.
   * @param pieceMoved The piece that is being moved.
   * @param destinationCoordinate The destination coordinate of the move.
   */
  protected Move(final Board board, final Piece pieceMoved, final int destinationCoordinate) {
    this.board = board;
    this.destinationCoordinate = destinationCoordinate;
    this.movedPiece = pieceMoved;
    this.isFirstMove = pieceMoved != null && pieceMoved.isFirstMove();
  }

  /**
   * Creates a new Move object with the given board and destination coordinate.
   * This constructor is used for pawn jump moves and for initializing move objects
   * that do not have a moved piece.
   *
   * @param board The current state of the chess board.
   * @param destinationCoordinate The destination coordinate of the move.
   */
  protected Move(final Board board, final int destinationCoordinate) {
    this.board = board;
    this.destinationCoordinate = destinationCoordinate;
    this.movedPiece = null;
    this.isFirstMove = false;
  }

  /**
   * Reset method for object pooling. Must be overridden by subclasses.
   * This method allows move objects to be reused to reduce garbage collection pressure.
   *
   * @return The reset move object.
   */
  protected abstract Move reset();

  /**
   * Calculates the hash code of the move.
   * This implementation safely handles null movedPiece references.
   *
   * @return The calculated hash code.
   */
  @Override
  public int hashCode() {
    final int currentPosition = (movedPiece != null) ? movedPiece.getPiecePosition() : -1;
    return Objects.hash(destinationCoordinate, movedPiece, currentPosition, isFirstMove);
  }

  /**
   * Compares this move to another object to determine if they are equal.
   * Moves are considered equal if they have the same current coordinate,
   * destination coordinate, and moved piece.
   *
   * @param other The other object to compare to.
   * @return True if the moves are equal, false otherwise.
   */
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof Move otherMove)) return false;
    return getCurrentCoordinate() == otherMove.getCurrentCoordinate() &&
            getDestinationCoordinate() == otherMove.getDestinationCoordinate() &&
            Objects.equals(movedPiece, otherMove.getMovedPiece());
  }

  /**
   * Gets the current state of the chess board.
   *
   * @return The current board.
   */
  public Board getBoard() {
    return this.board;
  }

  /**
   * Gets the current coordinate of the moved piece.
   *
   * @return The current coordinate, or -1 if no piece is being moved.
   */
  public int getCurrentCoordinate() {
    return this.movedPiece != null ? this.movedPiece.getPiecePosition() : -1;
  }

  /**
   * Gets the destination coordinate of the move.
   *
   * @return The destination coordinate.
   */
  public int getDestinationCoordinate() {
    return this.destinationCoordinate;
  }

  /**
   * Gets the piece that is being moved.
   *
   * @return The moved piece.
   */
  public Piece getMovedPiece() {
    return this.movedPiece;
  }

  /**
   * Checks if the move is an attack.
   * This base implementation returns false; subclasses that represent attack moves
   * should override this method to return true.
   *
   * @return True if the move is an attack, false otherwise.
   */
  public boolean isAttack() {
    return false;
  }

  /**
   * Checks if the move is a castling move.
   * This base implementation returns false; subclasses that represent castling moves
   * should override this method to return true.
   *
   * @return True if the move is a castling move, false otherwise.
   */
  public boolean isCastlingMove() {
    return false;
  }

  /**
   * Gets the attacked piece in case of an attack move.
   * This base implementation returns null; subclasses that represent attack moves
   * should override this method to return the attacked piece.
   *
   * @return The attacked piece, or null if this is not an attack move.
   */
  public Piece getAttackedPiece() {
    return null;
  }

  /**
   * Executes the move on the board and returns the resulting board.
   * This method creates a new board state reflecting the changes from the move.
   *
   * @return The board after executing the move.
   */
  public Board execute() {
    final Board.Builder builder = new Builder();
    Collection<Piece> currentPlayerPieces = this.board.currentPlayer().getActivePieces();
    for (Piece piece : currentPlayerPieces) {
      if (piece != this.movedPiece) {
        builder.setPiece(piece);
      }
    }

    for (Piece piece : this.board.currentPlayer().getOpponent().getActivePieces()) {
      builder.setPiece(piece);
    }

    builder.setPiece(this.movedPiece.movePiece(this));
    builder.setMoveMaker(this.board.currentPlayer().getOpponent().getAlliance());
    builder.setMoveTransition(this);

    long newHash = updateZobristHash(this.board.getZobristHash());
    builder.setZobristHash(newHash);

    return builder.build();
  }

  /**
   * Updates the Zobrist hash for this move.
   * This default implementation handles standard piece movement.
   * Subclasses should override this method for special moves.
   *
   * @param currentHash The current board hash.
   * @return The updated hash.
   */
  protected long updateZobristHash(final long currentHash) {
    long hash = ZobristHashing.updateHashPieceMove(
            currentHash,
            this.movedPiece,
            this.getCurrentCoordinate(),
            this.getDestinationCoordinate()
    );

    hash = ZobristHashing.updateHashSideToMove(hash);
    hash = clearCastlingRightsForMove(hash, this.board, this.movedPiece, this.getCurrentCoordinate());
    if (this.board.getEnPassantPawn() != null) {
      final int enPassantFile = this.board.getEnPassantPawn().getPiecePosition() % 8;
      hash = ZobristHashing.updateHashEnPassant(hash, enPassantFile);
    }

    return hash;
  }

  /**
   * Undoes the move on the board and returns the resulting board.
   * This method creates a new board state as it was before the move.
   *
   * @return The board after undoing the move.
   */
  public Board undo() {
    final Board.Builder builder = new Builder();
    this.board.getAllPieces().forEach(builder::setPiece);
    builder.setMoveMaker(this.board.currentPlayer().getAlliance());
    return builder.build();
  }

  /**
   * Generates disambiguation information for moves involving pieces of the same type.
   * When multiple pieces of the same type can move to the same destination, this method
   * provides the file letter to distinguish between them in algebraic notation.
   *
   * @return The disambiguation file character, or an empty string if disambiguation is not needed.
   */
  String disambiguationFile() {
    for (final Move move: this.board.currentPlayer().getLegalMoves()) {
      if (move.getDestinationCoordinate() == this.destinationCoordinate && !this.equals(move) &&
              this.movedPiece.getPieceType().equals(move.getMovedPiece().getPieceType())) {
        return BoardUtils.getPositionAtCoordinate(this.movedPiece.getPiecePosition()).substring(0, 1);
      }
    } return "";
  }

  /**
   * Represents the possible outcomes of a chess move, including successful execution,
   * an illegal move, or leaving the player's king in check. Each status indicates whether
   * a move is considered done or has specific implications on the game state.
   */
  public enum MoveStatus {
    /**
     * Represents a successfully executed move.
     */
    DONE {
      @Override
      public boolean isDone() {
        return true;
      }
    },

    /**
     * Represents an illegal move that cannot be executed.
     */
    ILLEGAL_MOVE {
      @Override
      public boolean isDone() {
        return false;
      }
    },

    /**
     * Represents a move that would leave the player's king in check.
     */
    LEAVES_PLAYER_IN_CHECK {
      @Override
      public boolean isDone() {
        return false;
      }
    };

    /**
     * Checks if the move status indicates that the move is done and valid.
     *
     * @return True if the move is done, false otherwise.
     */
    public abstract boolean isDone();
  }

  /**
   * The PawnPromotion class represents a special move in chess. When a pawn reaches
   * the opponent's back rank, it can be promoted to any other chess piece (queen, rook,
   * bishop, or knight). This class extends a standard PawnMove by adding promotion information
   * and handles the execution of the promotion move.
   */
  public static class PawnPromotion extends PawnMove {

    /** The decorated move that represents the original pawn move. */
    protected Move decoratedMove;

    /** The promoted pawn that is reaching the back rank. */
    protected Pawn promotedPawn;

    /** The piece to which the pawn is promoted (queen, rook, bishop, or knight). */
    protected Piece promotionPiece;

    /**
     * Constructs a PawnPromotion instance.
     *
     * @param decoratedMove    The decorated move representing the original pawn move.
     * @param promotionPiece   The piece to which the pawn is promoted (queen, rook, bishop, or knight).
     */
    public PawnPromotion(final Move decoratedMove, final Piece promotionPiece) {
      super(decoratedMove != null ? decoratedMove.getBoard() : null,
              decoratedMove != null ? decoratedMove.getMovedPiece() : null,
              decoratedMove != null ? decoratedMove.getDestinationCoordinate() : -1);
      this.decoratedMove = decoratedMove;
      this.promotedPawn = (decoratedMove != null && decoratedMove.getMovedPiece() instanceof Pawn) ?
              (Pawn) decoratedMove.getMovedPiece() : null;
      this.promotionPiece = promotionPiece;
    }

    /**
     * Resets the PawnPromotion with new values for object pooling.
     *
     * @param decoratedMove The base move to be decorated with promotion.
     * @param promotionPiece The piece type to which the pawn will be promoted.
     * @return The reset PawnPromotion instance.
     */
    public PawnPromotion reset(final Move decoratedMove, final Piece promotionPiece) {
      this.decoratedMove = decoratedMove;
      this.promotedPawn = (Pawn) decoratedMove.getMovedPiece();
      this.promotionPiece = promotionPiece;
      this.board = decoratedMove.getBoard();
      this.movedPiece = decoratedMove.getMovedPiece();
      this.destinationCoordinate = decoratedMove.getDestinationCoordinate();
      this.isFirstMove = decoratedMove.getMovedPiece() != null && decoratedMove.getMovedPiece().isFirstMove();
      return this;
    }

    /**
     * Checks if two PawnPromotion instances are equal by comparing their attributes.
     *
     * @param o The object to compare with this PawnPromotion.
     * @return True if the objects are equal, false otherwise.
     */
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      if (!super.equals(o)) return false;
      PawnPromotion that = (PawnPromotion) o;
      return Objects.equals(decoratedMove, that.decoratedMove) &&
              Objects.equals(promotedPawn, that.promotedPawn) &&
              Objects.equals(promotionPiece, that.promotionPiece);
    }

    /**
     * Generates a hash code for this PawnPromotion instance based on its attributes.
     *
     * @return The computed hash code.
     */
    @Override
    public int hashCode() {
      return Objects.hash(super.hashCode(), decoratedMove, promotedPawn, promotionPiece);
    }

    /**
     * Executes the pawn promotion move by first executing the decorated move and then
     * replacing the promoted pawn with the promoted piece.
     *
     * @return The resulting board after executing the pawn promotion move.
     */
    @Override
    public Board execute() {
      final Board pawnMovedBoard = this.decoratedMove.execute();
      final Board.Builder builder = new Builder();

      for (Piece piece : pawnMovedBoard.currentPlayer().getActivePieces()) {
        if (piece != this.promotedPawn) {
          builder.setPiece(piece);
        }
      }

      for (Piece piece : pawnMovedBoard.currentPlayer().getOpponent().getActivePieces()) {
        builder.setPiece(piece);
      }

      builder.setPiece(this.promotionPiece.movePiece(this));
      builder.setMoveMaker(pawnMovedBoard.currentPlayer().getAlliance());
      builder.setMoveTransition(this);

      long newHash = pawnMovedBoard.getZobristHash();
      newHash = ZobristHashing.updateHashPromotion(
              newHash,
              this.promotedPawn,
              this.promotionPiece,
              this.getDestinationCoordinate()
      );
      builder.setZobristHash(newHash);

      return builder.build();
    }

    /**
     * Updates the Zobrist hash for this pawn promotion move.
     *
     * @param currentHash The current board hash.
     * @return The updated hash.
     */
    @Override
    protected long updateZobristHash(final long currentHash) {
      long hash = super.updateZobristHash(currentHash);

      hash = ZobristHashing.updateHashPromotion(
              hash,
              this.promotedPawn,
              this.promotionPiece,
              this.getDestinationCoordinate()
      );

      return hash;
    }

    /**
     * Checks if the pawn promotion move is an attack on an opponent's piece.
     *
     * @return True if it's an attack, false otherwise.
     */
    @Override
    public boolean isAttack() {
      return this.decoratedMove.isAttack();
    }

    /**
     * Gets the piece attacked by this pawn promotion move.
     *
     * @return The attacked piece, or null if it's not an attack move.
     */
    @Override
    public Piece getAttackedPiece() {
      return this.decoratedMove.getAttackedPiece();
    }

    /**
     * Generates a string representation of the pawn promotion move.
     *
     * @return The string representing the move, e.g., "e8=Q" for a pawn promoting to a queen.
     */
    @Override
    public String toString() {
      return BoardUtils.getPositionAtCoordinate(this.movedPiece.getPiecePosition()) + "-" +
              BoardUtils.getPositionAtCoordinate(this.destinationCoordinate) + "=" + this.promotionPiece.getPieceType();
    }
  }


  /**
   * The MajorMove class represents a standard major move in chess. This move is typically
   * associated with major pieces (queen, rook, knight, or bishop) and involves the piece
   * moving to a new destination coordinate on the board without capturing any opponent's piece.
   */
  public static class MajorMove extends Move {

    /**
     * Constructs a MajorMove instance with the provided board, piece, and destination coordinate.
     *
     * @param board                 The chess board on which the move is made.
     * @param pieceMoved            The major piece (queen, rook, bishop, or knight) that is moved.
     * @param destinationCoordinate The coordinate to which the piece is moved.
     */
    public MajorMove(final Board board, final Piece pieceMoved, final int destinationCoordinate) {
      super(board, pieceMoved, destinationCoordinate);
    }

    /**
     * Reset method for object pooling.
     *
     * @return The reset move object.
     */
    @Override
    protected Move reset() {
      return this;
    }

    /**
     * Resets the MajorMove with new values for object pooling.
     *
     * @param board The chess board for this move.
     * @param pieceMoved The piece being moved.
     * @param destinationCoordinate The destination coordinate.
     * @return The reset MajorMove instance.
     */
    public MajorMove reset(final Board board, final Piece pieceMoved, final int destinationCoordinate) {
      this.board = board;
      this.movedPiece = pieceMoved;
      this.destinationCoordinate = destinationCoordinate;
      this.isFirstMove = pieceMoved != null && pieceMoved.isFirstMove();
      return this;
    }

    /**
     * Checks if two MajorMove instances are equal by comparing their attributes.
     *
     * @param other The object to compare with this MajorMove.
     * @return True if the objects are equal, false otherwise.
     */
    @Override
    public boolean equals(final Object other) {
      return this == other || other instanceof MajorMove && super.equals(other);
    }

    /**
     * Generates a string representation of the major move.
     *
     * @return The string representing the move, e.g., "Qd4" for a queen move to d4.
     */
    @Override
    public String toString() {
      return movedPiece.getPieceType().toString() + disambiguationFile() +
              BoardUtils.getPositionAtCoordinate(this.destinationCoordinate);
    }
  }

  /**
   * The MajorAttackMove class represents a major attack move in chess. This type of move
   * involves a major piece (queen, rook, knight, or bishop) moving to a new destination
   * coordinate and capturing an opponent's piece.
   */
  public static class MajorAttackMove extends AttackMove {

    /**
     * Constructs a MajorAttackMove instance with the provided board, moving piece,
     * destination coordinate, and the attacked piece.
     *
     * @param board                 The chess board on which the move is made.
     * @param pieceMoved            The major piece (queen, rook, bishop, or knight) that is moved.
     * @param destinationCoordinate The coordinate to which the piece is moved.
     * @param pieceAttacked         The opponent's piece that is being attacked and captured.
     */
    public MajorAttackMove(final Board board, final Piece pieceMoved, final int destinationCoordinate, final Piece pieceAttacked) {
      super(board, pieceMoved, destinationCoordinate, pieceAttacked);
    }

    /**
     * Reset method for object pooling.
     *
     * @return The reset move object.
     */
    @Override
    protected Move reset() {
      return this;
    }

    /**
     * Resets the MajorAttackMove with new values for object pooling.
     *
     * @param board The chess board for this move.
     * @param pieceMoved The piece being moved.
     * @param destinationCoordinate The destination coordinate.
     * @param pieceAttacked The piece being attacked and captured.
     * @return The reset MajorAttackMove instance.
     */
    public MajorAttackMove reset(final Board board, final Piece pieceMoved, final int destinationCoordinate, final Piece pieceAttacked) {
      this.board = board;
      this.movedPiece = pieceMoved;
      this.destinationCoordinate = destinationCoordinate;
      this.isFirstMove = pieceMoved != null && pieceMoved.isFirstMove();
      this.attackedPiece = pieceAttacked;
      return this;
    }

    /**
     * Checks if two MajorAttackMove instances are equal by comparing their attributes.
     *
     * @param other The object to compare with this MajorAttackMove.
     * @return True if the objects are equal, false otherwise.
     */
    @Override
    public boolean equals(final Object other) {
      return this == other || other instanceof MajorAttackMove && super.equals(other);
    }

    /**
     * Generates a string representation of the major attack move.
     *
     * @return The string representing the move, e.g., "Qxd4" for a queen capturing a piece on d4.
     */
    @Override
    public String toString() {
      return movedPiece.getPieceType() + disambiguationFile() + "x" +
              BoardUtils.getPositionAtCoordinate(this.destinationCoordinate);
    }
  }

  /**
   * The PawnMove class represents a basic pawn move in chess. This move involves a pawn
   * advancing to a new destination coordinate without capturing any opponent's piece.
   */
  public static class PawnMove extends Move {

    /**
     * Constructs a PawnMove instance with the provided board, moving piece, and destination coordinate.
     *
     * @param board                 The chess board on which the move is made.
     * @param pieceMoved            The pawn piece that is moved.
     * @param destinationCoordinate The coordinate to which the piece is moved.
     */
    public PawnMove(final Board board, final Piece pieceMoved, final int destinationCoordinate) {
      super(board, pieceMoved, destinationCoordinate);
    }

    /**
     * Reset method for object pooling.
     *
     * @return The reset move object.
     */
    @Override
    protected Move reset() {
      return this;
    }

    /**
     * Resets the PawnMove with new values for object pooling.
     *
     * @param board The chess board for this move.
     * @param pieceMoved The pawn being moved.
     * @param destinationCoordinate The destination coordinate.
     * @return The reset PawnMove instance.
     */
    public PawnMove reset(final Board board, final Piece pieceMoved, final int destinationCoordinate) {
      this.board = board;
      this.movedPiece = pieceMoved;
      this.destinationCoordinate = destinationCoordinate;
      this.isFirstMove = pieceMoved != null && pieceMoved.isFirstMove();
      return this;
    }

    /**
     * Checks if two PawnMove instances are equal by comparing their attributes.
     *
     * @param other The object to compare with this PawnMove.
     * @return True if the objects are equal, false otherwise.
     */
    @Override
    public boolean equals(final Object other) {
      return this == other || other instanceof PawnMove && super.equals(other);
    }

    /**
     * Generates a string representation of the pawn move.
     *
     * @return The string representing the move, e.g., "e4" for a pawn moving to e4.
     */
    @Override
    public String toString() {
      return BoardUtils.getPositionAtCoordinate(this.destinationCoordinate);
    }
  }

  /**
   * The PawnAttackMove class represents a pawn attack move in chess. This type of move
   * involves a pawn moving to a new destination coordinate and capturing an opponent's piece.
   */
  public static class PawnAttackMove extends AttackMove {

    /**
     * Constructs a PawnAttackMove instance with the provided board, moving piece,
     * destination coordinate, and the attacked piece.
     *
     * @param board                 The chess board on which the move is made.
     * @param pieceMoved            The pawn piece that is moved.
     * @param destinationCoordinate The coordinate to which the piece is moved.
     * @param pieceAttacked         The opponent's piece that is being attacked and captured.
     */
    public PawnAttackMove(final Board board, final Piece pieceMoved, final int destinationCoordinate, final Piece pieceAttacked) {
      super(board, pieceMoved, destinationCoordinate, pieceAttacked);
    }

    /**
     * Reset method for object pooling.
     *
     * @return The reset move object.
     */
    @Override
    protected Move reset() {
      return this;
    }

    /**
     * Resets the PawnAttackMove with new values for object pooling.
     *
     * @param board The chess board for this move.
     * @param pieceMoved The pawn being moved.
     * @param destinationCoordinate The destination coordinate.
     * @param pieceAttacked The piece being attacked and captured.
     * @return The reset PawnAttackMove instance.
     */
    public PawnAttackMove reset(final Board board, final Piece pieceMoved, final int destinationCoordinate, final Piece pieceAttacked) {
      this.board = board;
      this.movedPiece = pieceMoved;
      this.destinationCoordinate = destinationCoordinate;
      this.isFirstMove = pieceMoved != null && pieceMoved.isFirstMove();
      this.attackedPiece = pieceAttacked;
      return this;
    }

    /**
     * Checks if two PawnAttackMove instances are equal by comparing their attributes.
     *
     * @param other The object to compare with this PawnAttackMove.
     * @return True if the objects are equal, false otherwise.
     */
    @Override
    public boolean equals(final Object other) {
      return this == other || other instanceof PawnAttackMove && super.equals(other);
    }

    /**
     * Generates a string representation of the pawn attack move.
     *
     * @return The string representing the move, e.g., "exd4" for a pawn capturing a piece on d4.
     */
    @Override
    public String toString() {
      return BoardUtils.getPositionAtCoordinate(this.movedPiece.getPiecePosition()).charAt(0) + "x" +
              BoardUtils.getPositionAtCoordinate(this.destinationCoordinate);
    }
  }

  private static long clearCastlingRightsForMove(long hash, final Board board, final Piece movedPiece, final int fromPosition) {
    if (movedPiece.getPieceType() == Piece.PieceType.KING) {
      final boolean isWhite = movedPiece.getPieceAllegiance().isWhite();
      if (isWhite) {
        if (ZobristHashing.canCastle(board, true, true)) {
          hash = ZobristHashing.updateHashCastlingRight(hash, 0);
        }
        if (ZobristHashing.canCastle(board, true, false)) {
          hash = ZobristHashing.updateHashCastlingRight(hash, 1);
        }
      } else {
        if (ZobristHashing.canCastle(board, false, true)) {
          hash = ZobristHashing.updateHashCastlingRight(hash, 2);
        }
        if (ZobristHashing.canCastle(board, false, false)) {
          hash = ZobristHashing.updateHashCastlingRight(hash, 3);
        }
      }
    } else if (movedPiece.getPieceType() == Piece.PieceType.ROOK) {
      final boolean isWhite = movedPiece.getPieceAllegiance().isWhite();
      if (isWhite) {
        if (fromPosition == 0 && ZobristHashing.canCastle(board, true, false)) {
          hash = ZobristHashing.updateHashCastlingRight(hash, 1);
        } else if (fromPosition == 7 && ZobristHashing.canCastle(board, true, true)) {
          hash = ZobristHashing.updateHashCastlingRight(hash, 0);
        }
      } else {
        if (fromPosition == 56 && ZobristHashing.canCastle(board, false, false)) {
          hash = ZobristHashing.updateHashCastlingRight(hash, 3);
        } else if (fromPosition == 63 && ZobristHashing.canCastle(board, false, true)) {
          hash = ZobristHashing.updateHashCastlingRight(hash, 2);
        }
      }
    }
    return hash;
  }

  /**
   * The PawnEnPassantAttack class represents a special pawn attack move called "en passant" in chess.
   * En passant is a situation where a pawn captures an opponent's pawn that has moved two squares
   * forward from its starting position, as if the opponent's pawn had moved only one square.
   */
  public static class PawnEnPassantAttack extends PawnAttackMove {

    /**
     * Constructs a PawnEnPassantAttack instance with the provided board,
     * moving a piece, destination coordinate, and piece attacked.
     *
     * @param board                 The chess board on which the move is made.
     * @param pieceMoved            The pawn piece that is moved.
     * @param destinationCoordinate The coordinate to which the piece is moved.
     * @param pieceAttacked         The opponent's pawn piece that is attacked en passant.
     */
    public PawnEnPassantAttack(final Board board, final Piece pieceMoved, final int destinationCoordinate, final Piece pieceAttacked) {
      super(board, pieceMoved, destinationCoordinate, pieceAttacked);
    }

    /**
     * Resets the PawnEnPassantAttack with new values for object pooling.
     *
     * @param board The chess board for this move.
     * @param pieceMoved The pawn being moved.
     * @param destinationCoordinate The destination coordinate.
     * @param pieceAttacked The pawn being captured en passant.
     * @return The reset PawnEnPassantAttack instance.
     */
    public PawnEnPassantAttack reset(final Board board, final Piece pieceMoved, final int destinationCoordinate, final Piece pieceAttacked) {
      this.board = board;
      this.movedPiece = pieceMoved;
      this.destinationCoordinate = destinationCoordinate;
      this.isFirstMove = pieceMoved != null && pieceMoved.isFirstMove();
      this.attackedPiece = pieceAttacked;
      return this;
    }

    /**
     * Checks if two PawnEnPassantAttack instances are equal by comparing their attributes.
     *
     * @param other The object to compare with this PawnEnPassantAttack.
     * @return True if the objects are equal, false otherwise.
     */
    @Override
    public boolean equals(final Object other) {
      return this == other || other instanceof PawnEnPassantAttack && super.equals(other);
    }

    /**
     * Executes the "en passant" pawn attack move, updating the board accordingly.
     * This method creates a new board with pieces moved and removed due to the en passant capture.
     *
     * @return The resulting board after the en passant capture.
     */
    @Override
    public Board execute() {
      final Board.Builder builder = new Builder();

      for (Piece piece : this.board.currentPlayer().getActivePieces()) {
        if (piece != this.movedPiece) {
          builder.setPiece(piece);
        }
      }

      for (Piece piece : this.board.currentPlayer().getOpponent().getActivePieces()) {
        if (piece != this.getAttackedPiece()) {
          builder.setPiece(piece);
        }
      }

      builder.setPiece(this.movedPiece.movePiece(this));
      builder.setMoveMaker(this.board.currentPlayer().getOpponent().getAlliance());
      builder.setMoveTransition(this);

      long newHash = updateZobristHash(this.board.getZobristHash());
      builder.setZobristHash(newHash);

      return builder.build();
    }

    /**
     * Undoes the "en passant" pawn attack move, reverting the board to the state before the capture.
     * This method restores the captured pawn and resets the board state.
     *
     * @return The board state before the en passant capture.
     */
    @Override
    public Board undo() {
      final Board.Builder builder = new Builder();
      this.board.getAllPieces().forEach(builder::setPiece);
      builder.setEnPassantPawn((Pawn) this.getAttackedPiece());
      builder.setMoveMaker(this.board.currentPlayer().getAlliance());
      return builder.build();
    }
  }

  /**
   * The PawnJump class represents a pawn move where a pawn advances two squares from its starting position.
   * This special move is only available on a pawn's first move and creates an en passant opportunity
   * for opponent pawns on adjacent files.
   */
  public static class PawnJump extends Move {

    /**
     * Constructs a PawnJump instance with the provided board, moving pawn, and destination coordinate.
     *
     * @param board                 The chess board on which the move is made.
     * @param pieceMoved            The pawn piece that is moved.
     * @param destinationCoordinate The coordinate to which the piece is moved.
     */
    public PawnJump(final Board board, final Pawn pieceMoved, final int destinationCoordinate) {
      super(board, pieceMoved, destinationCoordinate);
    }

    /**
     * Reset method for object pooling.
     *
     * @return The reset move object.
     */
    @Override
    protected Move reset() {
      return this;
    }

    /**
     * Resets the PawnJump with new values for object pooling.
     *
     * @param board The chess board for this move.
     * @param pieceMoved The pawn being jumped.
     * @param destinationCoordinate The destination coordinate.
     * @return The reset PawnJump instance.
     */
    public PawnJump reset(final Board board, final Piece pieceMoved, final int destinationCoordinate) {
      this.board = board;
      this.movedPiece = pieceMoved;
      this.destinationCoordinate = destinationCoordinate;
      this.isFirstMove = pieceMoved != null && pieceMoved.isFirstMove();
      return this;
    }

    /**
     * Checks if two PawnJump instances are equal by comparing their attributes.
     *
     * @param other The object to compare with this PawnJump.
     * @return True if the objects are equal, false otherwise.
     */
    @Override
    public boolean equals(final Object other) {
      return this == other || other instanceof PawnJump && super.equals(other);
    }

    /**
     * Executes the pawn jump move, updating the board accordingly. This method creates
     * a new board with the pawn moved to the destination coordinate, an en passant pawn set,
     * and the move transition recorded.
     *
     * @return The resulting board after the pawn jump move.
     */
    @Override
    public Board execute() {
      final Board.Builder builder = new Builder();

      for (Piece piece : this.board.currentPlayer().getActivePieces()) {
        if (piece != this.movedPiece) {
          builder.setPiece(piece);
        }
      }

      for (Piece piece : this.board.currentPlayer().getOpponent().getActivePieces()) {
        builder.setPiece(piece);
      }

      final Pawn movedPawn = (Pawn) this.movedPiece.movePiece(this);
      builder.setPiece(movedPawn);
      builder.setEnPassantPawn(movedPawn);
      builder.setMoveMaker(this.board.currentPlayer().getOpponent().getAlliance());
      builder.setMoveTransition(this);

      long newHash = updateZobristHash(this.board.getZobristHash());
      builder.setZobristHash(newHash);

      return builder.build();
    }

    /**
     * Updates the Zobrist hash for this pawn jump move.
     *
     * @param currentHash The current board hash.
     * @return The updated hash.
     */
    @Override
    protected long updateZobristHash(final long currentHash) {
      long hash = super.updateZobristHash(currentHash);

      final int enPassantFile = this.getDestinationCoordinate() % 8;
      hash = ZobristHashing.updateHashEnPassant(hash, enPassantFile);

      return hash;
    }

    /**
     * Returns a string representation of the PawnJump move, showing only the destination coordinate.
     *
     * @return A string representing the PawnJump move.
     */
    @Override
    public String toString() {
      return BoardUtils.getPositionAtCoordinate(this.destinationCoordinate);
    }
  }


  /**
   * The CastleMove class represents a move that involves castling, which is a special king and rook move.
   * This abstract class provides the base functionality for king-side and queen-side castling moves.
   */
  static abstract class CastleMove extends Move {

    /** The rook involved in the castling move. */
    protected Rook castleRook;

    /** The starting position of the castle rook. */
    protected int castleRookStart;

    /** The destination position of the castle rook. */
    protected int castleRookDestination;

    /**
     * Constructs a CastleMove instance with the provided board, moving piece, destination coordinate,
     * castle rook, and rook start and destination positions.
     *
     * @param board                 The chess board on which the move is made.
     * @param pieceMoved            The piece being moved (usually the king).
     * @param destinationCoordinate The coordinate to which the piece is moved.
     * @param castleRook            The rook involved in the castling move.
     * @param castleRookStart       The starting position of the castle rook.
     * @param castleRookDestination The destination position of the castle rook.
     */
    CastleMove(final Board board,
               final Piece pieceMoved,
               final int destinationCoordinate,
               final Rook castleRook,
               final int castleRookStart,
               final int castleRookDestination) {
      super(board, pieceMoved, destinationCoordinate);
      this.castleRook = castleRook;
      this.castleRookStart = castleRookStart;
      this.castleRookDestination = castleRookDestination;
    }

    /**
     * Gets the rook involved in the castling move.
     *
     * @return The castle rook.
     */
    Rook getCastleRook() {
      return this.castleRook;
    }

    /**
     * Checks if this move is a castling move.
     *
     * @return True since it is a castling move.
     */
    @Override
    public boolean isCastlingMove() {
      return true;
    }

    /**
     * Executes the castling move on the chess board. This method creates a new board with
     * the pieces moved as part of the castling move, records the move transition, and
     * updates the board state accordingly.
     *
     * @return The resulting board after the castling move.
     */
    @Override
    public Board execute() {
      final Board.Builder builder = new Builder();

      for (final Piece piece: this.board.getAllPieces()) {
        if (piece != this.movedPiece && piece != this.castleRook) {
          builder.setPiece(piece);
        }
      }

      builder.setPiece(this.movedPiece.movePiece(this));
      builder.setPiece(new Rook(this.castleRook.getPieceAllegiance(), this.castleRookDestination, false, 1));
      builder.setMoveMaker(this.board.currentPlayer().getOpponent().getAlliance());
      builder.setMoveTransition(this);

      long newHash = updateZobristHash(this.board.getZobristHash());
      builder.setZobristHash(newHash);

      return builder.build();
    }

    /**
     * Updates the Zobrist hash for this castling move.
     *
     * @param currentHash The current board hash.
     * @return The updated hash.
     */
    @Override
    protected long updateZobristHash(final long currentHash) {
      long hash = ZobristHashing.updateHashCastle(
              currentHash,
              this.movedPiece,
              this.castleRook,
              this.getCurrentCoordinate(),
              this.getDestinationCoordinate(),
              this.castleRookStart,
              this.castleRookDestination
      );

      hash = ZobristHashing.updateHashSideToMove(hash);

      if (this.board.getEnPassantPawn() != null) {
        final int enPassantFile = this.board.getEnPassantPawn().getPiecePosition() % 8;
        hash = ZobristHashing.updateHashEnPassant(hash, enPassantFile);
      }

      final boolean isWhite = this.movedPiece.getPieceAllegiance().isWhite();
      if (isWhite) {
        if (ZobristHashing.canCastle(this.board, true, true)) {
          hash = ZobristHashing.updateHashCastlingRight(hash, 0);
        }
        if (ZobristHashing.canCastle(this.board, true, false)) {
          hash = ZobristHashing.updateHashCastlingRight(hash, 1);
        }
      } else {
        if (ZobristHashing.canCastle(this.board, false, true)) {
          hash = ZobristHashing.updateHashCastlingRight(hash, 2);
        }
        if (ZobristHashing.canCastle(this.board, false, false)) {
          hash = ZobristHashing.updateHashCastlingRight(hash, 3);
        }
      }

      return hash;
    }

    /**
     * Generates a hash code for this castling move, considering its attributes.
     *
     * @return The hash code for the castling move.
     */
    @Override
    public int hashCode() {
      return Objects.hash(super.hashCode(), castleRook);
    }

    /**
     * Checks if two CastleMove instances are equal by comparing their attributes.
     *
     * @param other The object to compare with this CastleMove.
     * @return True if the objects are equal, false otherwise.
     */
    @Override
    public boolean equals(final Object other) {
      if (this == other) return true;
      if (!(other instanceof CastleMove otherCastleMove)) return false;
      return super.equals(otherCastleMove) && this.castleRook.equals(otherCastleMove.getCastleRook());
    }
  }


  /**
   * The KingSideCastleMove class represents a move for king-side castling in chess.
   * King-side castling involves the king moving two squares towards the h-file (kingside)
   * and the rook moving to the square the king crossed.
   */
  public static class KingSideCastleMove extends CastleMove {

    /**
     * Constructs a KingSideCastleMove instance with the provided board, moving piece, destination coordinate,
     * castle rook, and rook start and destination positions.
     *
     * @param board                 The chess board on which the move is made.
     * @param pieceMoved            The piece being moved (usually the king).
     * @param destinationCoordinate The coordinate to which the piece is moved.
     * @param castleRook            The rook involved in the castling move.
     * @param castleRookStart       The starting position of the castle rook.
     * @param castleRookDestination The destination position of the castle rook.
     */
    public KingSideCastleMove(final Board board,
                              final Piece pieceMoved,
                              final int destinationCoordinate,
                              final Rook castleRook,
                              final int castleRookStart,
                              final int castleRookDestination) {
      super(board, pieceMoved, destinationCoordinate, castleRook, castleRookStart, castleRookDestination);
    }

    /**
     * Reset method for object pooling.
     *
     * @return The reset move object.
     */
    @Override
    protected Move reset() {
      return this;
    }

    /**
     * Resets the KingSideCastleMove with new values for object pooling.
     *
     * @param board The chess board for this move.
     * @param pieceMoved The king being moved.
     * @param destinationCoordinate The destination coordinate for the king.
     * @param castleRook The rook being moved in this castling operation.
     * @param castleRookStart The starting position of the rook.
     * @param castleRookDestination The destination position of the rook.
     * @return The reset KingSideCastleMove instance.
     */
    public KingSideCastleMove reset(final Board board, final Piece pieceMoved, final int destinationCoordinate,
                                    final Rook castleRook, final int castleRookStart, final int castleRookDestination) {
      this.board = board;
      this.movedPiece = pieceMoved;
      this.destinationCoordinate = destinationCoordinate;
      this.isFirstMove = pieceMoved != null && pieceMoved.isFirstMove();
      this.castleRook = castleRook;
      this.castleRookStart = castleRookStart;
      this.castleRookDestination = castleRookDestination;
      return this;
    }

    /**
     * Checks if two KingSideCastleMove instances are equal by comparing their attributes.
     *
     * @param other The object to compare with this KingSideCastleMove.
     * @return True if the objects are equal, false otherwise.
     */
    @Override
    public boolean equals(final Object other) {
      if (this == other) return true;
      if (!(other instanceof KingSideCastleMove otherKingSideCastleMove)) return false;
      return super.equals(otherKingSideCastleMove) && this.castleRook.equals(otherKingSideCastleMove.getCastleRook());
    }

    /**
     * Returns a string representation of the KingSideCastleMove, indicating king-side castling.
     *
     * @return The string "O-O" representing king-side castling.
     */
    @Override
    public String toString() {
      return "O-O";
    }
  }


  /**
   * The QueenSideCastleMove class represents a move for queen-side castling in chess.
   * Queen-side castling involves the king moving two squares towards the a-file (queenside)
   * and the rook moving to the square the king crossed.
   */
  public static class QueenSideCastleMove extends CastleMove {

    /**
     * Constructs a QueenSideCastleMove instance with the provided board, moving piece, destination coordinate,
     * castle rook, and rook start and destination positions.
     *
     * @param board                 The chess board on which the move is made.
     * @param pieceMoved            The piece being moved (usually the king).
     * @param destinationCoordinate The coordinate to which the piece is moved.
     * @param castleRook            The rook involved in the castling move.
     * @param castleRookStart       The starting position of the castle rook.
     * @param rookCastleDestination The destination position of the castle rook.
     */
    public QueenSideCastleMove(final Board board,
                               final Piece pieceMoved,
                               final int destinationCoordinate,
                               final Rook castleRook,
                               final int castleRookStart,
                               final int rookCastleDestination) {
      super(board, pieceMoved, destinationCoordinate, castleRook, castleRookStart, rookCastleDestination);
    }

    /**
     * Reset method for object pooling.
     *
     * @return The reset move object.
     */
    @Override
    protected Move reset() {
      return this;
    }

    /**
     * Resets the QueenSideCastleMove with new values for object pooling.
     *
     * @param board The chess board for this move.
     * @param pieceMoved The king being moved.
     * @param destinationCoordinate The destination coordinate for the king.
     * @param castleRook The rook being moved in this castling operation.
     * @param castleRookStart The starting position of the rook.
     * @param castleRookDestination The destination position of the rook.
     * @return The reset QueenSideCastleMove instance.
     */
    public QueenSideCastleMove reset(final Board board, final Piece pieceMoved, final int destinationCoordinate,
                                     final Rook castleRook, final int castleRookStart, final int castleRookDestination) {
      this.board = board;
      this.movedPiece = pieceMoved;
      this.destinationCoordinate = destinationCoordinate;
      this.isFirstMove = pieceMoved != null && pieceMoved.isFirstMove();
      this.castleRook = castleRook;
      this.castleRookStart = castleRookStart;
      this.castleRookDestination = castleRookDestination;
      return this;
    }

    /**
     * Checks if two QueenSideCastleMove instances are equal by comparing their attributes.
     *
     * @param other The object to compare with this QueenSideCastleMove.
     * @return True if the objects are equal, false otherwise.
     */
    @Override
    public boolean equals(final Object other) {
      if (this == other) return true;
      if (!(other instanceof QueenSideCastleMove otherQueenSideCastleMove)) return false;
      return super.equals(otherQueenSideCastleMove) && this.castleRook.equals(otherQueenSideCastleMove.getCastleRook());
    }

    /**
     * Returns a string representation of the QueenSideCastleMove, indicating queen-side castling.
     *
     * @return The string "O-O-O" representing queen-side castling.
     */
    @Override
    public String toString() {
      return "O-O-O";
    }
  }

  /**
   * The AttackMove class represents a move that results in attacking an opponent's piece in chess.
   * This abstract class provides the base functionality for all types of attack moves.
   */
  static abstract class AttackMove extends Move {

    /** The piece that is attacked as a result of this move. */
    protected Piece attackedPiece;

    /**
     * Constructs an AttackMove instance with the provided board, moving piece, destination coordinate,
     * and the piece being attacked.
     *
     * @param board                 The chess board on which the move is made.
     * @param pieceMoved            The piece being moved.
     * @param destinationCoordinate The coordinate to which the piece is moved.
     * @param pieceAttacked         The piece that is attacked in this move.
     */
    AttackMove(final Board board, final Piece pieceMoved,
               final int destinationCoordinate, final Piece pieceAttacked) {
      super(board, pieceMoved, destinationCoordinate);
      this.attackedPiece = pieceAttacked;
    }

    /**
     * Calculates a hash code for the AttackMove by combining the hash codes of the moving piece and the attacked piece.
     *
     * @return The hash code for this AttackMove.
     */
    @Override
    public int hashCode() {
      return Objects.hash(super.hashCode(), attackedPiece);
    }

    /**
     * Checks if two AttackMove instances are equal by comparing their attributes.
     *
     * @param other The object to compare with this AttackMove.
     * @return True if the objects are equal, false otherwise.
     */
    @Override
    public boolean equals(final Object other) {
      if (this == other) return true;
      if (!(other instanceof AttackMove otherAttackMove)) return false;
      return super.equals(otherAttackMove) && getAttackedPiece().equals(otherAttackMove.getAttackedPiece());
    }

    /**
     * Gets the piece attacked as a result of this move.
     *
     * @return The attacked piece.
     */
    @Override
    public Piece getAttackedPiece() {
      return this.attackedPiece;
    }

    /**
     * Indicates whether this move is an attack on an opponent's piece.
     *
     * @return True for an attack move, false otherwise.
     */
    @Override
    public boolean isAttack() {
      return true;
    }

    /**
     * Updates the Zobrist hash for this attack move.
     *
     * @param currentHash The current board hash.
     * @return The updated hash.
     */
    @Override
    protected long updateZobristHash(final long currentHash) {
      long hash = ZobristHashing.updateHashPieceCapture(
              currentHash,
              this.attackedPiece
      );

      hash = ZobristHashing.updateHashPieceMove(
              hash,
              this.movedPiece,
              this.getCurrentCoordinate(),
              this.getDestinationCoordinate()
      );

      hash = ZobristHashing.updateHashSideToMove(hash);
      hash = clearCastlingRightsForMove(hash, this.board, this.movedPiece, this.getCurrentCoordinate());
      if (this.attackedPiece.getPieceType() == Piece.PieceType.ROOK) {
        final boolean isWhiteRook = this.attackedPiece.getPieceAllegiance().isWhite();
        final int rookPosition = this.attackedPiece.getPiecePosition();

        if (isWhiteRook) {
          if (rookPosition == 0 && ZobristHashing.canCastle(this.board, true, false)) {
            hash = ZobristHashing.updateHashCastlingRight(hash, 1);
          } else if (rookPosition == 7 && ZobristHashing.canCastle(this.board, true, true)) {
            hash = ZobristHashing.updateHashCastlingRight(hash, 0);
          }
        } else {
          if (rookPosition == 56 && ZobristHashing.canCastle(this.board, false, false)) {
            hash = ZobristHashing.updateHashCastlingRight(hash, 3);
          } else if (rookPosition == 63 && ZobristHashing.canCastle(this.board, false, true)) {
            hash = ZobristHashing.updateHashCastlingRight(hash, 2);
          }
        }
      }

      if (this.board.getEnPassantPawn() != null) {
        final int enPassantFile = this.board.getEnPassantPawn().getPiecePosition() % 8;
        hash = ZobristHashing.updateHashEnPassant(hash, enPassantFile);
      }
      return hash;
    }
  }

  /**
   * The NullMove class represents a placeholder for no move in a chess game.
   * It is used as a sentinel value to indicate the absence of a valid move.
   */
  public static class NullMove extends Move {

    /**
     * Constructs a NullMove instance. A null move has no board or destination coordinate.
     */
    public NullMove() {
      super(null, -1);
    }

    /**
     * Reset method for object pooling.
     *
     * @return The reset move object.
     */
    @Override
    protected Move reset() {
      return this;
    }

    /**
     * Provides a safe implementation of hashCode for NullMove that doesn't rely on movedPiece.
     *
     * @return A constant hash code for NullMove.
     */
    @Override
    public int hashCode() {
      return 0;
    }

    /**
     * Gets the current coordinate, which is set to -1 for a null move.
     *
     * @return The current coordinate, which is -1.
     */
    @Override
    public int getCurrentCoordinate() {
      return -1;
    }

    /**
     * Gets the destination coordinate, which is also set to -1 for a null move.
     *
     * @return The destination coordinate, which is -1.
     */
    @Override
    public int getDestinationCoordinate() {
      return -1;
    }

    /**
     * Throws a runtime exception because a null move cannot be executed.
     *
     * @return A runtime exception with the message "cannot execute null move!"
     * @throws RuntimeException Always thrown when attempting to execute a null move.
     */
    @Override
    public Board execute() {
      throw new RuntimeException("Cannot execute null move!");
    }

    /**
     * Updates the Zobrist hash for a null move.
     * This is a no-op as null moves should not be executed.
     *
     * @param currentHash The current board hash.
     * @return The unchanged hash.
     */
    @Override
    protected long updateZobristHash(final long currentHash) {
      return currentHash;
    }

    /**
     * Gets a string representation of the null move.
     *
     * @return The string "Null Move."
     */
    @Override
    public String toString() {
      return "Null Move";
    }
  }

  /**
   * The MoveFactory class provides factory methods for creating different types of moves.
   * It ensures that moves are created consistently and efficiently.
   */
  public static class MoveFactory {

    /**
     * Private constructor to prevent instantiation.
     *
     * @throws RuntimeException Always thrown when attempting to instantiate MoveFactory.
     */
    private MoveFactory() {
      throw new RuntimeException("Not instantiatable!");
    }

    /**
     * Creates and returns a null move.
     * A null move is used as a sentinel value to indicate the absence of a valid move.
     *
     * @return The created null move.
     */
    public static Move getNullMove() {
      return MoveUtils.NULL_MOVE;
    }

    /**
     * Creates a move with the specified current and destination coordinates on the given board.
     * This method searches through the legal moves on the board to find a matching move.
     *
     * @param board The current state of the chess board.
     * @param currentCoordinate The current coordinate of the moved piece.
     * @param destinationCoordinate The destination coordinate of the move.
     * @return The created move, or a null move if no matching legal move is found.
     */
    public static Move createMove(final Board board,
                                  final int currentCoordinate,
                                  final int destinationCoordinate) {
      for (final Move move: board.getAllLegalMoves()) {
        if (move.getCurrentCoordinate() == currentCoordinate &&
                move.getDestinationCoordinate() == destinationCoordinate) {
          return move;
        }
      } return MoveUtils.NULL_MOVE;
    }
  }
}