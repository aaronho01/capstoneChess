package engine.forBoard;

import engine.Alliance;
import engine.forPiece.Pawn;
import engine.forPiece.Piece;
import engine.forPiece.Rook;

/**
 * UndoState captures everything {@link Board#unmakeMove()} needs to exactly reverse a single
 * call to {@link Board#makeMove(Move)}, without recomputing or re-deriving any of it. Every
 * field is a snapshot taken before the move that produced it was applied, so unmaking a move
 * never needs more than reading this record back onto the board.
 * <p>
 * Restoring a piece never requires re-creating it: this engine's pieces are immutable, and a
 * piece that has moved before is drawn from a shared flyweight pool, so putting the exact
 * object reference captured here back on the board is sufficient to restore its prior square,
 * first-move status, and move count in one step.
 * <p>
 * Every coordinate needed to reverse a move is stored here explicitly rather than read back off
 * the {@link Move} object at unmake time, since moves are pooled and reused elsewhere in this
 * engine; a move's own fields are only guaranteed accurate at the moment {@link Move#makeMove}
 * runs, not later when {@link Move#unmakeMove} is called.
 *
 * @param movedPieceBefore The piece exactly as it was before the move, still at its origin square.
 * @param capturedPiece The piece captured by this move, or null if the move was not a capture.
 *                       Restored to its own stored position, which differs from the destination
 *                       square for an en passant capture.
 * @param castleRookBefore The castling rook exactly as it was before the move, or null if this
 *                          move was not a castle.
 * @param destinationCoordinate The square the primary moved piece ended up on.
 * @param castleRookDestination The square the castling rook ended up on, or -1 if this move was
 *                               not a castle.
 * @param priorEnPassantPawn The en passant pawn on the board immediately before this move.
 * @param priorZobristHash The Zobrist hash of the board immediately before this move.
 * @param priorHalfMoveClock The halfmove clock immediately before this move.
 * @param priorTransitionMove The transition move recorded on the board immediately before this move.
 * @param priorMoveMaker The alliance to move immediately before this move.
 * @param appliedMove The move this undo state belongs to, used to dispatch back to the correct
 *                     {@link Move#unmakeMove} override.
 *
 * @author Aaron Ho
 */
public record UndoState(Piece movedPieceBefore,
                        Piece capturedPiece,
                        Rook castleRookBefore,
                        int destinationCoordinate,
                        int castleRookDestination,
                        Pawn priorEnPassantPawn,
                        long priorZobristHash,
                        int priorHalfMoveClock,
                        Move priorTransitionMove,
                        Alliance priorMoveMaker,
                        Move appliedMove) {
}