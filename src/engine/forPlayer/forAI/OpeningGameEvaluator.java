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
 * The OpeningGameEvaluator class provides specialized board evaluation for opening positions in chess.
 * This evaluator prioritizes opening principles such as piece development, center control, king safety,
 * and tempo over pure material advantage. It implements the BoardEvaluator interface and follows
 * the singleton pattern to ensure consistent evaluation throughout the chess engine.
 *
 * @author Aaron Ho
 */
public class OpeningGameEvaluator implements BoardEvaluator {

  /** Singleton instance of the OpeningGameEvaluator. */
  private static final OpeningGameEvaluator Instance = new OpeningGameEvaluator();

  /**
   * Constructs a new OpeningGameEvaluator instance.
   * Private constructor prevents external instantiation to enforce singleton pattern.
   */
  private OpeningGameEvaluator() {}

  /**
   * Returns the singleton instance of OpeningGameEvaluator.
   *
   * @return The singleton instance of OpeningGameEvaluator.
   */
  public static OpeningGameEvaluator get() {
    return Instance;
  }

  /**
   * Evaluates the given board position and returns a numerical score.
   * The evaluation considers opening-specific factors with appropriate weightings.
   *
   * @param board The current state of the chess board.
   * @param depth The search depth in the AI's thinking process.
   * @return The evaluation score of the board from white's perspective.
   */
  @Override
  public double evaluate(final Board board, final int depth) {
    return (score(board.whitePlayer(), board, depth) - score(board.blackPlayer(), board, depth));
  }

  /**
   * Calculates the overall positional score for a given player focusing on opening principles.
   * Combines material, development, center control, king safety, pawn structure, mobility,
   * piece coordination, and tempo evaluations with opening-specific weightings.
   *
   * @param player The player for whom the board position is being evaluated.
   * @param board The current state of the chess board.
   * @param depth The current search depth for evaluation adjustments.
   * @return The evaluation score from the perspective of the specified player.
   */
  @VisibleForTesting
  private double score(final Player player, final Board board, final int depth) {

    return materialScore(player.getActivePieces()) +
            developmentScore(player, board) +
            centerControlScore(player, board) +
            kingSafetyScore(player, board) +
            pawnStructureScore(player, board) +
            mobilityScore(player, board) +
            pieceCoordinationScore(player, board) +
            tempoScore(player, board, depth) +
            pieceSafetyScore(player, board);
  }

  /**
   * Evaluates material balance with reduced weight during the opening phase.
   * Material sacrifices for initiative are more acceptable in opening positions.
   *
   * @param playerPieces The collection of pieces belonging to the player.
   * @return The material evaluation score.
   */
  private double materialScore(final Collection<Piece> playerPieces) {
    double materialValue = 0;
    for (final Piece piece : playerPieces) {
      materialValue += piece.getPieceValue();
    }
    return materialValue;
  }

  /**
   * Evaluates piece development for the given player.
   * Scores each knight and bishop by whether it stands off its own back rank, penalises a queen
   * that has left its home square in proportion to the number of minor pieces still undeveloped,
   * and scores castling status.
   *
   * @param player The player whose development is being evaluated.
   * @param board The current chess board state.
   * @return The development evaluation score.
   */
  private double developmentScore(final Player player, final Board board) {
    double score = 0;
    Collection<Piece> playerPieces = player.getActivePieces();
    Alliance alliance = player.getAlliance();
    boolean isWhite = alliance.isWhite();

    final int queenHomeSquare = isWhite ? 59 : 3;

    int developedMinorPieces = 0;
    int undevelopedMinorPieces = 0;
    boolean queenSortied = false;
    boolean queenPastMidline = false;
    boolean castled = player.isCastled();

    for (Piece piece : playerPieces) {
      final int pieceRank = piece.getPiecePosition() / 8;

      if (piece.getPieceType() == Piece.PieceType.KNIGHT ||
              piece.getPieceType() == Piece.PieceType.BISHOP) {

        boolean developedPosition = false;
        if ((isWhite && pieceRank != 7) || (!isWhite && pieceRank != 0)) {
          score += 15;
          developedMinorPieces++;
          developedPosition = true;

          if (isCentralPosition(piece.getPiecePosition(), piece.getPieceType())) {
            score += 8;
          }
        } else {
          score -= 20;
          undevelopedMinorPieces++;
        }

        if (developedPosition) {
          Collection<Move> pieceMoves = piece.calculateLegalMoves(board);
          score += pieceMoves.size() * 2;
        }
      }

      if (piece.getPieceType() == Piece.PieceType.QUEEN &&
              piece.getPiecePosition() != queenHomeSquare) {
        queenSortied = true;

        final int queenRank = piece.getPiecePosition() / 8;
        if ((isWhite && queenRank < 4) || (!isWhite && queenRank > 3)) {
          queenPastMidline = true;
        }
      }
    }

    if (queenSortied) {
      score -= undevelopedMinorPieces * 20;

      if (queenPastMidline) {
        score -= undevelopedMinorPieces * 15;
      }
    }

    if (castled) {
      score += 100;
    } else if (canCastle(player)) {
      score += 25;
    } else if (!canCastle(player)) {
      score -= 60;
    }

    if (developedMinorPieces >= 3 && castled && !queenSortied) {
      score += 20;
    }

    return score;
  }

