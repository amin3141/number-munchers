package com.pairsys.numbermunchers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.RotateTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.effect.Glow;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.geometry.Bounds;
import javafx.stage.Stage;
import javafx.util.Duration;

public class NumberMunchersApp extends Application {
    private Random random;
    private long seed;
    private final int debugPort = Integer.getInteger("numberMunchers.debug.port", 8765);
    private final String sessionId = System.getenv().getOrDefault("NUMBER_MUNCHERS_SESSION_ID", "unknown");
    private final long startedAt = parseLong(System.getenv("NUMBER_MUNCHERS_STARTED_AT"), System.currentTimeMillis());
    private final String buildVersion = System.getenv().getOrDefault("NUMBER_MUNCHERS_BUILD_VERSION", "unknown");
    private final BoardCell[][] board = new BoardCell[GameConfig.ROWS][GameConfig.COLS];
    private final BoardCellView[][] boardViews = new BoardCellView[GameConfig.ROWS][GameConfig.COLS];
    private final List<EnemyActor> enemies = new ArrayList<>();
    private final SoundEngine soundEngine = new SoundEngine();
    private final GameState gameState = new GameState();

    private final Font titleFont = Font.font("Garamond", FontWeight.BOLD, 36);
    private final Font hudFont = Font.font("Trebuchet MS", FontWeight.SEMI_BOLD, 22);
    private final Font valueFont = Font.font("Consolas", FontWeight.BOLD, 24);
    private final Font cellFont = Font.font("Segoe UI", FontWeight.BOLD, 22);

    private BorderPane root;
    private Pane boardPane;
    private Text ruleText;
    private Text scoreText;
    private Text livesText;
    private Text roundText;
    private Text messageText;

    private Rule rule;
    private PlayerActor player;
    private Timeline enemyLoop;
    private GameDebugServer debugServer;
    private Stage stage;
    private long roundStartGraceEndsAt;
    private long roundStartedAt;
    private long enemyMovementEnabledAt;
    private long lastStateTimestamp;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        seed = Long.getLong("numberMunchers.seed", System.currentTimeMillis());
        random = new Random(seed);

        setupLayout();
        startRound();

        Scene scene = new Scene(root, GameConfig.WIDTH, GameConfig.HEIGHT, Color.web("#0a1023"));
        registerInput(scene);

        stage.setTitle("Number Munchers Deluxe");
        stage.setScene(scene);
        stage.show();

