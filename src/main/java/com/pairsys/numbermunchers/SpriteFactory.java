package com.pairsys.numbermunchers;

import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;

public final class SpriteFactory {
    private SpriteFactory() {
    }

    public static Group createPlayerSprite() {
        Circle body = new Circle(0, 0, 22, Color.web("#ffe15a"));
        body.setStroke(Color.web("#8a651f"));
        body.setStrokeWidth(2.4);

        Circle leftEye = new Circle(-7, -5, 3.1, Color.BLACK);
        Circle rightEye = new Circle(7, -5, 3.1, Color.BLACK);
        Circle leftShine = new Circle(-8.2, -6.1, 1.0, Color.WHITE);
        Circle rightShine = new Circle(5.8, -6.1, 1.0, Color.WHITE);

        Arc mouth = new Arc(0, 5, 11, 9, 205, 130);
        mouth.setType(ArcType.OPEN);
        mouth.setStrokeWidth(3.0);
        mouth.setStroke(Color.web("#77322f"));
        mouth.setFill(Color.TRANSPARENT);

        Rectangle shoeLeft = new Rectangle(-13, 19, 10, 6);
        shoeLeft.setFill(Color.web("#c05742"));
        Rectangle shoeRight = new Rectangle(3, 19, 10, 6);
        shoeRight.setFill(Color.web("#c05742"));

        Group group = new Group(body, leftEye, rightEye, leftShine, rightShine, mouth, shoeLeft, shoeRight);
        group.setUserData("actor");
        return group;
    }

    public static Group createEnemySprite(int index) {
        Color[] tones = new Color[] {
                Color.web("#fe6b8b"), Color.web("#ff9f59"), Color.web("#8f8aff"),
                Color.web("#73d4ff"), Color.web("#f66dff"), Color.web("#6ae0a7"), Color.web("#ff7b7b")
        };
        Color tone = tones[index % tones.length];

        Circle body = new Circle(0, 0, 20, tone);
        body.setStroke(Color.web("#2a1f2f"));
        body.setStrokeWidth(2.1);

        Polygon spikes = new Polygon(
                -18.0, -8.0, -27.0, -1.0, -17.0, 4.0,
                -10.0, 20.0, -2.0, 9.0, 9.0, 23.0,
                13.0, 8.0, 24.0, 13.0, 19.0, 1.0,
                30.0, -7.0, 16.0, -8.0, 11.0, -21.0,
                2.0, -11.0, -9.0, -23.0
        );
        spikes.setFill(tone.deriveColor(0, 1, 0.85, 1));
        spikes.setOpacity(0.7);

        Circle leftEye = new Circle(-7, -3, 4, Color.WHITE);
        Circle rightEye = new Circle(7, -3, 4, Color.WHITE);
        Circle leftPupil = new Circle(-6.5, -2.4, 1.6, Color.BLACK);
        Circle rightPupil = new Circle(6.5, -2.4, 1.6, Color.BLACK);

        Arc grin = new Arc(0, 7, 9, 6, 195, 150);
        grin.setStroke(Color.web("#401823"));
        grin.setStrokeWidth(2.2);
        grin.setFill(Color.TRANSPARENT);

        Group group = new Group(spikes, body, leftEye, rightEye, leftPupil, rightPupil, grin);
        group.setUserData("actor");
        return group;
    }
}
