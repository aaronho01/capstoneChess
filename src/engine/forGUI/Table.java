package engine.forGUI;

import com.google.common.collect.Lists;
import engine.forBoard.*;
import engine.forPiece.Piece;
import engine.forPlayer.Player;
import engine.forPlayer.forAI.AlphaBeta;
import org.apache.commons.io.output.ByteArrayOutputStream;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static engine.forBoard.Move.MoveFactory.createMove;
import static engine.forBoard.Move.MoveFactory.getNullMove;
import static javax.swing.JDialog.setDefaultLookAndFeelDecorated;
import static javax.swing.SwingUtilities.*;

/**
 * The Table class represents the main user interface for the Capstone chess engine.
 * It creates a graphical chess board using Swing components and provides various features
 * such as a menu bar, game history panel, debug panel, and AI integration.
 * The class follows the singleton pattern to ensure only one instance exists.
 *
 * @author Aaron Ho
 * @author dareTo81
 */
public final class Table extends Observable {

  /** The panel displaying the game's move history. */
  private final GameHistoryPanel gameHistoryPanel;

  /** The panel for displaying debug information or messages. */
  private final DebugPanel debugPanel;

  /** The panel representing the chess board and its tiles. */
  private final BoardPanel boardPanel;

  /** The log of moves made in the game. */
  public static MoveLog moveLog;

  /** The setup configuration for the current game. */
  private final GameSetup gameSetup;

  /** The current state of the chess board. */
  private Board chessBoard;

  /** The move made by the computer. */
  private Move computerMove;

  /** The source tile of the piece being moved. */
  private Piece sourceTile;

  /** The piece moved by the human player. */
  private Piece humanMovedPiece;

  /** The direction of the chess board (normal or flipped). */
  private BoardDirection boardDirection;

  /** The file path for the icons representing chess pieces. */
  private final String pieceIconPath;

  /** Indicates whether legal moves should be highlighted on the board. */
  private boolean highlightLegalMoves;

  /** The color of light tiles on the chess board. */
  private final Color lightTileColor = Color.decode("#FFFACD");

  /** The color of dark tiles on the chess board. */
  private final Color darkTileColor = Color.decode("#593E1A");

  /** The outer frame dimension of the chessboard. */
  private static final Dimension OUTER_FRAME_DIMENSION = Toolkit.getDefaultToolkit().getScreenSize();

  /** The board panel dimension. */
  private static final Dimension BOARD_PANEL_DIMENSION = new Dimension(620, 620);

  /** The tile panel dimension. */
  private static final Dimension TILE_PANEL_DIMENSION = new Dimension(40, 40);

  /**
   * Cache of rendered piece icons, keyed by SVG file path. A given piece renders identically
   * every time at a fixed tile size, so the Batik transcode is done once per distinct piece
   * rather than once per tile per redraw. Only ever touched from the EDT, so a plain HashMap
   * is sufficient.
   */
  private static final Map<String, ImageIcon> PIECE_ICON_CACHE = new HashMap<>();

  /** The green dot overlay used to mark legal move destinations, loaded once on first use. */
  private static ImageIcon greenDotIcon;

  /** The single instance of the Table class (singleton). */
  private static final Table Instance = new Table();

  /**
   * Constructs an instance of the Table class, creating the main graphical user interface.
   * Initializes and configures various components including the game frame, menu bar,
   * chess board, game history panel, debug panel, and game setup options.
   */
  private Table() {
    JFrame gameFrame = new JFrame("Capstone Chess");
    final JMenuBar tableMenuBar = new JMenuBar();
    populateMenuBar(tableMenuBar);
    gameFrame.setJMenuBar(tableMenuBar);
    gameFrame.setLayout(new BorderLayout());
    this.chessBoard = Board.createStandardBoard();
    this.boardDirection = BoardDirection.NORMAL;
    this.highlightLegalMoves = false;
    this.pieceIconPath = "art/simple/";
    this.gameHistoryPanel = new GameHistoryPanel();
    this.debugPanel = new DebugPanel();
    this.boardPanel = new BoardPanel();
    moveLog = new MoveLog();
    this.addObserver(new TableGameAIWatcher());
    this.gameSetup = new GameSetup(gameFrame, true);
    JPanel centeringPanel = new JPanel(new GridBagLayout());
    centeringPanel.add(this.boardPanel);
    gameFrame.add(centeringPanel, BorderLayout.CENTER);
    gameFrame.add(this.gameHistoryPanel, BorderLayout.EAST);
    gameFrame.add(debugPanel, BorderLayout.SOUTH);
    setDefaultLookAndFeelDecorated(true);
    gameFrame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
    gameFrame.setExtendedState(JFrame.MAXIMIZED_BOTH);
    gameFrame.setVisible(true);
  }

