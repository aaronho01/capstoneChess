package engine.forTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * The MatchStatistics record measures what a match came to. It holds the games one side won, lost,
 * and drew, and turns them into a score rate, an Elo difference, a confidence interval, and the
 * likelihood that the side is the stronger of the two.
 * <p>
 * Every figure is written from the point of view of the side the counts belong to, so a positive
 * Elo difference means that side is ahead.
 * <p>
 * The interval is a normal approximation taken on the score rate and then written as Elo through
 * the same logistic the point estimate uses. Its standard error is taken from the wins and draws
 * the match actually held rather than from an assumed draw rate. An endpoint that reaches a score
 * rate of zero or one is unbounded and is reported as such.
 * <p>
 * Each game is counted on its own, which leaves out the pairing of the openings a match is played
 * from. The two games of a pair are correlated, so an interval taken this way is wider than one
 * taken over pair results.
 * <p>
 * A match whose games all ended the same way has a standard error of zero and no interval can be
 * taken from it. A match of nothing but wins or nothing but losses is instead reported with a one
 * sided bound taken from the binomial, and a match of nothing but draws is reported with no
 * interval at all.
 *
 * @author Aaron Ho
 */
public record MatchStatistics(int wins, int losses, int draws) {

  /** The standard normal deviate the two sided interval is taken at. */
  private static final double CONFIDENCE_DEVIATE = 1.959964;

  /** The confidence the interval and the one sided bounds are stated at. */
  private static final double CONFIDENCE = 0.95;

  /** The scale of the logistic an Elo difference is written on. */
  private static final double ELO_SCALE = 400.0;

  /** The coefficient of the argument in the error function approximation. */
  private static final double ERF_ARGUMENT = 0.3275911;

  /** The coefficients of the polynomial in the error function approximation, in order. */
  private static final double[] ERF_TERMS = {0.254829592, -0.284496736, 1.421413741, -1.453152027,
          1.061405429};

  /** The text an Elo figure is written as when it is unbounded. */
  private static final String UNBOUNDED = "unbounded";

  /**
   * Constructs the statistics of a match.
   *
   * @throws IllegalArgumentException If any count is negative or the match holds no games.
   */
  public MatchStatistics {
    if (wins < 0 || losses < 0 || draws < 0) {
      throw new IllegalArgumentException("A match cannot hold a negative number of games");
    }
    if (wins + losses + draws < 1) {
      throw new IllegalArgumentException("A match must hold at least one game");
    }
  }

  /**
   * The number of games the match held.
   *
   * @return The number of games.
   */
  public int games() {
    return wins + losses + draws;
  }

  /**
   * The points the side scored, counting a win as one and a draw as a half.
   *
   * @return The points scored.
   */
  public double points() {
    return wins + draws / 2.0;
  }

  /**
   * The share of the points on offer that the side scored.
   *
   * @return The score rate, between zero and one.
   */
  public double scoreRate() {
    return points() / games();
  }

  /**
   * The share of the games that were drawn.
   *
   * @return The draw rate, between zero and one.
   */
  public double drawRate() {
    return (double) draws / games();
  }

  /**
   * The standard error of the score rate, taken from the wins and draws the match held.
   *
   * @return The standard error, which is zero when every game ended the same way.
   */
  public double standardError() {
    final double rate = scoreRate();
    final double meanSquare = (wins + draws * 0.25) / games();
    final double variance = Math.max(0.0, meanSquare - rate * rate);
    return Math.sqrt(variance / games());
  }

  /**
   * The Elo difference the score rate measures.
   *
   * @return The Elo difference, positive infinity if every game was won and negative infinity if
   *         every game was lost.
   */
  public double elo() {
    return eloOf(scoreRate());
  }

  /**
   * The lower end of the confidence interval on the Elo difference. A match of nothing but wins
   * has no interval, so the one sided binomial bound is returned in its place.
   *
   * @return The lower bound, or negative infinity if it is unbounded.
   */
  public double eloLowerBound() {
    if (standardError() == 0.0) {
      return wins == games() ? eloOf(oneSidedRate()) : Double.NEGATIVE_INFINITY;
    }
    return eloOf(scoreRate() - CONFIDENCE_DEVIATE * standardError());
  }

