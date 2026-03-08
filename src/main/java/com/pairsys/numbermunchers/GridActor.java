package com.pairsys.numbermunchers;

import javafx.scene.Group;

public abstract class GridActor {
    private int row;
    private int col;
    private final Group sprite;

    protected GridActor(int row, int col, Group sprite) {
        this.row = row;
        this.col = col;
        this.sprite = sprite;
    }

    public int getRow() {
        return row;
    }

    public int getCol() {
        return col;
    }

    public Group getSprite() {
        return sprite;
    }

    public void setPosition(int row, int col) {
        this.row = row;
        this.col = col;
    }

    public void moveBy(int dRow, int dCol) {
        this.row += dRow;
        this.col += dCol;
    }

    public void placeInstant() {
        sprite.setLayoutX(col * GameConfig.CELL_SIZE + GameConfig.CELL_SIZE / 2.0);
        sprite.setLayoutY(row * GameConfig.CELL_SIZE + GameConfig.CELL_SIZE / 2.0);
    }
}
