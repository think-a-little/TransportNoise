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
     * Если событий несколько, оставляет одно — с наибольшим STA/LTA в точке пика.
     */
    public static List<DetectionEvent> keepDominantEvent(List<DetectionEvent> events, double[] ratio) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        DetectionEvent best = events.get(0);
        double bestVal = ratioAtPeak(best, ratio);
        for (int i = 1; i < events.size(); i++) {
            DetectionEvent e = events.get(i);
            double v = ratioAtPeak(e, ratio);
            if (v > bestVal) {
                bestVal = v;
                best = e;
            }
        }
        return List.of(best);
    }

    private static double ratioAtPeak(DetectionEvent e, double[] ratio) {
        int p = e.getPeakSample();
        if (ratio == null || p < 0 || p >= ratio.length) {
            return -1;
        }
        return ratio[p];
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
                    int rawEnd = i - hangSamples;
                    if (rawEnd >= start && rawEnd - start + 1 >= minDuration) {
                        DetectionEvent raw = new DetectionEvent(start, rawEnd, peak, label);
                        DetectionEvent fin = refineEventBounds(raw, ratio, ltaWindow, threshold, offRatio);
                        events.add(fin);
                        active = false;
                        cooldownUntil = fin.getEndSample() + cooldownSamples + 1;
                    } else {
                        active = false;
                        cooldownUntil = rawEnd + cooldownSamples + 1;
                    }
                }
            }
        }
        if (active) {
            int end = ratio.length - 1;
            if (end >= start && end - start + 1 >= minDuration) {
                DetectionEvent raw = new DetectionEvent(start, end, peak, label);
                events.add(refineEventBounds(raw, ratio, ltaWindow, threshold, offRatio));
            }
        }
        return events;
    }

    /**
     * Уточнение начала (откат к фону ratio≈1) и конца (последний заметный всплеск до «тишины»).
     */
    public static DetectionEvent refineEventBounds(DetectionEvent e, double[] ratio, int ltaWindow,
                                                   double threshold, double offRatio) {
        int s = e.getStartSample();
        int end = e.getEndSample();
        int peak = e.getPeakSample();
        if (ratio == null || ratio.length == 0) {
            return e;
        }
        s = Math.min(Math.max(s, ltaWindow), ratio.length - 1);
        end = Math.min(Math.max(end, s), ratio.length - 1);
        double off = threshold * offRatio;

        // Используем вычисляемое значение вместо staWindow
        int staWindowEstimate = Math.max(5, ltaWindow / 20); // ~5% от LTA

        int backLimit = Math.max(ltaWindow + 1, s - Math.max(4 * staWindowEstimate, 40));
        int ns = s;
        while (ns > backLimit && ratio[ns - 1] > 1.02) {
            ns--;
        }
        ns = Math.max(ltaWindow, Math.min(ns, s));

        int ne = end;
        int forwardLim = Math.min(ratio.length - 1, end + Math.max(3 * staWindowEstimate, 60));
        for (int k = end; k <= forwardLim; k++) {
            if (ratio[k] >= off * 0.72) {
                ne = k;
            }
        }
        ne = Math.min(ratio.length - 1, Math.max(ne, end));

        int pk = peak;
        if (pk < ns) {
            pk = ns;
        }
        if (pk > ne) {
            pk = ne;
        }
        for (int k = ns; k <= ne; k++) {
            if (ratio[k] > ratio[pk]) {
                pk = k;
            }
        }
        return new DetectionEvent(ns, Math.max(ns, ne), pk, e.getLabel());
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
