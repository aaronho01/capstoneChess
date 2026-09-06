package engine.forPlayer.forAI;

import com.google.common.annotations.VisibleForTesting;
import engine.Alliance;
import engine.forBoard.Board;
import engine.forBoard.BoardUtils;
import engine.forBoard.Move;
import engine.forPiece.*;
import engine.forPlayer.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The MiddlegameBoardEvaluator class provides comprehensive position evaluation specifically
 * tuned for middlegame positions in chess. This evaluator implements modern chess engine
 * evaluation principles including material balance, piece mobility, king safety, pawn structure,
 * piece coordination, space control, attacking potential, and special positional patterns.
 * <p>
 * The evaluation function considers multiple strategic and tactical factors that are most
 * relevant during the middlegame phase, where piece development is complete and complex
 * tactical and strategic battles typically occur.
 *
 * @author Aaron Ho
 */
public class MiddlegameBoardEvaluator implements BoardEvaluator {

  /** Singleton instance of the MiddlegameBoardEvaluator. */
  private static final MiddlegameBoardEvaluator Instance = new MiddlegameBoardEvaluator();

  /**
   * Private constructor to prevent instantiation outside of the class.
   * Enforces the singleton pattern.
   */
  private MiddlegameBoardEvaluator() {}

  /**
   * Returns the singleton instance of MiddlegameBoardEvaluator.
   *
   * @return The instance of MiddlegameBoardEvaluator.
   */
  public static MiddlegameBoardEvaluator get() {
    return Instance;
  }

  /**
   * Evaluates the given board from the perspective of the current player and returns a score.
   * The evaluation is calculated as white's score minus black's score, making positive values
   * favorable for white and negative values favorable for black.
   *
   * @param board The current state of the chess board.
   * @return The evaluation score of the board.
   */
  @Override
  public double evaluate(final Board board) {
    return (score(board.whitePlayer(), board) - score(board.blackPlayer(), board));
  }

  /**
   * Calculates the overall score of the current board position for a given player
   * using modern chess engine evaluation principles tuned for middlegame positions.
   *
   * @param player The player for whom the board position is being evaluated.
   * @param board The current state of the chess board.
   * @return The evaluation score of the board from the perspective of the specified player.
   */
  @VisibleForTesting
  private double score(final Player player, final Board board) {
    final List<Piece> playerPawns = getPlayerPawns(player);
    final List<Piece> opponentPawns = getPlayerPawns(player.getOpponent());

    return materialEvaluation(player.getActivePieces()) +
            mobilityEvaluation(player) +
            kingSafetyEvaluation(player, board, playerPawns) +
            pawnStructureEvaluation(player, board, playerPawns, opponentPawns) +
            pieceCoordinationEvaluation(player, board) +
            spaceControlEvaluation(player) +
            attackingPotentialEvaluation(player) +
            specialPatternsEvaluation(player, board, playerPawns, opponentPawns);
  }

  /**
   * Evaluates material balance with refined piece values and contextual adjustments.
   * Modern engines use dynamic piece values based on the position.
   *
   * @param playerPieces The collection of pieces belonging to the player.
   * @return The material evaluation score.
   */
  private double materialEvaluation(final Collection<Piece> playerPieces) {
    double materialScore = 0;
    int numBishops = 0;

    for (final Piece piece : playerPieces) {
      materialScore += piece.getPieceValue();

      if (piece.getPieceType() == Piece.PieceType.BISHOP) {
        numBishops++;
      }
    }

    if (numBishops >= 2) {
      materialScore += 45;
    }

    return materialScore;
  }

  /**
   * Evaluates piece mobility for the given player.
   * Counts the player's legal moves and adds a further weighting for the moves available to each
   * knight, bishop, rook and queen. The result is a per-player quantity and the caller forms the
   * difference between the two players.
   *
   * @param player The player whose mobility is being evaluated.
   * @return The mobility evaluation score.
   */
  private double mobilityEvaluation(final Player player) {
    final Collection<Move> playerMoves = player.getLegalMoves();
    double mobilityScore = 0;

    Map<Piece.PieceType, Integer> movesPerPiece = new HashMap<>();
    for (final Move move : playerMoves) {
      Piece.PieceType pieceType = move.getMovedPiece().getPieceType();
      movesPerPiece.put(pieceType, movesPerPiece.getOrDefault(pieceType, 0) + 1);
    }

    if (movesPerPiece.containsKey(Piece.PieceType.KNIGHT)) {
      mobilityScore += movesPerPiece.get(Piece.PieceType.KNIGHT) * 4.5;
    }

    if (movesPerPiece.containsKey(Piece.PieceType.BISHOP)) {
      mobilityScore += movesPerPiece.get(Piece.PieceType.BISHOP) * 5.0;
    }

    if (movesPerPiece.containsKey(Piece.PieceType.ROOK)) {
      mobilityScore += movesPerPiece.get(Piece.PieceType.ROOK) * 4.0;
    }

    if (movesPerPiece.containsKey(Piece.PieceType.QUEEN)) {
      mobilityScore += movesPerPiece.get(Piece.PieceType.QUEEN) * 2.0;
    }

    mobilityScore += playerMoves.size() * 5.0;

    return mobilityScore;
  }

  /**
   * Evaluates king safety, considering pawn shield, attacking pieces,
   * open lines, and king attackers. This is a critical middlegame factor.
   *
   * @param player The player whose king safety is being evaluated.
   * @param board The current chess board.
   * @param playerPawns The player's pawns.
   * @return The king safety evaluation score.
   */
  private double kingSafetyEvaluation(final Player player, final Board board,
                                      final List<Piece> playerPawns) {
    double kingSafetyScore = 0;
    final King playerKing = player.getPlayerKing();
    final int kingPosition = playerKing.getPiecePosition();

    if (playerKing.isOnCastledSquare()) {
      kingSafetyScore += 40;
    }

    kingSafetyScore += evaluatePawnShield(player, board, kingPosition);
    kingSafetyScore -= evaluateKingExposure(player, kingPosition);
    kingSafetyScore -= evaluateKingTropism(player, kingPosition);
    kingSafetyScore -= evaluateOpenFilesToKing(player, kingPosition, playerPawns);

    return kingSafetyScore;
  }