  /**
   * Determines if the given position is centralized for the specified piece type.
   * Central positions are generally favorable for piece development in the opening.
   *
   * @param position The board position to evaluate.
   * @param pieceType The type of piece being evaluated.
   * @return True if the position is considered centralized for the piece type.
   */
  private boolean isCentralPosition(int position, Piece.PieceType pieceType) {
    int file = position % 8;
    int rank = position / 8;

    boolean isExtendedCenter = (file >= 2 && file <= 5 && rank >= 2 && rank <= 5);

    if (pieceType == Piece.PieceType.KNIGHT) {
      return (file >= 2 && file <= 5 && rank >= 2 && rank <= 5);
    } else if (pieceType == Piece.PieceType.BISHOP) {
      return isExtendedCenter ||
              position == 16 || position == 23 ||
              position == 40 || position == 47;
    }

    return isExtendedCenter;
  }

  /**
   * Checks if the player retains castling rights on either side.
   *
   * @param player The player to check for castling capabilities.
   * @return True if the player can still castle king-side or queen-side.
   */
  private boolean canCastle(Player player) {
    return player.getPlayerKing().isKingSideCastleCapable() ||
            player.getPlayerKing().isQueenSideCastleCapable();
  }

  /**
   * Evaluates center control which is fundamental to opening theory.
   * Rewards occupation and control of central squares with pieces and pawns.
   *
   * @param player The player whose center control is being evaluated.
   * @param board The current chess board state.
   * @return The center control evaluation score.
   */
  private double centerControlScore(final Player player, final Board board) {
    double score = 0;
    Collection<Piece> playerPieces = player.getActivePieces();
    Collection<Move> playerMoves = player.getLegalMoves();

    final int[] centralSquares = {28, 29, 36, 37};
    final int[] extendedCenterSquares = {
            18, 19, 20, 21, 22, 23,
            26, 27, 28, 29, 30, 31,
            34, 35, 36, 37, 38, 39,
            42, 43, 44, 45, 46, 47
    };

    for (Piece piece : playerPieces) {
      int position = piece.getPiecePosition();

      for (int centralSquare : centralSquares) {
        if (position == centralSquare) {
          if (piece.getPieceType() == Piece.PieceType.PAWN) {
            score += 80;
          } else if (piece.getPieceType() == Piece.PieceType.KNIGHT ||
                  piece.getPieceType() == Piece.PieceType.BISHOP) {
            score += 40;
          } else {
            score += 20;
          }
        }
      }

      for (int extendedSquare : extendedCenterSquares) {
        if (position == extendedSquare) {
          if (piece.getPieceType() == Piece.PieceType.PAWN) {
            score += 30;
          } else {
            score += 15;
          }
        }
      }
    }

    int[] controlledSquares = new int[BoardUtils.NUM_TILES];
    for (Move move : playerMoves) {
      controlledSquares[move.getDestinationCoordinate()]++;
    }

    for (int centralSquare : centralSquares) {
      score += controlledSquares[centralSquare] * 15;
    }

    for (int extendedSquare : extendedCenterSquares) {
      score += controlledSquares[extendedSquare] * 5;
    }

    int centerPawns = 0;
    boolean hasDPawn = false;
    boolean hasEPawn = false;

    for (Piece piece : playerPieces) {
      if (piece.getPieceType() == Piece.PieceType.PAWN) {
        int file = piece.getPiecePosition() % 8;
        if (file == 3 || file == 4) {
          centerPawns++;
          if (file == 3) hasDPawn = true;
          if (file == 4) hasEPawn = true;
        }
      }
    }

    if (hasDPawn && hasEPawn) {
      score += 50;
    }

    return score;
  }

