package engine.forBoard;

import engine.Alliance;
import engine.forPiece.*;
import engine.forPlayer.BlackPlayer;
import engine.forPlayer.Player;
import engine.forPlayer.WhitePlayer;

import java.util.*;

import static engine.forBoard.Move.MoveFactory.getNullMove;

/**
 * The Board class represents a chess board containing 64 tiles and chess pieces.
 * It provides methods for creating and maintaining the chess board state, managing pieces
 * and players, and calculating legal moves. The board keeps track of white and black pieces,
 * the current player, en passant opportunities, and the transition move that created the current state.
 * <p>
 * A board built through {@link Builder} still behaves as a fixed snapshot in the sense that
 * nothing else mutates it implicitly. In addition, a board now supports being mutated in place
 * through {@link #makeMove(Move)} and {@link #unmakeMove()}, which relocate pieces and update
 * state directly rather than constructing a new Board instance, pushing an undo record onto an
 * internal stack so the change can be exactly reversed. This mutating path is the only way a
 * move is applied anywhere in the engine; there is no longer an immutable transition that builds
 * a fresh board per move.
 *
 * @author Aaron Ho
 * @author dareTo81
 */
public final class Board {

  /** A mutable map representing the configuration of pieces on the board, keyed by tile coordinate. */
  private Map<Integer, Piece> boardConfig;

  /** The white pieces currently on the board. Kept in sync with boardConfig by placePiece/removePiece. */
  private List<Piece> whitePieces;

  /** The black pieces currently on the board. Kept in sync with boardConfig by placePiece/removePiece. */
  private List<Piece> blackPieces;

  /** The player controlling the white pieces on the board. Rebuilt whenever the board is mutated. */
  private WhitePlayer whitePlayer;

  /** The player controlling the black pieces on the board. Rebuilt whenever the board is mutated. */
  private BlackPlayer blackPlayer;

  /** The player whose turn it currently is to move. */
  public Player currentPlayer;

  /**
   * The pawn that is susceptible to en passant capture in the current position.
   * Null if no en passant opportunity exists.
   */
  private Pawn enPassantPawn;

  /**
   * The move that resulted in the current board position.
   * A null move if this is the initial board position.
   */
  private Move transitionMove;

  /** The Zobrist hash value for this board position, used for position identification. */
  private long zobristHash;

  /** The number of plies since the last pawn move or capture, tracked for the fifty-move rule. */
  private int halfMoveClock;

  /** The undo records for moves applied through {@link #makeMove(Move)} but not yet unmade. */
  private final Deque<UndoState> undoStack = new ArrayDeque<>();

  /**
   * The undo records for null moves applied through {@link #makeNullMove()} but not yet unmade.
   * Kept separate from {@link #undoStack} so a null move can never be popped in place of a real
   * move. The two stacks must still be unwound in strict last-in-first-out order relative to each
   * other: a move made after a null move must be unmade before that null move is unmade.
   */
  private final Deque<NullMoveUndo> nullMoveUndoStack = new ArrayDeque<>();

  /**
   * A running count of how many times each Zobrist hash has been reached along the current
   * make/unmake path, maintained by {@link #makeMove(Move)} and {@link #unmakeMove()} for
   * threefold repetition detection. This tracks repetition only along this board instance's own
   * make/unmake path, not a full game history supplied from outside it.
   */
  private final Map<Long, Integer> positionCounts = new HashMap<>();

  /**
   * A pre-constructed standard chess board configuration representing the starting position.
   * This is a constant instance shared across all instances of the Board class.
   */
  private static final Board STANDARD_BOARD = createStandardBoardImpl();

