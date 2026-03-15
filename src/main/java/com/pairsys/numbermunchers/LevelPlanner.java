package com.pairsys.numbermunchers;

import java.util.Random;

public final class LevelPlanner {
    private LevelPlanner() {
    }

    public static LevelPlan planForRound(int round, Random random) {
        Rule rule = Rule.forRound(round);
        int targetCellCount = GameConfig.MIN_TARGET_CELLS
                + random.nextInt(GameConfig.MAX_TARGET_CELLS - GameConfig.MIN_TARGET_CELLS + 1);
        return new LevelPlan(round, rule, targetCellCount, GameConfig.ENEMY_COUNT);
    }
}