  /**
   * Evaluates king safety which is critical during the opening phase.
   * Prioritizes castling and penalizes king exposure in the center.
   *
   * @param player The player whose king safety is being evaluated.
   * @param board The current chess board state.
   * @return The king safety evaluation score.
   */
  private double kingSafetyScore(final Player player, final Board board) {
    double score = 0;
    final King playerKing = player.getPlayerKing();
    final int kingPosition = playerKing.getPiecePosition();

    if (player.isCastled()) {
      score += 120;
      score += evaluatePawnShield(player, kingPosition);
    } else {
      int file = kingPosition % 8;
      int rank = kingPosition / 8;

      boolean inCenter = (file >= 2 && file <= 5);
      if (inCenter) {
        score -= 80;
      }

      if (!playerKing.isFirstMove()) {
        score -= 70;
      }
    }

    score -= evaluateKingAttackPotential(kingPosition, player.getOpponent());

    return score;
  }

  /**
   * Evaluates the quality of the pawn shield protecting the castled king.
   * Counts pawns in shield positions and awards bonuses for intact formations.
   *
   * @param player The player whose pawn shield is being evaluated.
   * @param kingPosition The position of the king on the board.
   * @return The pawn shield evaluation score.
   */
  private double evaluatePawnShield(Player player, int kingPosition) {
    double score = 0;
    Collection<Piece> playerPieces = player.getActivePieces();
    List<Piece> pawns = playerPieces.stream()
            .filter(p -> p.getPieceType() == Piece.PieceType.PAWN)
            .toList();

    int kingFile = kingPosition % 8;
    int kingRank = kingPosition / 8;
    boolean isKingSide = (kingFile >= 5);

    List<Integer> shieldSquares = new ArrayList<>();
    if (isKingSide) {
      if (player.getAlliance().isWhite()) {
        shieldSquares.add(kingPosition - 9);
        shieldSquares.add(kingPosition - 8);
        shieldSquares.add(kingPosition - 7);
      } else {
        shieldSquares.add(kingPosition + 7);
        shieldSquares.add(kingPosition + 8);
        shieldSquares.add(kingPosition + 9);
      }
    } else {
      if (player.getAlliance().isWhite()) {
        shieldSquares.add(kingPosition - 9);
        shieldSquares.add(kingPosition - 8);
        shieldSquares.add(kingPosition - 7);
      } else {
        shieldSquares.add(kingPosition + 7);
        shieldSquares.add(kingPosition + 8);
        shieldSquares.add(kingPosition + 9);
      }
    }

    int pawnsInShield = 0;
    for (Integer shieldSquare : shieldSquares) {
      if (shieldSquare >= 0 && shieldSquare < 64) {
        for (Piece pawn : pawns) {
          if (pawn.getPiecePosition() == shieldSquare) {
            pawnsInShield++;
            break;
          }
        }
      }
    }

    if (pawnsInShield == 3) {
      score += 60;
    } else if (pawnsInShield == 2) {
      score += 30;
    } else if (pawnsInShield == 1) {
      score += 10;
    } else {
      score -= 40;
    }

    return score;
  }

  /**
   * Evaluates the potential for king attacks by calculating opponent piece proximity.
   * Awards penalties based on the attacking potential of nearby opponent pieces.
   *
   * @param kingPosition The position of the king being evaluated.
   * @param opponent The opposing player whose pieces pose attack threats.
   * @return The king attack potential penalty score.
   */
  private double evaluateKingAttackPotential(int kingPosition, Player opponent) {
    double attackPotential = 0;
    Collection<Piece> opponentPieces = opponent.getActivePieces();

    for (Piece piece : opponentPieces) {
      int piecePos = piece.getPiecePosition();
      int rankDistance = Math.abs((kingPosition / 8) - (piecePos / 8));
      int fileDistance = Math.abs((kingPosition % 8) - (piecePos % 8));
      int distance = Math.max(rankDistance, fileDistance);

      if (distance <= 2) {
        switch (piece.getPieceType()) {
          case QUEEN:
            attackPotential += (3 - distance) * 40;
            break;
          case ROOK:
            attackPotential += (3 - distance) * 25;
            break;
          case BISHOP:
            attackPotential += (3 - distance) * 15;
            break;
          case KNIGHT:
            attackPotential += (3 - distance) * 20;
            break;
          case PAWN:
            attackPotential += (3 - distance) * 5;
            break;
        }
      }
    }

    return attackPotential;
  }