  /**
   * Constructs a board from a builder object containing the board configuration.
   *
   * @param builder The builder object containing board configuration details.
   */
  private Board(final Builder builder) {
    this.boardConfig = new HashMap<>(builder.BoardConfigurations);
    this.whitePieces = new ArrayList<>(calculateActivePieces(builder, Alliance.WHITE));
    this.blackPieces = new ArrayList<>(calculateActivePieces(builder, Alliance.BLACK));
    this.enPassantPawn = builder.enPassantPawn;
    this.whitePlayer = new WhitePlayer(this, this.blackPieces);
    this.blackPlayer = new BlackPlayer(this, this.whitePieces);
    this.currentPlayer = builder.nextMoveMaker.choosePlayerByAlliance(this.whitePlayer, this.blackPlayer);
    this.transitionMove = getNullMove();
    this.zobristHash = builder.zobristHash != 0 ? builder.zobristHash :
        ZobristHashing.calculateBoardHash(this);
    this.halfMoveClock = builder.halfMoveClock;
    this.plyCount = builder.plyCount;
    this.positionCounts.put(this.zobristHash, 1);
  }

  /**
   * Constructs a board that is positionally identical to the given board, sharing its piece
   * objects but owning fresh mutable collections, so that mutating one board cannot affect the
   * other. The pieces themselves are safe to share because they are immutable.
   * <p>
   * The copy starts with empty undo stacks, so it can only be unwound back to the position it
   * was copied at, not past it. {@link #positionCounts} is seeded from the source so that a copy
   * taken mid-game carries the repetition history that preceded it.
   * <p>
   * A {@link Move} generated from a board may be applied to a board other than the exact
   * instance it was generated from, but only while both boards stand in the position the move
   * belongs to. It is not enough for the target board to be in the right position:
   * {@link Move#updateZobristHash} reads castling rights and the en passant pawn from the board
   * the move was generated against rather than from the board being mutated, so that board must
   * still be in that position too. A copy taken from a board that is never subsequently mutated
   * satisfies both halves, which is what makes a shared root move list safe to apply across
   * search threads that each own a private copy, and what lets a move found by searching a copy
   * be applied to the board the game is played on.
   *
   * @param source The board to copy.
   */
  private Board(final Board source) {
    this.boardConfig = new HashMap<>(source.boardConfig);
    this.whitePieces = new ArrayList<>(source.whitePieces);
    this.blackPieces = new ArrayList<>(source.blackPieces);
    this.enPassantPawn = source.enPassantPawn;
    this.transitionMove = source.transitionMove;
    this.zobristHash = source.zobristHash;
    this.halfMoveClock = source.halfMoveClock;
    this.plyCount = source.plyCount;
    this.positionCounts.putAll(source.positionCounts);
    refreshPlayers(source.currentPlayer.getAlliance());
  }

  /**
   * Returns an independent board in the same position as this one, which may be mutated through
   * {@link #makeMove(Move)} without affecting this board.
   *
   * @return A copy of this board.
   */
  public Board copy() {
    return new Board(this);
  }

  /**
   * Returns a string representation of the board, showing the piece configuration.
   * The board is displayed as an 8x8 grid with pieces or empty spaces.
   *
   * @return A string representation of the board.
   */
  @Override
  public String toString() {
    final StringBuilder builder = new StringBuilder();
    for (int i = 0; i < BoardUtils.NUM_TILES; i++) {
      builder.append(prettyPrint(this.boardConfig.get(i))).append(" ");
      if ((i + 1) % 8 == 0) {
        builder.append("\n");
      }
    } return builder.toString();
  }

  /**
   * Converts a piece to a string representation for display.
   * White pieces are displayed as uppercase letters, black pieces as lowercase.
   * A dash represents empty tiles.
   *
   * @param piece The piece to convert to string.
   * @return The string representation of the piece.
   */
  private static String prettyPrint(final Piece piece) {
    if(piece != null) {
      return piece.getPieceAllegiance().isBlack() ?
              piece.toString().toLowerCase() : piece.toString();
    } return "-";
  }

  /**
   * Returns a collection of black pieces currently on the board.
   *
   * @return A collection of black pieces.
   */
  public Collection<Piece> getBlackPieces() {
    return this.blackPieces;
  }

  /**
   * Returns a collection of white pieces currently on the board.
   *
   * @return A collection of white pieces.
   */
  public Collection<Piece> getWhitePieces() {
    return this.whitePieces;
  }