  /**
   * Evaluates the pawn shield protecting the king.
   *
   * @param player The player whose king's pawn shield is being evaluated.
   * @param board The current chess board.
   * @param kingPosition The position of the king on the board.
   * @return The pawn shield evaluation score.
   */
  private double evaluatePawnShield(final Player player, final Board board, final int kingPosition) {
    double shieldScore = 0;
    final Alliance playerAlliance = player.getAlliance();

    List<Integer> shieldSquares = getKingShieldSquares(kingPosition, playerAlliance);

    int pawnsInShield = 0;
    int intactFileCount = 0;
    Set<Integer> shieldFiles = new HashSet<>();

    for (Integer shieldSquare : shieldSquares) {
      Piece piece = board.getPiece(shieldSquare);
      if (piece != null && piece.getPieceType() == Piece.PieceType.PAWN &&
              piece.getPieceAllegiance() == playerAlliance) {
        pawnsInShield++;
        int file = shieldSquare % 8;
        if (!shieldFiles.contains(file)) {
          intactFileCount++;
          shieldFiles.add(file);
        }
      }
    }

    if (pawnsInShield >= 3) {
      shieldScore += 35;
    } else if (pawnsInShield == 2) {
      shieldScore += 20;
    } else if (pawnsInShield == 1) {
      shieldScore += 5;
    } else {
      shieldScore -= 20;
    }

    shieldScore += intactFileCount * 10;

    return shieldScore;
  }

  /**
   * Gets the squares that form a pawn shield for the king.
   *
   * @param kingPosition The position of the king on the board.
   * @param alliance The alliance of the king.
   * @return A list of coordinates representing the king's pawn shield squares.
   */
  private List<Integer> getKingShieldSquares(final int kingPosition, final Alliance alliance) {
    List<Integer> shieldSquares = new ArrayList<>();
    final int kingFile = kingPosition % 8;
    final int kingRank = kingPosition / 8;

    boolean kingSide = kingFile >= 4;
    int shieldRank = alliance.isWhite() ? kingRank - 1 : kingRank + 1;

    if (shieldRank >= 0 && shieldRank < 8) {
      for (int file = Math.max(0, kingFile - 1); file <= Math.min(7, kingFile + 1); file++) {
        shieldSquares.add(shieldRank * 8 + file);
      }

      if ((kingSide && (kingFile == 6 || kingFile == 5)) ||
              (!kingSide && (kingFile == 1 || kingFile == 2))) {
        if (alliance.isWhite()) {
          if (kingRank == 7) {
            for (int file = Math.max(0, kingFile - 1); file <= Math.min(7, kingFile + 1); file++) {
              shieldSquares.add((kingRank - 2) * 8 + file);
            }
          }
        } else {
          if (kingRank == 0) {
            for (int file = Math.max(0, kingFile - 1); file <= Math.min(7, kingFile + 1); file++) {
              shieldSquares.add((kingRank + 2) * 8 + file);
            }
          }
        }
      }
    }

    return shieldSquares;
  }

  /**
   * Evaluates the king's exposure to attacks.
   *
   * @param player The player whose king's exposure is being evaluated.
   * @param kingPosition The position of the king on the board.
   * @return The king exposure score (higher values indicate more exposure).
   */
  private double evaluateKingExposure(final Player player, final int kingPosition) {
    double exposureScore = 0;
    final Collection<Move> opponentMoves = player.getOpponent().getLegalMoves();

    int attacksNearKing = 0;

    for (final Move move : opponentMoves) {
      final int moveDestination = move.getDestinationCoordinate();
      if (isSquareNearKing(kingPosition, moveDestination)) {
        attacksNearKing++;

        if (move.getMovedPiece().getPieceType() == Piece.PieceType.QUEEN) {
          exposureScore += 10;
        } else if (move.getMovedPiece().getPieceType() == Piece.PieceType.ROOK) {
          exposureScore += 7;
        } else if (move.getMovedPiece().getPieceType() == Piece.PieceType.BISHOP ||
                move.getMovedPiece().getPieceType() == Piece.PieceType.KNIGHT) {
          exposureScore += 5;
        } else {
          exposureScore += 2;
        }
      }
    }

    if (attacksNearKing > 2) {
      exposureScore += (attacksNearKing - 2) * 15;
    }

    if (player.isInCheck()) {
      exposureScore += 30;
    }

    return exposureScore;
  }

  /**
   * Determines if a square is near the king.
   *
   * @param kingPosition The position of the king on the board.
   * @param square The square to check proximity to the king.
   * @return True if the square is within 2 squares of the king, false otherwise.
   */
  private boolean isSquareNearKing(final int kingPosition, final int square) {
    final int kingRank = kingPosition / 8;
    final int kingFile = kingPosition % 8;
    final int squareRank = square / 8;
    final int squareFile = square % 8;

    return Math.max(Math.abs(kingRank - squareRank), Math.abs(kingFile - squareFile)) <= 2;
  }

  /**
   * Evaluates king tropism - the proximity of opponent pieces to the king.
   *
   * @param player The player whose king is being evaluated for tropism.
   * @param kingPosition The position of the king on the board.
   * @return The king tropism score (higher values indicate more danger).
   */
  private double evaluateKingTropism(final Player player, final int kingPosition) {
    double tropismScore = 0;
    final Collection<Piece> opponentPieces = player.getOpponent().getActivePieces();

    for (final Piece piece : opponentPieces) {
      if (piece.getPieceType() == Piece.PieceType.KING) {
        continue;
      }

      int distance = calculateChebyshevDistance(kingPosition, piece.getPiecePosition());
      double pieceValue = piece.getPieceValue() / 100.0;

      switch (piece.getPieceType()) {
        case QUEEN:
          tropismScore += (7 - distance) * 6 * pieceValue;
          break;
        case ROOK:
          tropismScore += (7 - distance) * 4 * pieceValue;
          break;
        case BISHOP:
          tropismScore += (7 - distance) * 3 * pieceValue;
          break;
        case KNIGHT:
          if (distance <= 3) {
            tropismScore += (4 - distance) * 4 * pieceValue;
          }
          break;
        case PAWN:
          if (distance <= 2) {
            tropismScore += (3 - distance) * 2 * pieceValue;
          }
          break;
      }
    }

    return tropismScore;
  }

  /**
   * Evaluates open files leading to the king, which can be dangerous.
   *
   * @param player The player whose king is being evaluated for open file exposure.
   * @param kingPosition The position of the king on the board.
   * @param playerPawns The player's pawns.
   * @return The open files evaluation score (higher values indicate more exposure).
   */
  private double evaluateOpenFilesToKing(final Player player, final int kingPosition,
                                         final List<Piece> playerPawns) {
    double openFileScore = 0;
    final int kingFile = kingPosition % 8;

    if (isPawnOnFile(kingFile, playerPawns)) {
      openFileScore += 25;

      if (hasHeavyPieceOnFile(kingFile, player.getOpponent().getActivePieces())) {
        openFileScore += 35;
      }
    }

    for (int file = Math.max(0, kingFile - 1); file <= Math.min(7, kingFile + 1); file++) {
      if (file == kingFile) continue;

      if (isPawnOnFile(file, playerPawns)) {
        openFileScore += 15;

        if (hasHeavyPieceOnFile(file, player.getOpponent().getActivePieces())) {
          openFileScore += 25;
        }
      }
    }

    return openFileScore;
  }