  /**
   * Evaluates pawn structure with emphasis on opening considerations.
   * Analyzes center pawns, doubled pawns, isolated pawns, pawn chains, and pawn advances.
   *
   * @param player The player whose pawn structure is being evaluated.
   * @param board The current chess board state.
   * @return The pawn structure evaluation score.
   */
  private double pawnStructureScore(final Player player, final Board board) {
    double score = 0;
    Alliance alliance = player.getAlliance();
    List<Piece> pawns = getPawns(player);

    score += evaluateCenterPawns(pawns, alliance);
    score += evaluateDoubledPawns(pawns);
    score += evaluateIsolatedPawns(pawns);
    score += evaluatePawnChains(pawns);
    score += evaluatePawnAdvances(pawns, alliance);

    return score;
  }

  /**
   * Evaluates the central pawn structure for opening control.
   * Rewards presence of pawns on central files and supporting pawn formations.
   *
   * @param pawns The collection of pawns to evaluate.
   * @param alliance The alliance of the pawns being evaluated.
   * @return The center pawn evaluation score.
   */
  private double evaluateCenterPawns(final List<Piece> pawns, final Alliance alliance) {
    double score = 0;

    boolean hasDPawn = false;
    boolean hasEPawn = false;
    boolean hasCPawn = false;
    boolean hasFPawn = false;

    for (Piece pawn : pawns) {
      int position = pawn.getPiecePosition();
      int file = position % 8;

      switch (file) {
        case 2: hasCPawn = true; break;
        case 3: hasDPawn = true; break;
        case 4: hasEPawn = true; break;
        case 5: hasFPawn = true; break;
      }
    }

    if (hasDPawn && hasEPawn) {
      score += 60;
    } else if (hasDPawn || hasEPawn) {
      score += 30;
    }

    if ((hasDPawn && hasCPawn) || (hasEPawn && hasFPawn)) {
      score += 20;
    }

    return score;
  }

  /**
   * Evaluates doubled pawns which are particularly problematic in the opening.
   * Awards severe penalties for multiple pawns on the same file.
   *
   * @param pawns The collection of pawns to evaluate.
   * @return The doubled pawn penalty score.
   */
  private double evaluateDoubledPawns(final List<Piece> pawns) {
    double score = 0;

    int[] pawnsPerFile = new int[8];
    for (Piece pawn : pawns) {
      pawnsPerFile[pawn.getPiecePosition() % 8]++;
    }

    for (int count : pawnsPerFile) {
      if (count > 1) {
        score -= (count - 1) * 35;
      }
    }

    return score;
  }

  /**
   * Evaluates isolated pawns which lack support from adjacent files.
   * Awards higher penalties for isolated center pawns versus wing pawns.
   *
   * @param pawns The collection of pawns to evaluate.
   * @return The isolated pawn penalty score.
   */
  private double evaluateIsolatedPawns(final List<Piece> pawns) {
    double score = 0;

    boolean[] filesWithPawns = new boolean[8];
    for (Piece pawn : pawns) {
      filesWithPawns[pawn.getPiecePosition() % 8] = true;
    }

    for (Piece pawn : pawns) {
      int file = pawn.getPiecePosition() % 8;
      boolean isIsolated = file <= 0 || !filesWithPawns[file - 1];

      if (file < 7 && filesWithPawns[file + 1]) {
        isIsolated = false;
      }

      if (isIsolated) {
        if (file == 3 || file == 4) {
          score -= 40;
        } else {
          score -= 25;
        }
      }
    }

    return score;
  }

