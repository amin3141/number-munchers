package com.pairsys.numbermunchers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.RotateTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
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
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
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
    private static final List<String> PLAYER_OPTIONS = List.of(
            "Abeera", "Amina", "Hamza", "Mariam", "Mohammed", "Mustafa",
            "Palwasha", "Yusef", "Zahra", "Zarghuna", "Zhaley"
    );
    private static final java.util.prefs.Preferences APP_PREFS =
            java.util.prefs.Preferences.userRoot().node("com/pairsys/numbermunchers/app");

    private long seed;
    private final int debugPort = Integer.getInteger("numberMunchers.debug.port", 8765);
    private final String sessionId = System.getenv().getOrDefault("NUMBER_MUNCHERS_SESSION_ID", "unknown");
    private final long startedAt = parseLong(System.getenv("NUMBER_MUNCHERS_STARTED_AT"), System.currentTimeMillis());
    private final String buildVersion = System.getenv().getOrDefault("NUMBER_MUNCHERS_BUILD_VERSION", "unknown");
    private final GameSession session = new GameSession(GameConfig.ROWS, GameConfig.COLS);
    private final BoardCellView[][] boardViews = new BoardCellView[GameConfig.ROWS][GameConfig.COLS];
    private final List<EnemyActor> enemyActors = new ArrayList<>();
    private final SoundEngine soundEngine = new SoundEngine();
    private final PlayerProgressStore progressStore = new PlayerProgressStore();

    private final Font titleFont = Font.font("Garamond", FontWeight.BOLD, 38);
    private final Font hudFont = Font.font("Trebuchet MS", FontWeight.SEMI_BOLD, 24);
    private final Font valueFont = Font.font("Consolas", FontWeight.BOLD, 30);
    private final Font cellFont = Font.font("Segoe UI", FontWeight.BOLD, 28);

    private BorderPane root;
    private VBox topBox;
    private Pane boardPane;
    private StackPane centerPane;
    private StackPane startOverlay;
    private VBox titleMenuList;
    private VBox playerSelectList;
    private Text startOverlayHeader;
    private Text startOverlaySubtitle;
    private Text startOverlayHint;
    private Text startOverlayStatus;
    private VBox leaderboardEntries;
    private Text leaderboardFooter;
    private Text ruleText;
    private Text scoreText;
    private Text livesText;
    private Text roundText;
    private Text playerText;
    private Text timerText;
    private Text messageText;
    private final List<Text> playerOptionTexts = new ArrayList<>();
    private Rectangle timerBarFill;

    private PlayerActor playerActor;
    private Timeline enemyLoop;
    private Timeline roundTimerLoop;
    private Timeline frontScreenPulse;
    private GameDebugServer debugServer;
    private Stage stage;
    private long roundStartGraceEndsAt;
    private long roundStartedAt;
    private int lastDisplayedScore;
    private long enemyMovementEnabledAt;
    private long lastStateTimestamp;
    private long roundTimerElapsedMillis;
    private long lastRoundTimerTickAt;
    private int roundTimerPoints;
    private boolean startScreenActive = true;
    private FrontScreenMode frontScreenMode = FrontScreenMode.TITLE;
    private int titleMenuIndex = 0;
    private int selectedPlayerIndex = loadLastSelectedPlayerIndex();

    private static int loadLastSelectedPlayerIndex() {
        String lastPlayer = APP_PREFS.get("lastSelectedPlayer", "");
        int index = PLAYER_OPTIONS.indexOf(lastPlayer);
        return index >= 0 ? index : 0;
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        seed = Long.getLong("numberMunchers.seed", System.currentTimeMillis());

        setupLayout();
        showStartScreen();

        Scene scene = new Scene(root, GameConfig.WIDTH, GameConfig.HEIGHT, Color.web("#0a1023"));
        registerInput(scene);

        stage.setTitle("Number Munchers Deluxe");
        stage.setFullScreenExitHint("");
        stage.setScene(scene);
        stage.setMaximized(true);
        stage.show();
        stage.setFullScreen(true);

        // Ensure keyboard focus after fullscreen transition and when window gains focus
        stage.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused) {
                scene.getRoot().requestFocus();
            }
        });
        Platform.runLater(() -> {
            stage.requestFocus();
            scene.getRoot().requestFocus();
        });

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

        topBox = new VBox(8);
        topBox.setPadding(new Insets(8, 4, 16, 4));
        topBox.setAlignment(Pos.CENTER_LEFT);

        Text title = new Text("Number Munchers Deluxe");
        title.setFont(titleFont);
        title.setFill(Color.web("#ffedba"));
        title.setEffect(new Glow(0.35));

        ruleText = new Text();
        ruleText.setFont(Font.font("Georgia", FontWeight.BOLD, 24));
        ruleText.setFill(Color.web("#8ae8ff"));

        // Left side HUD
        HBox hudLeft = new HBox(26);
        hudLeft.setAlignment(Pos.CENTER_LEFT);

        livesText = new Text();
        roundText = new Text();
        playerText = new Text();
        for (Text text : Arrays.asList(livesText, roundText, playerText)) {
            text.setFont(hudFont);
            text.setFill(Color.web("#f9f2d4"));
        }
        hudLeft.getChildren().addAll(livesText, roundText, playerText);

        // Arcade-style score display (upper right) - big and bold with glow
        scoreText = new Text("0");
        scoreText.setFont(Font.font("Consolas", FontWeight.BOLD, 64));
        scoreText.setFill(Color.web("#ffea00"));
        javafx.scene.effect.DropShadow scoreShadow = new javafx.scene.effect.DropShadow();
        scoreShadow.setColor(Color.web("#ff6600"));
        scoreShadow.setRadius(15);
        scoreShadow.setSpread(0.4);
        scoreText.setEffect(scoreShadow);

        Rectangle scoreBack = new Rectangle(180, 70, Color.web("#1a0a30"));
        scoreBack.setArcWidth(16);
        scoreBack.setArcHeight(16);
        scoreBack.setStroke(Color.web("#ffaa00"));
        scoreBack.setStrokeWidth(3);

        StackPane scoreBox = new StackPane(scoreBack, scoreText);
        scoreBox.setAlignment(Pos.CENTER);

        // Subtle idle glow animation on score
        Timeline scoreGlow = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(scoreShadow.radiusProperty(), 12)),
                new KeyFrame(Duration.millis(800), new KeyValue(scoreShadow.radiusProperty(), 20, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.millis(1600), new KeyValue(scoreShadow.radiusProperty(), 12, Interpolator.EASE_BOTH))
        );
        scoreGlow.setCycleCount(Animation.INDEFINITE);
        scoreGlow.play();

        // Arcade-style timer bar - big and dramatic like Street Fighter
        Text timerLabel = new Text("TIME");
        timerLabel.setFont(Font.font("Trebuchet MS", FontWeight.BLACK, 18));
        timerLabel.setFill(Color.web("#ff9944"));

        timerText = new Text();
        timerText.setFont(Font.font("Consolas", FontWeight.BOLD, 28));
        timerText.setFill(Color.web("#ffffff"));
        javafx.scene.effect.DropShadow timerTextGlow = new javafx.scene.effect.DropShadow();
        timerTextGlow.setColor(Color.web("#ff4400"));
        timerTextGlow.setRadius(10);
        timerTextGlow.setSpread(0.4);
        timerText.setEffect(timerTextGlow);

        Rectangle timerBarBack = new Rectangle(700, 36, Color.web("#0a0a18"));
        timerBarBack.setArcWidth(6);
        timerBarBack.setArcHeight(6);
        timerBarBack.setStroke(Color.web("#ff6622"));
        timerBarBack.setStrokeWidth(4);
        javafx.scene.effect.DropShadow backGlow = new javafx.scene.effect.DropShadow();
        backGlow.setColor(Color.web("#ff4400"));
        backGlow.setRadius(8);
        backGlow.setSpread(0.2);
        timerBarBack.setEffect(backGlow);

        timerBarFill = new Rectangle(700, 36, Color.web("#ffdd00"));
        timerBarFill.setArcWidth(4);
        timerBarFill.setArcHeight(4);
        javafx.scene.effect.DropShadow timerGlow = new javafx.scene.effect.DropShadow();
        timerGlow.setColor(Color.web("#ffaa00"));
        timerGlow.setRadius(18);
        timerGlow.setSpread(0.6);
        timerBarFill.setEffect(timerGlow);

        StackPane timerBarWrap = new StackPane(timerBarBack, timerBarFill);
        timerBarWrap.setAlignment(Pos.CENTER_LEFT);
        timerBarWrap.setMaxWidth(Region.USE_PREF_SIZE);

        HBox timerRow = new HBox(16, timerLabel, timerBarWrap, timerText);
        timerRow.setAlignment(Pos.CENTER_RIGHT);

        VBox scoreAndTimer = new VBox(12, scoreBox, timerRow);
        scoreAndTimer.setAlignment(Pos.CENTER_RIGHT);

        BorderPane hud = new BorderPane();
        hud.setLeft(hudLeft);
        hud.setRight(scoreAndTimer);

        Text controlsText = new Text("Move: Arrows/WASD   Eat: Space   Pause: P   Restart: Enter   Fullscreen: F11");
        controlsText.setFont(Font.font("Trebuchet MS", FontWeight.NORMAL, 16));
        controlsText.setFill(Color.web("#b7c9eb"));

        topBox.getChildren().addAll(title, ruleText, hud, controlsText);
        root.setTop(topBox);

        boardPane = new Pane();
        boardPane.setPrefSize(GameConfig.COLS * GameConfig.CELL_SIZE, GameConfig.ROWS * GameConfig.CELL_SIZE);
        boardPane.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        boardPane.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        boardPane.setBackground(new Background(new BackgroundFill(Color.web("#101b36"), CornerRadii.EMPTY, Insets.EMPTY)));
        buildBoard();
        centerPane = centered(boardPane);
        startOverlay = createStartOverlay();
        centerPane.getChildren().add(startOverlay);
        root.setCenter(centerPane);

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

    private StackPane createStartOverlay() {
        StackPane overlay = new StackPane();
        overlay.setPickOnBounds(true);
        overlay.setBackground(new Background(new BackgroundFill(Color.rgb(4, 8, 19, 0.78), CornerRadii.EMPTY, Insets.EMPTY)));

        VBox content = new VBox(30);
        content.setAlignment(Pos.TOP_CENTER);
        content.setMaxWidth(1380);
        content.setPadding(new Insets(28, 44, 30, 44));

        VBox marquee = new VBox(8);
        marquee.setAlignment(Pos.CENTER);
        marquee.setMaxWidth(1260);

        Text bootText = new Text("PAIR SYSTEMS PRESENTS");
        bootText.setFont(Font.font("Consolas", FontWeight.BOLD, 20));
        bootText.setFill(Color.web("#7df2d6"));

        Text versusLine = new Text("ARCADE MATH BATTLE");
        versusLine.setFont(Font.font("Trebuchet MS", FontWeight.BLACK, 28));
        versusLine.setFill(Color.web("#ff8f70"));
        versusLine.setStroke(Color.web("#3a0c1a"));
        versusLine.setStrokeWidth(0.8);

        Text title = new Text("NUMBER MUNCHERS");
        title.setFont(Font.font("Garamond", FontWeight.BOLD, 108));
        title.setFill(Color.web("#ffe5a1"));
        title.setStroke(Color.web("#50bfff"));
        title.setStrokeWidth(1.5);

        Text deluxe = new Text("DELUXE");
        deluxe.setFont(Font.font("Trebuchet MS", FontWeight.BLACK, 44));
        deluxe.setFill(Color.web("#fff7d1"));
        deluxe.setStroke(Color.web("#b2432d"));
        deluxe.setStrokeWidth(1.1);

        Rectangle accentBar = new Rectangle(1040, 12, Color.web("#17417e"));
        accentBar.setArcWidth(12);
        accentBar.setArcHeight(12);
        accentBar.setStroke(Color.web("#5fd0ff"));
        accentBar.setStrokeWidth(2);

        startOverlaySubtitle = new Text();
        startOverlaySubtitle.setWrappingWidth(1040);
        startOverlaySubtitle.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        startOverlaySubtitle.setFont(Font.font("Trebuchet MS", FontWeight.BOLD, 24));
        startOverlaySubtitle.setFill(Color.web("#d7e6ff"));

        marquee.getChildren().addAll(bootText, versusLine, title, deluxe, accentBar, startOverlaySubtitle);

        HBox lowerDeck = new HBox(42);
        lowerDeck.setAlignment(Pos.TOP_CENTER);

        VBox selectorCard = new VBox(14);
        selectorCard.setPrefWidth(600);
        selectorCard.setPadding(new Insets(24, 28, 26, 28));
        selectorCard.setBackground(new Background(new BackgroundFill(Color.rgb(8, 23, 55, 0.92), new CornerRadii(22), Insets.EMPTY)));
        selectorCard.setStyle("-fx-border-color: #6bc8ff; -fx-border-width: 3; -fx-border-radius: 22;");

        startOverlayHeader = new Text();
        startOverlayHeader.setFont(Font.font("Trebuchet MS", FontWeight.BLACK, 38));
        startOverlayHeader.setFill(Color.web("#fff1af"));

        startOverlayHint = new Text();
        startOverlayHint.setWrappingWidth(530);
        startOverlayHint.setFont(Font.font("Trebuchet MS", FontWeight.NORMAL, 18));
        startOverlayHint.setFill(Color.web("#b9cfff"));

        titleMenuList = new VBox(12);
        titleMenuList.getChildren().addAll(
                createMenuOptionText(),
                createMenuOptionText(),
                createMenuOptionText()
        );

        playerSelectList = new VBox(7);
        for (String playerName : PLAYER_OPTIONS) {
            Text option = new Text();
            option.setFont(Font.font("Trebuchet MS", FontWeight.BOLD, 24));
            playerOptionTexts.add(option);
            playerSelectList.getChildren().add(option);
        }

        startOverlayStatus = new Text();
        startOverlayStatus.setWrappingWidth(530);
        startOverlayStatus.setFont(Font.font("Consolas", FontWeight.BOLD, 18));
        startOverlayStatus.setFill(Color.web("#7df2d6"));

        Rectangle selectorDivider = new Rectangle(540, 2, Color.web("#2d5e9a"));
        selectorDivider.setOpacity(0.8);

        selectorCard.getChildren().addAll(startOverlayHeader, startOverlayHint, selectorDivider, titleMenuList, playerSelectList, startOverlayStatus);

        VBox right = new VBox(16);
        right.setAlignment(Pos.TOP_LEFT);
        right.setPrefWidth(470);
        HBox.setHgrow(right, Priority.NEVER);

        Text leaderboardTitle = new Text("CHAMPION BOARD");
        leaderboardTitle.setFont(Font.font("Trebuchet MS", FontWeight.BLACK, 34));
        leaderboardTitle.setFill(Color.web("#96dcff"));

        Text leaderboardSubtitle = new Text("Real player records, stored per user. Score leads, then furthest level reached breaks ties.");
        leaderboardSubtitle.setWrappingWidth(470);
        leaderboardSubtitle.setFont(Font.font("Trebuchet MS", FontWeight.NORMAL, 18));
        leaderboardSubtitle.setFill(Color.web("#b5d1ff"));

        VBox leaderboardShot = new VBox(10);
        leaderboardShot.setPadding(new Insets(22));
        leaderboardShot.setBackground(new Background(new BackgroundFill(Color.rgb(28, 18, 46, 0.93), new CornerRadii(24), Insets.EMPTY)));
        leaderboardShot.setStyle("-fx-border-color: #ffd15b; -fx-border-width: 3; -fx-border-radius: 24;");

        Text shotHeader = new Text("★ TOP SCORES ★");
        shotHeader.setFont(Font.font("Consolas", FontWeight.BOLD, 20));
        shotHeader.setFill(Color.web("#ffd777"));

        leaderboardEntries = new VBox(10);
        leaderboardFooter = new Text();
        leaderboardFooter.setWrappingWidth(410);
        leaderboardFooter.setFont(Font.font("Trebuchet MS", FontWeight.NORMAL, 15));
        leaderboardFooter.setFill(Color.web("#b6bedb"));

        leaderboardShot.getChildren().addAll(shotHeader, leaderboardEntries, leaderboardFooter);

        right.getChildren().addAll(leaderboardTitle, leaderboardSubtitle, leaderboardShot);

        lowerDeck.getChildren().addAll(selectorCard, right);
        content.getChildren().addAll(marquee, lowerDeck);
        overlay.getChildren().add(content);
        frontScreenPulse = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(title.scaleXProperty(), 1.0),
                        new KeyValue(title.scaleYProperty(), 1.0),
                        new KeyValue(bootText.opacityProperty(), 0.72)),
                new KeyFrame(Duration.millis(520),
                        new KeyValue(title.scaleXProperty(), 1.035, Interpolator.EASE_BOTH),
                        new KeyValue(title.scaleYProperty(), 1.035, Interpolator.EASE_BOTH),
                        new KeyValue(bootText.opacityProperty(), 1.0, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.millis(1040),
                        new KeyValue(title.scaleXProperty(), 1.0, Interpolator.EASE_BOTH),
                        new KeyValue(title.scaleYProperty(), 1.0, Interpolator.EASE_BOTH),
                        new KeyValue(bootText.opacityProperty(), 0.72, Interpolator.EASE_BOTH))
        );
        frontScreenPulse.setCycleCount(Animation.INDEFINITE);
        frontScreenPulse.play();
        updateStartOverlay();
        return overlay;
    }

    private Text createMenuOptionText() {
        Text option = new Text();
        option.setFont(Font.font("Trebuchet MS", FontWeight.BLACK, 34));
        return option;
    }

    private HBox leaderboardHeader() {
        Text rankHeader = new Text("#");
        rankHeader.setFont(Font.font("Consolas", FontWeight.BOLD, 14));
        rankHeader.setFill(Color.web("#7a8aaa"));
        StackPane rankCol = new StackPane(rankHeader);
        rankCol.setPrefWidth(32);
        rankCol.setAlignment(Pos.CENTER_LEFT);

        Text nameHeader = new Text("PLAYER");
        nameHeader.setFont(Font.font("Consolas", FontWeight.BOLD, 14));
        nameHeader.setFill(Color.web("#7a8aaa"));
        StackPane nameCol = new StackPane(nameHeader);
        nameCol.setPrefWidth(130);
        nameCol.setAlignment(Pos.CENTER_LEFT);

        Text scoreHeader = new Text("SCORE");
        scoreHeader.setFont(Font.font("Consolas", FontWeight.BOLD, 14));
        scoreHeader.setFill(Color.web("#7a8aaa"));
        StackPane scoreCol = new StackPane(scoreHeader);
        scoreCol.setPrefWidth(80);
        scoreCol.setAlignment(Pos.CENTER_RIGHT);

        Text levelHeader = new Text("LV");
        levelHeader.setFont(Font.font("Consolas", FontWeight.BOLD, 14));
        levelHeader.setFill(Color.web("#7a8aaa"));
        StackPane levelCol = new StackPane(levelHeader);
        levelCol.setPrefWidth(40);
        levelCol.setAlignment(Pos.CENTER_RIGHT);

        HBox header = new HBox(8, rankCol, nameCol, scoreCol, levelCol);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 6, 0));
        return header;
    }

    private HBox leaderboardRow(int rank, String name, int score, int level, String accentColor) {
        Text rankText = new Text(String.format("%d", rank));
        rankText.setFont(Font.font("Consolas", FontWeight.BOLD, 18));
        rankText.setFill(Color.web(accentColor));
        StackPane rankCol = new StackPane(rankText);
        rankCol.setPrefWidth(32);
        rankCol.setAlignment(Pos.CENTER_LEFT);

        Text nameText = new Text(name);
        nameText.setFont(Font.font("Trebuchet MS", FontWeight.SEMI_BOLD, 18));
        nameText.setFill(Color.web("#edf0ff"));
        StackPane nameCol = new StackPane(nameText);
        nameCol.setPrefWidth(130);
        nameCol.setAlignment(Pos.CENTER_LEFT);

        Text scoreText = new Text(String.format("%,d", score));
        scoreText.setFont(Font.font("Consolas", FontWeight.BOLD, 18));
        scoreText.setFill(Color.web("#8fd4ff"));
        StackPane scoreCol = new StackPane(scoreText);
        scoreCol.setPrefWidth(80);
        scoreCol.setAlignment(Pos.CENTER_RIGHT);

        Text levelText = new Text(String.valueOf(level));
        levelText.setFont(Font.font("Consolas", FontWeight.BOLD, 18));
        levelText.setFill(Color.web("#c4b5fd"));
        StackPane levelCol = new StackPane(levelText);
        levelCol.setPrefWidth(40);
        levelCol.setAlignment(Pos.CENTER_RIGHT);

        HBox row = new HBox(8, rankCol, nameCol, scoreCol, levelCol);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void updateStartOverlay() {
        refreshLeaderboard();
        if (frontScreenMode == FrontScreenMode.TITLE) {
            startOverlayHeader.setText("PRESS START");
            startOverlaySubtitle.setText("Munch the multiples, dodge the Troggles! Choose a player or jump right in.");
            startOverlayHint.setText("Use arrow keys or W/S to navigate. Press Enter or Space to select.");
            titleMenuList.setVisible(true);
            titleMenuList.setManaged(true);
            playerSelectList.setVisible(false);
            playerSelectList.setManaged(false);
            String[] options = {"START GAME", "PLAYER SELECT", "QUIT"};
            for (int i = 0; i < titleMenuList.getChildren().size(); i++) {
                Text option = (Text) titleMenuList.getChildren().get(i);
                boolean selected = i == titleMenuIndex;
                option.setText((selected ? ">> " : "   ") + options[i]);
                option.setFill(selected ? Color.web("#fff4ad") : Color.web("#d1daf5"));
                option.setOpacity(selected ? 1.0 : 0.76);
                option.setStyle(selected ? "-fx-stroke: #56caff; -fx-stroke-width: 1.1;" : "");
            }
            startOverlayStatus.setText("◆ " + playerProgressSummary(getSelectedPlayerName()));
        } else {
            startOverlayHeader.setText("PLAYER SELECT");
            startOverlaySubtitle.setText("Pick your character! Each muncher tracks their own high scores.");
            startOverlayHint.setText("Use arrow keys or W/S to choose. Press Enter to confirm. Esc to go back.");
            titleMenuList.setVisible(false);
            titleMenuList.setManaged(false);
            playerSelectList.setVisible(true);
            playerSelectList.setManaged(true);
            for (int i = 0; i < playerOptionTexts.size(); i++) {
                Text option = playerOptionTexts.get(i);
                boolean selected = i == selectedPlayerIndex;
                option.setText((selected ? ">> " : "   ") + PLAYER_OPTIONS.get(i));
                option.setFill(selected ? Color.web("#fff4ad") : Color.web("#d1daf5"));
                option.setOpacity(selected ? 1.0 : 0.72);
                option.setStyle(selected ? "-fx-stroke: #56caff; -fx-stroke-width: 1.0;" : "");
            }
            startOverlayStatus.setText("◆ " + playerProgressSummary(getSelectedPlayerName()));
        }
    }

    private void showStartScreen() {
        if (!startScreenActive) {
            persistCurrentPlayerProgress();
        }
        startScreenActive = true;
        frontScreenMode = FrontScreenMode.TITLE;
        titleMenuIndex = 0;
        clearActors();
        session.reset(seed);
        roundStartedAt = 0L;
        roundStartGraceEndsAt = 0L;
        enemyMovementEnabledAt = 0L;
        messageText.setVisible(false);
        topBox.setVisible(false);
        topBox.setManaged(false);
        startOverlay.setVisible(true);
        startOverlay.setManaged(true);
        stopRoundTimerLoop();
        roundTimerElapsedMillis = 0L;
        roundTimerPoints = GameConfig.ROUND_TIMER_START_POINTS;
        lastRoundTimerTickAt = 0L;
        updateRoundTimerHud();
        refreshBoardViews();
        updateHud();
        updateStartOverlay();
        soundEngine.playTitleMusic();
    }

    private void beginGameFromStartScreen() {
        startScreenActive = false;
        topBox.setVisible(true);
        topBox.setManaged(true);
        startOverlay.setVisible(false);
        startOverlay.setManaged(false);
        saveLastSelectedPlayer();
        resetGame();
    }

    private void saveLastSelectedPlayer() {
        APP_PREFS.put("lastSelectedPlayer", getSelectedPlayerName());
        try {
            APP_PREFS.flush();
        } catch (java.util.prefs.BackingStoreException ignored) {
        }
    }

    private void refreshLeaderboard() {
        if (leaderboardEntries == null || leaderboardFooter == null) {
            return;
        }

        leaderboardEntries.getChildren().clear();
        leaderboardEntries.getChildren().add(leaderboardHeader());

        List<PlayerProgress> standings = progressStore.leaderboard(PLAYER_OPTIONS, 5);
        String[] accentColors = {"#77f7d2", "#9ad8ff", "#ffb8d3", "#d6d0ff", "#f4e1a1"};

        for (int i = 0; i < standings.size(); i++) {
            PlayerProgress progress = standings.get(i);
            leaderboardEntries.getChildren().add(
                    leaderboardRow(i + 1, progress.playerName(), progress.topScore(), progress.maxLevelReached(), accentColors[i % accentColors.length])
            );
        }

        boolean hasRecords = standings.stream().anyMatch(progress -> progress.topScore() > 0 || progress.maxLevelReached() > 0);
        if (hasRecords) {
            leaderboardFooter.setText("Saved locally for this Windows user via Java Preferences.");
        } else {
            leaderboardFooter.setText("No saved records yet. Start a run and your family scoreboard will populate here.");
        }
    }

    private void buildBoard() {
        boardPane.getChildren().clear();

        for (int row = 0; row < GameConfig.ROWS; row++) {
            for (int col = 0; col < GameConfig.COLS; col++) {
                BoardCellView view = new BoardCellView(row, col, cellFont, valueFont);
                boardViews[row][col] = view;
                view.refresh(session.getCell(row, col));
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

    private void refreshBoardViews() {
        BoardCell[][] board = session.getBoard();
        for (int row = 0; row < GameConfig.ROWS; row++) {
            for (int col = 0; col < GameConfig.COLS; col++) {
                boardViews[row][col].refresh(board[row][col]);
            }
        }
    }

    private void registerInput(Scene scene) {
        scene.setOnKeyPressed(event -> {
            if (startScreenActive) {
                handleFrontScreenInput(event.getCode());
                return;
            }

            if (event.getCode() == KeyCode.F11) {
                toggleFullScreen();
                return;
            }

            if (event.getCode() == KeyCode.P) {
                setPaused(!session.getGameState().isPaused());
                return;
            }

            if (session.getGameState().isGameOver()) {
                if (event.getCode() == KeyCode.ENTER) {
                    resetGame();
                }
                return;
            }

            if (session.getGameState().isPaused()) {
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

    private void handleFrontScreenInput(KeyCode code) {
        if (code == KeyCode.F11) {
            toggleFullScreen();
            return;
        }
        if (frontScreenMode == FrontScreenMode.TITLE) {
            if (code == KeyCode.UP || code == KeyCode.W || code == KeyCode.LEFT || code == KeyCode.A) {
                titleMenuIndex = Math.floorMod(titleMenuIndex - 1, 3);
                updateStartOverlay();
                return;
            }
            if (code == KeyCode.DOWN || code == KeyCode.S || code == KeyCode.RIGHT || code == KeyCode.D) {
                titleMenuIndex = Math.floorMod(titleMenuIndex + 1, 3);
                updateStartOverlay();
                return;
            }
            if (code == KeyCode.ENTER || code == KeyCode.SPACE) {
                confirmFrontSelection();
            }
            return;
        }

        if (code == KeyCode.ESCAPE || code == KeyCode.BACK_SPACE) {
            frontScreenMode = FrontScreenMode.TITLE;
            updateStartOverlay();
            return;
        }
        if (code == KeyCode.UP || code == KeyCode.W || code == KeyCode.LEFT || code == KeyCode.A) {
            selectedPlayerIndex = Math.floorMod(selectedPlayerIndex - 1, PLAYER_OPTIONS.size());
            updateStartOverlay();
            return;
        }
        if (code == KeyCode.DOWN || code == KeyCode.S || code == KeyCode.RIGHT || code == KeyCode.D) {
            selectedPlayerIndex = Math.floorMod(selectedPlayerIndex + 1, PLAYER_OPTIONS.size());
            updateStartOverlay();
            return;
        }
        if (code == KeyCode.ENTER || code == KeyCode.SPACE) {
            frontScreenMode = FrontScreenMode.TITLE;
            titleMenuIndex = 0;
            updateStartOverlay();
        }
    }

    private void moveFrontSelection(int delta) {
        if (!startScreenActive) {
            return;
        }
        if (frontScreenMode == FrontScreenMode.TITLE) {
            titleMenuIndex = Math.floorMod(titleMenuIndex + delta, 3);
        } else {
            selectedPlayerIndex = Math.floorMod(selectedPlayerIndex + delta, PLAYER_OPTIONS.size());
        }
        updateStartOverlay();
    }

    private void confirmFrontSelection() {
        if (!startScreenActive) {
            return;
        }
        if (frontScreenMode == FrontScreenMode.TITLE) {
            if (titleMenuIndex == 0) {
                beginGameFromStartScreen();
            } else if (titleMenuIndex == 1) {
                frontScreenMode = FrontScreenMode.PLAYER_SELECT;
                updateStartOverlay();
            } else {
                Platform.exit();
            }
            return;
        }

        frontScreenMode = FrontScreenMode.TITLE;
        titleMenuIndex = 0;
        updateStartOverlay();
    }

    private void backFrontSelection() {
        if (!startScreenActive || frontScreenMode != FrontScreenMode.PLAYER_SELECT) {
            return;
        }
        frontScreenMode = FrontScreenMode.TITLE;
        updateStartOverlay();
    }

    private void startRound() {
        startScreenActive = false;
        topBox.setVisible(true);
        topBox.setManaged(true);
        startOverlay.setVisible(false);
        startOverlay.setManaged(false);
        clearActors();
        messageText.setVisible(false);
        LevelPlan levelPlan = session.startRound();
        ruleText.setText("Eat numbers that are " + levelPlan.getRule().getDescription());
        refreshBoardViews();
        spawnVisualActors();
        updateHud();
        roundStartedAt = System.currentTimeMillis();
        roundStartGraceEndsAt = System.currentTimeMillis() + GameConfig.ROUND_START_GRACE_MILLIS;
        enemyMovementEnabledAt = roundStartGraceEndsAt;
        resetRoundTimer();
        startEnemyLoop();
        startRoundTimerLoop();
        showGraceCountdown();
    }

    private void resetRoundTimer() {
        roundTimerElapsedMillis = 0L;
        lastRoundTimerTickAt = System.currentTimeMillis();
        roundTimerPoints = GameConfig.ROUND_TIMER_START_POINTS;
        updateRoundTimerHud();
    }

    private void spawnVisualActors() {
        GridPoint playerPosition = session.getPlayerPosition();
        if (playerPosition == null) {
            return;
        }

        playerActor = new PlayerActor(
                playerPosition.row(),
                playerPosition.col(),
                SpriteFactory.createPlayerSprite(getSelectedPlayerName())
        );
        boardPane.getChildren().add(playerActor.getSprite());
        playerActor.placeInstant();
        animateIdle(playerActor.getSprite());

        List<GridPoint> enemies = session.getEnemies();
        for (int i = 0; i < enemies.size(); i++) {
            GridPoint enemyPosition = enemies.get(i);
            EnemyActor enemyActor = new EnemyActor(enemyPosition.row(), enemyPosition.col(), SpriteFactory.createEnemySprite(i));
            enemyActors.add(enemyActor);
            boardPane.getChildren().add(enemyActor.getSprite());
            enemyActor.placeInstant();
            animateEnemy(enemyActor.getSprite(), i);
        }
    }

    private void clearActors() {
        if (enemyLoop != null) {
            enemyLoop.stop();
        }
        playerActor = null;
        enemyActors.clear();
        boardPane.getChildren().removeIf(node -> node instanceof Group && "actor".equals(node.getUserData()));
    }

    private void movePlayer(int dRow, int dCol) {
        if (playerActor == null) {
            return;
        }
        GameSession.MoveResult result = session.movePlayer(dRow, dCol);
        if (!result.moved() || result.position() == null) {
            return;
        }

        playerActor.setPosition(result.position().row(), result.position().col());
        animateMove(
                playerActor.getSprite(),
                result.position().col() * GameConfig.CELL_SIZE + GameConfig.CELL_SIZE / 2.0,
                result.position().row() * GameConfig.CELL_SIZE + GameConfig.CELL_SIZE / 2.0
        );
        soundEngine.playPlayerMove();

        ScaleTransition step = new ScaleTransition(Duration.millis(100), playerActor.getSprite());
        step.setToX(1.08);
        step.setToY(0.92);
        step.setCycleCount(2);
        step.setAutoReverse(true);
        step.play();

        if (result.collision()) {
            soundEngine.playCaught();
            endGame("A Troggle Got You");
        }
    }

    private void munchCurrentCell() {
        GameSession.MunchResult result = session.munchCurrentCell();
        if (result.type() == GameSession.MunchResultType.IGNORED || result.type() == GameSession.MunchResultType.ALREADY_MUNCHED) {
            return;
        }

        BoardCell cell = session.getCell(result.row(), result.col());
        boardViews[result.row()][result.col()].refresh(cell);

        if (result.type() == GameSession.MunchResultType.CORRECT || result.type() == GameSession.MunchResultType.ROUND_CLEARED) {
            soundEngine.playChomp();
            pulseCell(boardViews[result.row()][result.col()].getRoot(), Color.web("#4edb86"));
            spawnMunchParticles(result.col(), result.row());
        } else {
            soundEngine.playError();
            pulseCell(boardViews[result.row()][result.col()].getRoot(), Color.web("#ff5764"));
            if (result.type() == GameSession.MunchResultType.OUT_OF_LIVES) {
                soundEngine.playCaught();
                endGame("Out Of Lives");
                return;
            }
        }

        if (result.type() == GameSession.MunchResultType.ROUND_CLEARED) {
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
        enemyLoop = new Timeline(new KeyFrame(Duration.millis(GameConfig.enemyTickMillis(session.getGameState().getRound())), e -> updateEnemies()));
        enemyLoop.setCycleCount(Animation.INDEFINITE);
        enemyLoop.play();
    }

    private void startRoundTimerLoop() {
        if (roundTimerLoop != null) {
            roundTimerLoop.stop();
        }
        roundTimerLoop = new Timeline(new KeyFrame(Duration.millis(100), e -> updateRoundTimer()));
        roundTimerLoop.setCycleCount(Animation.INDEFINITE);
        roundTimerLoop.play();
    }

    private void updateRoundTimer() {
        if (startScreenActive || session.getGameState().isPaused() || session.getGameState().isGameOver()) {
            lastRoundTimerTickAt = System.currentTimeMillis();
            return;
        }

        long now = System.currentTimeMillis();
        if (lastRoundTimerTickAt == 0L) {
            lastRoundTimerTickAt = now;
        }
        roundTimerElapsedMillis += Math.max(0L, now - lastRoundTimerTickAt);
        lastRoundTimerTickAt = now;

        int stepsElapsed = (int) (roundTimerElapsedMillis / GameConfig.ROUND_TIMER_STEP_MILLIS);
        roundTimerPoints = Math.max(0, GameConfig.ROUND_TIMER_START_POINTS - stepsElapsed * GameConfig.ROUND_TIMER_DECREMENT);
        updateRoundTimerHud();

        if (roundTimerElapsedMillis >= GameConfig.ROUND_TIMER_TOTAL_MILLIS) {
            handleRoundTimeout();
        }
    }

    private void updateRoundTimerHud() {
        double fraction = Math.max(0.0, Math.min(1.0, 1.0 - ((double) roundTimerElapsedMillis / GameConfig.ROUND_TIMER_TOTAL_MILLIS)));
        timerBarFill.setWidth(700 * fraction);
        if (fraction > 0.5) {
            timerBarFill.setFill(Color.web("#ffd44f"));
        } else if (fraction > 0.25) {
            timerBarFill.setFill(Color.web("#ff9f43"));
        } else {
            timerBarFill.setFill(Color.web("#ff5d5d"));
        }

        int secondsRemaining = (int) Math.max(0, Math.ceil((GameConfig.ROUND_TIMER_TOTAL_MILLIS - roundTimerElapsedMillis) / 1000.0));
        timerText.setText(roundTimerPoints + " pts  |  " + secondsRemaining + "s");
    }

    private void stopRoundTimerLoop() {
        if (roundTimerLoop != null) {
            roundTimerLoop.stop();
        }
    }

    private void updateEnemies() {
        if (playerActor == null || session.getGameState().isGameOver() || session.getGameState().isPaused()) {
            return;
        }
        if (System.currentTimeMillis() < roundStartGraceEndsAt) {
            return;
        }

        boolean collision = session.updateEnemies();
        List<GridPoint> enemyPositions = session.getEnemies();
        for (int i = 0; i < enemyActors.size() && i < enemyPositions.size(); i++) {
            EnemyActor enemy = enemyActors.get(i);
            GridPoint enemyPosition = enemyPositions.get(i);
            enemy.setPosition(enemyPosition.row(), enemyPosition.col());
            animateMove(
                    enemy.getSprite(),
                    enemyPosition.col() * GameConfig.CELL_SIZE + GameConfig.CELL_SIZE / 2.0,
                    enemyPosition.row() * GameConfig.CELL_SIZE + GameConfig.CELL_SIZE / 2.0
            );
        }
        if (!enemyActors.isEmpty()) {
            soundEngine.playEnemyMove();
        }

        if (collision) {
            soundEngine.playCaught();
            endGame("A Troggle Got You");
        }
    }

    private void endRound() {
        if (enemyLoop != null) {
            enemyLoop.stop();
        }
        stopRoundTimerLoop();
        session.getGameState().addScore(roundTimerPoints);
        session.advanceRound();
        persistCurrentPlayerProgress();
        updateHud();
        showMessage("Round Cleared! +" + roundTimerPoints, Color.web("#9dffbe"), this::startRound);
    }

    private void handleRoundTimeout() {
        if (session.getGameState().isGameOver()) {
            return;
        }
        if (enemyLoop != null) {
            enemyLoop.stop();
        }
        stopRoundTimerLoop();
        session.setPaused(false);
        updateHud();
        showMessage("Time Up! Retry Round", Color.web("#ffd37a"), this::startRound);
    }

    private void endGame(String text) {
        if (enemyLoop != null) {
            enemyLoop.stop();
        }
        stopRoundTimerLoop();
        session.setGameOver(true);
        session.setPaused(false);
        boolean newHighScore = persistCurrentPlayerProgress();
        shakeScreen();
        flashScreen(Color.web("#ff3344"));
        if (newHighScore) {
            showHighScoreCelebration(text);
        } else {
            showMessage(text + " - Press Enter", Color.web("#ffd3d3"), null);
        }
        updateHud();
    }

    private void showHighScoreCelebration(String gameOverText) {
        messageText.setText("NEW HIGH SCORE!");
        messageText.setFill(Color.web("#ffd700"));
        messageText.setVisible(true);
        messageText.setOpacity(1.0);

        ScaleTransition pulse = new ScaleTransition(Duration.millis(300), messageText);
        pulse.setFromX(0.5);
        pulse.setFromY(0.5);
        pulse.setToX(1.2);
        pulse.setToY(1.2);
        pulse.setCycleCount(2);
        pulse.setAutoReverse(true);
        pulse.setOnFinished(e -> {
            messageText.setScaleX(1.0);
            messageText.setScaleY(1.0);
            showMessage(gameOverText + " - Press Enter", Color.web("#ffd3d3"), null);
        });
        pulse.play();

        flashScreen(Color.web("#ffd700"));
    }

    private void shakeScreen() {
        Timeline shake = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(boardPane.translateXProperty(), 0)),
                new KeyFrame(Duration.millis(50), new KeyValue(boardPane.translateXProperty(), -8)),
                new KeyFrame(Duration.millis(100), new KeyValue(boardPane.translateXProperty(), 8)),
                new KeyFrame(Duration.millis(150), new KeyValue(boardPane.translateXProperty(), -6)),
                new KeyFrame(Duration.millis(200), new KeyValue(boardPane.translateXProperty(), 6)),
                new KeyFrame(Duration.millis(250), new KeyValue(boardPane.translateXProperty(), -3)),
                new KeyFrame(Duration.millis(300), new KeyValue(boardPane.translateXProperty(), 0))
        );
        shake.play();
    }

    private void flashScreen(Color color) {
        Rectangle flash = new Rectangle(root.getWidth(), root.getHeight(), color);
        flash.setMouseTransparent(true);
        root.getChildren().add(flash);
        FadeTransition fade = new FadeTransition(Duration.millis(300), flash);
        fade.setFromValue(0.4);
        fade.setToValue(0.0);
        fade.setOnFinished(e -> root.getChildren().remove(flash));
        fade.play();
    }

    private void spawnMunchParticles(int col, int row) {
        double centerX = col * GameConfig.CELL_SIZE + GameConfig.CELL_SIZE / 2.0;
        double centerY = row * GameConfig.CELL_SIZE + GameConfig.CELL_SIZE / 2.0;
        Color[] colors = {Color.web("#4edb86"), Color.web("#7affb2"), Color.web("#fff176"), Color.web("#80ffea")};

        for (int i = 0; i < 8; i++) {
            double angle = (i / 8.0) * 2 * Math.PI;
            double distance = 30 + Math.random() * 20;
            double endX = centerX + Math.cos(angle) * distance;
            double endY = centerY + Math.sin(angle) * distance;

            javafx.scene.shape.Circle particle = new javafx.scene.shape.Circle(4, colors[i % colors.length]);
            particle.setCenterX(centerX);
            particle.setCenterY(centerY);
            boardPane.getChildren().add(particle);

            Timeline move = new Timeline(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(particle.centerXProperty(), centerX),
                            new KeyValue(particle.centerYProperty(), centerY),
                            new KeyValue(particle.opacityProperty(), 1.0),
                            new KeyValue(particle.scaleXProperty(), 1.0),
                            new KeyValue(particle.scaleYProperty(), 1.0)),
                    new KeyFrame(Duration.millis(350),
                            new KeyValue(particle.centerXProperty(), endX, Interpolator.EASE_OUT),
                            new KeyValue(particle.centerYProperty(), endY, Interpolator.EASE_OUT),
                            new KeyValue(particle.opacityProperty(), 0.0),
                            new KeyValue(particle.scaleXProperty(), 0.3),
                            new KeyValue(particle.scaleYProperty(), 0.3))
            );
            move.setOnFinished(e -> boardPane.getChildren().remove(particle));
            move.play();
        }
    }

    private void resetGame() {
        session.restartCurrentRound(seed);
        soundEngine.playGameplayMusic();
        startRound();
    }

    private void setPaused(boolean paused) {
        if (session.getGameState().isGameOver()) {
            return;
        }
        session.setPaused(paused);
        lastRoundTimerTickAt = System.currentTimeMillis();
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

    private void showGraceCountdown() {
        messageText.setText("GET READY!");
        messageText.setFill(Color.web("#7aefff"));
        messageText.setVisible(true);
        messageText.setOpacity(1.0);

        FadeTransition fade = new FadeTransition(Duration.millis(GameConfig.ROUND_START_GRACE_MILLIS - 200), messageText);
        fade.setFromValue(1.0);
        fade.setToValue(0.0);
        fade.setDelay(Duration.millis(200));
        fade.setOnFinished(e -> messageText.setVisible(false));
        fade.play();
    }

    private void updateHud() {
        GameState gameState = session.getGameState();
        int currentScore = gameState.getScore();
        scoreText.setText(String.format("%,d", currentScore));
        if (currentScore > lastDisplayedScore && lastDisplayedScore > 0) {
            pulseScore();
        }
        lastDisplayedScore = currentScore;
        livesText.setText("Lives: " + gameState.getLives() + (gameState.isPaused() ? " | Paused" : ""));
        roundText.setText("Round: " + gameState.getRound() + " | Targets Left: " + gameState.getEdibleRemaining());
        playerText.setText("Muncher: " + getSelectedPlayerName());
    }

    private void pulseScore() {
        ScaleTransition pulse = new ScaleTransition(Duration.millis(120), scoreText);
        pulse.setFromX(1.0);
        pulse.setFromY(1.0);
        pulse.setToX(1.25);
        pulse.setToY(1.25);
        pulse.setCycleCount(2);
        pulse.setAutoReverse(true);
        pulse.play();
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
            case "show-start-screen":
                showStartScreen();
                events.add("front_screen_shown");
                return okResponse(command, previous, events);
            case "reset":
                resetGame();
                events.add("round_reset");
                events.add("seed_reapplied");
                events.add("grace_period_active_until_" + enemyMovementEnabledAt);
                return okResponse(command, previous, events);
            case "front-next":
                moveFrontSelection(1);
                events.add("front_selection_advanced");
                return okResponse(command, previous, events);
            case "front-prev":
                moveFrontSelection(-1);
                events.add("front_selection_reversed");
                return okResponse(command, previous, events);
            case "front-confirm":
                confirmFrontSelection();
                events.add("front_selection_confirmed");
                return okResponse(command, previous, events);
            case "front-back":
                backFrontSelection();
                events.add("front_selection_backed_out");
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
                    session.reseed(seed);
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
                setPaused(!session.getGameState().isPaused());
                events.add("pause_toggled");
                return okResponse(command, previous, events);
            case "tick-enemies":
                int ticks = parseInt(params.getOrDefault("count", "1"), 1, 100);
                boolean pausedBefore = session.getGameState().isPaused();
                session.setPaused(false);
                for (int i = 0; i < ticks; i++) {
                    updateEnemies();
                    if (session.getGameState().isGameOver()) {
                        break;
                    }
                }
                session.setPaused(pausedBefore);
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
        GameState gameState = session.getGameState();
        StringBuilder boardJson = new StringBuilder();
        boardJson.append('[');
        BoardCell[][] board = session.getBoard();
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
        List<GridPoint> enemies = session.getEnemies();
        for (int i = 0; i < enemies.size(); i++) {
            GridPoint enemy = enemies.get(i);
            if (i > 0) {
                enemiesJson.append(',');
            }
            enemiesJson.append('{')
                    .append("\"row\":").append(enemy.row()).append(',')
                    .append("\"col\":").append(enemy.col())
                    .append('}');
        }
        enemiesJson.append(']');

        long now = lastStateTimestamp;
        GridPoint playerPosition = session.getPlayerPosition();
        boolean roundIntroActive = !startScreenActive && playerPosition != null && now < enemyMovementEnabledAt;
        boolean enemyAiActive = !startScreenActive && playerPosition != null && !gameState.isGameOver() && !gameState.isPaused() && !roundIntroActive;
        boolean acceptingInput = !startScreenActive && playerPosition != null && !gameState.isGameOver() && !gameState.isPaused();
        int playerRow = playerPosition == null ? -1 : playerPosition.row();
        int playerCol = playerPosition == null ? -1 : playerPosition.col();
        long effectiveRoundStartedAt = startScreenActive ? 0 : roundStartedAt;
        long effectiveEnemyMovementEnabledAt = startScreenActive ? 0 : enemyMovementEnabledAt;
        long millisecondsSinceRoundStart = effectiveRoundStartedAt == 0 ? 0 : Math.max(0, now - effectiveRoundStartedAt);
        long millisecondsUntilEnemyMovement = effectiveEnemyMovementEnabledAt == 0 ? 0 : Math.max(0, effectiveEnemyMovementEnabledAt - now);
        int effectiveRoundTimerPoints = startScreenActive ? 0 : roundTimerPoints;
        long effectiveRoundTimerElapsed = startScreenActive ? 0L : roundTimerElapsedMillis;
        double roundTimerFraction = startScreenActive ? 0.0 : Math.max(0.0, Math.min(1.0, 1.0 - ((double) roundTimerElapsedMillis / GameConfig.ROUND_TIMER_TOTAL_MILLIS)));

        return "{"
                + "\"title\":\"" + escapeJson("Number Munchers Deluxe") + "\","
                + "\"session_id\":\"" + escapeJson(sessionId) + "\","
                + "\"process_id\":" + ProcessHandle.current().pid() + ","
                + "\"started_at\":" + startedAt + ","
                + "\"build_version\":\"" + escapeJson(buildVersion) + "\","
                + "\"seed\":" + seed + ","
                + "\"debug_port\":" + debugPort + ","
                + "\"state_timestamp\":" + now + ","
                + "\"round_started_at\":" + effectiveRoundStartedAt + ","
                + "\"enemy_movement_enabled_at\":" + effectiveEnemyMovementEnabledAt + ","
                + "\"milliseconds_since_round_start\":" + millisecondsSinceRoundStart + ","
                + "\"milliseconds_until_enemy_movement\":" + millisecondsUntilEnemyMovement + ","
                + "\"round_timer_elapsed_millis\":" + effectiveRoundTimerElapsed + ","
                + "\"round_timer_points\":" + effectiveRoundTimerPoints + ","
                + "\"round_timer_fraction\":" + roundTimerFraction + ","
                + "\"score\":" + gameState.getScore() + ","
                + "\"lives\":" + gameState.getLives() + ","
                + "\"round\":" + gameState.getRound() + ","
                + "\"edible_remaining\":" + gameState.getEdibleRemaining() + ","
                + "\"start_screen_active\":" + startScreenActive + ","
                + "\"front_screen_mode\":\"" + frontScreenMode.name().toLowerCase() + "\","
                + "\"title_menu_selection\":\"" + escapeJson(titleMenuSelectionLabel()) + "\","
                + "\"selected_player\":\"" + escapeJson(getSelectedPlayerName()) + "\","
                + "\"game_over\":" + gameState.isGameOver() + ","
                + "\"paused\":" + gameState.isPaused() + ","
                + "\"accepting_input\":" + acceptingInput + ","
                + "\"window_focused\":" + isWindowFocused() + ","
                + "\"round_intro_active\":" + roundIntroActive + ","
                + "\"enemy_ai_active\":" + enemyAiActive + ","
                + "\"rule\":\"" + escapeJson(session.getCurrentRule() == null ? "" : session.getCurrentRule().getDescription()) + "\","
                + "\"player\":{\"row\":" + playerRow + ",\"col\":" + playerCol + "},"
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
        GameState gameState = session.getGameState();
        GridPoint playerPosition = session.getPlayerPosition();
        return new StateSummary(
                gameState.getScore(),
                gameState.getLives(),
                gameState.getRound(),
                gameState.getEdibleRemaining(),
                gameState.isPaused(),
                gameState.isGameOver(),
                playerPosition == null ? -1 : playerPosition.row(),
                playerPosition == null ? -1 : playerPosition.col()
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

    private void toggleFullScreen() {
        if (stage == null) {
            return;
        }
        boolean next = !stage.isFullScreen();
        stage.setFullScreen(next);
        if (!next) {
            stage.setMaximized(true);
        }
    }

    private String getSelectedPlayerName() {
        return PLAYER_OPTIONS.get(selectedPlayerIndex);
    }

    private boolean persistCurrentPlayerProgress() {
        GameState gameState = session.getGameState();
        int currentScore = gameState.getScore();
        int previousBest = progressStore.getProgress(getSelectedPlayerName()).topScore();
        progressStore.recordProgress(getSelectedPlayerName(), currentScore, gameState.getRound());
        return currentScore > previousBest && currentScore > 0;
    }

    private String playerProgressSummary(String playerName) {
        PlayerProgress progress = progressStore.getProgress(playerName);
        return playerName + "  ·  BEST: " + progress.topScore() + "  ·  MAX LVL: " + progress.maxLevelReached();
    }
    private String titleMenuSelectionLabel() {
        return switch (titleMenuIndex) {
            case 0 -> "START GAME";
            case 1 -> "PLAYER SELECT";
            default -> "QUIT";
        };
    }


    private enum FrontScreenMode {
        TITLE,
        PLAYER_SELECT
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
        stopRoundTimerLoop();
        if (frontScreenPulse != null) {
            frontScreenPulse.stop();
        }
        if (!startScreenActive) {
            persistCurrentPlayerProgress();
        }
        soundEngine.shutdown();
    }

    public static void main(String[] args) {
        launch(args);
    }
}












