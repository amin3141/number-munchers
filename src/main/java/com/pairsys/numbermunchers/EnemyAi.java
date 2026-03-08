package com.pairsys.numbermunchers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

public final class EnemyAi {
    private EnemyAi() {
    }

    public static int[] bestMove(EnemyActor enemy, PlayerActor player, int rows, int cols, int round, Random random) {
        List<int[]> candidates = new ArrayList<>();
        candidates.add(new int[] {0, 0});
        candidates.add(new int[] {-1, 0});
        candidates.add(new int[] {1, 0});
        candidates.add(new int[] {0, -1});
        candidates.add(new int[] {0, 1});

        return candidates.stream()
                .filter(move -> inBounds(enemy.getRow() + move[0], enemy.getCol() + move[1], rows, cols))
                .min(Comparator.comparingDouble(move -> {
                    int nextRow = enemy.getRow() + move[0];
                    int nextCol = enemy.getCol() + move[1];
                    int dist = Math.abs(nextRow - player.getRow()) + Math.abs(nextCol - player.getCol());
                    double noise = random.nextDouble() * (round < 4 ? 1.4 : 0.7);
                    return dist + noise;
                }))
                .orElse(new int[] {0, 0});
    }

    private static boolean inBounds(int row, int col, int rows, int cols) {
        return row >= 0 && row < rows && col >= 0 && col < cols;
    }
}
