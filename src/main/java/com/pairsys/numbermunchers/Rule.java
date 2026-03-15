package com.pairsys.numbermunchers;

import java.util.Random;

public final class Rule {
    private final String description;
    private final int multiple;
    private final int maxMultiplier;

    private Rule(String description, int multiple, int maxMultiplier) {
        this.description = description;
        this.multiple = multiple;
        this.maxMultiplier = maxMultiplier;
    }

    public static Rule forRound(int round) {
        int roundIndex = Math.max(0, round - 1);
        int multiple = 2 + (roundIndex % 9);
        int cycle = roundIndex / 9;
        int maxMultiplier = 10 * (cycle + 1);
        int maxValue = multiple * maxMultiplier;
        return new Rule("multiples of " + multiple + " up to " + maxValue, multiple, maxMultiplier);
    }

    public boolean matches(int value) {
        return value % multiple == 0 && value / multiple >= 1 && value / multiple <= maxMultiplier;
    }

    public String getDescription() {
        return description;
    }

    public int generateMatching(Random random) {
        return multiple * (1 + random.nextInt(maxMultiplier));
    }

    public int randomBoardValue(Random random) {
        return 1 + random.nextInt(maxValue());
    }

    public int maxValue() {
        return multiple * maxMultiplier;
    }

    public int getMultiple() {
        return multiple;
    }

    public int getMaxMultiplier() {
        return maxMultiplier;
    }
}