  /**
   * Retrieves the singleton instance of the Table class.
   *
   * @return The singleton instance of the Table class.
   */
  public static Table get() {
    return Instance;
  }

  /**
   * Retrieves the Board object representing the current state of the chess board.
   *
   * @return The Board object representing the chess board.
   */
  private Board getGameBoard() {
    return this.chessBoard;
  }

  /**
   * Retrieves the MoveLog object for tracking the move history.
   *
   * @return The MoveLog object for tracking the move history.
   */
  private MoveLog getMoveLog() {
    return moveLog;
  }

  /**
   * Retrieves the BoardPanel object for rendering the chess board.
   *
   * @return The BoardPanel object for rendering the chess board.
   */
  private BoardPanel getBoardPanel() {
    return this.boardPanel;
  }

  /**
   * Retrieves the GameHistoryPanel object for displaying move history.
   *
   * @return The GameHistoryPanel object for displaying move history.
   */
  private GameHistoryPanel getGameHistoryPanel() {
    return this.gameHistoryPanel;
  }

  /**
   * Retrieves the DebugPanel object for displaying debugging information.
   *
   * @return The DebugPanel object for displaying debugging information.
   */
  private DebugPanel getDebugPanel() {
    return this.debugPanel;
  }

  /**
   * Retrieves the GameSetup object for configuring game parameters.
   *
   * @return The GameSetup object for configuring game settings.
   */
  private GameSetup getGameSetup() {
    return this.gameSetup;
  }

  /**
   * Retrieves the current status of legal move highlighting on the chess board.
   *
   * @return True if legal move highlighting is enabled, false otherwise.
   */
  private boolean getHighlightLegalMoves() {
    return this.highlightLegalMoves;
  }

  /**
   * Refreshes and updates the graphical user interface to reflect the current game state.
   * Updates the game history panel, redraws the chess board, and updates the debug panel.
   */
  public void show() {
    if (moveLog.size() == 0) {
      Table.get().getGameHistoryPanel().redo(chessBoard, Table.get().getMoveLog());
      Table.get().getBoardPanel().drawBoard(Table.get().getGameBoard());
      Table.get().getDebugPanel().redo();
      setChanged();
      notifyObservers();
    }
  }

  /**
   * Populates the menu bar with Preferences and Options menus.
   *
   * @param tableMenuBar The JMenuBar to populate.
   */
  private void populateMenuBar(final JMenuBar tableMenuBar) {
    tableMenuBar.add(createPreferencesMenu());
    tableMenuBar.add(createOptionsMenu());
  }

  /**
   * Centers the provided JFrame on the screen.
   *
   * @param frame The JFrame to be centered.
   */
  private static void center(final JFrame frame) {
    final Dimension dim = Toolkit.getDefaultToolkit().getScreenSize();
    final int w = Toolkit.getDefaultToolkit().getScreenSize().width;
    final int h = Toolkit.getDefaultToolkit().getScreenSize().height;
    final int x = (dim.width - w) / 2;
    final int y = (dim.height - h) / 2;
    frame.setLocation(x, y);
    frame.pack();
  }

  /**
   * Returns the green dot overlay used to highlight legal move destinations, reading it from
   * disk on the first call and reusing that instance afterward.
   *
   * @return The green dot icon.
   * @throws IOException If the image cannot be read.
   */
  private static ImageIcon greenDot() throws IOException {
    if (greenDotIcon == null) {
      greenDotIcon = new ImageIcon(ImageIO.read(new File("art/misc/green_dot.png")));
    }
    return greenDotIcon;
  }

