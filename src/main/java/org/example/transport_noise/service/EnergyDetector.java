package org.example.transport_noise.service;

import org.example.transport_noise.model.DetectionEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Чистый энергетический детектор событий.
 * Находит моменты, где энергия сигнала в скользящем окне превышает заданный порог.
 */
public class EnergyDetector {

    private EnergyDetector() {}

    /**
     * Обнаружение событий по превышению энергией порога.
     *
     * @param signal        входной сигнал
     * @param sampleRate    частота дискретизации, Гц
     * @param windowSec     размер скользящего окна, секунды
     * @param threshold     порог энергии (средний квадрат амплитуды в окне)
     * @param label         метка для событий
     * @return список обнаруженных событий
     */
    public static List<DetectionEvent> detect(List<Double> signal, int sampleRate,
                                              double windowSec, double threshold,
                                              String label) {
        List<DetectionEvent> events = new ArrayList<>();
        int windowSamples = Math.max(2, (int)(windowSec * sampleRate));

        if (signal.size() < windowSamples) {
            return events;
        }

        boolean active = false;
        int startIdx = 0;
        int peakIdx = 0;
        double peakEnergy = 0;
        int hangCount = 0;
        int hangSamples = windowSamples / 2;       // пол-окна на гистерезис
        double offRatio = 0.5;                     // порог отключения = 50% от threshold

        for (int i = windowSamples; i < signal.size(); i++) {
            // Энергия в окне (средний квадрат амплитуды)
            double energy = 0;
            for (int j = i - windowSamples; j <= i; j++) {
                double v = signal.get(j);
                energy += v * v;
            }
            energy /= windowSamples;

            if (!active) {
                if (energy > threshold) {
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
                if (energy < threshold * offRatio) {
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

        // Если событие не закрылось до конца сигнала
        if (active) {
            int endIdx = signal.size() - 1;
            if (endIdx > startIdx) {
                events.add(new DetectionEvent(startIdx, endIdx, peakIdx, label));
            }
        }

        return events;
    }
}