        startDebugServer();
    }

    private void startDebugServer() {
        if (debugPort <= 0) {
            return;
        }

        debugServer = new GameDebugServer(this, debugPort);
        try {
            debugServer.start();
            System.out.println("Number Munchers debug server listening on http://127.0.0.1:" + debugPort);
        } catch (IOException ex) {
            System.err.println("Failed to start debug server on port " + debugPort + ": " + ex.getMessage());
        }
    }

    private void setupLayout() {
        root = new BorderPane();
        root.setPadding(new Insets(18));
        root.setBackground(new Background(new BackgroundFill(Color.web("#0a1023"), CornerRadii.EMPTY, Insets.EMPTY)));

        VBox topBox = new VBox(8);
        topBox.setPadding(new Insets(8, 4, 16, 4));
        topBox.setAlignment(Pos.CENTER_LEFT);

        Text title = new Text("Number Munchers Deluxe");
        title.setFont(titleFont);
        title.setFill(Color.web("#ffedba"));
        title.setEffect(new Glow(0.35));

        ruleText = new Text();
        ruleText.setFont(Font.font("Georgia", FontWeight.BOLD, 24));
        ruleText.setFill(Color.web("#8ae8ff"));

        HBox hud = new HBox(26);
        scoreText = new Text();
        livesText = new Text();
        roundText = new Text();
        for (Text text : Arrays.asList(scoreText, livesText, roundText)) {
            text.setFont(hudFont);
            text.setFill(Color.web("#f9f2d4"));
        }
        hud.getChildren().addAll(scoreText, livesText, roundText);

        Text controlsText = new Text("Move: Arrows/WASD   Eat: Space   Pause: P   Restart: Enter");
        controlsText.setFont(Font.font("Trebuchet MS", FontWeight.NORMAL, 16));
        controlsText.setFill(Color.web("#b7c9eb"));

        topBox.getChildren().addAll(title, ruleText, hud, controlsText);
        root.setTop(topBox);

        boardPane = new Pane();
        boardPane.setPrefSize(GameConfig.COLS * GameConfig.CELL_SIZE, GameConfig.ROWS * GameConfig.CELL_SIZE);
        boardPane.setBackground(new Background(new BackgroundFill(Color.web("#101b36"), CornerRadii.EMPTY, Insets.EMPTY)));
        buildBoard();
        root.setCenter(centered(boardPane));

        messageText = new Text();
        messageText.setFont(Font.font("Palatino Linotype", FontWeight.BOLD, 40));
        messageText.setFill(Color.web("#fff9de"));
        messageText.setVisible(false);
        root.setBottom(centered(messageText));
    }

    private StackPane centered(javafx.scene.Node node) {
        StackPane wrap = new StackPane(node);
        wrap.setPadding(new Insets(4));
        return wrap;
    }

    private void buildBoard() {
        boardPane.getChildren().clear();

        for (int row = 0; row < GameConfig.ROWS; row++) {
            for (int col = 0; col < GameConfig.COLS; col++) {
                BoardCell cell = new BoardCell(row, col, 1);
                BoardCellView view = new BoardCellView(row, col, cellFont, valueFont);
                board[row][col] = cell;
                boardViews[row][col] = view;
                boardPane.getChildren().add(view.getRoot());
            }
        }

        for (int x = 0; x <= GameConfig.COLS; x++) {
            Line line = new Line(x * GameConfig.CELL_SIZE, 0, x * GameConfig.CELL_SIZE, GameConfig.ROWS * GameConfig.CELL_SIZE);
            line.setStroke(Color.web("#2d4d89"));
            line.setOpacity(0.35);
            boardPane.getChildren().add(line);
        }
        for (int y = 0; y <= GameConfig.ROWS; y++) {
            Line line = new Line(0, y * GameConfig.CELL_SIZE, GameConfig.COLS * GameConfig.CELL_SIZE, y * GameConfig.CELL_SIZE);
            line.setStroke(Color.web("#2d4d89"));
            line.setOpacity(0.35);
            boardPane.getChildren().add(line);
        }

        Rectangle outerGrid = new Rectangle(
                GameConfig.CELL_INSET,
                GameConfig.CELL_INSET,
                GameConfig.COLS * GameConfig.CELL_SIZE - (GameConfig.CELL_INSET * 2),
                GameConfig.ROWS * GameConfig.CELL_SIZE - (GameConfig.CELL_INSET * 2)
        );
        outerGrid.setFill(Color.TRANSPARENT);
        outerGrid.setStroke(Color.web("#3f6bb4"));
        outerGrid.setStrokeWidth(1.4);
        outerGrid.setOpacity(0.55);
        boardPane.getChildren().add(outerGrid);
    }

    private void registerInput(Scene scene) {
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.P) {
                setPaused(!gameState.isPaused());
                return;
            }

            if (gameState.isGameOver()) {
                if (event.getCode() == KeyCode.ENTER) {
                    resetGame();
                }
                return;
            }

            if (gameState.isPaused()) {
                return;
            }

            int dCol = 0;
            int dRow = 0;
            if (event.getCode() == KeyCode.LEFT || event.getCode() == KeyCode.A) {
                dCol = -1;
            } else if (event.getCode() == KeyCode.RIGHT || event.getCode() == KeyCode.D) {
                dCol = 1;
            } else if (event.getCode() == KeyCode.UP || event.getCode() == KeyCode.W) {
                dRow = -1;
            } else if (event.getCode() == KeyCode.DOWN || event.getCode() == KeyCode.S) {
                dRow = 1;
            }

            if (dRow != 0 || dCol != 0) {
                movePlayer(dRow, dCol);
                return;
            }

            if (event.getCode() == KeyCode.SPACE) {
                munchCurrentCell();
            }
        });
    }

    private void startRound() {
        clearActors();
        messageText.setVisible(false);
        gameState.setGameOver(false);
        gameState.setPaused(false);

        rule = Rule.randomRule(random, gameState.getRound());
        ruleText.setText("Eat numbers that are " + rule.getDescription());

        int edibleCount = 0;
        for (int row = 0; row < GameConfig.ROWS; row++) {
            for (int col = 0; col < GameConfig.COLS; col++) {
                BoardCell cell = board[row][col];
                int value = random.nextInt(99) + 1;
                cell.setValue(value);
                cell.setMunched(false);
                cell.setEdible(rule.matches(value));
                if (cell.isEdible()) {
                    edibleCount++;
                }
                boardViews[row][col].refresh(cell);
            }
        }

        int targetCells = GameConfig.MIN_TARGET_CELLS + random.nextInt(GameConfig.MAX_TARGET_CELLS - GameConfig.MIN_TARGET_CELLS + 1);
        if (edibleCount > targetCells) {
            edibleCount = trimEdibleCells(edibleCount, targetCells);
        } else {
            edibleCount = growEdibleCells(edibleCount, targetCells);
        }
        gameState.setEdibleRemaining(edibleCount);

        player = new PlayerActor(
                random.nextInt(GameConfig.ROWS),
                random.nextInt(GameConfig.COLS),
                SpriteFactory.createPlayerSprite()
        );
        boardPane.getChildren().add(player.getSprite());
        player.placeInstant();
        animateIdle(player.getSprite());

        spawnEnemies();
        updateHud();
        roundStartedAt = System.currentTimeMillis();
        roundStartGraceEndsAt = System.currentTimeMillis() + GameConfig.ROUND_START_GRACE_MILLIS;
        enemyMovementEnabledAt = roundStartGraceEndsAt;
        startEnemyLoop();
    }

    private int trimEdibleCells(int current, int target) {
        while (current > target) {
            int row = random.nextInt(GameConfig.ROWS);
            int col = random.nextInt(GameConfig.COLS);
            BoardCell cell = board[row][col];
            if (!cell.isEdible()) {
                continue;
            }
            int value;
            int tries = 0;
            do {
                value = random.nextInt(99) + 1;
                tries++;
            } while (rule.matches(value) && tries < 400);

            if (!rule.matches(value)) {
                cell.setValue(value);
                cell.setEdible(false);
                boardViews[row][col].refresh(cell);
                current--;
            }
        }
        return current;
    }

    private int growEdibleCells(int current, int target) {
        while (current < target) {
            int row = random.nextInt(GameConfig.ROWS);
            int col = random.nextInt(GameConfig.COLS);
            BoardCell cell = board[row][col];
            if (cell.isEdible()) {
                continue;
            }
            cell.setValue(rule.generateMatching(random));
            cell.setEdible(true);
            boardViews[row][col].refresh(cell);
            current++;
        }
        return current;
    }

    private void spawnEnemies() {
        int enemyCount = Math.min(2 + gameState.getRound() / 2, GameConfig.MAX_ENEMIES);
        Set<Integer> occupied = new HashSet<>();
        occupied.add(player.getRow() * GameConfig.COLS + player.getCol());

        for (int i = 0; i < enemyCount; i++) {
            int row;
            int col;
            int key;
            do {
                row = random.nextInt(GameConfig.ROWS);
                col = random.nextInt(GameConfig.COLS);
                key = row * GameConfig.COLS + col;
            } while (occupied.contains(key));

            occupied.add(key);
            EnemyActor enemy = new EnemyActor(row, col, SpriteFactory.createEnemySprite(i));
            enemies.add(enemy);
            boardPane.getChildren().add(enemy.getSprite());
            enemy.placeInstant();
            animateEnemy(enemy.getSprite(), i);
        }
    }

    private void clearActors() {
        if (enemyLoop != null) {
            enemyLoop.stop();
        }
        enemies.clear();
        boardPane.getChildren().removeIf(node -> node instanceof Group && "actor".equals(node.getUserData()));
    }

    private void movePlayer(int dRow, int dCol) {
        if (gameState.isPaused() || gameState.isGameOver()) {
            return;
        }

        int nextRow = player.getRow() + dRow;
        int nextCol = player.getCol() + dCol;
        if (!inBounds(nextRow, nextCol)) {
            return;
        }

        player.setPosition(nextRow, nextCol);
        animateMove(player.getSprite(), nextCol * GameConfig.CELL_SIZE + GameConfig.CELL_SIZE / 2.0, nextRow * GameConfig.CELL_SIZE + GameConfig.CELL_SIZE / 2.0);

        ScaleTransition step = new ScaleTransition(Duration.millis(100), player.getSprite());
        step.setToX(1.08);
        step.setToY(0.92);
        step.setCycleCount(2);
        step.setAutoReverse(true);
        step.play();

        checkCollisions();
    }

    private void munchCurrentCell() {
        if (gameState.isPaused() || gameState.isGameOver()) {
            return;
        }

        BoardCell cell = board[player.getRow()][player.getCol()];
        if (cell.isMunched()) {
            return;
        }

        cell.setMunched(true);
        boardViews[player.getRow()][player.getCol()].refresh(cell);

        if (cell.isEdible()) {
            gameState.onCorrectMunch();
            soundEngine.playChomp();
            pulseCell(boardViews[player.getRow()][player.getCol()].getRoot(), Color.web("#4edb86"));
        } else {
            gameState.onWrongMunch();
            soundEngine.playError();
            pulseCell(boardViews[player.getRow()][player.getCol()].getRoot(), Color.web("#ff5764"));
            if (gameState.getLives() <= 0) {
                soundEngine.playCaught();
                endGame("Out Of Lives");
                return;
            }
        }

        if (gameState.getEdibleRemaining() <= 0) {
            soundEngine.playRoundClear();
            endRound();
            return;
        }

        updateHud();
    }

    private void pulseCell(StackPane cellRoot, Color color) {
        Rectangle flash = new Rectangle(
                GameConfig.CELL_SIZE - (GameConfig.CELL_INSET * 2),
                GameConfig.CELL_SIZE - (GameConfig.CELL_INSET * 2),
                color
        );
        flash.setOpacity(0.5);
        flash.setArcWidth(14);
        flash.setArcHeight(14);
        cellRoot.getChildren().add(flash);

        FadeTransition fade = new FadeTransition(Duration.millis(220), flash);
        fade.setFromValue(0.5);
        fade.setToValue(0.0);
        fade.setOnFinished(e -> cellRoot.getChildren().remove(flash));
        fade.play();
    }

    private void startEnemyLoop() {
        enemyLoop = new Timeline(new KeyFrame(Duration.millis(GameConfig.enemyTickMillis(gameState.getRound())), e -> updateEnemies()));
        enemyLoop.setCycleCount(Animation.INDEFINITE);
        enemyLoop.play();
    }

    private void updateEnemies() {
        if (gameState.isGameOver() || gameState.isPaused()) {
            return;
        }
        if (System.currentTimeMillis() < roundStartGraceEndsAt) {
            return;
        }

        for (EnemyActor enemy : enemies) {
            int[] move = EnemyAi.bestMove(enemy, player, GameConfig.ROWS, GameConfig.COLS, gameState.getRound(), random);
            enemy.moveBy(move[0], move[1]);
            animateMove(
                    enemy.getSprite(),
                    enemy.getCol() * GameConfig.CELL_SIZE + GameConfig.CELL_SIZE / 2.0,
                    enemy.getRow() * GameConfig.CELL_SIZE + GameConfig.CELL_SIZE / 2.0
            );
        }

        checkCollisions();
    }

    private boolean inBounds(int row, int col) {
        return row >= 0 && row < GameConfig.ROWS && col >= 0 && col < GameConfig.COLS;
    }

    private void checkCollisions() {
        for (EnemyActor enemy : enemies) {
            if (enemy.getRow() == player.getRow() && enemy.getCol() == player.getCol()) {
                soundEngine.playCaught();
                endGame("A Troggle Got You");
                return;
            }
        }
    }

    private void endRound() {
        if (enemyLoop != null) {
            enemyLoop.stop();
        }
        gameState.advanceRoundWithBonus();
        updateHud();
        showMessage("Round Cleared!", Color.web("#9dffbe"), this::startRound);
    }

    private void endGame(String text) {
        if (enemyLoop != null) {
            enemyLoop.stop();
        }
        gameState.setGameOver(true);
        gameState.setPaused(false);
        showMessage(text + " - Press Enter", Color.web("#ffd3d3"), null);
        updateHud();
    }

    private void resetGame() {
        gameState.reset();
        random = new Random(seed);
        startRound();
    }

    private void setPaused(boolean paused) {
        if (gameState.isGameOver()) {
            return;
        }
        gameState.setPaused(paused);
        if (paused) {
            showMessage("Paused", Color.web("#fff1a8"), null);
        } else {
            messageText.setVisible(false);
        }
        updateHud();
    }

    private void showMessage(String text, Color color, Runnable onDone) {
        messageText.setText(text);
        messageText.setFill(color);
        messageText.setVisible(true);
        messageText.setOpacity(0.0);

        FadeTransition fade = new FadeTransition(Duration.millis(520), messageText);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.setCycleCount(onDone == null ? 1 : 2);
        fade.setAutoReverse(onDone != null);
        if (onDone != null) {
            fade.setOnFinished(e -> onDone.run());
        }
        fade.play();
    }

    private void updateHud() {
        scoreText.setText("Score: " + gameState.getScore());
        livesText.setText("Lives: " + gameState.getLives() + (gameState.isPaused() ? " | Paused" : ""));
        roundText.setText("Round: " + gameState.getRound() + " | Targets Left: " + gameState.getEdibleRemaining());
    }

    private void animateMove(Group node, double targetX, double targetY) {
        Timeline timeline = new Timeline(
                new KeyFrame(Duration.millis(160),
                        new KeyValue(node.layoutXProperty(), targetX, Interpolator.EASE_BOTH),
                        new KeyValue(node.layoutYProperty(), targetY, Interpolator.EASE_BOTH))
        );
        timeline.play();
    }

    private void animateIdle(Group node) {
        Timeline bob = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(node.translateYProperty(), 0)),
                new KeyFrame(Duration.millis(390), new KeyValue(node.translateYProperty(), -2.8, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.millis(780), new KeyValue(node.translateYProperty(), 0, Interpolator.EASE_BOTH))
        );
        bob.setCycleCount(Animation.INDEFINITE);
        bob.play();
    }

    private void animateEnemy(Group node, int index) {
        RotateTransition rotate = new RotateTransition(Duration.millis(900 + index * 90L), node);
        rotate.setFromAngle(-4);
        rotate.setToAngle(4);
        rotate.setCycleCount(Animation.INDEFINITE);
        rotate.setAutoReverse(true);
        rotate.play();
    }

    String handleDebugCommand(String command, Map<String, String> params) {
        StateSummary previous = captureStateSummary();
        List<String> events = new ArrayList<>();
        switch (command) {
            case "state":
                events.add("state_snapshot_returned");
                return okResponse(command, previous, events);
            case "reset":
                resetGame();
                events.add("round_reset");
                events.add("seed_reapplied");
                events.add("grace_period_active_until_" + enemyMovementEnabledAt);
                return okResponse(command, previous, events);
            case "set-seed":
                String rawSeed = params.get("seed");
                if (rawSeed == null || rawSeed.isBlank()) {
                    throw new IllegalArgumentException("seed query parameter is required.");
                }
                seed = Long.parseLong(rawSeed);
                boolean reset = !"false".equalsIgnoreCase(params.getOrDefault("reset", "true"));
                if (reset) {
                    resetGame();
                    events.add("game_reset_after_seed_change");
                } else {
                    random = new Random(seed);
                    events.add("rng_reseeded_without_reset");
                }
                events.add("seed_set_to_" + seed);
                return okResponse(command, previous, events);
            case "move":
                applyMove(params.getOrDefault("direction", ""));
                events.add("player_move_attempted");
                return okResponse(command, previous, events);
            case "munch":
                munchCurrentCell();
                events.add("munch_attempted");
                return okResponse(command, previous, events);
            case "pause":
                setPaused(true);
                events.add("paused");
                return okResponse(command, previous, events);
            case "resume":
                setPaused(false);
                events.add("resumed");
                return okResponse(command, previous, events);
            case "toggle-pause":
                setPaused(!gameState.isPaused());
                events.add("pause_toggled");
                return okResponse(command, previous, events);
            case "tick-enemies":
                int ticks = parseInt(params.getOrDefault("count", "1"), 1, 100);
                boolean pausedBefore = gameState.isPaused();
                gameState.setPaused(false);
                for (int i = 0; i < ticks; i++) {
                    updateEnemies();
                    if (gameState.isGameOver()) {
                        break;
                    }
                }
                gameState.setPaused(pausedBefore);
                updateHud();
                events.add("enemy_ticks_applied_" + ticks);
                return okResponse(command, previous, events);
            default:
                throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    String debugHealthJson() {
        long timestamp = System.currentTimeMillis();
        return "{"
                + "\"status\":\"ok\","
                + "\"service\":\"number-munchers-debug\","
                + "\"session_id\":\"" + escapeJson(sessionId) + "\","
                + "\"process_id\":" + ProcessHandle.current().pid() + ","
                + "\"started_at\":" + startedAt + ","
                + "\"build_version\":\"" + escapeJson(buildVersion) + "\","
                + "\"seed\":" + seed + ","
                + "\"debug_port\":" + debugPort + ","
                + "\"window_title\":\"" + escapeJson(stage == null ? "" : stage.getTitle()) + "\","
                + "\"window_focused\":" + isWindowFocused() + ","
                + "\"last_state_timestamp\":" + lastStateTimestamp + ","
                + "\"timestamp\":" + timestamp
                + "}";
    }

    String debugStateJson() {
        lastStateTimestamp = System.currentTimeMillis();
        StringBuilder boardJson = new StringBuilder();
        boardJson.append('[');
        for (int row = 0; row < GameConfig.ROWS; row++) {
            if (row > 0) {
                boardJson.append(',');
            }
            boardJson.append('[');
            for (int col = 0; col < GameConfig.COLS; col++) {
                if (col > 0) {
                    boardJson.append(',');
                }
                BoardCell cell = board[row][col];
                boardJson.append('{')
                        .append("\"row\":").append(row).append(',')
                        .append("\"col\":").append(col).append(',')
                        .append("\"value\":").append(cell.getValue()).append(',')
                        .append("\"edible\":").append(cell.isEdible()).append(',')
                        .append("\"munched\":").append(cell.isMunched())
                        .append('}');
            }
            boardJson.append(']');
        }
        boardJson.append(']');

        StringBuilder enemiesJson = new StringBuilder();
        enemiesJson.append('[');
        for (int i = 0; i < enemies.size(); i++) {
            EnemyActor enemy = enemies.get(i);
            if (i > 0) {
                enemiesJson.append(',');
            }
            enemiesJson.append('{')
                    .append("\"row\":").append(enemy.getRow()).append(',')
                    .append("\"col\":").append(enemy.getCol())
                    .append('}');
        }
        enemiesJson.append(']');

        long now = lastStateTimestamp;
        boolean roundIntroActive = now < enemyMovementEnabledAt;
        boolean enemyAiActive = !gameState.isGameOver() && !gameState.isPaused() && !roundIntroActive;
        boolean acceptingInput = player != null && !gameState.isGameOver() && !gameState.isPaused();

        return "{"
                + "\"title\":\"" + escapeJson("Number Munchers Deluxe") + "\","
                + "\"session_id\":\"" + escapeJson(sessionId) + "\","
                + "\"process_id\":" + ProcessHandle.current().pid() + ","
                + "\"started_at\":" + startedAt + ","
                + "\"build_version\":\"" + escapeJson(buildVersion) + "\","
                + "\"seed\":" + seed + ","
                + "\"debug_port\":" + debugPort + ","
                + "\"state_timestamp\":" + now + ","
                + "\"round_started_at\":" + roundStartedAt + ","
                + "\"enemy_movement_enabled_at\":" + enemyMovementEnabledAt + ","
                + "\"milliseconds_since_round_start\":" + Math.max(0, now - roundStartedAt) + ","
                + "\"milliseconds_until_enemy_movement\":" + Math.max(0, enemyMovementEnabledAt - now) + ","
                + "\"score\":" + gameState.getScore() + ","
                + "\"lives\":" + gameState.getLives() + ","
                + "\"round\":" + gameState.getRound() + ","
                + "\"edible_remaining\":" + gameState.getEdibleRemaining() + ","
                + "\"game_over\":" + gameState.isGameOver() + ","
                + "\"paused\":" + gameState.isPaused() + ","
                + "\"accepting_input\":" + acceptingInput + ","
                + "\"window_focused\":" + isWindowFocused() + ","
                + "\"round_intro_active\":" + roundIntroActive + ","
                + "\"enemy_ai_active\":" + enemyAiActive + ","
                + "\"rule\":\"" + escapeJson(rule == null ? "" : rule.getDescription()) + "\","
                + "\"player\":{\"row\":" + player.getRow() + ",\"col\":" + player.getCol() + "},"
                + "\"enemies\":" + enemiesJson + ","
                + "\"board\":" + boardJson + ","
                + "\"layout\":" + layoutJson() + ","
                + "\"state_stable\":true"
                + "}";
    }

    private String layoutJson() {
        Bounds boardBounds = boardPane.getLayoutBounds();
        Bounds hudBounds = scoreText.getParent().getLayoutBounds();
        StringBuilder corners = new StringBuilder();
        corners.append('[')
                .append(cellBoundsJson(0, 0)).append(',')
                .append(cellBoundsJson(0, GameConfig.COLS - 1)).append(',')
                .append(cellBoundsJson(GameConfig.ROWS - 1, 0)).append(',')
                .append(cellBoundsJson(GameConfig.ROWS - 1, GameConfig.COLS - 1))
                .append(']');
        return "{"
                + "\"board\":{\"x\":0,\"y\":0,\"width\":" + (int) boardBounds.getWidth() + ",\"height\":" + (int) boardBounds.getHeight() + "},"
                + "\"outer_border\":{\"x\":" + GameConfig.CELL_INSET + ",\"y\":" + GameConfig.CELL_INSET
                + ",\"width\":" + (GameConfig.COLS * GameConfig.CELL_SIZE - (GameConfig.CELL_INSET * 2))
                + ",\"height\":" + (GameConfig.ROWS * GameConfig.CELL_SIZE - (GameConfig.CELL_INSET * 2)) + "},"
                + "\"hud\":{\"x\":0,\"y\":0,\"width\":" + (int) hudBounds.getWidth() + ",\"height\":" + (int) hudBounds.getHeight() + "},"
                + "\"cell_inset\":" + GameConfig.CELL_INSET + ","
                + "\"cell_size\":" + GameConfig.CELL_SIZE + ","
                + "\"cell_bounds\":" + corners
                + "}";
    }

    private String cellBoundsJson(int row, int col) {
        return "{"
                + "\"row\":" + row + ","
                + "\"col\":" + col + ","
                + "\"x\":" + (col * GameConfig.CELL_SIZE + GameConfig.CELL_INSET) + ","
                + "\"y\":" + (row * GameConfig.CELL_SIZE + GameConfig.CELL_INSET) + ","
                + "\"width\":" + (GameConfig.CELL_SIZE - (GameConfig.CELL_INSET * 2)) + ","
                + "\"height\":" + (GameConfig.CELL_SIZE - (GameConfig.CELL_INSET * 2))
                + "}";
    }

    private void applyMove(String direction) {
        switch (direction.toLowerCase()) {
            case "left":
                movePlayer(0, -1);
                break;
            case "right":
                movePlayer(0, 1);
                break;
            case "up":
                movePlayer(-1, 0);
                break;
            case "down":
                movePlayer(1, 0);
                break;
            default:
                throw new IllegalArgumentException("direction must be one of left, right, up, down.");
        }
    }

    private int parseInt(String value, int min, int max) {
        int parsed = Integer.parseInt(value);
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException("count must be between " + min + " and " + max + ".");
        }
        return parsed;
    }

    private String okResponse(String command, StateSummary previous, List<String> events) {
        StateSummary current = captureStateSummary();
        long appliedAt = System.currentTimeMillis();
        return "{"
                + "\"ok\":true,"
                + "\"command\":\"" + escapeJson(command) + "\","
                + "\"applied_at\":" + appliedAt + ","
                + "\"previous_state_summary\":" + previous.toJson() + ","
                + "\"new_state_summary\":" + current.toJson() + ","
                + "\"events_emitted\":" + stringArrayJson(events) + ","
                + "\"state\":" + debugStateJson()
                + "}";
    }

    private StateSummary captureStateSummary() {
        return new StateSummary(
                gameState.getScore(),
                gameState.getLives(),
                gameState.getRound(),
                gameState.getEdibleRemaining(),
                gameState.isPaused(),
                gameState.isGameOver(),
                player == null ? -1 : player.getRow(),
                player == null ? -1 : player.getCol()
        );
    }

    private String stringArrayJson(List<String> values) {
        StringBuilder json = new StringBuilder();
        json.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(escapeJson(values.get(i))).append('"');
        }
        json.append(']');
        return json.toString();
    }

    private boolean isWindowFocused() {
        return stage != null && stage.isFocused();
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static final class StateSummary {
        private final int score;
        private final int lives;
        private final int round;
        private final int edibleRemaining;
        private final boolean paused;
        private final boolean gameOver;
        private final int playerRow;
        private final int playerCol;

        private StateSummary(int score, int lives, int round, int edibleRemaining, boolean paused, boolean gameOver, int playerRow, int playerCol) {
            this.score = score;
            this.lives = lives;
            this.round = round;
            this.edibleRemaining = edibleRemaining;
            this.paused = paused;
            this.gameOver = gameOver;
            this.playerRow = playerRow;
            this.playerCol = playerCol;
        }

        private String toJson() {
            return "{"
                    + "\"score\":" + score + ","
                    + "\"lives\":" + lives + ","
                    + "\"round\":" + round + ","
                    + "\"edible_remaining\":" + edibleRemaining + ","
                    + "\"paused\":" + paused + ","
                    + "\"game_over\":" + gameOver + ","
                    + "\"player\":{\"row\":" + playerRow + ",\"col\":" + playerCol + "}"
                    + "}";
        }
    }

    @Override
    public void stop() {
        if (debugServer != null) {
            debugServer.stop();
        }
        soundEngine.shutdown();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
