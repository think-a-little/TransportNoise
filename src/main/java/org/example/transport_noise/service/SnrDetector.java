package org.example.transport_noise.service;

import org.example.transport_noise.model.DetectionEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * SNR детектор (Signal-to-Noise Ratio).
 * Сравнивает энергию сигнала внутри STA-окна с энергией шума в крыльях LTA-окна.
 * Формула: SNR = A × СУММА(|сигнал| в STA) / СУММА(|шум| в крыльях LTA)
 * где A = (2×(N_LTA - N_STA)) / (2×N_STA + 1) — нормировочный коэффициент
 */
public class SnrDetector {

    private SnrDetector() {}

    /**
     * Вычисление отношения SNR для каждой точки сигнала.
     *
     * @param signal   входной сигнал
     * @param halfSta  полуширина STA-окна (отсчётов)
     * @param halfLta  полуширина LTA-окна (отсчётов)
     * @return массив значений SNR
     */
    public static double[] snrRatio(List<Double> signal, int halfSta, int halfLta) {
        int n = signal.size();
        double[] snr = new double[n];

        if (n < 2 * halfLta + 1 || halfSta < 1 || halfLta <= halfSta) {
            return snr;
        }

        double normA = (2.0 * (halfLta - halfSta)) / (2.0 * halfSta + 1);

        for (int i = halfLta; i < n - halfLta; i++) {
            double signalSum = 0;
            for (int j = i - halfSta; j <= i + halfSta; j++) {
                signalSum += Math.abs(signal.get(j));
            }

            double noiseSum = 0;
            for (int j = i - halfLta; j <= i - halfSta - 1; j++) {
                noiseSum += Math.abs(signal.get(j));
            }
            for (int j = i + halfSta + 1; j <= i + halfLta; j++) {
                noiseSum += Math.abs(signal.get(j));
            }

            snr[i] = noiseSum > 0 ? normA * signalSum / noiseSum : 0;
        }
        return snr;
    }

    /**
     * Обнаружение событий по превышению SNR порога.
     *
     * @param signal      входной сигнал
     * @param sampleRate  частота дискретизации, Гц
     * @param staSec      полуширина STA-окна, секунды
     * @param ltaSec      полуширина LTA-окна, секунды
     * @param threshold   порог SNR (например, 2.0 = сигнал в 2 раза сильнее шума)
     * @param label       метка для событий
     * @return список обнаруженных событий
     */
    public static List<DetectionEvent> detect(List<Double> signal, int sampleRate,
                                              double staSec, double ltaSec,
                                              double threshold, String label) {
        List<DetectionEvent> events = new ArrayList<>();

        int halfSta = Math.max(1, (int)(staSec * sampleRate / 2));
        int halfLta = Math.max(halfSta + 1, (int)(ltaSec * sampleRate / 2));

        if (signal.size() < 2 * halfLta + 1) {
            return events;
        }

        double[] snr = snrRatio(signal, halfSta, halfLta);

        boolean active = false;
        int startIdx = 0;
        int peakIdx = 0;
        double peakSnr = 0;
        int hangCount = 0;
        int hangSamples = halfSta * 2;
        double offRatio = 0.5;

        for (int i = halfLta; i < snr.length - halfLta; i++) {
            double r = snr[i];

            if (!active) {
                if (r > threshold) {
                    active = true;
                    startIdx = i;
                    peakIdx = i;
                    peakSnr = r;
                    hangCount = 0;
                }
            } else {
                if (r > peakSnr) {
                    peakSnr = r;
                    peakIdx = i;
                }
                if (r < threshold * offRatio) {
                    hangCount++;
                } else {
                    hangCount = 0;
                }
                if (hangCount >= hangSamples) {
                    int endIdx = i - hangSamples;
                    if (endIdx > startIdx) {
                        events.add(new DetectionEvent(startIdx, endIdx, peakIdx, label));
                    }
                    active = false;
                }
            }
        }

        if (active) {
            int endIdx = snr.length - halfLta - 1;
            if (endIdx > startIdx) {
                events.add(new DetectionEvent(startIdx, endIdx, peakIdx, label));
            }
        }

        return events;
    }

    /**
     * Выбор доминирующего события по максимальному пику SNR.
     */
    public static List<DetectionEvent> keepDominantEvent(List<DetectionEvent> events, double[] snr) {
        if (events == null || events.isEmpty()) return List.of();

        DetectionEvent best = events.get(0);
        double bestSnr = snrAtPeak(best, snr);

        for (int i = 1; i < events.size(); i++) {
            DetectionEvent e = events.get(i);
            double v = snrAtPeak(e, snr);
            if (v > bestSnr) {
                bestSnr = v;
                best = e;
            }
        }
        return List.of(best);
    }

    private static double snrAtPeak(DetectionEvent e, double[] snr) {
        int p = e.getPeakSample();
        if (snr == null || p < 0 || p >= snr.length) return -1;
        return snr[p];
    }
}