  /**
   * Evaluates pawn chains which provide mutual protection and structural strength.
   * Awards bonuses for connected pawns on adjacent files.
   *
   * @param pawns The collection of pawns to evaluate.
   * @return The pawn chain bonus score.
   */
  private double evaluatePawnChains(final List<Piece> pawns) {
    double score = 0;

    Map<Integer, List<Piece>> pawnsByFile = new HashMap<>();
    for (Piece pawn : pawns) {
      int file = pawn.getPiecePosition() % 8;
      if (!pawnsByFile.containsKey(file)) {
        pawnsByFile.put(file, new ArrayList<>());
      }
      pawnsByFile.get(file).add(pawn);
    }

    int chainLength = 0;
    for (int file = 0; file < 7; file++) {
      if (pawnsByFile.containsKey(file) && pawnsByFile.containsKey(file + 1)) {
        chainLength++;
      } else if (chainLength > 0) {
        score += 10 * chainLength;
        chainLength = 0;
      }
    }

    if (chainLength > 0) {
      score += 10 * chainLength;
    }

    return score;
  }

  /**
   * Evaluates pawn advances and penalizes excessive early advances.
   * Encourages conservative pawn play except for central files in the opening.
   *
   * @param pawns The collection of pawns to evaluate.
   * @param alliance The alliance of the pawns being evaluated.
   * @return The pawn advance evaluation score.
   */
  private double evaluatePawnAdvances(final List<Piece> pawns, final Alliance alliance) {
    double score = 0;

    for (Piece pawn : pawns) {
      int position = pawn.getPiecePosition();
      int rank = position / 8;
      int file = position % 8;

      int advanceLevel = alliance.isWhite() ?
              (6 - rank) : (rank - 1);

      if (advanceLevel > 2) {
        if (file == 3 || file == 4) {
          if (advanceLevel > 3) {
            score -= (advanceLevel - 3) * 15;
          }
        } else {
          score -= (advanceLevel - 2) * 20;
        }
      }

      if ((file == 0 || file == 7) && advanceLevel > 0) {
        score -= 15;
      }

      if (advanceLevel > 0 &&
              ((alliance.isWhite() && rank <= 6 && (file >= 5 || file <= 2)) ||
                      (!alliance.isWhite() && rank >= 1 && (file >= 5 || file <= 2)))) {
        score -= 15;
      }
    }

    return score;
  }

  /**
   * Evaluates piece mobility for the given player.
   * Counts the player's legal moves and adds a further weighting for the moves available to each
   * knight and bishop.
   *
   * @param player The player whose mobility is being evaluated.
   * @param board The current chess board state.
   * @return The mobility evaluation score.
   */
  private double mobilityScore(final Player player, final Board board) {
    double score = 0;
    Collection<Move> playerMoves = player.getLegalMoves();

    score += playerMoves.size();

    for (Piece piece : player.getActivePieces()) {
      if (piece.getPieceType() == Piece.PieceType.KNIGHT) {
        Collection<Move> knightMoves = piece.calculateLegalMoves(board);
        score += knightMoves.size() * 1.5;
      } else if (piece.getPieceType() == Piece.PieceType.BISHOP) {
        Collection<Move> bishopMoves = piece.calculateLegalMoves(board);
        score += bishopMoves.size() * 1.5;
      }
    }

    return score;
  }

  /**
   * Evaluates piece coordination and harmony in opening positions.
   * Rewards pieces that protect each other and follow good development patterns.
   *
   * @param player The player whose piece coordination is being evaluated.
   * @param board The current chess board state.
   * @return The piece coordination evaluation score.
   */
  private double pieceCoordinationScore(final Player player, final Board board) {
    double score = 0;
    Collection<Piece> playerPieces = player.getActivePieces();
    Collection<Move> playerMoves = player.getLegalMoves();

    int[] protectedSquares = new int[BoardUtils.NUM_TILES];

    for (Move move : playerMoves) {
      protectedSquares[move.getDestinationCoordinate()]++;
    }

    for (Piece piece : playerPieces) {
      int position = piece.getPiecePosition();
      if (protectedSquares[position] > 0) {
        score += 10;

        if (piece.getPieceType() == Piece.PieceType.KNIGHT ||
                piece.getPieceType() == Piece.PieceType.BISHOP) {
          score += 15;
        }
      }
    }

    score += evaluateDevelopmentPatterns(player, board);

    return score;
  }

