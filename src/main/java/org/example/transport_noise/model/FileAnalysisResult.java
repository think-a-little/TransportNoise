package org.example.transport_noise.model;

import java.util.List;

public class FileAnalysisResult {
    private String fileName;
    private List<Double> variances;
    private List<Double> rawSamples;  // Добавили
    private int totalSamples;
    private int totalSeconds;
    private FileHeader header;

    public FileAnalysisResult(String fileName, List<Double> variances,
                              List<Double> rawSamples, int totalSamples,
                              int totalSeconds, FileHeader header) {
        this.fileName = fileName;
        this.variances = variances;
        this.rawSamples = rawSamples;
        this.totalSamples = totalSamples;
        this.totalSeconds = totalSeconds;
        this.header = header;
    }

    // Геттеры
    public String getFileName() { return fileName; }
    public List<Double> getVariances() { return variances; }
    public List<Double> getRawSamples() { return rawSamples; }
    public int getTotalSamples() { return totalSamples; }
    public int getTotalSeconds() { return totalSeconds; }
    public FileHeader getHeader() { return header; }

    public double getMinVariance() {
        return variances.stream().min(Double::compareTo).orElse(0.0);
    }

    public double getMaxVariance() {
        return variances.stream().max(Double::compareTo).orElse(0.0);
    }

    public double getAvgVariance() {
        return variances.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    public int getSampleRate() {
        return header.getSamplRate() & 0xFFFF;
    }
}