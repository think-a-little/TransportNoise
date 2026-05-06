package org.example.transport_noise.service;

import java.util.ArrayList;
import java.util.List;

public class VarianceCalculator {

    public List<Double> calculatePerSecond(List<Double> samples, int sampleRate) {
        List<Double> variances = new ArrayList<>();

        if (samples == null || samples.isEmpty()) {
            return variances;
        }

        int seconds = (int) Math.ceil((double) samples.size() / sampleRate);

        for (int s = 0; s < seconds; s++) {
            int start = s * sampleRate;
            int end = Math.min(start + sampleRate, samples.size());
            List<Double> secondData = samples.subList(start, end);
            double variance = calculateVariance(secondData);
            variances.add(variance);
        }

        return variances;
    }

    private double calculateVariance(List<Double> data) {
        if (data.size() < 2) return 0.0;

        double mean = data.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double sum = data.stream().mapToDouble(v -> Math.pow(v - mean, 2)).sum();

        return sum / (data.size() - 1);
    }
}