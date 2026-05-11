package org.example.transport_noise.service;

import org.example.transport_noise.model.DetectionEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * STA/LTA и энергетический STA/LTA (квадрат сигнала) с выделением интервала [начало, конец].
 */
public final class StaLtaEventDetector {

    private StaLtaEventDetector() {
    }

    /**
     * Отношение STA/LTA по модулю отсчётов (как классический детектор вибраций).
     */
    public static double[] absStaLtaRatio(List<Double> signal, int staWindow, int ltaWindow) {
        int n = signal.size();
        double[] ratio = new double[n];
        if (n == 0 || ltaWindow < 1 || staWindow < 1) {
            return ratio;
        }
        for (int i = ltaWindow; i < n; i++) {
            ratio[i] = ratioAt(signal, i, staWindow, ltaWindow, false);
        }
        return ratio;
    }

    /**
     * Отношение STA/LTA по кратковременной энергии (среднее квадрата).
     */
    public static double[] energyStaLtaRatio(List<Double> signal, int staWindow, int ltaWindow) {
        int n = signal.size();
        double[] ratio = new double[n];
        if (n == 0 || ltaWindow < 1 || staWindow < 1) {
            return ratio;
        }
        for (int i = ltaWindow; i < n; i++) {
            ratio[i] = ratioAt(signal, i, staWindow, ltaWindow, true);
        }
        return ratio;
    }

    /**
     * «Консервативное» трёхкомпонентное слияние: в каждой точке минимум из трёх отношений STA/LTA.
     */
    public static double[] minStaLtaRatio(List<Double> x, List<Double> y, List<Double> z,
                                          int staWindow, int ltaWindow) {
        int n = Math.min(Math.min(x.size(), y.size()), z.size());
        double[] rx = absStaLtaRatio(x.subList(0, n), staWindow, ltaWindow);
        double[] ry = absStaLtaRatio(y.subList(0, n), staWindow, ltaWindow);
        double[] rz = absStaLtaRatio(z.subList(0, n), staWindow, ltaWindow);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = Math.min(rx[i], Math.min(ry[i], rz[i]));
        }
        return out;
    }

    /**
     * «Чувствительное» слияние: максимум из трёх отношений.
     */
    public static double[] maxStaLtaRatio(List<Double> x, List<Double> y, List<Double> z,
                                         int staWindow, int ltaWindow) {
        int n = Math.min(Math.min(x.size(), y.size()), z.size());
        double[] rx = absStaLtaRatio(x.subList(0, n), staWindow, ltaWindow);
        double[] ry = absStaLtaRatio(y.subList(0, n), staWindow, ltaWindow);
        double[] rz = absStaLtaRatio(z.subList(0, n), staWindow, ltaWindow);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = Math.max(rx[i], Math.max(ry[i], rz[i]));
        }
        return out;
    }

    /**
     * Пороговое выделение событий по предвычисленному отношению STA/LTA.
     *
     * @param offRatio        относительный порог отключения (0..1) от основного threshold
     * @param hangSamples     сколько подряд отсчётов ниже порога отключения нужно для конца события
     * @param minDuration     минимальная длина события в отсчётах (короткие отбрасываются)
     * @param cooldownSamples пауза после конца события до нового триггера
     */
    public static List<DetectionEvent> pickEventsFromRatio(double[] ratio,
                                                            int ltaWindow,
                                                            double threshold,
                                                            double offRatio,
                                                            int hangSamples,
                                                            int minDuration,
                                                            int cooldownSamples,
                                                            String label) {
        List<DetectionEvent> events = new ArrayList<>();
        if (ratio == null || ratio.length <= ltaWindow) {
            return events;
        }
        double offTh = threshold * offRatio;
        boolean active = false;
        int start = 0;
        int peak = 0;
        double peakRatio = 0;
        int hang = 0;
        int cooldownUntil = 0;

        for (int i = ltaWindow; i < ratio.length; i++) {
            double r = ratio[i];
            if (!active) {
                if (i < cooldownUntil) {
                    continue;
                }
                if (r > threshold) {
                    active = true;
                    start = i;
                    peak = i;
                    peakRatio = r;
                    hang = 0;
                }
            } else {
                if (r > peakRatio) {
                    peakRatio = r;
                    peak = i;
                }
                if (r < offTh) {
                    hang++;
                } else {
                    hang = 0;
                }
                if (hang >= hangSamples) {
                    int end = i - hang;
                    if (end >= start && end - start + 1 >= minDuration) {
                        events.add(new DetectionEvent(start, end, peak, label));
                    }
                    active = false;
                    cooldownUntil = end + cooldownSamples + 1;
                }
            }
        }
        if (active) {
            int end = ratio.length - 1;
            if (end >= start && end - start + 1 >= minDuration) {
                events.add(new DetectionEvent(start, end, peak, label));
            }
        }
        return events;
    }

    private static double ratioAt(List<Double> signal, int i, int staWindow, int ltaWindow, boolean energy) {
        int staStart = Math.max(0, i - staWindow);
        double sta = 0;
        for (int j = staStart; j < i; j++) {
            double v = signal.get(j);
            sta += energy ? v * v : Math.abs(v);
        }
        sta /= (i - staStart);

        int ltaStart = Math.max(0, i - ltaWindow);
        double lta = 0;
        for (int j = ltaStart; j < i; j++) {
            double v = signal.get(j);
            lta += energy ? v * v : Math.abs(v);
        }
        lta /= (i - ltaStart);

        return lta > 1e-20 ? sta / lta : 0;
    }
}