  /**
   * Creates a JMenu with options to interact with the game.
   * Includes starting a new game, evaluating the board, inspecting game state,
   * undoing moves, and setting up game parameters.
   *
   * @return The JMenu containing gameplay options.
   */
  private JMenu createOptionsMenu() {

    final JMenu optionsMenu = new JMenu("Options");
    optionsMenu.setMnemonic(KeyEvent.VK_O);

    final JMenuItem resetMenuItem = new JMenuItem("New Game", KeyEvent.VK_P);
    resetMenuItem.addActionListener(e -> undoAllMoves());
    optionsMenu.add(resetMenuItem);

    final JMenuItem legalMovesMenuItem = new JMenuItem("Current State", KeyEvent.VK_L);
    legalMovesMenuItem.addActionListener(e -> {
      System.out.println(chessBoard.getWhitePieces());
      System.out.println(chessBoard.getBlackPieces());
      System.out.println(playerInfo(chessBoard.currentPlayer()));
      System.out.println(playerInfo(chessBoard.currentPlayer().getOpponent()));
    }); optionsMenu.add(legalMovesMenuItem);

    final JMenuItem showBoardValueItem = new JMenuItem("Show board value", KeyEvent.VK_1);
    showBoardValueItem.addActionListener(e -> {
      int boardValue = -20000;
      for (final Piece piece : chessBoard.getWhitePieces()) {
        boardValue += piece.getPieceValue();
      } for (final Piece piece : chessBoard.getBlackPieces()) {
        boardValue += piece.getPieceValue();
      } debugPanel.addText(Integer.toString(boardValue)); debugPanel.redo();
    }); optionsMenu.add(showBoardValueItem);

    final JMenuItem undoMoveMenuItem = new JMenuItem("Undo last move", KeyEvent.VK_M);
    undoMoveMenuItem.addActionListener(e -> {
      if (Table.get().getMoveLog().size() > 0) {
        undoLastMove();
      }
    }); optionsMenu.add(undoMoveMenuItem);

    final JMenuItem setupGameMenuItem = new JMenuItem("Setup Game", KeyEvent.VK_S);
    setupGameMenuItem.addActionListener(e -> {
      Table.get().getGameSetup().promptUser();
      Table.get().setupUpdate(Table.get().getGameSetup());
    }); optionsMenu.add(setupGameMenuItem);

    return optionsMenu;
  }

  /**
   * Creates a JMenu with options for customizing user preferences and settings.
   * Users can choose tile colors, chess piece images, highlight legal moves,
   * and toggle book moves usage.
   *
   * @return The JMenu containing user preferences and settings.
   */
  private JMenu createPreferencesMenu() {
    final JMenu preferencesMenu = new JMenu("Preferences");
    final JMenuItem flipBoardMenuItem = new JMenuItem("Flip board");

    flipBoardMenuItem.addActionListener(e -> {
      boardDirection = boardDirection.opposite();
      boardPanel.drawBoard(chessBoard);
    });

    preferencesMenu.add(flipBoardMenuItem);
    preferencesMenu.addSeparator();
    final JCheckBoxMenuItem cbLegalMoveHighlighter = new JCheckBoxMenuItem("Highlight Legal Moves", false);
    cbLegalMoveHighlighter.addActionListener(e -> highlightLegalMoves = cbLegalMoveHighlighter.isSelected());
    preferencesMenu.add(cbLegalMoveHighlighter);
    return preferencesMenu;
  }

  /**
   * Constructs a string containing details about the given player's alliance, legal moves,
   * check status, checkmate status, and castling status.
   *
   * @param player The player for which to generate the information.
   * @return A string representation of the player's status and legal moves.
   */
  private static String playerInfo(final Player player) {
    return ("Player is: " + player.getAlliance() +
            "\nlegal moves (" + player.getLegalMoves().size() + ") = " + player.getLegalMoves() +
            "\ninCheck = " + player.isInCheck() +
            "\nisInCheckMate = " + player.isInCheckMate() +
            "\nisCastled = " + player.isCastled()) +
            "\n";
  }

  /**
   * Updates the internal state of the chess game's board to match the provided board.
   *
   * @param board The new board configuration to be applied to the game.
   */
  private void updateGameBoard(final Board board) {
    this.chessBoard = board;
  }

  /**
   * Updates the internal state of the computer's chosen move.
   *
   * @param move The move chosen by the computer player.
   */
  private void updateComputerMove(final Move move) {
    this.computerMove = move;
  }

  /**
   * Reverses all moves that have been made in the current game session.
   * Effectively resets the game to its initial state.
   */
  private void undoAllMoves() {
    updateGameBoard(Board.createStandardBoard());
    this.computerMove = null;
    this.sourceTile = null;
    this.humanMovedPiece = null;
    Table.get().getMoveLog().clear();
    Table.get().getGameHistoryPanel().redo(chessBoard, Table.get().getMoveLog());
    Table.get().getBoardPanel().drawBoard(chessBoard);
    Table.get().getDebugPanel().redo();
  }