  /**
   * Evaluates specific good development patterns common in opening theory.
   * Rewards fianchetto formations, connected rooks, and penalizes poorly placed knights.
   *
   * @param player The player whose development patterns are being evaluated.
   * @param board The current chess board state.
   * @return The development pattern evaluation score.
   */
  private double evaluateDevelopmentPatterns(Player player, Board board) {
    double score = 0;
    Collection<Piece> pieces = player.getActivePieces();
    Alliance alliance = player.getAlliance();

    boolean kingsideFianchetto = false;
    boolean queensideFianchetto = false;

    for (Piece piece : pieces) {
      if (piece.getPieceType() == Piece.PieceType.BISHOP) {
        int position = piece.getPiecePosition();

        if (alliance.isWhite()) {
          if (position == 16) queensideFianchetto = true;
          if (position == 23) kingsideFianchetto = true;
        } else {
          if (position == 40) queensideFianchetto = true;
          if (position == 47) kingsideFianchetto = true;
        }
      }
    }

    if (kingsideFianchetto) score += 25;
    if (queensideFianchetto) score += 20;

    boolean rooksConnected = areRooksConnected(pieces);
    if (rooksConnected) score += 30;

    for (Piece piece : pieces) {
      if (piece.getPieceType() == Piece.PieceType.KNIGHT) {
        int position = piece.getPiecePosition();
        int file = position % 8;
        int rank = position / 8;

        if (file == 0 || file == 7 || rank == 0 || rank == 7) {
          score -= 30;
        }
      }
    }

    return score;
  }

  /**
   * Checks if rooks are connected by being placed on the same rank.
   * Connected rooks provide mutual protection and increased activity.
   *
   * @param pieces The collection of pieces to check for rook connections.
   * @return True if two or more rooks are connected on the same rank.
   */
  private boolean areRooksConnected(Collection<Piece> pieces) {
    List<Piece> rooks = pieces.stream()
            .filter(p -> p.getPieceType() == Piece.PieceType.ROOK)
            .toList();

    if (rooks.size() >= 2) {
      int firstRookRank = rooks.get(0).getPiecePosition() / 8;

      for (int i = 1; i < rooks.size(); i++) {
        int rookRank = rooks.get(i).getPiecePosition() / 8;
        if (rookRank == firstRookRank) {
          return true;
        }
      }
    }

    return false;
  }

  /**
   * Evaluates tempo and initiative which are crucial in opening positions.
   * Rewards attacking moves, development advantage, and initiative-gaining moves.
   *
   * @param player The player whose tempo is being evaluated.
   * @param board The current chess board state.
   * @param depth The search depth for depth-based tempo considerations.
   * @return The tempo evaluation score.
   */
  private double tempoScore(final Player player, final Board board, final int depth) {
    double score = 0;
    Collection<Move> playerMoves = player.getLegalMoves();
    Collection<Piece> playerPieces = player.getActivePieces();
    Collection<Piece> opponentPieces = player.getOpponent().getActivePieces();

    int attackingMoves = 0;
    for (Move move : playerMoves) {
      if (move.isAttack()) {
        attackingMoves++;

        Piece attackedPiece = move.getAttackedPiece();
        if (attackedPiece != null &&
                !isPieceDefended(attackedPiece, player.getOpponent(), board)) {
          score += 15;
        }
      }
    }

    score += Math.min(attackingMoves, 10) * 3;

    int developedMinorPieces = countDevelopedMinorPieces(playerPieces, player.getAlliance());
    int opponentDevelopedMinorPieces = countDevelopedMinorPieces(opponentPieces, player.getOpponent().getAlliance());

    if (developedMinorPieces > opponentDevelopedMinorPieces) {
      score += (developedMinorPieces - opponentDevelopedMinorPieces) * 30;
    }

    if (depth > 5) {
      score += evaluateInitiative(player, board);
    }

    return score;
  }

