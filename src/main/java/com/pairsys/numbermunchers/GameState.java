package com.pairsys.numbermunchers;

public final class GameState {
    private int score = 0;
    private int lives = 4;
    private int round = 1;
    private int edibleRemaining = 0;
    private boolean gameOver = false;
    private boolean paused = false;

    public int getScore() {
        return score;
    }

    public int getLives() {
        return lives;
    }

    public int getRound() {
        return round;
    }

    public int getEdibleRemaining() {
        return edibleRemaining;
    }

    public void setEdibleRemaining(int edibleRemaining) {
        this.edibleRemaining = edibleRemaining;
    }

    public boolean isGameOver() {
        return gameOver;
    }

    public void setGameOver(boolean gameOver) {
        this.gameOver = gameOver;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public void onCorrectMunch() {
        edibleRemaining--;
        score += 200;
    }

    public void onWrongMunch() {
        lives--;
        score = Math.max(0, score - 35);
    }

    public void advanceRoundWithBonus() {
        round++;
    }

    public void addScore(int points) {
        score += Math.max(0, points);
    }

    public void reset() {
        score = 0;
        lives = 4;
        round = 1;
        edibleRemaining = 0;
        gameOver = false;
        paused = false;
    }

    public void restartCurrentRound() {
        score = 0;
        lives = 4;
        // Keep the current round - don't reset to 1
        edibleRemaining = 0;
        gameOver = false;
        paused = false;
    }
}
