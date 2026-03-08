package com.pairsys.numbermunchers;

public final class GameConfig {
    public static final int COLS = 8;
    public static final int ROWS = 6;
    public static final int CELL_SIZE = 78;
    public static final int CELL_INSET = 6;
    public static final int ROUND_START_GRACE_MILLIS = 1800;
    public static final int WIDTH = COLS * CELL_SIZE + 180;
    public static final int HEIGHT = ROWS * CELL_SIZE + 220;
    public static final int MAX_ENEMIES = 7;
    public static final int MIN_TARGET_CELLS = 6;
    public static final int MAX_TARGET_CELLS = 8;

    private GameConfig() {
    }

    public static int enemyTickMillis(int round) {
        return Math.max(1240, 1960 - round * 56);
    }
}