  /**
   * Evaluates piece safety by detecting hanging pieces and undefended valuable pieces.
   * Heavily penalizes pieces that can be captured for free or are inadequately defended.
   * This is crucial in the opening to prevent tactical blunders.
   *
   * @param player The player whose piece safety is being evaluated.
   * @param board The current chess board state.
   * @return The piece safety evaluation score (negative values indicate unsafe pieces).
   */
  private double pieceSafetyScore(final Player player, final Board board) {
    double safetyScore = 0;
    final Collection<Piece> playerPieces = player.getActivePieces();
    final Collection<Move> opponentMoves = player.getOpponent().getLegalMoves();

    final int[] attackerCounts = new int[BoardUtils.NUM_TILES];
    for (final Move move : opponentMoves) {
      attackerCounts[move.getDestinationCoordinate()]++;
    }

    for (final Piece piece : playerPieces) {
      if (piece.getPieceType() == Piece.PieceType.KING) continue;

      final int position = piece.getPiecePosition();
      final int attackerCount = attackerCounts[position];
      final int[] defenderValues = sortedDefenderValues(playerPieces, position, board);
      final int defenderCount = defenderValues.length;

      if (attackerCount > 0) {
        if (defenderCount == 0) {
          switch (piece.getPieceType()) {
            case QUEEN -> safetyScore -= 800;
            case ROOK -> safetyScore -= 450;
            case BISHOP, KNIGHT -> safetyScore -= 280;
            case PAWN -> safetyScore -= 90;
          }
        } else if (attackerCount > defenderCount) {
          final int[] attackerValues = sortedMovedPieceValues(opponentMoves, position, attackerCount);
          int materialLoss = calculateSimpleExchange(piece, attackerValues, defenderValues);
          safetyScore -= materialLoss * 0.7;
        }
      }

      if (defenderCount > 0) {
        switch (piece.getPieceType()) {
          case QUEEN -> safetyScore += Math.min(defenderCount * 15, 45);
          case ROOK -> safetyScore += Math.min(defenderCount * 10, 30);
          case BISHOP, KNIGHT -> safetyScore += Math.min(defenderCount * 5, 15);
        }
      }
    }

    return safetyScore;
  }

  /**
   * Collects the moved piece values of every move that targets a square, in ascending order.
   *
   * @param moves The moves to scan.
   * @param position The targeted square.
   * @param count The number of moves in the collection whose destination is the targeted square.
   * @return The moved piece values in ascending order.
   */
  private int[] sortedMovedPieceValues(final Collection<Move> moves, final int position, final int count) {
    final int[] values = new int[count];
    int index = 0;

    for (final Move move : moves) {
      if (move.getDestinationCoordinate() == position) {
        values[index++] = move.getMovedPiece().getPieceValue();
      }
    }

    Arrays.sort(values);

    return values;
  }

  /**
   * Collects the values of the pieces in the given collection that defend the given square, in
   * ascending order. The piece standing on the square is not included, and a piece whose line to
   * the square is blocked by another piece is not included.
   *
   * @param playerPieces The pieces to test.
   * @param square The square to test.
   * @param board The current chess board state.
   * @return The values of the defending pieces, in ascending order.
   */
  private int[] sortedDefenderValues(final Collection<Piece> playerPieces,
                                     final int square,
                                     final Board board) {
    final int[] values = new int[playerPieces.size()];
    int index = 0;

    for (final Piece piece : playerPieces) {
      if (piece.getPiecePosition() != square && piece.defendsSquare(square, board)) {
        values[index++] = piece.getPieceValue();
      }
    }

    final int[] defenderValues = Arrays.copyOf(values, index);
    Arrays.sort(defenderValues);

    return defenderValues;
  }

  /**
   * Calculates the approximate material outcome of an exchange sequence.
   * Uses simplified logic to estimate the result of a capture sequence.
   *
   * @param piece The piece being attacked.
   * @param attackerValues The values of the pieces that can capture, in ascending order.
   * @param defenderValues The values of the pieces that can defend, in ascending order.
   * @return The estimated material loss for the defending side.
   */
  private int calculateSimpleExchange(final Piece piece, final int[] attackerValues, final int[] defenderValues) {
    int materialBalance = 0;
    int targetValue = piece.getPieceValue();
    boolean attackerTurn = true;

    materialBalance += targetValue;

    int attackerIndex = 0;
    int defenderIndex = 0;

    while ((attackerTurn && defenderIndex < defenderValues.length) ||
            (!attackerTurn && attackerIndex < attackerValues.length)) {

      if (attackerTurn) {
        if (attackerIndex < attackerValues.length) {
          materialBalance -= attackerValues[attackerIndex];
          attackerIndex++;
        }
        defenderIndex++;
      } else {
        if (defenderIndex < defenderValues.length) {
          materialBalance += defenderValues[defenderIndex];
          defenderIndex++;
        }
        attackerIndex++;
      }

      attackerTurn = !attackerTurn;

      if (attackerTurn && materialBalance <= 0) break;
      if (!attackerTurn && materialBalance >= 0) break;
    }

    return Math.max(0, materialBalance);
  }

