package com.pairsys.numbermunchers;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

public final class SoundEngine {
    private static final float SAMPLE_RATE = 44100f;
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();

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

    private void play(double[] frequencies, int[] durationsMs, double volume) {
        audioExecutor.submit(() -> synthesize(frequencies, durationsMs, volume));
    }

    private void synthesize(double[] frequencies, int[] durationsMs, double volume) {
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

    public void shutdown() {
        audioExecutor.shutdownNow();
    }
}
