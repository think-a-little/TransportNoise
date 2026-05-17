package org.example.transport_noise.service;

import org.example.transport_noise.model.DetectionEvent;

import java.util.List;

public final class TransportDetectionTuning {

    /** Значения по умолчанию (используются только если поля GUI пустые) */
    public static double defaultStaSec() { return 1.0; }
    public static double defaultLtaSec() { return 5.0; }
    public static double defaultThreshold() { return 2.0; }

    /** Вспомогательные методы для перевода секунд в отсчеты */
    public static int staSamples(int sampleRate, double staSec) {
        return Math.max(2, (int)(staSec * sampleRate));
    }

    public static int ltaSamples(int sampleRate, double staSec, double ltaSec) {
        int sta = staSamples(sampleRate, staSec);
        return Math.max(sta + 1, (int)(ltaSec * sampleRate));
    }

    public static ThreeComponentAnalyzer.DetectionParams params(int sampleRate,
                                                                double staSec,
                                                                double ltaSec,
                                                                double threshold) {
        int sta = staSamples(sampleRate, staSec);
        int lta = TransportDetectionTuning.ltaSamples(sampleRate, staSec, ltaSec);
        int hang = Math.max(8, sta * 3);
        int minDur = Math.max(sta, sta / 2);
        int cooldown = lta;
        double offRatio = 0.6;
        return new ThreeComponentAnalyzer.DetectionParams(threshold, offRatio, hang, minDur, cooldown);
    }

    private TransportDetectionTuning() {}
}