  /**
   * Checks if there's a pawn on the specified file.
   *
   * @param file The file to check for pawns (0-7).
   * @param pawns The list of pawns to check.
   * @return True if no pawn is found on the file, false otherwise.
   */
  private boolean isPawnOnFile(final int file, final List<Piece> pawns) {
    for (final Piece pawn : pawns) {
      if (pawn.getPiecePosition() % 8 == file) {
        return false;
      }
    }
    return true;
  }

  /**
   * Checks if there's a heavy piece (rook or queen) on the specified file.
   *
   * @param file The file to check for heavy pieces (0-7).
   * @param pieces The collection of pieces to check.
   * @return True if a heavy piece is found on the file, false otherwise.
   */
  private boolean hasHeavyPieceOnFile(final int file, final Collection<Piece> pieces) {
    for (final Piece piece : pieces) {
      if ((piece.getPieceType() == Piece.PieceType.ROOK ||
              piece.getPieceType() == Piece.PieceType.QUEEN) &&
              piece.getPiecePosition() % 8 == file) {
        return true;
      }
    }
    return false;
  }

  /**
   * Evaluates the pawn structure, considering passed pawns, isolated pawns,
   * doubled pawns, pawn chains, and pawn islands.
   *
   * @param player The player whose pawn structure is being evaluated.
   * @param board The current state of the chess board.
   * @param playerPawns The player's pawns.
   * @param opponentPawns The opponent's pawns.
   * @return The pawn structure evaluation score.
   */
  private double pawnStructureEvaluation(final Player player, final Board board,
                                         final List<Piece> playerPawns,
                                         final List<Piece> opponentPawns) {
    final Player opponent = player.getOpponent();
    double pawnStructureScore = 0;

    pawnStructureScore += evaluatePassedPawns(playerPawns, opponentPawns,
            opponent.getActivePieces(), player.getAlliance(), board);
    pawnStructureScore += evaluatePawnIslands(playerPawns);
    pawnStructureScore += evaluateDoubledPawns(playerPawns);
    pawnStructureScore += evaluateIsolatedPawns(playerPawns, opponentPawns);
    pawnStructureScore += evaluateBackwardPawns(playerPawns, opponentPawns, player.getAlliance());
    pawnStructureScore += evaluatePawnChains(playerPawns, player.getAlliance());
    pawnStructureScore += evaluateCentralPawnControl(playerPawns);

    return pawnStructureScore;
  }

  /**
   * Evaluates passed pawns, which are more valuable in the middlegame.
   *
   * @param playerPawns The player's pawns.
   * @param opponentPawns The opponent's pawns.
   * @param opponentPieces The opponent's pieces.
   * @param alliance The alliance of the player.
   * @param board The current state of the chess board.
   * @return The passed pawns evaluation score.
   */
  private double evaluatePassedPawns(final List<Piece> playerPawns,
                                     final List<Piece> opponentPawns,
                                     final Collection<Piece> opponentPieces,
                                     final Alliance alliance,
                                     final Board board) {
    double passedPawnScore = 0;

    for (final Piece pawn : playerPawns) {
      if (isPassedPawn(pawn, opponentPawns, alliance)) {
        final int rank = pawn.getPiecePosition() / 8;
        final int advancementBonus = alliance.isWhite() ? 7 - rank : rank;

        passedPawnScore += 20 + (advancementBonus * 10);

        if (isPawnProtected(pawn, playerPawns, alliance)) {
          passedPawnScore += 15;
        }

        if (!opponentControlsStopSquare(pawn, alliance, opponentPieces, board)) {
          passedPawnScore += 10;
        }
      }
    }

    return passedPawnScore;
  }

  /**
   * Checks if a pawn is a passed pawn (no opposing pawns in front or on adjacent files).
   *
   * @param pawn The pawn to check.
   * @param opponentPawns The opponent's pawns.
   * @param alliance The alliance of the pawn.
   * @return True if the pawn is passed, false otherwise.
   */
  private boolean isPassedPawn(final Piece pawn, final List<Piece> opponentPawns, final Alliance alliance) {
    final int pawnPosition = pawn.getPiecePosition();
    final int pawnFile = pawnPosition % 8;
    final int pawnRank = pawnPosition / 8;

    final int rankDirection = alliance.isWhite() ? -1 : 1;

    for (int rank = pawnRank + rankDirection; alliance.isWhite() ? (rank >= 0) : (rank < 8); rank += rankDirection) {
      if (isPawnAtPosition(opponentPawns, rank * 8 + pawnFile)) {
        return false;
      }

      if (pawnFile > 0 && isPawnAtPosition(opponentPawns, rank * 8 + (pawnFile - 1))) {
        return false;
      }

      if (pawnFile < 7 && isPawnAtPosition(opponentPawns, rank * 8 + (pawnFile + 1))) {
        return false;
      }
    }

    return true;
  }

  /**
   * Checks if there's a pawn at the specified position.
   *
   * @param pawns The list of pawns to check.
   * @param position The position to check for a pawn.
   * @return True if a pawn is found at the position, false otherwise.
   */
  private boolean isPawnAtPosition(final List<Piece> pawns, final int position) {
    for (final Piece pawn : pawns) {
      if (pawn.getPiecePosition() == position) {
        return true;
      }
    }
    return false;
  }

  /**
   * Checks if a pawn is protected by another pawn.
   *
   * @param pawn The pawn to check for protection.
   * @param playerPawns The player's pawns.
   * @param alliance The alliance of the pawn.
   * @return True if the pawn is protected, false otherwise.
   */
  private boolean isPawnProtected(final Piece pawn, final List<Piece> playerPawns, final Alliance alliance) {
    final int pawnPosition = pawn.getPiecePosition();
    final int pawnFile = pawnPosition % 8;
    final int pawnRank = pawnPosition / 8;

    final int rankBehind = alliance.isWhite() ? pawnRank + 1 : pawnRank - 1;

    if (rankBehind < 0 || rankBehind >= 8) {
      return false;
    }

    if (pawnFile > 0 && isPawnAtPosition(playerPawns, rankBehind * 8 + (pawnFile - 1))) {
      return true;
    }

    return pawnFile < 7 && isPawnAtPosition(playerPawns, rankBehind * 8 + (pawnFile + 1));
  }