  /**
   * Removes the last move from the move log and updates the game board state.
   * Clears the computer move and refreshes UI components.
   */
  private void undoLastMove() {
    Table.get().getMoveLog().removeMove(Table.get().getMoveLog().size() - 1);
    this.chessBoard.unmakeMove();
    this.computerMove = null;
    Table.get().getGameHistoryPanel().redo(chessBoard, Table.get().getMoveLog());
    Table.get().getBoardPanel().drawBoard(chessBoard);
    Table.get().getDebugPanel().redo();
  }

  /**
   * Sets the state as changed and notifies registered observers with the specified player type.
   *
   * @param playerType The type of player (Human or Computer) who made the move.
   */
  private void moveMadeUpdate(final PlayerType playerType) {
    setChanged();
    notifyObservers(playerType);
  }

  /**
   * Sets the state as changed and notifies registered observers with the game setup configuration.
   *
   * @param gameSetup The updated game setup configuration.
   */
  private void setupUpdate(final GameSetup gameSetup) {
    setChanged();
    notifyObservers(gameSetup);
  }

  /**
   * The TableGameAIWatcher class observes changes in the game state and triggers AI actions
   * or displays game-over messages based on certain conditions.
   */
  private static class TableGameAIWatcher implements Observer {

    /**
     * Responds to updates from the observed object, triggering AI actions or
     * displaying game-over messages based on the current game state.
     *
     * @param o   The observable object.
     * @param arg An argument passed by the observed object.
     */
    @Override
    public void update(final Observable o,
                       final Object arg) {
      final Player currentPlayer = Table.get().getGameBoard().currentPlayer();

      // Checkmate and stalemate are resolved here, before the worker is started, and then read
      // from these locals. Both answers are reached by applying moves to the game board and
      // reversing them, so asking for either one after the worker has been handed that same board
      // would have this thread mutating a board the worker is reading.
      final boolean isInCheckMate = currentPlayer.isInCheckMate();
      final boolean isInStaleMate = currentPlayer.isInStaleMate();

      if (isInCheckMate) {
        JOptionPane.showMessageDialog(Table.get().getBoardPanel(),
                "Game Over: Player " + currentPlayer + " is in checkmate!", "Game Over",
                JOptionPane.INFORMATION_MESSAGE);
      } if (isInStaleMate) {
        JOptionPane.showMessageDialog(Table.get().getBoardPanel(),
                "Game Over: Player " + currentPlayer + " is in stalemate!", "Game Over",
                JOptionPane.INFORMATION_MESSAGE);
      } if (Table.get().getGameSetup().isAIPlayer(currentPlayer) && !isInCheckMate && !isInStaleMate) {
        System.out.println(currentPlayer + " is thinking....");
        final AIThinkTank thinkTank = new AIThinkTank();
        thinkTank.execute();
      }
    }
  }

  /**
   * The PlayerType enumeration represents the types of players in the chess game.
   */
  enum PlayerType {

    /** Represents a human player. */
    HUMAN,

    /** Represents a computer player. */
    COMPUTER
  }

  /**
   * The AIThinkTank class is an asynchronous worker responsible for AI move calculation and execution.
   */
  private static class AIThinkTank extends SwingWorker <Move, String> {

    /** The executor service for managing AI computation threads. */
    private final ExecutorService executorService;

    /** Constructs an instance of AIThinkTank. */
    private AIThinkTank() {
      this.executorService = Executors.newFixedThreadPool(1);
    }

    /**
     * Performs AI move calculation in the background.
     *
     * @return The best move calculated by the AI.
     */
    @Override
    protected Move doInBackground() {
      final Move bestMove;
      final AlphaBeta strategy = new AlphaBeta(Table.get().getGameSetup().getSearchDepth(), Table.get().getGameBoard());
      strategy.addObserver(Table.get().getDebugPanel());
      bestMove = strategy.execute(Table.get().getGameBoard());
      return bestMove;
    }