  /**
   * Returns a collection of all pieces on the board, both white and black.
   *
   * @return A collection of all pieces on the board.
   */
  public Collection<Piece> getAllPieces() {
    final List<Piece> allPieces = new ArrayList<>();
    allPieces.addAll(this.whitePieces);
    allPieces.addAll(this.blackPieces);
    return allPieces;
  }

  /**
   * Returns a collection of all legal moves that can be made by both players
   * on the current board.
   *
   * @return A collection of all legal moves.
   */
  public Collection<Move> getAllLegalMoves() {
    final List<Move> allLegalMoves = new ArrayList<>();
    allLegalMoves.addAll(this.whitePlayer.getLegalMoves());
    allLegalMoves.addAll(this.blackPlayer.getLegalMoves());
    return allLegalMoves;
  }

  /**
   * Retrieves the white player controlling the white pieces.
   *
   * @return The white player.
   */
  public WhitePlayer whitePlayer() {
    return this.whitePlayer;
  }

  /**
   * Retrieves the black player controlling the black pieces.
   *
   * @return The black player.
   */
  public BlackPlayer blackPlayer() {
    return this.blackPlayer;
  }

  /**
   * Retrieves the player whose turn it is to move.
   *
   * @return The current player.
   */
  public Player currentPlayer() {
    return this.currentPlayer;
  }

  /**
   * Retrieves the piece at the specified coordinate on the board.
   *
   * @param coordinate The coordinate to check (0-63).
   * @return The piece at the specified coordinate, or null if the tile is empty.
   */
  public Piece getPiece(final int coordinate) {
    return this.boardConfig.get(coordinate);
  }

  /**
   * Retrieves the pawn susceptible to en passant capture, if any.
   *
   * @return The en passant pawn, or null if no pawn is susceptible.
   */
  public Pawn getEnPassantPawn() {
    return this.enPassantPawn;
  }

  /**
   * Retrieves the move that led to the current board state.
   *
   * @return The transition move, or a null move if no transition occurred.
   */
  public Move getTransitionMove() {
    return this.transitionMove;
  }

  /**
   * Gets the Zobrist hash value for this board position.
   * This hash is used for efficient position identification and transposition table lookups.
   *
   * @return The 64-bit Zobrist hash.
   */
  public long getZobristHash() {
    return this.zobristHash;
  }

  /**
   * Returns the number of plies played since the last pawn move or capture, used by the
   * fifty-move rule. A value of one hundred or more means fifty full moves have passed without
   * a pawn move or capture.
   *
   * @return The current halfmove clock.
   */
  public int getHalfMoveClock() {
    return this.halfMoveClock;
  }

  /**
   * Returns the number of plies played on this board since it was constructed, through
   * {@link #makeMove(Move)}. Not advanced by {@link #makeNullMove()}.
   *
   * @return The current ply count.
   */
  public int getPlyCount() {
    return this.plyCount;
  }

  /**
   * Returns the hash code for this board, using the Zobrist hash value.
   *
   * @return The hash code.
   */
  @Override
  public int hashCode() {
    return (int) this.zobristHash;
  }

  /**
   * The number of plies played on this board since it was constructed, advanced by
   * {@link #makeMove(Move)} and reversed by {@link #unmakeMove()}. Not advanced by
   * {@link #makeNullMove()}, since a null move is synthetic and is only ever applied to a
   * private search copy, never to the board a game is actually played on.
   */
  private int plyCount;

  /**
   * Applies the given move to this board in place: relocates pieces, updates the Zobrist hash,
   * en passant state, halfmove clock, and transition move, refreshes both players against the
   * resulting position, and pushes an undo record onto this board's undo stack.
   * <p>
   * The move must have been generated from this exact board, since it carries a reference back
   * to the board it was generated from and reads that board's state while computing its hash
   * update. Passing a move generated from a different board produces an incorrect result.
   *
   * @param move The move to apply, generated from this board's current position.
   */
  public void makeMove(final Move move) {
    final Alliance nextMover = this.currentPlayer.getOpponent().getAlliance();
    final UndoState undo = move.makeMove(this);
    refreshPlayers(nextMover);
    this.undoStack.push(undo);
    this.positionCounts.merge(this.zobristHash, 1, Integer::sum);
    this.plyCount++;
  }

