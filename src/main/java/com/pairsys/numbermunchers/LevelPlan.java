package com.pairsys.numbermunchers;

public final class LevelPlan {
    private final int round;
    private final Rule rule;
    private final int targetCellCount;
    private final int enemyCount;

    public LevelPlan(int round, Rule rule, int targetCellCount, int enemyCount) {
        this.round = round;
        this.rule = rule;
        this.targetCellCount = targetCellCount;
        this.enemyCount = enemyCount;
    }

    public int getRound() {
        return round;
    }

    public Rule getRule() {
        return rule;
    }

    public int getTargetCellCount() {
        return targetCellCount;
    }

    public int getEnemyCount() {
        return enemyCount;
    }
}