    /**
     * Handles the completion of the AI move calculation.
     * Updates the game state with the calculated move and refreshes the UI.
     */
    @Override
    public void done() {
      try {
        final Move bestMove = get();
        final Board board = Table.get().getGameBoard();
        final String notation = bestMove.toNotation(board);
        Table.get().updateComputerMove(bestMove);
        board.makeMove(bestMove);
        Table.get().getMoveLog().addMove(bestMove, notation);
        Table.get().getGameHistoryPanel().redo(board, Table.get().getMoveLog());
        Table.get().getBoardPanel().drawBoard(board);
        Table.get().getDebugPanel().redo();
        Table.get().moveMadeUpdate(PlayerType.COMPUTER);
      } catch (final Exception e) {
        System.out.println("Exception in AI move handling!");
        e.printStackTrace();
      } finally {
        executorService.shutdown();
      }
    }
  }

  /**
   * The BoardPanel class represents a graphical panel displaying the chess board and its tiles.
   */
  private class BoardPanel extends JPanel {

    /** List of tile panels representing the individual tiles on the chess board. */
    final List <TilePanel> boardTiles;

    /** Constructs a new BoardPanel and initializes its tile panels. */
    BoardPanel() {
      super(new GridLayout(8, 8));
      setFocusTraversalPolicyProvider(false);
      setFocusable(false);
      this.boardTiles = new ArrayList < > ();
      for (int i = 0; i < BoardUtils.NUM_TILES; i++) {
        final TilePanel tilePanel = new TilePanel(this, i);
        this.boardTiles.add(tilePanel);
        add(tilePanel);
      }

      setPreferredSize(BOARD_PANEL_DIMENSION);
      setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
      setBackground(Color.decode("#8B4726"));
      validate();
    }

    /**
     * Draws the chess board based on the given board state, updating the display.
     *
     * @param board The current state of the chess board.
     */
    void drawBoard(final Board board) {
      removeAll();
      for (final TilePanel boardTile: boardDirection.traverse(boardTiles)) {
        boardTile.drawTile(board);
        add(boardTile);
      } validate();
      repaint();
    }
  }

  /**
   * The BoardDirection enumeration represents the two possible directions of the chess board display.
   */
  enum BoardDirection {

    /** Represents the normal direction of the chess board. */
    NORMAL {
      @Override
      List <TilePanel> traverse(final List <TilePanel> boardTiles) {
        return boardTiles;
      }

      @Override
      BoardDirection opposite() {
        return FLIPPED;
      }
    },

    /** Represents the flipped direction of the chess board. */
    FLIPPED {
      @Override
      List <TilePanel> traverse(final List <TilePanel> boardTiles) {
        return Lists.reverse(boardTiles);
      }

      @Override
      BoardDirection opposite() {
        return NORMAL;
      }
    };

    /**
     * Traverses the list of tile panels according to the current board direction.
     *
     * @param boardTiles The list of tile panels to traverse.
     * @return A list of tile panels in the desired traversal order.
     */
    abstract List <TilePanel> traverse(final List <TilePanel> boardTiles);

    /**
     * Returns the opposite board direction.
     *
     * @return The opposite board direction.
     */
    abstract BoardDirection opposite();
  }

  /**
   * The MoveLog class represents a log of moves made during a chess game, together with the
   * algebraic notation each move was rendered with at the time it was played.
   */
  public static class MoveLog {

    /** A list of moves to be used in the move log. */
    private final List <Move> moves;

    /**
     * The algebraic notation for each logged move, held in the same order as {@link #moves} and
     * captured at the moment the move was played. Notation is frozen here rather than rendered
     * on demand because disambiguation has to be resolved against the position the move was
     * played from, and under the mutable board model that position no longer exists once the
     * game moves on. Every mutator on this class must keep the two lists in step.
     */
    private final List <String> notations;

    /** Constructs a new MoveLog. */
    MoveLog() {
      this.moves = new ArrayList <>();
      this.notations = new ArrayList <>();
    }

    /**
     * Gets the list of moves stored in the MoveLog.
     *
     * @return The list of moves.
     */
    public List <Move> getMoves() {
      return this.moves;
    }

    /**
     * Gets the notation captured for the move at the given index.
     *
     * @param index The index of the move.
     * @return The algebraic notation that move was rendered with when it was played.
     */
    public String getNotation(final int index) {
      return this.notations.get(index);
    }

    /**
     * Adds a move to the MoveLog, along with the notation it was rendered with.
     *
     * @param move The move to be added.
     * @param notation The algebraic notation for the move, rendered against the position it was played from.
     */
    void addMove(final Move move, final String notation) {
      this.moves.add(move);
      this.notations.add(notation);
    }

