package engine.forPlayer.forAI;

import engine.forBoard.Board;
import engine.forPiece.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The GameStateDetector class provides precise identification of the current chess game phase
 * and selects the appropriate evaluation function based on comprehensive position analysis.
 * This class replaces the TaperedEvaluator with specialized evaluators for opening,
 * middlegame, and endgame positions, enhancing evaluation accuracy.
 *
 * @author Aaron Ho
 */
public class GameStateDetector {

  /** The singleton instance of the GameStateDetector. */
  private static final GameStateDetector INSTANCE = new GameStateDetector();

  /** Cache to avoid recalculating game state too frequently. */
  private final Map<Long, GamePhase> gameStateCache = new ConcurrentHashMap<>();

  /** Maximum size for the game state cache to prevent excessive memory usage. */
  private static final int CACHE_MAX_SIZE = 10000;

  /**
   * Enumeration representing the three phases of a chess game.
   */
  public enum GamePhase {
    /** The opening phase of the game. */
    OPENING,

    /** The middlegame phase of the game. */
    MIDDLEGAME,

    /** The endgame phase of the game. */
    ENDGAME
  }

  /**
   * Private constructor to enforce the singleton pattern.
   */
  private GameStateDetector() {}

  /**
   * Returns the singleton instance of the GameStateDetector.
   *
   * @return The singleton instance of GameStateDetector.
   */
  public static GameStateDetector get() {
    return INSTANCE;
  }

  /**
   * Determines the appropriate evaluator for the current board position
   * based on detailed analysis of the game state.
   *
   * @param board The current chess board.
   * @return The appropriate BoardEvaluator for the detected game phase.
   */
  public BoardEvaluator determineEvaluator(final Board board) {
    GamePhase gamePhase = detectGamePhase(board);

    return switch (gamePhase) {
      case OPENING -> OpeningGameEvaluator.get();
      case MIDDLEGAME -> MiddlegameBoardEvaluator.get();
      case ENDGAME -> EndgameBoardEvaluator.get();
      default -> MiddlegameBoardEvaluator.get();
    };
  }

  /**
   * Determines the current phase of the game by analyzing the board position.
   * Uses caching to avoid redundant calculations.
   *
   * @param board The current chess board.
   * @return The detected game phase.
   */
  public GamePhase detectGamePhase(final Board board) {
    long boardHash = board.getZobristHash();
    GamePhase cached = gameStateCache.get(boardHash);
    if (cached != null) {
      return cached;
    }

    int materialScore = calculateMaterialScore(board);
    int developmentScore = calculateDevelopmentScore(board);
    int moveCount = calculateMoveCount(board);
    int pieceCount = board.getAllPieces().size();
    int pawnStructureScore = evaluatePawnStructure(board);
    int kingActivityScore = evaluateKingActivity(board);

    GamePhase phase = determinePhaseFromIndicators(
            materialScore, developmentScore, moveCount,
            pieceCount, pawnStructureScore, kingActivityScore, board);

    if (gameStateCache.size() < CACHE_MAX_SIZE) {
      gameStateCache.put(boardHash, phase);
    }

    return phase;
  }

  /**
   * Calculates a material-based score for phase detection.
   * Lower scores indicate progression toward endgame.
   *
   * @param board The current chess board.
   * @return A material score for phase detection.
   */
  private int calculateMaterialScore(final Board board) {
    int materialScore = 0;
    int queenCount = 0;
    int minorPieceCount = 0;
    int majorPieceCount = 0;

    for (final Piece piece : board.getAllPieces()) {
      if (piece.getPieceType() != Piece.PieceType.KING &&
              piece.getPieceType() != Piece.PieceType.PAWN) {
        materialScore += piece.getPieceValue();

        switch (piece.getPieceType()) {
          case QUEEN:
            queenCount++;
            majorPieceCount++;
            break;
          case ROOK:
            majorPieceCount++;
            break;
          case BISHOP:
          case KNIGHT:
            minorPieceCount++;
            break;
        }
      }
    }

    return materialScore;
  }

  /**
   * Calculates a development score for phase detection.
   * Higher scores indicate more development which suggests middlegame.
   *
   * @param board The current chess board.
   * @return A development score for phase detection.
   */
  private int calculateDevelopmentScore(final Board board) {
    int developmentScore = 0;
    int developedMinorPieces = 0;

    for (final Piece piece : board.getAllPieces()) {
      if ((piece.getPieceType() == Piece.PieceType.KNIGHT ||
              piece.getPieceType() == Piece.PieceType.BISHOP) &&
              !piece.isFirstMove()) {

        int rank = piece.getPiecePosition() / 8;
        boolean isOffStartingRank = (piece.getPieceAllegiance().isWhite() && rank != 7) ||
                (!piece.getPieceAllegiance().isWhite() && rank != 0);

        if (isOffStartingRank) {
          developedMinorPieces++;
          developmentScore += 10;
        }
      }
    }

    if (board.whitePlayer().isCastled()) {
      developmentScore += 20;
    }
    if (board.blackPlayer().isCastled()) {
      developmentScore += 20;
    }

    return developmentScore;
  }

