package com.pairsys.numbermunchers;

import javafx.scene.Group;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;

public final class SpriteFactory {
    private static final double PLAYER_SCALE = 4.8;
    private static final double ENEMY_SCALE = 4.2;
    private static final int FRAME_SIZE = 16;
    private static final Map<String, Image> IMAGE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String> PLAYER_SHEETS = Map.ofEntries(
            Map.entry("Hamza", "/sprites/players/Blue_16x16RetroCharacterMidTone.png"),
            Map.entry("Yusef", "/sprites/players/Green_16x16RetroCharacterMidTone.png"),
            Map.entry("Mohammed", "/sprites/players/Orange_16x16RetroCharacterMidTone.png"),
            Map.entry("Abeera", "/sprites/players/Lavender_16x16RetroCharacterMidTone.png"),
            Map.entry("Amina", "/sprites/players/Coral_16x16RetroCharacterMidTone.png"),
            Map.entry("Mariam", "/sprites/players/Teal_16x16RetroCharacterMidTone.png"),
            Map.entry("Mustafa", "/sprites/players/Red_16x16RetroCharacterMidTone.png"),
            Map.entry("Zahra", "/sprites/players/Purple_16x16RetroCharacterMidTone.png"),
            Map.entry("Zhaley", "/sprites/players/Yellow_16x16RetroCharacterMidTone.png"),
            Map.entry("Palwasha", "/sprites/players/White_16x16RetroCharacterMidTone.png"),
            Map.entry("Zarghuna", "/sprites/players/Brown_16x16RetroCharacterMidTone.png")
    );
    private static final List<String> ENEMY_STRIPS = List.of(
            "/sprites/enemies/BlueCat.png",
            "/sprites/enemies/CoralCat.png",
            "/sprites/enemies/GreenCat.png",
            "/sprites/enemies/PurpleCat.png",
            "/sprites/enemies/RedCat.png",
            "/sprites/enemies/TealCat.png",
            "/sprites/enemies/YellowCat.png"
    );

    private SpriteFactory() {
    }

    public static Group createPlayerSprite(String playerName) {
        String resource = PLAYER_SHEETS.getOrDefault(playerName, PLAYER_SHEETS.get("Hamza"));
        ImageView sprite = createSheetFrame(resource, 0, 0, PLAYER_SCALE);
        Circle glow = new Circle(0, 0, 24, Color.web("#ffffff22"));
        Group group = new Group(glow, sprite);
        playTwoFrameAnimation(group, sprite, resource, 0, 0, 1, 430);
        group.setUserData("actor");
        return group;
    }

    public static Group createEnemySprite(int index) {
        String resource = ENEMY_STRIPS.get(index % ENEMY_STRIPS.size());
        ImageView sprite = createStripFrame(resource, 0, ENEMY_SCALE);
        Circle shadow = new Circle(0, 0, 22, Color.web("#00000022"));
        Group group = new Group(shadow, sprite);
        playStripAnimation(group, sprite, resource, 7, 120 + index * 12L);
        group.setUserData("actor");
        return group;
    }

    private static ImageView createSheetFrame(String resourcePath, int row, int col, double scale) {
        ImageView view = baseImageView(loadImage(resourcePath), scale);
        view.setViewport(new Rectangle2D(col * FRAME_SIZE, row * FRAME_SIZE, FRAME_SIZE, FRAME_SIZE));
        return view;
    }

    private static ImageView createStripFrame(String resourcePath, int frame, double scale) {
        ImageView view = baseImageView(loadImage(resourcePath), scale);
        view.setViewport(new Rectangle2D(frame * FRAME_SIZE, 0, FRAME_SIZE, FRAME_SIZE));
        return view;
    }

    private static ImageView baseImageView(Image image, double scale) {
        ImageView view = new ImageView(image);
        double size = FRAME_SIZE * scale;
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setPreserveRatio(false);
        view.setSmooth(false);
        view.setLayoutX(-size / 2.0);
        view.setLayoutY(-size / 2.0);
        return view;
    }

    private static Image loadImage(String resourcePath) {
        return IMAGE_CACHE.computeIfAbsent(resourcePath, path -> new Image(SpriteFactory.class.getResource(path).toExternalForm()));
    }

    private static void playTwoFrameAnimation(Group group, ImageView sprite, String resourcePath, int row, int colA, int colB, long durationMillis) {
        Timeline timeline = new Timeline(
                new KeyFrame(Duration.ZERO, e -> sprite.setViewport(new Rectangle2D(colA * FRAME_SIZE, row * FRAME_SIZE, FRAME_SIZE, FRAME_SIZE))),
                new KeyFrame(Duration.millis(durationMillis), e -> sprite.setViewport(new Rectangle2D(colB * FRAME_SIZE, row * FRAME_SIZE, FRAME_SIZE, FRAME_SIZE)))
        );
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.setAutoReverse(true);
        timeline.play();
        group.getProperties().put("animation", timeline);
        group.getProperties().put("sheet", resourcePath);
    }

    private static void playStripAnimation(Group group, ImageView sprite, String resourcePath, int frameCount, long frameDurationMillis) {
        Timeline timeline = new Timeline();
        for (int i = 0; i < frameCount; i++) {
            final int frame = i;
            timeline.getKeyFrames().add(
                    new KeyFrame(Duration.millis(frameDurationMillis * i),
                            e -> sprite.setViewport(new Rectangle2D(frame * FRAME_SIZE, 0, FRAME_SIZE, FRAME_SIZE)))
            );
        }
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
        group.getProperties().put("animation", timeline);
        group.getProperties().put("sheet", resourcePath);
    }
}