  /**
   * Checks if any opponent piece controls the stop square of the pawn, meaning the square
   * directly ahead of it. A piece standing on the stop square counts only if it also bears on
   * that square, and a pawn with no square ahead of it is reported as uncontrolled.
   *
   * @param pawn The pawn to check.
   * @param alliance The alliance of the pawn.
   * @param opponentPieces The opponent's pieces.
   * @param board The current state of the chess board.
   * @return True if an opponent piece controls the stop square, false otherwise.
   */
  private boolean opponentControlsStopSquare(final Piece pawn,
                                             final Alliance alliance,
                                             final Collection<Piece> opponentPieces,
                                             final Board board) {
    final int pawnPosition = pawn.getPiecePosition();
    final int pawnRank = pawnPosition / 8;

    final int rankDirection = alliance.isWhite() ? -1 : 1;
    final int frontRank = pawnRank + rankDirection;

    if (frontRank < 0 || frontRank >= 8) {
      return false;
    }

    final int stopSquare = (frontRank * 8) + (pawnPosition % 8);

    for (final Piece opponentPiece : opponentPieces) {
      if (opponentPiece.defendsSquare(stopSquare, board)) {
        return true;
      }
    }

    return false;
  }

  /**
   * Evaluates pawn islands (groups of connected pawns).
   *
   * @param playerPawns The player's pawns.
   * @return The pawn islands evaluation score.
   */
  private double evaluatePawnIslands(final List<Piece> playerPawns) {
    double islandScore = 0;

    boolean[] filesWithPawns = new boolean[8];
    for (final Piece pawn : playerPawns) {
      filesWithPawns[pawn.getPiecePosition() % 8] = true;
    }

    int islands = 0;
    boolean inIsland = false;

    for (int file = 0; file < 8; file++) {
      if (filesWithPawns[file]) {
        if (!inIsland) {
          islands++;
          inIsland = true;
        }
      } else {
        inIsland = false;
      }
    }

    if (islands > 1) {
      islandScore -= (islands - 1) * 10;
    }

    return islandScore;
  }

  /**
   * Evaluates doubled pawns (multiple pawns on the same file).
   *
   * @param playerPawns The player's pawns.
   * @return The doubled pawns evaluation score.
   */
  private double evaluateDoubledPawns(final List<Piece> playerPawns) {
    double doubledPawnScore = 0;

    int[] pawnsPerFile = new int[8];
    for (final Piece pawn : playerPawns) {
      pawnsPerFile[pawn.getPiecePosition() % 8]++;
    }

    for (int count : pawnsPerFile) {
      if (count > 1) {
        doubledPawnScore -= (count - 1) * 20;
      }
    }

    return doubledPawnScore;
  }

  /**
   * Evaluates isolated pawns (pawns with no friendly pawns on adjacent files).
   *
   * @param playerPawns The player's pawns.
   * @param opponentPawns The opponent's pawns.
   * @return The isolated pawns evaluation score.
   */
  private double evaluateIsolatedPawns(final List<Piece> playerPawns,
                                       final List<Piece> opponentPawns) {
    double isolatedPawnScore = 0;

    boolean[] filesWithPawns = new boolean[8];
    for (final Piece pawn : playerPawns) {
      filesWithPawns[pawn.getPiecePosition() % 8] = true;
    }

    for (final Piece pawn : playerPawns) {
      final int pawnFile = pawn.getPiecePosition() % 8;
      boolean isIsolated = pawnFile <= 0 || !filesWithPawns[pawnFile - 1];

      if (pawnFile < 7 && filesWithPawns[pawnFile + 1]) {
        isIsolated = false;
      }

      if (isIsolated) {
        isolatedPawnScore -= 15;

        if (isOnSemiOpenFile(pawn, opponentPawns)) {
          isolatedPawnScore -= 5;
        }

        if (pawnFile > 1 && pawnFile < 6) {
          isolatedPawnScore -= 5;
        }
      }
    }

    return isolatedPawnScore;
  }

  /**
   * Checks if a pawn is on a semi-open file, meaning no opponent pawn stands on that file.
   *
   * @param pawn The pawn to check.
   * @param opponentPawns The opponent's pawns.
   * @return True if the pawn is on a semi-open file, false otherwise.
   */
  private boolean isOnSemiOpenFile(final Piece pawn, final List<Piece> opponentPawns) {
    final int pawnFile = pawn.getPiecePosition() % 8;

    for (final Piece opponentPawn : opponentPawns) {
      if (opponentPawn.getPiecePosition() % 8 == pawnFile) {
        return false;
      }
    }

    return true;
  }

  /**
   * Evaluates backward pawns (pawns that can't be protected by adjacent pawns).
   *
   * @param playerPawns The player's pawns.
   * @param opponentPawns The opponent's pawns.
   * @param alliance The alliance of the player.
   * @return The backward pawns evaluation score.
   */
  private double evaluateBackwardPawns(final List<Piece> playerPawns,
                                       final List<Piece> opponentPawns,
                                       final Alliance alliance) {
    double backwardPawnScore = 0;

    final int[] rearmostRankOnFile = new int[8];
    final boolean[] fileHasPawn = new boolean[8];

    for (final Piece pawn : playerPawns) {
      final int file = pawn.getPiecePosition() % 8;
      final int rank = pawn.getPiecePosition() / 8;

      if (!fileHasPawn[file]) {
        fileHasPawn[file] = true;
        rearmostRankOnFile[file] = rank;
      } else if (alliance.isWhite()) {
        rearmostRankOnFile[file] = Math.max(rearmostRankOnFile[file], rank);
      } else {
        rearmostRankOnFile[file] = Math.min(rearmostRankOnFile[file], rank);
      }
    }

    for (final Piece pawn : playerPawns) {
      if (isBackward(alliance, pawn, rearmostRankOnFile, fileHasPawn)) {
        backwardPawnScore -= 20;

        if (isOnSemiOpenFile(pawn, opponentPawns)) {
          backwardPawnScore -= 5;
        }
      }
    }

    return backwardPawnScore;
  }

  /**
   * Determines if a pawn is backward, meaning it has at least one friendly pawn on an adjacent
   * file and every such pawn is strictly more advanced than it is. Rank indices run from zero on
   * black's back rank to seven on white's, so a smaller index is more advanced for white and a
   * larger index is more advanced for black.
   *
   * @param alliance The alliance of the pawn.
   * @param pawn The pawn to check.
   * @param rearmostRankOnFile The rank of the least advanced friendly pawn on each file.
   * @param fileHasPawn Whether each file holds at least one friendly pawn.
   * @return True if the pawn is backward, false otherwise.
   */
  private static boolean isBackward(final Alliance alliance,
                                    final Piece pawn,
                                    final int[] rearmostRankOnFile,
                                    final boolean[] fileHasPawn) {
    final int file = pawn.getPiecePosition() % 8;
    final int rank = pawn.getPiecePosition() / 8;

    boolean hasAdjacentPawn = false;

    for (int adjacentFile = file - 1; adjacentFile <= file + 1; adjacentFile += 2) {
      if (adjacentFile < 0 || adjacentFile > 7 || !fileHasPawn[adjacentFile]) {
        continue;
      }

      hasAdjacentPawn = true;
      final int adjacentRank = rearmostRankOnFile[adjacentFile];

      if (alliance.isWhite() ? adjacentRank >= rank : adjacentRank <= rank) {
        return false;
      }
    }

    return hasAdjacentPawn;
  }

