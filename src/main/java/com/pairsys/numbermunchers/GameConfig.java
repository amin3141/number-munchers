package com.pairsys.numbermunchers;

public final class GameConfig {
    public static final int COLS = 7;
    public static final int ROWS = 6;
    public static final int CELL_SIZE = 100;
    public static final int CELL_INSET = 6;
    public static final int ROUND_START_GRACE_MILLIS = 1800;
    public static final int WIDTH = COLS * CELL_SIZE + 240;
    public static final int HEIGHT = ROWS * CELL_SIZE + 260;
    public static final int ENEMY_COUNT = 2;
    public static final int MAX_ENEMIES = 7;
    public static final int MIN_TARGET_CELLS = 6;
    public static final int MAX_TARGET_CELLS = 8;
    public static final int ROUND_TIMER_START_POINTS = 2000;
    public static final int ROUND_TIMER_DECREMENT = 100;
    public static final int ROUND_TIMER_STEP_MILLIS = 4000;
    public static final int ROUND_TIMER_TOTAL_MILLIS = 80000;

    private GameConfig() {
    }

    public static int enemyTickMillis(int round) {
        return Math.max(1240, 1960 - round * 56);
    }
}