  /**
   * Checks if a piece is defended by another piece of the same alliance.
   * Used to determine if attacks target undefended pieces.
   *
   * @param piece The piece to check for defense.
   * @param owner The player who owns the piece.
   * @param board The current chess board state.
   * @return True if the piece is defended by a friendly piece.
   */
  private boolean isPieceDefended(Piece piece, Player owner, Board board) {
    final int position = piece.getPiecePosition();

    for (final Piece defender : owner.getActivePieces()) {
      if (defender.getPiecePosition() != position && defender.defendsSquare(position, board)) {
        return true;
      }
    }

    return false;
  }

  /**
   * Counts the number of developed minor pieces for the given alliance.
   * A piece is considered developed if it has moved from its starting rank.
   *
   * @param pieces The collection of pieces to count.
   * @param alliance The alliance of the pieces being counted.
   * @return The number of developed minor pieces.
   */
  private int countDevelopedMinorPieces(Collection<Piece> pieces, Alliance alliance) {
    int count = 0;

    for (Piece piece : pieces) {
      if ((piece.getPieceType() == Piece.PieceType.KNIGHT ||
              piece.getPieceType() == Piece.PieceType.BISHOP) &&
              !piece.isFirstMove()) {

        int rank = piece.getPiecePosition() / 8;
        if ((alliance.isWhite() && rank != 7) ||
                (!alliance.isWhite() && rank != 0)) {
          count++;
        }
      }
    }

    return count;
  }

  /**
   * Evaluates initiative factors including space advantage and piece activity.
   * Used for deeper search depths to consider longer-term initiative.
   *
   * @param player The player whose initiative is being evaluated.
   * @param board The current chess board state.
   * @return The initiative evaluation score.
   */
  private double evaluateInitiative(Player player, Board board) {
    double score = 0;

    score += evaluateSpaceAdvantage(player, board);
    score += evaluatePieceActivity(player, board);

    return score;
  }

  /**
   * Evaluates space advantage by counting moves into the opponent's territory.
   * Space control contributes to initiative and attacking potential.
   *
   * @param player The player whose space advantage is being evaluated.
   * @param board The current chess board state.
   * @return The space advantage evaluation score.
   */
  private double evaluateSpaceAdvantage(Player player, Board board) {
    double score = 0;
    Alliance alliance = player.getAlliance();
    Collection<Move> moves = player.getLegalMoves();

    int advancedMoves = 0;
    for (Move move : moves) {
      int destRank = move.getDestinationCoordinate() / 8;

      if ((alliance.isWhite() && destRank < 4) ||
              (!alliance.isWhite() && destRank > 3)) {
        advancedMoves++;
      }
    }

    score += Math.min(advancedMoves, 10) * 2;

    return score;
  }

  /**
   * Evaluates piece activity by counting legal moves for non-pawn pieces.
   * Active pieces contribute to initiative and tactical opportunities.
   *
   * @param player The player whose piece activity is being evaluated.
   * @param board The current chess board state.
   * @return The piece activity evaluation score.
   */
  private double evaluatePieceActivity(Player player, Board board) {
    double score = 0;
    Collection<Piece> pieces = player.getActivePieces();

    for (Piece piece : pieces) {
      if (piece.getPieceType() == Piece.PieceType.KING ||
              piece.getPieceType() == Piece.PieceType.PAWN) {
        continue;
      }

      Collection<Move> pieceMoves = piece.calculateLegalMoves(board);

      switch (piece.getPieceType()) {
        case KNIGHT:
          score += pieceMoves.size() * 2;
          break;
        case BISHOP:
          score += pieceMoves.size() * 1.5;
          break;
        case ROOK:
          score += pieceMoves.size();
          break;
        case QUEEN:
          score += pieceMoves.size() * 0.5;
          break;
      }
    }

    return score;
  }

  /**
   * Returns a list of pawn pieces belonging to the specified player.
   * Utility method for pawn structure evaluation functions.
   *
   * @param player The player whose pawns are to be retrieved.
   * @return A list of pawn pieces belonging to the player.
   */
  private List<Piece> getPawns(final Player player) {
    return player.getActivePieces().stream()
            .filter(piece -> piece.getPieceType() == Piece.PieceType.PAWN)
            .collect(Collectors.toList());
  }
}