  /**
   * Reverses the most recent move applied through {@link #makeMove(Move)}, restoring this
   * board's pieces and state to exactly what they were beforehand.
   *
   * @throws IllegalStateException If no move is on the undo stack to unmake.
   */
  public void unmakeMove() {
    if (this.undoStack.isEmpty()) {
      throw new IllegalStateException("No move on the undo stack to unmake.");
    }
    final int remaining = this.positionCounts.merge(this.zobristHash, -1, Integer::sum);
    if (remaining <= 0) {
      this.positionCounts.remove(this.zobristHash);
    }
    final UndoState undo = this.undoStack.pop();
    undo.appliedMove().unmakeMove(this, undo);
    refreshPlayers(undo.priorMoveMaker());
    this.plyCount--;
  }

  /**
   * Passes the turn to the opponent without moving a piece, for null move pruning in search.
   * The side to move flips, any en passant opportunity is cleared because it cannot survive a
   * turn in which nothing was played, the halfmove clock advances, and the transition move
   * becomes the null move so that move ordering heuristics keyed on the previous move do not
   * read a real move that was not actually just played.
   * <p>
   * {@link #positionCounts} is deliberately not touched. A null move position is synthetic and
   * was never reached in a real game, so counting it toward threefold repetition would be wrong.
   */
  public void makeNullMove() {
    final NullMoveUndo undo = new NullMoveUndo(this.enPassantPawn, this.zobristHash,
            this.halfMoveClock, this.transitionMove, this.currentPlayer.getAlliance());
    final Alliance nextMover = this.currentPlayer.getOpponent().getAlliance();

    long hash = ZobristHashing.updateHashSideToMove(this.zobristHash);
    if (this.enPassantPawn != null) {
      hash = ZobristHashing.updateHashEnPassant(hash, this.enPassantPawn.getPiecePosition() % 8);
    }

    this.zobristHash = hash;
    this.enPassantPawn = null;
    this.halfMoveClock = this.halfMoveClock + 1;
    this.transitionMove = getNullMove();
    refreshPlayers(nextMover);
    this.nullMoveUndoStack.push(undo);
  }

  /**
   * Reverses the most recent null move applied through {@link #makeNullMove()}, restoring this
   * board to exactly what it was beforehand.
   *
   * @throws IllegalStateException If no null move is on the null move undo stack to unmake.
   */
  public void unmakeNullMove() {
    if (this.nullMoveUndoStack.isEmpty()) {
      throw new IllegalStateException("No null move on the undo stack to unmake.");
    }
    final NullMoveUndo undo = this.nullMoveUndoStack.pop();
    this.enPassantPawn = undo.priorEnPassantPawn();
    this.zobristHash = undo.priorZobristHash();
    this.halfMoveClock = undo.priorHalfMoveClock();
    this.transitionMove = undo.priorTransitionMove();
    refreshPlayers(undo.priorMoveMaker());
  }