  /**
   * Returns the number of plies played on this board since it was constructed.
   *
   * @param board The current chess board.
   * @return The number of plies played so far.
   */
  private int calculateMoveCount(final Board board) {
    return board.getPlyCount();
  }

  /**
   * Evaluates pawn structure to help determine game phase.
   *
   * @param board The current chess board.
   * @return A pawn structure score for phase detection.
   */
  private int evaluatePawnStructure(final Board board) {
    int score = 0;
    int totalPawns = 0;

    for (final Piece piece : board.getAllPieces()) {
      if (piece.getPieceType() == Piece.PieceType.PAWN) {
        totalPawns++;

        int rank = piece.getPiecePosition() / 8;
        if ((piece.getPieceAllegiance().isWhite() && rank <= 2) ||
                (!piece.getPieceAllegiance().isWhite() && rank >= 5)) {
          score += 10;
        }
      }
    }

    score += (16 - totalPawns) * 5;

    return score;
  }

  /**
   * Evaluates king activity to help determine game phase.
   * More active kings indicate endgame.
   *
   * @param board The current chess board.
   * @return A king activity score for phase detection.
   */
  private int evaluateKingActivity(final Board board) {
    int score = 0;

    King whiteKing = board.whitePlayer().getPlayerKing();
    King blackKing = board.blackPlayer().getPlayerKing();

    score += calculateKingCentralization(whiteKing.getPiecePosition());
    score += calculateKingCentralization(blackKing.getPiecePosition());

    if (!isKingOnBackRank(whiteKing)) {
      score += 30;
    }

    if (!isKingOnBackRank(blackKing)) {
      score += 30;
    }

    return score;
  }

  /**
   * Calculates a king centralization score.
   * Higher values indicate more central kings which is typical in endgame.
   *
   * @param kingPosition The position of the king.
   * @return A centralization score.
   */
  private int calculateKingCentralization(final int kingPosition) {
    final int file = kingPosition % 8;
    final int rank = kingPosition / 8;

    final int fileDistance = Math.min(file, 7 - file);
    final int rankDistance = Math.min(rank, 7 - rank);

    return (fileDistance + rankDistance) * 5;
  }

  /**
   * Checks if a king is on its original back rank.
   *
   * @param king The king to check.
   * @return True if the king is on its back rank, false otherwise.
   */
  private boolean isKingOnBackRank(final King king) {
    final int kingRank = king.getPiecePosition() / 8;
    return (king.getPieceAllegiance().isWhite() && kingRank == 7) ||
            (!king.getPieceAllegiance().isWhite() && kingRank == 0);
  }

  /**
   * Determines the game phase based on all calculated indicators.
   * Uses a sophisticated weighted approach to handle edge cases.
   *
   * @param materialScore Material-based score.
   * @param developmentScore Development-based score.
   * @param moveCount Approximate number of moves made.
   * @param pieceCount Total pieces remaining.
   * @param pawnStructureScore Pawn structure evaluation.
   * @param kingActivityScore King activity evaluation.
   * @param board The current chess board for additional checks.
   * @return The determined game phase.
   */
  private GamePhase determinePhaseFromIndicators(
          int materialScore, int developmentScore, int moveCount,
          int pieceCount, int pawnStructureScore, int kingActivityScore,
          Board board) {

    int openingScore = calculateOpeningScore(
            materialScore, developmentScore, moveCount, pieceCount, board);

    int middlegameScore = calculateMiddlegameScore(
            materialScore, developmentScore, moveCount, pieceCount, board);

    int endgameScore = calculateEndgameScore(
            materialScore, moveCount, pieceCount,
            pawnStructureScore, kingActivityScore, board);

    if (endgameScore > middlegameScore && endgameScore > openingScore) {
      return GamePhase.ENDGAME;
    } else if (middlegameScore > openingScore) {
      return GamePhase.MIDDLEGAME;
    } else {
      return GamePhase.OPENING;
    }
  }

  /**
   * Calculates a score indicating how much the position resembles an opening.
   *
   * @param materialScore The material score.
   * @param developmentScore The development score.
   * @param moveCount The number of moves made.
   * @param pieceCount The total piece count.
   * @param board The current chess board.
   * @return A score indicating opening characteristics.
   */
  private int calculateOpeningScore(
          int materialScore, int developmentScore, int moveCount,
          int pieceCount, Board board) {

    int score = 0;

    if (materialScore > 7000) {
      score += 50;
    } else if (materialScore > 6000) {
      score += 30;
    }

    if (developmentScore < 40) {
      score += 40;
    } else if (developmentScore < 80) {
      score += 20;
    }

    if (moveCount < 10) {
      score += 50;
    } else if (moveCount < 20) {
      score += 30;
    }

    if (pieceCount >= 30) {
      score += 40;
    } else if (pieceCount >= 26) {
      score += 20;
    }

    int undevelopedMinorPieces = countUndevelopedMinorPieces(board);
    score += undevelopedMinorPieces * 5;

    if (isKingOnBackRank(board.whitePlayer().getPlayerKing()) &&
            isKingOnBackRank(board.blackPlayer().getPlayerKing())) {
      score += 30;
    }

    return score;
  }

