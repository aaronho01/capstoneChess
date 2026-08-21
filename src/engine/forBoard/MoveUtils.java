package engine.forBoard;

import java.util.ArrayList;
import java.util.List;

/**
 * The MoveUtils class is a utility class that provides various helper methods related to chess moves and calculations.
 * It serves as a collection of static utilities for move management, evaluation, and handling special move cases
 * such as null moves. The class also provides utilities for managing coordinate lines in move generation.
 * <p>
 * This class follows the singleton pattern with a public Instance field to ensure consistent global access
 * to its functionality throughout the chess engine.
 *
 * @author Aaron Ho
 */
public class MoveUtils {

  /**
   * An instance of the MoveUtils class that can be used to access its methods.
   * This field provides global access to the utility functionality while maintaining singleton behavior.
   */
  public static final MoveUtils Instance = new MoveUtils();

  /**
   * A special move representing a lack of valid move, used to signify an invalid or non-existent move.
   * This constant serves as a null object pattern implementation for moves, allowing code to avoid
   * null checks when dealing with potentially absent moves.
   */
  public static final Move NULL_MOVE = new Move.NullMove();

  /**
   * The Line class represents a sequence of integer coordinates forming a line on the chess board.
   * It is used to manage and store sequences of coordinates for move generation, particularly for
   * sliding pieces like bishops, rooks, and queens that can move along directional lines.
   * <p>
   * This class provides methods for adding coordinates to the line and retrieving the complete line.
   */
  public static class Line {

    /**
     * The list of coordinates that make up this line. Each coordinate represents a square
     * on the chess board that forms part of the continuous line.
     */
    private final List<Integer> coordinates;

    /**
     * Creates a new Line instance with an empty list of coordinates.
     * The line can then be populated with coordinates using the addCoordinate method.
     */
    public Line() {
      this.coordinates = new ArrayList<>();
    }

    /**
     * Adds a coordinate to the line. Coordinates are added in sequence to form a continuous line
     * on the chess board, representing possible move destinations for sliding pieces.
     *
     * @param coordinate The coordinate to be added to the line.
     */
    public void addCoordinate(int coordinate) {
      this.coordinates.add(coordinate);
    }

    /**
     * Retrieves the list of coordinates in the line as an array.
     * This method converts the internal list representation to an array for easier consumption
     * by move generation code.
     *
     * @return The array of coordinates that form this line.
     */
    public int[] getLineCoordinates() {
      int[] result = new int[coordinates.size()];
      for (int i = 0; i < coordinates.size(); i++) {
        result[i] = coordinates.get(i);
      }
      return result;
    }

    /**
     * Checks if the line is empty (contains no coordinates).
     * This is useful for determining if a line has any valid moves or destinations.
     *
     * @return true if the line is empty, false otherwise.
     */
    public boolean isEmpty() {
      return this.coordinates.isEmpty();
    }
  }
}