  /**
   * Returns whether the given move is legal for the side to move, meaning it does not leave that
   * side's own king attacked. Move generation is pseudo-legal, so this is the check that separates
   * the moves that may actually be played from the moves that merely look playable.
   * <p>
   * The move is applied and reversed through {@link Move#makeMove(Board)} and
   * {@link Move#unmakeMove(Board, UndoState)} rather than through {@link #makeMove(Move)} and
   * {@link #unmakeMove()}, deliberately. That lower pair relocates pieces and updates position
   * state without touching this board's players, undo stack, repetition counts, or ply count, so
   * asking this question costs neither a board construction nor a player rebuild, and leaves the
   * cached {@link Player} objects on this board intact for the caller to keep using afterwards.
   * The board is restored before this method returns, whether it completes normally or throws.
   * <p>
   * The king's square is read before the move is applied, taking the destination square when the
   * king itself is the piece being moved, which covers castling as well since a castle move
   * records the king as its moved piece.
   *
   * @param move The move to test, which must have been generated from this board's position.
   * @return True if the move leaves the moving side's king unattacked, false otherwise.
   */
  public boolean isLegal(final Move move) {
    final Player mover = this.currentPlayer;
    final Collection<Piece> opponentPieces = mover.getOpponent().getActivePieces();
    final int kingSquare = move.getMovedPiece().getPieceType() == Piece.PieceType.KING ?
            move.getDestinationCoordinate() : mover.getPlayerKing().getPiecePosition();
    final UndoState undo = move.makeMove(this);
    try {
      return !Player.isSquareAttacked(kingSquare, opponentPieces, this);
    } finally {
      move.unmakeMove(this, undo);
    }
  }

  /**
   * Reassigns both players and the current player against this board's current piece
   * configuration. Called after every mutation, since a board mutated in place cannot rely on
   * a {@link Player} built against an earlier position the way an immutably constructed board
   * can.
   * <p>
   * Constructing a {@link Player} here no longer computes its full legal move list up front;
   * that generation is deferred to {@link Player#getLegalMoves()} and computed at most once per
   * side, only if something actually asks for it. Only the cheap parts, a player's king and
   * check status, are established eagerly here.
   *
   * @param moveMaker The alliance to move in the resulting position.
   */
  private void refreshPlayers(final Alliance moveMaker) {
    this.whitePlayer = new WhitePlayer(this, this.blackPieces);
    this.blackPlayer = new BlackPlayer(this, this.whitePieces);
    this.currentPlayer = moveMaker.choosePlayerByAlliance(this.whitePlayer, this.blackPlayer);
  }

  /**
   * Places a piece on the board at its own reported position, adding it to the board's piece
   * configuration and to the appropriate alliance's piece list. Used only by the mutating
   * makeMove/unmakeMove path in the {@link Move} hierarchy.
   *
   * @param piece The piece to place, at the square given by its own position.
   */
  void placePiece(final Piece piece) {
    this.boardConfig.put(piece.getPiecePosition(), piece);
    final List<Piece> pieces = piece.getPieceAllegiance().isWhite() ? this.whitePieces : this.blackPieces;
    final int coordinate = piece.getPiecePosition();
    int index = 0;
    while (index < pieces.size() && pieces.get(index).getPiecePosition() < coordinate) {
      index++;
    }
    pieces.add(index, piece);
  }

  /**
   * Removes whatever piece occupies the given square, if any, from the board's piece
   * configuration and from the appropriate alliance's piece list. Used only by the mutating
   * makeMove/unmakeMove path in the {@link Move} hierarchy.
   *
   * @param coordinate The square to clear.
   */
  void removePiece(final int coordinate) {
    final Piece piece = this.boardConfig.remove(coordinate);
    if (piece == null) {
      return;
    }
    final List<Piece> pieces = piece.getPieceAllegiance().isWhite() ? this.whitePieces : this.blackPieces;
    for (int index = 0; index < pieces.size(); index++) {
      if (pieces.get(index).getPiecePosition() == coordinate) {
        pieces.remove(index);
        return;
      }
    }
  }

  /**
   * Sets the en passant pawn directly, without going through a Builder. Used only by the
   * mutating makeMove/unmakeMove path in the {@link Move} hierarchy.
   *
   * @param pawn The pawn now susceptible to en passant capture, or null if there is none.
   */
  void setEnPassantPawn(final Pawn pawn) {
    this.enPassantPawn = pawn;
  }

  /**
   * Sets the Zobrist hash directly, without going through a Builder. Used only by the mutating
   * makeMove/unmakeMove path in the {@link Move} hierarchy.
   *
   * @param zobristHash The new Zobrist hash value.
   */
  void setZobristHash(final long zobristHash) {
    this.zobristHash = zobristHash;
  }