  /**
   * Evaluates pawn chains (connected pawns that protect each other).
   *
   * @param playerPawns The player's pawns.
   * @param alliance The alliance of the pawns.
   * @return The pawn chains evaluation score.
   */
  private double evaluatePawnChains(final List<Piece> playerPawns, final Alliance alliance) {
    double pawnChainScore = 0;

    Map<Integer, Set<Integer>> pawnsByRank = new HashMap<>();

    for (final Piece pawn : playerPawns) {
      final int rank = pawn.getPiecePosition() / 8;
      final int file = pawn.getPiecePosition() % 8;

      if (!pawnsByRank.containsKey(rank)) {
        pawnsByRank.put(rank, new HashSet<>());
      }

      pawnsByRank.get(rank).add(file);
    }

    int chainLinks = 0;
    for (final Piece pawn : playerPawns) {
      final int rank = pawn.getPiecePosition() / 8;
      final int file = pawn.getPiecePosition() % 8;

      if (isPawnProtectingFile(pawnsByRank, rank, file, alliance)) {
        chainLinks++;
      }
    }

    pawnChainScore += chainLinks * 8;

    if (chainLinks >= 3) {
      pawnChainScore += 10;
    }

    return pawnChainScore;
  }

  /**
   * Checks if a pawn is protected by another pawn on an adjacent file. Rank indices run from zero
   * on black's back rank to seven on white's, so a protecting pawn stands one index higher for
   * white and one index lower for black.
   *
   * @param pawnsByRank Map of pawns organized by rank.
   * @param rank The rank of the pawn.
   * @param file The file of the pawn.
   * @param alliance The alliance of the pawn.
   * @return True if the pawn is protected, false otherwise.
   */
  private boolean isPawnProtectingFile(final Map<Integer, Set<Integer>> pawnsByRank,
                                       final int rank,
                                       final int file,
                                       final Alliance alliance) {
    final int protectingRank = alliance.isWhite() ? rank + 1 : rank - 1;

    if (pawnsByRank.containsKey(protectingRank)) {
      final Set<Integer> filesWithPawns = pawnsByRank.get(protectingRank);

      return (file > 0 && filesWithPawns.contains(file - 1)) ||
              (file < 7 && filesWithPawns.contains(file + 1));
    }

    return false;
  }

  /**
   * Evaluates central pawn control, which is important in the middlegame.
   *
   * @param playerPawns The player's pawns.
   * @return The central pawn control evaluation score.
   */
  private double evaluateCentralPawnControl(final List<Piece> playerPawns) {
    double centralControlScore = 0;
    final int[] centralSquares = {27, 28, 35, 36};

    for (final Piece pawn : playerPawns) {
      final int position = pawn.getPiecePosition();

      for (final int centralSquare : centralSquares) {
        if (position == centralSquare) {
          centralControlScore += 25;
        }
      }

      for (final int centralSquare : centralSquares) {
        if (pawnControlsSquare(pawn, centralSquare)) {
          centralControlScore += 15;
        }
      }
    }

    return centralControlScore;
  }

  /**
   * Checks if a pawn controls (attacks) a specific square.
   *
   * @param pawn The pawn to check.
   * @param square The square to check for control.
   * @return True if the pawn controls the square, false otherwise.
   */
  private boolean pawnControlsSquare(final Piece pawn, final int square) {
    final int pawnPosition = pawn.getPiecePosition();
    final int pawnFile = pawnPosition % 8;
    final int pawnRank = pawnPosition / 8;
    final int squareFile = square % 8;
    final int squareRank = square / 8;

    if (pawn.getPieceAllegiance().isWhite()) {
      return (pawnRank - 1 == squareRank) &&
              (pawnFile - 1 == squareFile || pawnFile + 1 == squareFile);
    } else {
      return (pawnRank + 1 == squareRank) &&
              (pawnFile - 1 == squareFile || pawnFile + 1 == squareFile);
    }
  }

  /**
   * Evaluates piece coordination, synergy and cooperative control.
   *
   * @param player The player whose piece coordination is being evaluated.
   * @param board The current chess board.
   * @return The piece coordination evaluation score.
   */
  private double pieceCoordinationEvaluation(final Player player, final Board board) {
    double coordinationScore = 0;
    final Collection<Piece> playerPieces = player.getActivePieces();

    coordinationScore += evaluateBishopPair(playerPieces);
    coordinationScore += evaluateRookCoordination(playerPieces, board);
    coordinationScore += evaluatePieceProtection(playerPieces, board, player.getOpponent());
    coordinationScore += evaluatePieceActivity(playerPieces);

    return coordinationScore;
  }

  /**
   * Evaluates bishop pair bonus, which is significant in middlegame.
   *
   * @param playerPieces The player's pieces.
   * @return The bishop pair evaluation score.
   */
  private double evaluateBishopPair(final Collection<Piece> playerPieces) {
    double bishopPairScore = 0;
    boolean hasLightSquareBishop = false;
    boolean hasDarkSquareBishop = false;

    for (final Piece piece : playerPieces) {
      if (piece.getPieceType() == Piece.PieceType.BISHOP) {
        final int square = piece.getPiecePosition();
        final boolean isLightSquare = ((square / 8) + (square % 8)) % 2 == 0;

        if (isLightSquare) {
          hasLightSquareBishop = true;
        } else {
          hasDarkSquareBishop = true;
        }
      }
    }

    if (hasLightSquareBishop && hasDarkSquareBishop) {
      bishopPairScore += 50;
    }

    return bishopPairScore;
  }

  /**
   * Evaluates rook coordination, including connected rooks and rooks on open and semi-open files.
   *
   * @param playerPieces The player's pieces.
   * @param board The current chess board.
   * @return The rook coordination evaluation score.
   */
  private double evaluateRookCoordination(final Collection<Piece> playerPieces, final Board board) {
    double rookScore = 0;
    List<Piece> rooks = new ArrayList<>();

    for (final Piece piece : playerPieces) {
      if (piece.getPieceType() == Piece.PieceType.ROOK) {
        rooks.add(piece);
      }
    }

    if (rooks.size() >= 2) {
      boolean rooksConnected = false;
      for (int i = 0; i < rooks.size() - 1; i++) {
        for (int j = i + 1; j < rooks.size(); j++) {
          final int rookPos1 = rooks.get(i).getPiecePosition();
          final int rookPos2 = rooks.get(j).getPiecePosition();

          if (rookPos1 / 8 == rookPos2 / 8) {
            rookScore += 20;
            rooksConnected = true;
          }

          if (rookPos1 % 8 == rookPos2 % 8) {
            rookScore += 15;
            rooksConnected = true;
          }
        }
      }

      if (rooksConnected) {
        rookScore += 10;
      }
    }

    for (final Piece rook : rooks) {
      final int rookFile = rook.getPiecePosition() % 8;

      if (isOpenFile(rookFile, board)) {
        rookScore += 20;
      } else if (isSemiOpenFile(rookFile, board, rook.getPieceAllegiance())) {
        rookScore += 10;
      }
    }

    return rookScore;
  }