    /**
     * Gets the number of moves in the MoveLog.
     *
     * @return The number of moves.
     */
    public int size() {
      return this.moves.size();
    }

    /**
     * Clears all moves from the MoveLog.
     */
    void clear() {
      this.moves.clear();
      this.notations.clear();
    }

    /**
     * Removes a move at the specified index from the MoveLog.
     *
     * @param index The index of the move to be removed.
     * @return The removed move.
     */
    Move removeMove(final int index) {
      this.notations.remove(index);
      return this.moves.remove(index);
    }
  }

  /**
   * The TilePanel class represents a graphical panel for an individual tile on the chessboard.
   * Each TilePanel displays a single tile with its associated piece, legal move highlights,
   * and other visual elements.
   */
  private class TilePanel extends JPanel {

    /** The tile number identifier. */
    private final int tileId;

    /**
     * Constructs a TilePanel for a specific tile on the chessboard.
     *
     * @param boardPanel The parent BoardPanel that contains this tile.
     * @param tileId The unique identifier of the tile (0 to 63).
     */
    TilePanel(final BoardPanel boardPanel,
              final int tileId) {
      super(new GridBagLayout());
      this.tileId = tileId;
      setPreferredSize(TILE_PANEL_DIMENSION);
      assignTileColor();
      assignTilePieceIcon(chessBoard);
      highlightTileBorder(chessBoard);
      addMouseListener(new MouseListener() {
        @Override
        public void mouseClicked(final MouseEvent event) {
          if (Table.get().getGameSetup().isAIPlayer(Table.get().getGameBoard().currentPlayer()) ||
                  BoardUtils.isEndOfGame(Table.get().getGameBoard())) {
            return;
          } if (isRightMouseButton(event)) {
            sourceTile = null;
            humanMovedPiece = null;
          } else if (isLeftMouseButton(event)) {
            if (sourceTile == null) {
              sourceTile = chessBoard.getPiece(tileId);
              humanMovedPiece = sourceTile;
            } else {
              final Move move = createMove(chessBoard, sourceTile.getPiecePosition(), tileId);
              if (move != getNullMove() &&
                  move.getMovedPiece().getPieceAllegiance() == chessBoard.currentPlayer().getAlliance()) {
                final String notation = move.toNotation(chessBoard);
                chessBoard.makeMove(move);
                if (chessBoard.currentPlayer().getOpponent().isInCheck()) {
                  chessBoard.unmakeMove();
                } else {
                  moveLog.addMove(move, notation);
                }
              } sourceTile = null;
              humanMovedPiece = null;
            }
          } invokeLater(() -> {
            gameHistoryPanel.redo(chessBoard, moveLog);
            Table.get().moveMadeUpdate(PlayerType.HUMAN);
            boardPanel.drawBoard(chessBoard);
            debugPanel.redo();
          });
        }

        @Override
        public void mouseExited(final MouseEvent e) {}

        @Override
        public void mouseEntered(final MouseEvent e) {}

        @Override
        public void mouseReleased(final MouseEvent e) {}

        @Override
        public void mousePressed(final MouseEvent e) {}
      });
      validate();
    }

    /**
     * Draws the appearance of the tile based on the current state of the chessboard.
     *
     * @param board The current chessboard.
     */
    void drawTile(final Board board) {
      SwingUtilities.invokeLater(() -> {
        assignTileColor();
        assignTilePieceIcon(board);
        highlightTileBorder(board);
        highlightLegals(board);
        highlightAIMove();
        validate();
        repaint();
      });
    }

    /**
     * Highlights the border of the tile based on the current state of the game.
     *
     * @param board The current state of the chessboard.
     */
    private void highlightTileBorder(final Board board) {
      if (humanMovedPiece != null &&
              humanMovedPiece.getPieceAllegiance() == board.currentPlayer().getAlliance() &&
              humanMovedPiece.getPiecePosition() == this.tileId) {
        setBorder(BorderFactory.createLineBorder(Color.cyan));
      } else {
        setBorder(BorderFactory.createLineBorder(Color.GRAY));
      }
    }

    /**
     * Highlights the background of the tile if a computer move is available.
     */
    private void highlightAIMove() {
      if (computerMove != null) {
        if (this.tileId == computerMove.getCurrentCoordinate()) {
          setBackground(Color.pink);
        } else if (this.tileId == computerMove.getDestinationCoordinate()) {
          setBackground(Color.red);
        }
      }
    }

