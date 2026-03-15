package com.pairsys.numbermunchers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public final class GameSession {
    private final int rows;
    private final int cols;
    private final BoardCell[][] board;
    private final GameState gameState = new GameState();
    private final List<GridPoint> enemies = new ArrayList<>();

    private Random random = new Random();
    private Rule currentRule;
    private LevelPlan currentLevelPlan;
    private GridPoint playerPosition;

    public GameSession(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        this.board = new BoardCell[rows][cols];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                board[row][col] = new BoardCell(row, col, 1);
            }
        }
        clearBoard();
    }

    public void reset(long seed) {
        random = new Random(seed);
        gameState.reset();
        currentRule = null;
        currentLevelPlan = null;
        playerPosition = null;
        enemies.clear();
        clearBoard();
    }

    public void reseed(long seed) {
        random = new Random(seed);
    }

    public LevelPlan startRound() {
        currentLevelPlan = LevelPlanner.planForRound(gameState.getRound(), random);
        currentRule = currentLevelPlan.getRule();
        gameState.setGameOver(false);
        gameState.setPaused(false);
        playerPosition = null;
        enemies.clear();

        int edibleCount = populateBoard();
        if (edibleCount > currentLevelPlan.getTargetCellCount()) {
            edibleCount = trimEdibleCells(edibleCount, currentLevelPlan.getTargetCellCount());
        } else {
            edibleCount = growEdibleCells(edibleCount, currentLevelPlan.getTargetCellCount());
        }
        gameState.setEdibleRemaining(edibleCount);

        playerPosition = new GridPoint(random.nextInt(rows), random.nextInt(cols));
        spawnEnemies(currentLevelPlan.getEnemyCount());
        return currentLevelPlan;
    }

    public MoveResult movePlayer(int dRow, int dCol) {
        if (playerPosition == null || gameState.isPaused() || gameState.isGameOver()) {
            return MoveResult.ignored(playerPosition, false);
        }

        int nextRow = playerPosition.row() + dRow;
        int nextCol = playerPosition.col() + dCol;
        if (!inBounds(nextRow, nextCol)) {
            return MoveResult.ignored(playerPosition, false);
        }

        playerPosition = new GridPoint(nextRow, nextCol);
        return new MoveResult(true, playerPosition, hasCollision());
    }

    public MunchResult munchCurrentCell() {
        if (playerPosition == null || gameState.isPaused() || gameState.isGameOver()) {
            return new MunchResult(MunchResultType.IGNORED, -1, -1);
        }

        BoardCell cell = getCell(playerPosition.row(), playerPosition.col());
        if (cell.isMunched()) {
            return new MunchResult(MunchResultType.ALREADY_MUNCHED, playerPosition.row(), playerPosition.col());
        }

        cell.setMunched(true);
        if (cell.isEdible()) {
            gameState.onCorrectMunch();
            if (gameState.getEdibleRemaining() <= 0) {
                return new MunchResult(MunchResultType.ROUND_CLEARED, playerPosition.row(), playerPosition.col());
            }
            return new MunchResult(MunchResultType.CORRECT, playerPosition.row(), playerPosition.col());
        }

        gameState.onWrongMunch();
        if (gameState.getLives() <= 0) {
            gameState.setGameOver(true);
            gameState.setPaused(false);
            return new MunchResult(MunchResultType.OUT_OF_LIVES, playerPosition.row(), playerPosition.col());
        }
        return new MunchResult(MunchResultType.WRONG, playerPosition.row(), playerPosition.col());
    }

    public boolean updateEnemies() {
        if (playerPosition == null || gameState.isPaused() || gameState.isGameOver()) {
            return false;
        }

        for (int i = 0; i < enemies.size(); i++) {
            GridPoint enemy = enemies.get(i);
            int[] move = EnemyAi.bestMove(
                    enemy.row(),
                    enemy.col(),
                    playerPosition.row(),
                    playerPosition.col(),
                    rows,
                    cols,
                    gameState.getRound(),
                    random
            );
            enemies.set(i, new GridPoint(enemy.row() + move[0], enemy.col() + move[1]));
        }

        return hasCollision();
    }

    public void advanceRound() {
        gameState.advanceRoundWithBonus();
    }

    public void setPaused(boolean paused) {
        gameState.setPaused(paused);
    }

    public void setGameOver(boolean gameOver) {
        gameState.setGameOver(gameOver);
    }

    public BoardCell[][] getBoard() {
        return board;
    }

    public BoardCell getCell(int row, int col) {
        return board[row][col];
    }

    public GameState getGameState() {
        return gameState;
    }

    public Rule getCurrentRule() {
        return currentRule;
    }

    public LevelPlan getCurrentLevelPlan() {
        return currentLevelPlan;
    }

    public GridPoint getPlayerPosition() {
        return playerPosition;
    }

    public List<GridPoint> getEnemies() {
        return List.copyOf(enemies);
    }

    public boolean hasCollision() {
        if (playerPosition == null) {
            return false;
        }
        return enemies.stream().anyMatch(enemy -> enemy.row() == playerPosition.row() && enemy.col() == playerPosition.col());
    }

    private int populateBoard() {
        int edibleCount = 0;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                BoardCell cell = board[row][col];
                int value = currentRule.randomBoardValue(random);
                cell.setValue(value);
                cell.setMunched(false);
                cell.setEdible(currentRule.matches(value));
                if (cell.isEdible()) {
                    edibleCount++;
                }
            }
        }
        return edibleCount;
    }

    private int trimEdibleCells(int current, int target) {
        while (current > target) {
            int row = random.nextInt(rows);
            int col = random.nextInt(cols);
            BoardCell cell = board[row][col];
            if (!cell.isEdible()) {
                continue;
            }

            int value;
            int tries = 0;
            do {
                value = currentRule.randomBoardValue(random);
                tries++;
            } while (currentRule.matches(value) && tries < 400);

            if (!currentRule.matches(value)) {
                cell.setValue(value);
                cell.setEdible(false);
                current--;
            }
        }
        return current;
    }

    private int growEdibleCells(int current, int target) {
        while (current < target) {
            int row = random.nextInt(rows);
            int col = random.nextInt(cols);
            BoardCell cell = board[row][col];
            if (cell.isEdible()) {
                continue;
            }
            cell.setValue(currentRule.generateMatching(random));
            cell.setEdible(true);
            current++;
        }
        return current;
    }

    private void spawnEnemies(int enemyCount) {
        Set<Integer> occupied = new HashSet<>();
        occupied.add(playerPosition.row() * cols + playerPosition.col());

        while (enemies.size() < enemyCount) {
            int row = random.nextInt(rows);
            int col = random.nextInt(cols);
            int key = row * cols + col;
            if (occupied.contains(key)) {
                continue;
            }
            occupied.add(key);
            enemies.add(new GridPoint(row, col));
        }
    }

    private void clearBoard() {
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                BoardCell cell = board[row][col];
                cell.setValue(1);
                cell.setEdible(false);
                cell.setMunched(false);
            }
        }
    }

    private boolean inBounds(int row, int col) {
        return row >= 0 && row < rows && col >= 0 && col < cols;
    }

    public enum MunchResultType {
        IGNORED,
        ALREADY_MUNCHED,
        CORRECT,
        WRONG,
        ROUND_CLEARED,
        OUT_OF_LIVES
    }

    public record MoveResult(boolean moved, GridPoint position, boolean collision) {
        private static MoveResult ignored(GridPoint position, boolean collision) {
            return new MoveResult(false, position, collision);
        }
    }

    public record MunchResult(MunchResultType type, int row, int col) {
    }
}
