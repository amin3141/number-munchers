package com.pairsys.numbermunchers;

import java.util.Comparator;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

public final class PlayerProgressStore {
    private static final String SCORE_SUFFIX = ".topScore";
    private static final String LEVEL_SUFFIX = ".maxLevelReached";

    private final Preferences preferences = Preferences.userRoot().node("com/pairsys/numbermunchers/playerProgress");

    public PlayerProgress getProgress(String playerName) {
        String key = keyFor(playerName);
        int topScore = preferences.getInt(key + SCORE_SUFFIX, 0);
        int maxLevelReached = preferences.getInt(key + LEVEL_SUFFIX, 0);
        return new PlayerProgress(playerName, topScore, maxLevelReached);
    }

    public void recordProgress(String playerName, int score, int maxLevelReached) {
        PlayerProgress current = getProgress(playerName);
        preferences.putInt(keyFor(playerName) + SCORE_SUFFIX, Math.max(current.topScore(), score));
        preferences.putInt(keyFor(playerName) + LEVEL_SUFFIX, Math.max(current.maxLevelReached(), maxLevelReached));
        try {
            preferences.flush();
        } catch (BackingStoreException ignored) {
            // Best effort
        }
    }

    public List<PlayerProgress> leaderboard(List<String> playerNames, int limit) {
        return playerNames.stream()
                .map(this::getProgress)
                .sorted((a, b) -> {
                    // Sort by score descending
                    int scoreCompare = Integer.compare(b.topScore(), a.topScore());
                    if (scoreCompare != 0) return scoreCompare;
                    // Then by level descending
                    int levelCompare = Integer.compare(b.maxLevelReached(), a.maxLevelReached());
                    if (levelCompare != 0) return levelCompare;
                    // Then by name ascending
                    return a.playerName().compareTo(b.playerName());
                })
                .limit(limit)
                .collect(Collectors.toList());
    }

    private String keyFor(String playerName) {
        return playerName.toLowerCase().replaceAll("[^a-z0-9]+", "_");
    }
}
