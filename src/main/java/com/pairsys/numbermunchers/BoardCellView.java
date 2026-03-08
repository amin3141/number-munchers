package com.pairsys.numbermunchers;

import java.util.Random;
import javafx.scene.Group;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

public final class BoardCellView {
    private final StackPane root;
    private final Rectangle backing;
    private final Group textureLayer;
    private final Text numberText;
    private final Font cellFont;
    private final Font valueFont;

    public BoardCellView(int row, int col, Font cellFont, Font valueFont) {
        this.cellFont = cellFont;
        this.valueFont = valueFont;

        backing = new Rectangle(
                GameConfig.CELL_SIZE - (GameConfig.CELL_INSET * 2),
                GameConfig.CELL_SIZE - (GameConfig.CELL_INSET * 2)
        );
        backing.setArcWidth(14);
        backing.setArcHeight(14);
        backing.setStrokeWidth(1.8);

        textureLayer = createTextureLayer(
                GameConfig.CELL_SIZE - (GameConfig.CELL_INSET * 2),
                GameConfig.CELL_SIZE - (GameConfig.CELL_INSET * 2),
                row,
                col
        );

        numberText = new Text("1");
        numberText.setFont(cellFont);

        root = new StackPane(backing, textureLayer, numberText);
        root.setLayoutX(col * GameConfig.CELL_SIZE + GameConfig.CELL_INSET);
        root.setLayoutY(row * GameConfig.CELL_SIZE + GameConfig.CELL_INSET);
    }

    public StackPane getRoot() {
        return root;
    }

    public void refresh(BoardCell cell) {
        if (cell.isMunched() && cell.isEdible()) {
            backing.setFill(Color.web("#114d2f"));
            backing.setStroke(Color.web("#4de08b"));
            numberText.setFill(Color.web("#cbffd7"));
            numberText.setText("\u2713");
            numberText.setFont(valueFont);
            textureLayer.setOpacity(0.08);
            return;
        }

        if (cell.isMunched()) {
            backing.setFill(Color.web("#4a1e29"));
            backing.setStroke(Color.web("#ef6d76"));
            numberText.setFill(Color.web("#ffd7d9"));
            numberText.setText("X");
            numberText.setFont(valueFont);
            textureLayer.setOpacity(0.08);
            return;
        }

        backing.setFill(Color.web("#1b223c"));
        backing.setStroke(Color.web("#54628a"));
        numberText.setFill(Color.web("#d3d7ef"));
        numberText.setFont(cellFont);
        numberText.setText(Integer.toString(cell.getValue()));
        textureLayer.setOpacity(0.22);
    }

    private Group createTextureLayer(double width, double height, int row, int col) {
        Group layer = new Group();
        Random random = new Random((long) row * 997 + col * 131 + 73);

        for (int i = 0; i < 9; i++) {
            double x = 4 + random.nextDouble() * (width - 8);
            double y = 4 + random.nextDouble() * (height - 8);
            double r = 0.7 + random.nextDouble() * 1.3;
            Circle speck = new Circle(x, y, r, Color.web("#ffffff"));
            speck.setOpacity(0.18);
            layer.getChildren().add(speck);
        }

        for (int i = 0; i < 4; i++) {
            double y1 = 8 + i * (height - 16) / 3.0;
            Line line = new Line(6, y1, width - 6, y1 + random.nextDouble() * 3 - 1.5);
            line.setStroke(Color.web("#ffffff"));
            line.setOpacity(0.09);
            line.setStrokeWidth(0.9);
            layer.getChildren().add(line);
        }

        return layer;
    }
}