  /**
   * The upper end of the confidence interval on the Elo difference. A match of nothing but losses
   * has no interval, so the one sided binomial bound is returned in its place.
   *
   * @return The upper bound, or positive infinity if it is unbounded.
   */
  public double eloUpperBound() {
    if (standardError() == 0.0) {
      return losses == games() ? eloOf(1.0 - oneSidedRate()) : Double.POSITIVE_INFINITY;
    }
    return eloOf(scoreRate() + CONFIDENCE_DEVIATE * standardError());
  }

  /**
   * The likelihood that the side is the stronger of the two, taken from the decisive games alone.
   *
   * @return The likelihood, between zero and one, which is one half when no game was decisive.
   */
  public double likelihoodOfSuperiority() {
    final int decisive = wins + losses;
    if (decisive == 0) {
      return 0.5;
    }
    return 0.5 * (1.0 + erf((wins - losses) / Math.sqrt(2.0 * decisive)));
  }

  /**
   * Writes the statistics as lines of text, naming the side the figures belong to.
   *
   * @param name The name the side is reported under.
   * @return The lines to report, in order.
   */
  public List<String> report(final String name) {
    final List<String> lines = new ArrayList<>();
    final int percent = (int) Math.round(CONFIDENCE * 100.0);
    if (standardError() == 0.0 && draws == games()) {
      lines.add("Elo for " + name + ": " + eloText(elo()) + ", every game was drawn, so no " +
              "interval can be taken");
    } else if (standardError() == 0.0 && wins == games()) {
      lines.add("Elo for " + name + ": above " + eloText(eloLowerBound()) + " at " + percent +
              " percent confidence, every game was won");
    } else if (standardError() == 0.0) {
      lines.add("Elo for " + name + ": below " + eloText(eloUpperBound()) + " at " + percent +
              " percent confidence, every game was lost");
    } else {
      lines.add("Elo for " + name + ": " + eloText(elo()) + ", " + percent + " percent interval " +
              eloText(eloLowerBound()) + " to " + eloText(eloUpperBound()));
    }
    lines.add(String.format("Draw rate: %.1f percent, likelihood %s is the stronger engine: " +
            "%.1f percent", 100.0 * drawRate(), name, 100.0 * likelihoodOfSuperiority()));
    return lines;
  }

  /**
   * Writes an Elo figure with its sign. A figure of negative zero is written as a positive zero.
   *
   * @param elo The figure to write.
   * @return The figure, or a word naming it as unbounded if it is infinite.
   */
  private static String eloText(final double elo) {
    if (Double.isInfinite(elo)) {
      return UNBOUNDED;
    }
    return String.format("%+.1f", elo == 0.0 ? 0.0 : elo);
  }

  /**
   * The Elo difference a score rate measures.
   *
   * @param rate The score rate, between zero and one.
   * @return The Elo difference, infinite at either end of the range.
   */
  private static double eloOf(final double rate) {
    if (rate <= 0.0) {
      return Double.NEGATIVE_INFINITY;
    }
    if (rate >= 1.0) {
      return Double.POSITIVE_INFINITY;
    }
    return -ELO_SCALE * Math.log10(1.0 / rate - 1.0);
  }

  /**
   * The score rate a one sided bound is taken at when every game ended the same way.
   *
   * @return The score rate the confidence and the number of games give.
   */
  private double oneSidedRate() {
    return Math.pow(1.0 - CONFIDENCE, 1.0 / games());
  }

  /**
   * The error function, to an accuracy of about one part in ten million.
   *
   * @param value The argument of the function.
   * @return The value of the function at that argument.
   */
  private static double erf(final double value) {
    final double magnitude = Math.abs(value);
    final double step = 1.0 / (1.0 + ERF_ARGUMENT * magnitude);
    double polynomial = 0.0;
    for (int term = ERF_TERMS.length - 1; term >= 0; term--) {
      polynomial = (polynomial + ERF_TERMS[term]) * step;
    }
    final double result = 1.0 - polynomial * Math.exp(-magnitude * magnitude);
    return value < 0.0 ? -result : result;
  }
}