  /**
   * Counts undeveloped minor pieces on the board.
   *
   * @param board The current chess board.
   * @return The number of undeveloped knights and bishops.
   */
  private int countUndevelopedMinorPieces(Board board) {
    int count = 0;

    for (final Piece piece : board.getAllPieces()) {
      if ((piece.getPieceType() == Piece.PieceType.KNIGHT ||
              piece.getPieceType() == Piece.PieceType.BISHOP) &&
              piece.isFirstMove()) {
        count++;
      }
    }

    return count;
  }

  /**
   * Calculates a score indicating how much the position resembles a middlegame.
   *
   * @param materialScore The material score.
   * @param developmentScore The development score.
   * @param moveCount The number of moves made.
   * @param pieceCount The total piece count.
   * @param board The current chess board.
   * @return A score indicating middlegame characteristics.
   */
  private int calculateMiddlegameScore(
          int materialScore, int developmentScore, int moveCount,
          int pieceCount, Board board) {

    int score = 0;

    if (materialScore >= 4000 && materialScore <= 7000) {
      score += 40;
    }

    if (developmentScore >= 80 && developmentScore <= 120) {
      score += 40;
    } else if (developmentScore > 40) {
      score += 20;
    }

    if (moveCount >= 15 && moveCount <= 40) {
      score += 40;
    } else if (moveCount > 10) {
      score += 20;
    }

    if (pieceCount >= 20 && pieceCount < 30) {
      score += 40;
    } else if (pieceCount >= 16) {
      score += 20;
    }

    boolean whiteQueenPresent = false;
    boolean blackQueenPresent = false;

    for (final Piece piece : board.getAllPieces()) {
      if (piece.getPieceType() == Piece.PieceType.QUEEN) {
        if (piece.getPieceAllegiance().isWhite()) {
          whiteQueenPresent = true;
        } else {
          blackQueenPresent = true;
        }
      }
    }

    if (whiteQueenPresent && blackQueenPresent) {
      score += 40;
    } else if (whiteQueenPresent || blackQueenPresent) {
      score += 20;
    }

    if (board.whitePlayer().isCastled() || board.blackPlayer().isCastled()) {
      score += 20;
    }

    return score;
  }

  /**
   * Calculates a score indicating how much the position resembles an endgame.
   *
   * @param materialScore The material score.
   * @param moveCount The number of moves made.
   * @param pieceCount The total piece count.
   * @param pawnStructureScore The pawn structure score.
   * @param kingActivityScore The king activity score.
   * @param board The current chess board.
   * @return A score indicating endgame characteristics.
   */
  private int calculateEndgameScore(
          int materialScore, int moveCount, int pieceCount,
          int pawnStructureScore, int kingActivityScore, Board board) {

    int score = 0;

    if (materialScore < 3000) {
      score += 60;
    } else if (materialScore < 4000) {
      score += 40;
    }

    if (moveCount > 40) {
      score += 30;
    } else if (moveCount > 30) {
      score += 15;
    }

    if (pieceCount < 16) {
      score += 60;
    } else if (pieceCount < 20) {
      score += 30;
    }

    score += pawnStructureScore;
    score += kingActivityScore;

    boolean queensPresent = false;
    for (final Piece piece : board.getAllPieces()) {
      if (piece.getPieceType() == Piece.PieceType.QUEEN) {
        queensPresent = true;
        break;
      }
    }

    if (!queensPresent) {
      score += 50;
    }

    if (hasPawnPromotionPotential(board)) {
      score += 30;
    }

    int nonPawnPieceCount = 0;
    for (final Piece piece : board.getAllPieces()) {
      if (piece.getPieceType() != Piece.PieceType.PAWN &&
              piece.getPieceType() != Piece.PieceType.KING) {
        nonPawnPieceCount++;
      }
    }

    if (nonPawnPieceCount <= 6) {
      score += 40;
    } else if (nonPawnPieceCount <= 10) {
      score += 20;
    }

    return score;
  }

  /**
   * Checks if there are pawns with promotion potential.
   *
   * @param board The current chess board.
   * @return True if pawns are close to promotion, false otherwise.
   */
  private boolean hasPawnPromotionPotential(final Board board) {
    for (final Piece piece : board.getAllPieces()) {
      if (piece.getPieceType() == Piece.PieceType.PAWN) {
        final int rank = piece.getPiecePosition() / 8;
        if ((piece.getPieceAllegiance().isWhite() && rank <= 2) ||
                (!piece.getPieceAllegiance().isWhite() && rank >= 5)) {
          return true;
        }
      }
    }
    return false;
  }
}