  /**
   * Checks if a file is completely open (no pawns on it).
   *
   * @param file The file to check (0-7).
   * @param board The current chess board.
   * @return True if the file is open, false otherwise.
   */
  private boolean isOpenFile(final int file, final Board board) {
    for (int rank = 0; rank < 8; rank++) {
      final Piece piece = board.getPiece(rank * 8 + file);
      if (piece != null && piece.getPieceType() == Piece.PieceType.PAWN) {
        return false;
      }
    }
    return true;
  }

  /**
   * Checks if a file is semi-open (no friendly pawns on it).
   *
   * @param file The file to check (0-7).
   * @param board The current chess board.
   * @param alliance The alliance to check for absence of pawns.
   * @return True if the file is semi-open, false otherwise.
   */
  private boolean isSemiOpenFile(final int file, final Board board, final Alliance alliance) {
    for (int rank = 0; rank < 8; rank++) {
      final Piece piece = board.getPiece(rank * 8 + file);
      if (piece != null && piece.getPieceType() == Piece.PieceType.PAWN &&
              piece.getPieceAllegiance() == alliance) {
        return false;
      }
    }
    return true;
  }

  /**
   * Evaluates knight outposts - knights protected by pawns and in opponent's territory.
   *
   * @param playerPieces The player's pieces.
   * @param playerPawns The player's pawns.
   * @param opponentPawns The opponent's pawns.
   * @return The knight outposts evaluation score.
   */
  private double evaluateKnightOutposts(final Collection<Piece> playerPieces,
                                        final List<Piece> playerPawns,
                                        final List<Piece> opponentPawns) {
    double outpostScore = 0;

    for (final Piece piece : playerPieces) {
      if (piece.getPieceType() == Piece.PieceType.KNIGHT) {
        final int position = piece.getPiecePosition();
        final int file = position % 8;
        final int rank = position / 8;
        final Alliance alliance = piece.getPieceAllegiance();

        boolean inOpponentTerritory = (alliance.isWhite() && rank < 4) ||
                (!alliance.isWhite() && rank > 3);

        if (inOpponentTerritory) {
          boolean canBeAttackedByPawn = false;

          for (final Piece opponentPawn : opponentPawns) {
            if (pawnControlsSquare(opponentPawn, position)) {
              canBeAttackedByPawn = true;
              break;
            }
          }

          if (!canBeAttackedByPawn) {
            outpostScore += 20;

            for (final Piece playerPawn : playerPawns) {
              if (pawnControlsSquare(playerPawn, position)) {
                outpostScore += 15;
                break;
              }
            }

            if (file >= 2 && file <= 5) {
              outpostScore += 10;
            }
          }
        }
      }
    }

    return outpostScore;
  }

  /**
   * Evaluates how well pieces protect each other and heavily penalizes hanging pieces.
   * This version includes comprehensive piece safety analysis with severe penalties
   * for undefended valuable pieces that can be captured.
   *
   * @param playerPieces The player's pieces.
   * @param board The current chess board state.
   * @param opponent The opposing player.
   * @return The enhanced piece protection evaluation score.
   */
  private double evaluatePieceProtection(final Collection<Piece> playerPieces,
                                         final Board board,
                                         final Player opponent) {
    double protectionScore = 0;

    int[] squareAttackCount = new int[BoardUtils.NUM_TILES];
    int[] squarePawnAttackCount = new int[BoardUtils.NUM_TILES];
    int[] defenderCount = new int[BoardUtils.NUM_TILES];

    for (final Piece piece : playerPieces) {
      piece.addDefendedSquares(board, defenderCount);
    }

    for (final Move move : opponent.getLegalMoves()) {
      final int destination = move.getDestinationCoordinate();
      squareAttackCount[destination]++;
      if (move.getMovedPiece().getPieceType() == Piece.PieceType.PAWN) {
        squarePawnAttackCount[destination]++;
      }
    }

    for (final Piece piece : playerPieces) {
      if (piece.getPieceType() == Piece.PieceType.KING) continue;

      final int position = piece.getPiecePosition();
      final int protectionCount = defenderCount[position];
      final int attackCount = squareAttackCount[position];

      if (attackCount > 0) {
        if (protectionCount == 0) {
          switch (piece.getPieceType()) {
            case QUEEN -> protectionScore -= 850;
            case ROOK -> protectionScore -= 480;
            case BISHOP -> protectionScore -= 320;
            case KNIGHT -> protectionScore -= 300;
            case PAWN -> protectionScore -= 95;
          }
        } else if (attackCount > protectionCount) {
          double penalty = piece.getPieceValue() * 0.8 * (attackCount - protectionCount);
          protectionScore -= penalty;
        } else {
          protectionScore += piece.getPieceValue() * 0.1;
        }
      } else if (protectionCount > 0) {
        protectionScore += 8;

        if (piece.getPieceType() == Piece.PieceType.QUEEN) {
          protectionScore += 6 * protectionCount;
        } else if (piece.getPieceType() == Piece.PieceType.ROOK) {
          protectionScore += 4 * protectionCount;
        }
      }

      if (protectionCount == 0) {
        for (int i = 0; i < squarePawnAttackCount[position]; i++) {
          protectionScore -= piece.getPieceValue() * 0.3;
        }
      }
    }

    return protectionScore;
  }

  /**
   * Evaluates piece activity, particularly centralization of pieces.
   *
   * @param playerPieces The player's pieces.
   * @return The piece activity evaluation score.
   */
  private double evaluatePieceActivity(final Collection<Piece> playerPieces) {
    double activityScore = 0;

    for (final Piece piece : playerPieces) {
      final int position = piece.getPiecePosition();
      final int file = position % 8;
      final int rank = position / 8;

      int fileDistance = Math.min(Math.abs(file - 3), Math.abs(file - 4));
      int rankDistance = Math.min(Math.abs(rank - 3), Math.abs(rank - 4));
      int distanceFromCenter = fileDistance + rankDistance;

      switch (piece.getPieceType()) {
        case KNIGHT:
          activityScore += (6 - distanceFromCenter) * 5;
          break;
        case BISHOP:
          activityScore += (6 - distanceFromCenter) * 4;
          break;
        case ROOK:
          activityScore += (3 - fileDistance) * 3;
          break;
        case QUEEN:
          activityScore += (6 - distanceFromCenter) * 2;
          break;
      }
    }

    return activityScore;
  }

