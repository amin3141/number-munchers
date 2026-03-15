package com.pairsys.numbermunchers;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;

public final class SoundEngine {
    private static final float SAMPLE_RATE = 44100f;
    private static final String TITLE_TRACK = "/audio/title-theme.mp3";
    private static final String GAMEPLAY_TRACK = "/audio/gameplay-theme.mp3";
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean introLoopRunning = new AtomicBoolean(false);
    private volatile Thread introThread;
    private MediaPlayer titleMusicPlayer;
    private MediaPlayer gameplayMusicPlayer;

    public void playChomp() {
        play(new double[] {740, 880, 690}, new int[] {35, 35, 45}, 0.18);
    }

    public void playError() {
        play(new double[] {210, 175, 130}, new int[] {65, 65, 85}, 0.22);
    }

    public void playCaught() {
        play(new double[] {320, 250, 170, 120}, new int[] {80, 80, 90, 140}, 0.26);
    }

    public void playRoundClear() {
        play(new double[] {510, 640, 770, 920}, new int[] {80, 80, 80, 140}, 0.22);
    }

    public void playTitleMusic() {
        stopGameplayMusic();
        MediaPlayer player = getOrCreateTitlePlayer();
        if (player != null) {
            stopIntroLoop();
            player.stop();
            player.play();
            return;
        }
        startIntroLoop();
    }

    public void playGameplayMusic() {
        stopBackgroundMusic();
    }

    public void stopBackgroundMusic() {
        stopIntroLoop();
        stopTitleMusic();
        stopGameplayMusic();
    }

    public void startIntroLoop() {
        if (!introLoopRunning.compareAndSet(false, true)) {
            return;
        }

        introThread = new Thread(this::playIntroLoop, "number-munchers-intro");
        introThread.setDaemon(true);
        introThread.start();
    }

    public void stopIntroLoop() {
        introLoopRunning.set(false);
        Thread thread = introThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void stopTitleMusic() {
        if (titleMusicPlayer != null) {
            titleMusicPlayer.stop();
        }
    }

    private void stopGameplayMusic() {
        if (gameplayMusicPlayer != null) {
            gameplayMusicPlayer.stop();
        }
    }

    private MediaPlayer getOrCreateTitlePlayer() {
        if (titleMusicPlayer == null) {
            titleMusicPlayer = createLoopingPlayer(TITLE_TRACK, 0.55);
        }
        return titleMusicPlayer;
    }

    private MediaPlayer getOrCreateGameplayPlayer() {
        if (gameplayMusicPlayer == null) {
            gameplayMusicPlayer = createLoopingPlayer(GAMEPLAY_TRACK, 0.38);
        }
        return gameplayMusicPlayer;
    }

    private MediaPlayer createLoopingPlayer(String resourcePath, double volume) {
        try {
            var resource = SoundEngine.class.getResource(resourcePath);
            if (resource == null) {
                return null;
            }

            MediaPlayer player = new MediaPlayer(new Media(resource.toExternalForm()));
            player.setCycleCount(MediaPlayer.INDEFINITE);
            player.setVolume(volume);
            return player;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void play(double[] frequencies, int[] durationsMs, double volume) {
        audioExecutor.submit(() -> synthesize(frequencies, durationsMs, volume));
    }

    private void playIntroLoop() {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
            line.open(format);
            line.start();

            while (introLoopRunning.get()) {
                writePhrase(line, new double[] {392, 523.25, 784, 659.25}, new int[] {170, 170, 160, 220}, 0.13);
                writePhrase(line, new double[] {349.23, 440, 698.46, 587.33}, new int[] {170, 170, 160, 220}, 0.12);
                writePhrase(line, new double[] {392, 493.88, 587.33, 783.99}, new int[] {140, 140, 150, 260}, 0.13);
                writePhrase(line, new double[] {523.25, 493.88, 440, 392}, new int[] {140, 140, 180, 260}, 0.12);
                writePhrase(line, new double[] {784, 698.46, 659.25, 587.33, 523.25}, new int[] {110, 110, 110, 140, 220}, 0.11);
                writeSilence(line, 120);
            }

            line.drain();
        } catch (Exception ignored) {
            // Best-effort audio only.
        } finally {
            introLoopRunning.set(false);
            introThread = null;
        }
    }

    private void synthesize(double[] frequencies, int[] durationsMs, double volume) {
        byte[] buffer = synthesizeBuffer(frequencies, durationsMs, volume);
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
            line.open(format, buffer.length);
            line.start();
            line.write(buffer, 0, buffer.length);
            line.drain();
        } catch (Exception ignored) {
            // Best-effort audio only.
        }
    }

    private byte[] synthesizeBuffer(double[] frequencies, int[] durationsMs, double volume) {
        int totalSamples = 0;
        for (int ms : durationsMs) {
            totalSamples += (int) ((ms / 1000.0) * SAMPLE_RATE);
        }

        byte[] buffer = new byte[totalSamples * 2];
        int cursor = 0;

        for (int i = 0; i < frequencies.length; i++) {
            int samples = (int) ((durationsMs[i] / 1000.0) * SAMPLE_RATE);
            double freq = frequencies[i];
            for (int s = 0; s < samples; s++) {
                double t = s / SAMPLE_RATE;
                double envelope = Math.min(1.0, s / (SAMPLE_RATE * 0.01)) * Math.max(0.0, 1.0 - (double) s / samples);
                double wave = Math.sin(2 * Math.PI * freq * t) + 0.32 * Math.sin(2 * Math.PI * (freq * 0.5) * t);
                short sample = (short) (wave * envelope * volume * Short.MAX_VALUE);

                buffer[cursor++] = (byte) (sample & 0xFF);
                buffer[cursor++] = (byte) ((sample >> 8) & 0xFF);
            }
        }
        return buffer;
    }

    private void writePhrase(SourceDataLine line, double[] frequencies, int[] durationsMs, double volume) {
        byte[] buffer = synthesizeBuffer(frequencies, durationsMs, volume);
        line.write(buffer, 0, buffer.length);
    }

    private void writeSilence(SourceDataLine line, int durationMs) {
        int samples = (int) ((durationMs / 1000.0) * SAMPLE_RATE);
        byte[] silence = new byte[samples * 2];
        line.write(silence, 0, silence.length);
    }

    public void shutdown() {
        stopBackgroundMusic();
        if (titleMusicPlayer != null) {
            titleMusicPlayer.dispose();
        }
        if (gameplayMusicPlayer != null) {
            gameplayMusicPlayer.dispose();
        }
        audioExecutor.shutdownNow();
    }
}
