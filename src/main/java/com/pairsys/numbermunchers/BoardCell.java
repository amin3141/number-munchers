package com.pairsys.numbermunchers;

public final class BoardCell {
    private final int row;
    private final int col;
    private int value;
    private boolean edible;
    private boolean munched;

    public BoardCell(int row, int col, int value) {
        this.row = row;
        this.col = col;
        this.value = value;
    }

    public int getRow() {
        return row;
    }

    public int getCol() {
        return col;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }

    public boolean isEdible() {
        return edible;
    }

    public void setEdible(boolean edible) {
        this.edible = edible;
    }

    public boolean isMunched() {
        return munched;
    }

    public void setMunched(boolean munched) {
        this.munched = munched;
    }
}
