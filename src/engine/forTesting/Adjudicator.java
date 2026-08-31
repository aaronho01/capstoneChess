package engine.forTesting;

/**
 * The Adjudicator class decides whether the scores the engines report have settled a game before
 * the position itself settles it. One adjudicator holds the state of one game, and is given every
 * reply of that game in the order the moves were played.
 * <p>
 * A win is adjudicated when a run of consecutive plies all report a score of at least the resign
 * score in the same direction. The two engines move in turn, so a run covers both of them, and a
 * win is only adjudicated when the engine awarded the loss agrees that it is losing.
 * <p>
 * A draw is adjudicated when a run of consecutive plies all report a score within the draw score
 * of zero, once the game has reached the ply the draw rule starts at.
 * <p>
 * A score holding a mate is decisive whatever the resign score is, and never counts towards a
 * draw. A reply holding no score at all ends both runs.
 *
 * @author Aaron Ho
 */
public class Adjudicator {

  /** True if the game may be adjudicated at all. */
  private final boolean enabled;

  /** The score a side must be ahead by for a ply to count towards a win. */
  private final int resignScore;

  /** The number of consecutive plies at the resign score that adjudicate a win. */
  private final int resignPlies;

  /** The score a position must be within for a ply to count towards a draw. */
  private final int drawScore;

  /** The number of consecutive plies within the draw score that adjudicate a draw. */
  private final int drawPlies;

  /** The ply the game must have reached before a draw may be adjudicated. */
  private final int drawAfter;

  /** The length of the run of decisive plies ending at the last reply. */
  private int decisivePlies;

  /** The direction of that run, one for White and minus one for Black. */
  private int decisiveSign;

  /** The length of the run of level plies ending at the last reply. */
  private int quietPlies;

  /**
   * Constructs an adjudicator for one game.
   *
   * @param enabled True if the game may be adjudicated at all.
   * @param resignScore The score a side must be ahead by for a ply to count towards a win.
   * @param resignPlies The number of consecutive plies at the resign score that adjudicate a win.
   * @param drawScore The score a position must be within for a ply to count towards a draw.
   * @param drawPlies The number of consecutive plies within the draw score that adjudicate a draw.
   * @param drawAfter The ply the game must have reached before a draw may be adjudicated.
   */
  public Adjudicator(final boolean enabled, final int resignScore, final int resignPlies,
                     final int drawScore, final int drawPlies, final int drawAfter) {
    this.enabled = enabled;
    this.resignScore = resignScore;
    this.resignPlies = resignPlies;
    this.drawScore = drawScore;
    this.drawPlies = drawPlies;
    this.drawAfter = drawAfter;
  }

  /**
   * Judges the game from the reply to the move just played, advancing the runs the rules are
   * decided on. Replies must be given in the order the moves were played, one for every ply after
   * the opening.
   *
   * @param reply What the engine reported for the position it moved from.
   * @param whiteMoved True if White played the move, which is what the score is turned around by.
   * @param plies The number of plies the game holds now, including the moves of the opening.
   * @return What the reply settled, or no verdict if it settled nothing.
   */
  public Verdict judge(final EngineProcess.Reply reply, final boolean whiteMoved, final int plies) {
    if (!this.enabled) {
      return Verdict.NONE;
    }
    if (reply.mateIn() != null) {
      final int sign = signOf(whiteMoved ? reply.mateIn() : -reply.mateIn());
      this.quietPlies = 0;
      return sign == 0 ? clear() : countDecisive(sign);
    }
    if (reply.score() == null) {
      return clear();
    }

    final int score = whiteMoved ? reply.score() : -reply.score();
    if (Math.abs(score) >= this.resignScore) {
      this.quietPlies = 0;
      return countDecisive(signOf(score));
    }
    this.decisivePlies = 0;
    this.decisiveSign = 0;
    if (Math.abs(score) > this.drawScore) {
      this.quietPlies = 0;
      return Verdict.NONE;
    }
    this.quietPlies++;
    return plies >= this.drawAfter && this.quietPlies >= this.drawPlies ?
            Verdict.DRAW : Verdict.NONE;
  }

  /**
   * Adds a decisive ply to the run in the given direction, starting a new run if the direction
   * has changed.
   *
   * @param sign The direction of the ply, one for White and minus one for Black.
   * @return The side the run has awarded the game to, or no verdict if the run is too short.
   */
  private Verdict countDecisive(final int sign) {
    if (sign == this.decisiveSign) {
      this.decisivePlies++;
    } else {
      this.decisiveSign = sign;
      this.decisivePlies = 1;
    }
    if (this.decisivePlies < this.resignPlies) {
      return Verdict.NONE;
    }
    return sign > 0 ? Verdict.WHITE_WINS : Verdict.BLACK_WINS;
  }

  /**
   * Ends both runs.
   *
   * @return No verdict, which is what a ply that ends both runs settles.
   */
  private Verdict clear() {
    this.decisivePlies = 0;
    this.decisiveSign = 0;
    this.quietPlies = 0;
    return Verdict.NONE;
  }

  /**
   * Names the direction of a score.
   *
   * @param score The score to read.
   * @return One for a score above zero, minus one for a score below zero, and zero for a score of
   *         zero.
   */
  private static int signOf(final int score) {
    return Integer.compare(score, 0);
  }

  /**
   * The Verdict enum names what the reported scores have settled, from White's point of view.
   */
  public enum Verdict {

    /** The scores have settled nothing. */
    NONE(null),

    /** The scores have awarded the game to White. */
    WHITE_WINS("adjudicated on score"),

    /** The scores have awarded the game to Black. */
    BLACK_WINS("adjudicated on score"),

    /** The scores have made the game a draw. */
    DRAW("adjudicated as drawn");

    /** Why a game ending on this verdict ended, or null for a verdict that ends no game. */
    private final String reason;

    /**
     * Constructs a verdict.
     *
     * @param reason Why a game ending on this verdict ended, or null for a verdict that ends no
     *               game.
     */
    Verdict(final String reason) {
      this.reason = reason;
    }

    /**
     * Returns why a game ending on this verdict ended.
     *
     * @return The reason, or null for a verdict that ends no game.
     */
    public String getReason() {
      return this.reason;
    }
  }
}