  /**
   * Evaluates space control for the given player.
   * Scores the player's moves into the enemy half and into the extended center, the player's
   * control of the four central squares, and the player's clamped legal move advantage over the
   * opponent. Every term but the clamped move advantage is a per-player quantity and the caller
   * forms the difference between the two players.
   *
   * @param player The player whose space control is being evaluated.
   * @return The space control evaluation score.
   */
  private double spaceControlEvaluation(final Player player) {
    double spaceScore = 0;
    final Collection<Move> playerMoves = player.getLegalMoves();
    final Collection<Move> opponentMoves = player.getOpponent().getLegalMoves();
    final Alliance alliance = player.getAlliance();

    int spacePiecesControl = 0;
    int[] controlledSquares = new int[BoardUtils.NUM_TILES];

    for (final Move move : playerMoves) {
      final int destination = move.getDestinationCoordinate();
      controlledSquares[destination]++;

      final int rank = destination / 8;
      final int file = destination % 8;

      if ((alliance.isWhite() && rank < 4) || (!alliance.isWhite() && rank > 3)) {
        spacePiecesControl++;
      }

      if (file >= 2 && file <= 5 && rank >= 2 && rank <= 5) {
        spacePiecesControl++;
      }
    }

    spaceScore += spacePiecesControl * 1.0;
    final int moveAdvantage = playerMoves.size() - opponentMoves.size();
    spaceScore += Math.max(-10, Math.min(moveAdvantage, 10)) * 3;

    final int[] keySquares = {27, 28, 35, 36};
    for (final int square : keySquares) {
      spaceScore += controlledSquares[square] * 5;
    }

    return spaceScore;
  }

  /**
   * Evaluates the attacking potential against the opponent's king.
   *
   * @param player The player whose attacking potential is being evaluated.
   * @return The attacking potential evaluation score.
   */
  private double attackingPotentialEvaluation(final Player player) {
    double attackScore = 0;
    final Collection<Move> playerMoves = player.getLegalMoves();
    final King opponentKing = player.getOpponent().getPlayerKing();
    final int kingPosition = opponentKing.getPiecePosition();

    int attackingPiecesCount = 0;
    int attackingSquaresCount = 0;

    boolean queenAttacking = false;
    boolean rookAttacking = false;
    boolean bishopAttacking = false;
    boolean knightAttacking = false;
    boolean pawnAttacking = false;

    for (final Move move : playerMoves) {
      final int destination = move.getDestinationCoordinate();
      final Piece piece = move.getMovedPiece();

      if (isSquareNearKing(kingPosition, destination)) {
        attackingSquaresCount++;

        switch (piece.getPieceType()) {
          case QUEEN:
            if (!queenAttacking) {
              attackingPiecesCount++;
              queenAttacking = true;
            }
            break;
          case ROOK:
            if (!rookAttacking) {
              attackingPiecesCount++;
              rookAttacking = true;
            }
            break;
          case BISHOP:
            if (!bishopAttacking) {
              attackingPiecesCount++;
              bishopAttacking = true;
            }
            break;
          case KNIGHT:
            if (!knightAttacking) {
              attackingPiecesCount++;
              knightAttacking = true;
            }
            break;
          case PAWN:
            if (!pawnAttacking) {
              attackingPiecesCount++;
              pawnAttacking = true;
            }
            break;
        }
      }
    }

    if (attackingPiecesCount >= 2) {
      attackScore += 20 * attackingPiecesCount;
      attackScore += 10 * attackingSquaresCount;

      if (queenAttacking) attackScore += 40;
      if (rookAttacking) attackScore += 30;
      if (bishopAttacking) attackScore += 20;
      if (knightAttacking) attackScore += 15;
      if (pawnAttacking) attackScore += 10;

      final boolean opponentKingOnCastledSquare = opponentKing.isOnCastledSquare();
      final boolean opponentChecked = player.getOpponent().isInCheck();

      if (opponentChecked) {
        attackScore += 50;
      }

      if (!opponentKingOnCastledSquare) {
        attackScore += 30;
      }

      final int defendingPiecesCount = countDefendingPieces(player.getOpponent(), kingPosition);
      attackScore -= defendingPiecesCount * 15;
    }

    return attackScore;
  }

  /**
   * Counts pieces that can help defend the king.
   *
   * @param player The defending player.
   * @param kingPosition The position of the king being defended.
   * @return The number of pieces that can defend the king.
   */
  private int countDefendingPieces(final Player player, final int kingPosition) {
    int defendingCount = 0;

    for (final Piece piece : player.getActivePieces()) {
      if (piece.getPieceType() == Piece.PieceType.KING) {
        continue;
      }

      final int piecePosition = piece.getPiecePosition();

      if (isSquareNearKing(kingPosition, piecePosition)) {
        defendingCount++;
      }
    }

    return defendingCount;
  }

  /**
   * Evaluates special positional patterns that are important in the middlegame.
   *
   * @param player The player whose special patterns are being evaluated.
   * @param board The current chess board.
   * @param playerPawns The player's pawns.
   * @param opponentPawns The opponent's pawns.
   * @return The special patterns evaluation score.
   */
  private double specialPatternsEvaluation(final Player player, final Board board,
                                           final List<Piece> playerPawns,
                                           final List<Piece> opponentPawns) {
    double patternScore = 0;
    final Collection<Piece> playerPieces = player.getActivePieces();
    final Alliance alliance = player.getAlliance();

    patternScore += evaluateRooksOn7thRank(playerPieces, board, alliance);
    patternScore += evaluateFianchetto(playerPieces, alliance);
    patternScore += evaluateBadBishops(playerPieces, playerPawns);
    patternScore += evaluateKnightOutposts(playerPieces, playerPawns, opponentPawns);
    patternScore += evaluateQueenPositioning(playerPieces, board, alliance);

    return patternScore;
  }

  /**
   * Evaluates rooks on the 7th rank (or 2nd for black), which is often very strong.
   *
   * @param playerPieces The player's pieces.
   * @param board The current chess board.
   * @param alliance The alliance of the player.
   * @return The rooks on 7th rank evaluation score.
   */
  private double evaluateRooksOn7thRank(final Collection<Piece> playerPieces,
                                        final Board board,
                                        final Alliance alliance) {
    double rookScore = 0;

    for (final Piece piece : playerPieces) {
      if (piece.getPieceType() == Piece.PieceType.ROOK) {
        final int position = piece.getPiecePosition();
        final int rank = position / 8;

        if ((alliance.isWhite() && rank == 1) || (!alliance.isWhite() && rank == 6)) {
          rookScore += 30;

          final Collection<Piece> opponentPieces = alliance.isWhite() ?
                  board.getBlackPieces() :
                  board.getWhitePieces();

          for (final Piece opponentPiece : opponentPieces) {
            if (opponentPiece.getPieceType() == Piece.PieceType.PAWN) {
              final int opponentRank = opponentPiece.getPiecePosition() / 8;

              if ((alliance.isWhite() && opponentRank == 1) ||
                      (!alliance.isWhite() && opponentRank == 6)) {
                rookScore += 10;
              }
            }
          }

          final King opponentKing = opposingKing(board, alliance);
          final int kingRank = opponentKing.getPiecePosition() / 8;

          if ((alliance.isWhite() && kingRank == 0) || (!alliance.isWhite() && kingRank == 7)) {
            rookScore += 20;
          }
        }
      }
    }

    return rookScore;
  }