  /**
   * Sets the transition move directly, without going through a Builder. Used only by the
   * mutating makeMove/unmakeMove path in the {@link Move} hierarchy.
   *
   * @param move The move to record as having produced the current position.
   */
  void setTransitionMove(final Move move) {
    this.transitionMove = move;
  }

  /**
   * Sets the halfmove clock directly, without going through a Builder. Used only by the
   * mutating makeMove/unmakeMove path in the {@link Move} hierarchy.
   *
   * @param halfMoveClock The new halfmove clock value.
   */
  void setHalfMoveClock(final int halfMoveClock) {
    this.halfMoveClock = halfMoveClock;
  }

  /**
   * Returns a standard chess board configuration representing the starting position.
   * This method uses a cached instance for better performance.
   *
   * @return A standard chess board with pieces in their initial positions.
   */
  public static Board createStandardBoard() {
    return STANDARD_BOARD.copy();
  }

  /**
   * Creates and returns a standard chess board configuration with all pieces
   * in their initial positions.
   *
   * @return A new Board instance with the standard starting positions of pieces.
   */
  private static Board createStandardBoardImpl() {
    final Builder builder = new Builder();

    builder.setPiece(new Rook(Alliance.BLACK, 0, 0));
    builder.setPiece(new Knight(Alliance.BLACK, 1, 0));
    builder.setPiece(new Bishop(Alliance.BLACK, 2, 0));
    builder.setPiece(new Queen(Alliance.BLACK, 3, 0));
    builder.setPiece(new King(Alliance.BLACK, 4, true, true));
    builder.setPiece(new Bishop(Alliance.BLACK, 5, 0));
    builder.setPiece(new Knight(Alliance.BLACK, 6, 0));
    builder.setPiece(new Rook(Alliance.BLACK, 7, 0));
    builder.setPiece(new Pawn(Alliance.BLACK, 8, 0));
    builder.setPiece(new Pawn(Alliance.BLACK, 9,0));
    builder.setPiece(new Pawn(Alliance.BLACK, 10, 0));
    builder.setPiece(new Pawn(Alliance.BLACK, 11, 0));
    builder.setPiece(new Pawn(Alliance.BLACK, 12, 0));
    builder.setPiece(new Pawn(Alliance.BLACK, 13, 0));
    builder.setPiece(new Pawn(Alliance.BLACK, 14, 0));
    builder.setPiece(new Pawn(Alliance.BLACK, 15, 0));

    builder.setPiece(new Pawn(Alliance.WHITE, 48, 0));
    builder.setPiece(new Pawn(Alliance.WHITE, 49, 0));
    builder.setPiece(new Pawn(Alliance.WHITE, 50, 0));
    builder.setPiece(new Pawn(Alliance.WHITE, 51, 0));
    builder.setPiece(new Pawn(Alliance.WHITE, 52, 0));
    builder.setPiece(new Pawn(Alliance.WHITE, 53, 0));
    builder.setPiece(new Pawn(Alliance.WHITE, 54, 0));
    builder.setPiece(new Pawn(Alliance.WHITE, 55, 0));
    builder.setPiece(new Rook(Alliance.WHITE, 56, 0));
    builder.setPiece(new Knight(Alliance.WHITE, 57, 0));
    builder.setPiece(new Bishop(Alliance.WHITE, 58, 0));
    builder.setPiece(new Queen(Alliance.WHITE, 59, 0));
    builder.setPiece(new King(Alliance.WHITE, 60, true, true));
    builder.setPiece(new Bishop(Alliance.WHITE, 61, 0));
    builder.setPiece(new Knight(Alliance.WHITE, 62, 0));
    builder.setPiece(new Rook(Alliance.WHITE, 63, 0));
    builder.setMoveMaker(Alliance.WHITE);

    return builder.build();
  }