    /**
     * Highlights legal move destinations on the tile panel.
     *
     * @param board The current state of the chess board.
     */
    private void highlightLegals(final Board board) {
      if (!Table.get().getHighlightLegalMoves()) {
        return;
      }
      for (final Move move : pieceLegalMoves(board)) {
        if (move.getDestinationCoordinate() == this.tileId) {
          try {
            add(new JLabel(greenDot()));
          } catch (final IOException e) {
            System.out.println("Exception in highlightLegals in Table.java");
          }
        }
      }
    }

    /**
     * Retrieves the collection of legal moves for the currently selected piece.
     *
     * @param board The current state of the chess board.
     * @return A collection of legal moves for the selected piece.
     */
    private Collection < Move > pieceLegalMoves(final Board board) {
      if (humanMovedPiece != null && humanMovedPiece.getPieceAllegiance() == board.currentPlayer().getAlliance()) {
        return humanMovedPiece.calculateLegalMoves(board);
      } return Collections.emptyList();
    }

    /**
     * Assigns the appropriate piece icon to the tile based on the current board state, reusing
     * a previously rendered icon for that piece when one is available.
     *
     * @param board The current state of the chess board.
     */
    private void assignTilePieceIcon(final Board board) {
      this.removeAll();
      final Piece piece = board.getPiece(this.tileId);
      if (piece == null) {
        return;
      }
      final String svgFilePath = pieceIconPath +
          piece.getPieceAllegiance().toString().charAt(0) +
          piece +
          ".svg";
      final ImageIcon cached = PIECE_ICON_CACHE.get(svgFilePath);
      if (cached != null) {
        add(new JLabel(cached));
        return;
      }
      try {
        final BufferedImage image = renderSvgToImage(new File(svgFilePath), TILE_PANEL_DIMENSION.width);
        final ImageIcon icon = new ImageIcon(image);
        PIECE_ICON_CACHE.put(svgFilePath, icon);
        add(new JLabel(icon));
      } catch (final Exception e) {
        System.out.println("Exception in assignTilePieceIcon in Table.java: " + e.getMessage());
        e.printStackTrace();
      }
    }

    /**
     * Renders an SVG file to a BufferedImage for display.
     *
     * @param svgFile The SVG file to render.
     * @param size The desired size of the rendered image.
     * @return A BufferedImage containing the rendered SVG.
     * @throws Exception If rendering fails.
     */
    private BufferedImage renderSvgToImage(File svgFile, int size) throws Exception {
      org.apache.batik.transcoder.image.PNGTranscoder transcoder =
              new org.apache.batik.transcoder.image.PNGTranscoder();

      org.apache.batik.transcoder.TranscoderInput input =
              new org.apache.batik.transcoder.TranscoderInput(svgFile.toURI().toString());

      BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      org.apache.batik.transcoder.TranscoderOutput output =
              new org.apache.batik.transcoder.TranscoderOutput(outputStream);

      transcoder.addTranscodingHint(org.apache.batik.transcoder.SVGAbstractTranscoder.KEY_WIDTH,
              (float)size);
      transcoder.addTranscodingHint(org.apache.batik.transcoder.SVGAbstractTranscoder.KEY_HEIGHT,
              (float)size);

      transcoder.transcode(input, output);

      ByteArrayInputStream bis = new ByteArrayInputStream(outputStream.toByteArray());
      return ImageIO.read(bis);
    }

    /**
     * Assigns the appropriate background color to the tile based on its position.
     */
    private void assignTileColor() {
      if (BoardUtils.Instance.FirstRow.get(this.tileId) ||
              BoardUtils.Instance.ThirdRow.get(this.tileId) ||
              BoardUtils.Instance.FifthRow.get(this.tileId) ||
              BoardUtils.SeventhRow.get(this.tileId)) {
        setBackground(this.tileId % 2 == 0 ? lightTileColor : darkTileColor);
      } else if (BoardUtils.Instance.SecondRow.get(this.tileId) ||
              BoardUtils.Instance.FourthRow.get(this.tileId) ||
              BoardUtils.Instance.SixthRow.get(this.tileId) ||
              BoardUtils.Instance.EighthRow.get(this.tileId)) {
        setBackground(this.tileId % 2 != 0 ? lightTileColor : darkTileColor);
      }
    }
  }
}