  /**
   * Evaluates bishops standing on the fianchetto squares b2 and g2 for white, or b7 and g7 for
   * black. The supporting pawn structure is not inspected.
   *
   * @param playerPieces The player's pieces.
   * @param alliance The alliance of the player.
   * @return The fianchetto evaluation score.
   */
  private double evaluateFianchetto(final Collection<Piece> playerPieces, final Alliance alliance) {
    double fianchettoScore = 0;

    final int kingsideBishopPosition = alliance.isWhite() ? 54 : 14;
    final int queensideBishopPosition = alliance.isWhite() ? 49 : 9;

    for (final Piece piece : playerPieces) {
      if (piece.getPieceType() == Piece.PieceType.BISHOP) {
        final int position = piece.getPiecePosition();

        if (position == kingsideBishopPosition) {
          fianchettoScore += 20;
        } else if (position == queensideBishopPosition) {
          fianchettoScore += 15;
        }
      }
    }

    return fianchettoScore;
  }

  /**
   * Evaluates bad bishops - bishops blocked by their own pawns.
   *
   * @param playerPieces The player's pieces.
   * @param playerPawns The player's pawns.
   * @return The bad bishops evaluation score.
   */
  private double evaluateBadBishops(final Collection<Piece> playerPieces,
                                    final List<Piece> playerPawns) {
    double badBishopScore = 0;

    for (final Piece piece : playerPieces) {
      if (piece.getPieceType() == Piece.PieceType.BISHOP) {
        int pawnsOnSameColor = getPawnsOnSameColor(playerPawns, piece);

        if (pawnsOnSameColor >= 3) {
          badBishopScore -= 20;
        } else if (pawnsOnSameColor == 2) {
          badBishopScore -= 10;
        }
      }
    }

    return badBishopScore;
  }

  /**
   * Counts the number of pawns on the same colored squares as the bishop.
   *
   * @param playerPawns The player's pawns.
   * @param piece The bishop piece.
   * @return The number of pawns on the same colored squares.
   */
  private static int getPawnsOnSameColor(List<Piece> playerPawns, Piece piece) {
    final int position = piece.getPiecePosition();
    final boolean isLightSquare = ((position / 8) + (position % 8)) % 2 == 0;

    int pawnsOnSameColor = 0;

    for (final Piece pawn : playerPawns) {
      final int pawnPosition = pawn.getPiecePosition();
      final boolean isPawnOnLightSquare = ((pawnPosition / 8) + (pawnPosition % 8)) % 2 == 0;

      if (isLightSquare == isPawnOnLightSquare) {
        pawnsOnSameColor++;
      }
    }
    return pawnsOnSameColor;
  }

  /**
   * Evaluates queen positioning in the middlegame.
   *
   * @param playerPieces The player's pieces.
   * @param board The current chess board.
   * @param alliance The alliance of the player.
   * @return The queen positioning evaluation score.
   */
  private double evaluateQueenPositioning(final Collection<Piece> playerPieces,
                                          final Board board,
                                          final Alliance alliance) {
    double queenScore = 0;

    for (final Piece piece : playerPieces) {
      if (piece.getPieceType() == Piece.PieceType.QUEEN) {
        final int position = piece.getPiecePosition();
        final int rank = position / 8;
        final int file = position % 8;

        if (file >= 2 && file <= 5 && rank >= 2 && rank <= 5) {
          queenScore += 10;
        }

        boolean queenTooAdvanced = false;
        if (alliance.isWhite() && rank < 2) {
          queenTooAdvanced = true;
        } else if (!alliance.isWhite() && rank > 5) {
          queenTooAdvanced = true;
        }

        if (queenTooAdvanced) {
          int supportingPieces = getSupportingPieces(playerPieces, alliance, rank);

          if (supportingPieces < 2) {
            queenScore -= 25;
          }
        }

        final King opponentKing = opposingKing(board, alliance);
        final int kingPosition = opponentKing.getPiecePosition();

        final int distance = calculateChebyshevDistance(position, kingPosition);
        if (distance <= 2) {
          queenScore += 20;
        } else if (distance == 3) {
          queenScore += 10;
        }
      }
    }

    return queenScore;
  }

  /**
   * Counts the number of supporting pieces for a queen in an advanced position.
   *
   * @param playerPieces The player's pieces.
   * @param alliance The alliance of the player.
   * @param rank The rank of the queen.
   * @return The number of supporting pieces.
   */
  private static int getSupportingPieces(Collection<Piece> playerPieces, Alliance alliance, int rank) {
    int supportingPieces = 0;
    for (final Piece supportPiece : playerPieces) {
      if (supportPiece.getPieceType() != Piece.PieceType.QUEEN &&
              supportPiece.getPieceType() != Piece.PieceType.KING) {
        final int supportPosition = supportPiece.getPiecePosition();
        final int supportRank = supportPosition / 8;

        if ((alliance.isWhite() && supportRank <= rank) ||
                (!alliance.isWhite() && supportRank >= rank)) {
          supportingPieces++;
        }
      }
    }
    return supportingPieces;
  }

  /**
   * Calculates the Chebyshev distance between two squares.
   *
   * @param position1 The first position.
   * @param position2 The second position.
   * @return The Chebyshev distance between the positions.
   */
  private int calculateChebyshevDistance(final int position1, final int position2) {
    final int file1 = position1 % 8;
    final int rank1 = position1 / 8;
    final int file2 = position2 % 8;
    final int rank2 = position2 / 8;

    return Math.max(Math.abs(file1 - file2), Math.abs(rank1 - rank2));
  }

  /**
   * Gets a list of pawn pieces for the specified player.
   *
   * @param player The player whose pawns to retrieve.
   * @return A list of the player's pawn pieces.
   */
  private static List<Piece> getPlayerPawns(final Player player) {
    return player.getActivePieces().stream()
            .filter(piece -> piece.getPieceType() == Piece.PieceType.PAWN)
            .collect(Collectors.toList());
  }

  /**
   * Retrieves the king of the alliance opposing the given one. The returned king is null if the
   * opposing alliance has no king on the board.
   *
   * @param board The current chess board.
   * @param alliance The alliance whose opposing king to retrieve.
   * @return The king opposing the given alliance.
   */
  private static King opposingKing(final Board board, final Alliance alliance) {
    return board.getKing(alliance.isWhite() ? Alliance.BLACK : Alliance.WHITE);
  }
}