  /**
   * Calculates the pseudo-legal moves for a collection of pieces on the board. Public so that
   * {@link Player#getLegalMoves()} can call it lazily to generate its own side's moves, rather
   * than this board generating moves for both sides eagerly on every mutation.
   *
   * @param pieces The collection of pieces for which to calculate legal moves.
   * @return A collection of legal moves for the given pieces.
   */
  public Collection<Move> calculateLegalMoves(Collection<Piece> pieces) {
    List<Move> legalMoves = new ArrayList<>();
    for (Piece piece : pieces) {
      legalMoves.addAll(piece.calculateLegalMoves(this));
    } return legalMoves;
  }

  /**
   * Calculates and returns the active pieces of a specified alliance on the board.
   *
   * @param builder  The builder containing the board configurations.
   * @param alliance The alliance (color) of the pieces to be considered.
   * @return A collection of active pieces belonging to the specified alliance.
   */
  private Collection<Piece> calculateActivePieces(Builder builder, Alliance alliance) {
    List<Piece> activePieces = new ArrayList<>();
    for (Piece piece : builder.BoardConfigurations.values()) {
      if (piece.getPieceAllegiance() == alliance) {
        activePieces.add(piece);
      }
    }
    activePieces.sort(Comparator.comparingInt(Piece::getPiecePosition));
    return activePieces;
  }

  /**
   * A builder class for constructing instances of the Board class with specific configurations.
   * This class follows the Builder pattern to facilitate the creation of complex Board objects.
   */
  public static class Builder {
    /** A map of board coordinates to pieces for the board being built. */
    private final Map<Integer, Piece> BoardConfigurations;
    /** The player who will make the next move on the board being built. */
    private Alliance nextMoveMaker;
    /** The pawn that can be captured via en passant, if any. */
    private Pawn enPassantPawn;
    /** The Zobrist hash value for the board being built. */
    private long zobristHash;
    /** The halfmove clock for the board being built, defaulting to zero. */
    private int halfMoveClock;
    /** The ply count for the board being built, defaulting to zero for a freshly started game. */
    private int plyCount;

    /*** Constructs a new Builder instance with empty configurations. */
    public Builder() {
      this.BoardConfigurations = new HashMap<>(32, 1.0f);
      this.zobristHash = 0;
    }

    /**
     * Sets a piece at a specific position on the board being built.
     *
     * @param piece The piece to be placed on the board at its specified position.
     * @return The current builder instance to continue configuring the board.
     */
    public Builder setPiece(final Piece piece) {
      this.BoardConfigurations.put(piece.getPiecePosition(), piece);
      return this;
    }

    /**
     * Sets the alliance of the player who will make the next move.
     *
     * @param nextMoveMaker The alliance of the player who has the next move.
     */
    public void setMoveMaker(final Alliance nextMoveMaker) {
      this.nextMoveMaker = nextMoveMaker;
    }

    /**
     * Sets the en passant pawn for the current board state.
     *
     * @param enPassantPawn The pawn that can be captured en passant in the current move.
     */
    public void setEnPassantPawn(final Pawn enPassantPawn) {
      this.enPassantPawn = enPassantPawn;
    }

    /**
     * Sets the Zobrist hash value for the board being built.
     *
     * @param zobristHash The Zobrist hash value.
     */
    public void setZobristHash(final long zobristHash) {
      this.zobristHash = zobristHash;
    }

    /**
     * Sets the halfmove clock for the board being built, for the fifty-move rule. Defaults to
     * zero when not set, which is correct for a freshly started game but should be supplied
     * when building a position parsed from FEN or otherwise reached mid-game.
     *
     * @param halfMoveClock The number of plies since the last pawn move or capture.
     */
    public void setHalfMoveClock(final int halfMoveClock) {
      this.halfMoveClock = halfMoveClock;
    }

    /**
     * Sets the ply count for the board being built, for {@link Board#getPlyCount()}. Defaults
     * to zero, which is correct for a freshly started game.
     *
     * @param plyCount The number of plies already played.
     */
    public void setPlyCount(final int plyCount) {
      this.plyCount = plyCount;
    }

    /**
     * Constructs and returns a new instance of the Board class based on the configured parameters.
     *
     * @return A new Board instance with the specified configurations.
     */
    public Board build() {
      return new Board(this);
    }
  }
}