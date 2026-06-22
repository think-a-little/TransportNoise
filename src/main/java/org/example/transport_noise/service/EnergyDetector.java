package org.example.transport_noise.service;

import org.example.transport_noise.model.DetectionEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Чистый энергетический детектор событий с нормализацией порога.
 * Измеряет фоновую энергию по первым 10% записи и использует
 * безразмерный порог (например, 2.0 = "энергия в 2 раза выше фона").
 */
public class EnergyDetector {

    private EnergyDetector() {}

    /**
     * Обнаружение событий по превышению энергией порога.
     *
     * @param signal      входной сигнал (после применения scale — физические единицы)
     * @param sampleRate  частота дискретизации, Гц
     * @param windowSec   размер скользящего окна, секунды
     * @param threshold   безразмерный порог (например, 2.0 = энергия в 2 раза выше фона)
     * @param label       метка для событий
     * @return список обнаруженных событий
     */
    public static List<DetectionEvent> detect(List<Double> signal, int sampleRate,
                                              double windowSec, double threshold,
                                              String label) {
        List<DetectionEvent> events = new ArrayList<>();
        int windowSamples = Math.max(2, (int)(windowSec * sampleRate));

        if (signal.size() < windowSamples * 2) {
            return events;
        }

        int backgroundSamples = Math.max(windowSamples, signal.size() / 10);
        double backgroundEnergy = 0;
        for (int i = 0; i < backgroundSamples; i++) {
            double v = signal.get(i);
            backgroundEnergy += v * v;
        }
        backgroundEnergy /= backgroundSamples;

        if (backgroundEnergy < 1e-20) {
            backgroundEnergy = 1e-20;
        }

        double absoluteThreshold = threshold * backgroundEnergy;

        System.out.println("  📊 Энергетический детектор: фоновая энергия = "
                + String.format("%.3e", backgroundEnergy)
                + ", порог = " + String.format("%.3e", absoluteThreshold)
                + " (безразмерный = " + threshold + ")");

        boolean active = false;
        int startIdx = 0;
        int peakIdx = 0;
        double peakEnergy = 0;
        int hangCount = 0;
        int hangSamples = windowSamples / 2;
        double offRatio = 0.5;

        for (int i = windowSamples; i < signal.size(); i++) {
            double energy = 0;
            for (int j = i - windowSamples; j <= i; j++) {
                double v = signal.get(j);
                energy += v * v;
            }
            energy /= windowSamples;

            if (!active) {
                if (energy > absoluteThreshold) {
                    active = true;
                    startIdx = i;
                    peakIdx = i;
                    peakEnergy = energy;
                    hangCount = 0;
                }
            } else {
                if (energy > peakEnergy) {
                    peakEnergy = energy;
                    peakIdx = i;
                }
                if (energy < absoluteThreshold * offRatio) {
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
            int endIdx = signal.size() - 1;
            if (endIdx > startIdx) {
                events.add(new DetectionEvent(startIdx, endIdx, peakIdx, label));
            }
        }

        return events;
    }
}