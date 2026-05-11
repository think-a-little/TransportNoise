package org.example.transport_noise.model;

/**
 * Интервал обнаруженного события в отсчётах.
 */
public class DetectionEvent {
    private final int startSample;
    private final int endSample;
    private final int peakSample;
    private final String label;

    public DetectionEvent(int startSample, int endSample, int peakSample, String label) {
        this.startSample = startSample;
        this.endSample = Math.max(endSample, startSample);
        this.peakSample = peakSample;
        this.label = label;
    }

    public int getStartSample() {
        return startSample;
    }

    public int getEndSample() {
        return endSample;
    }

    public int getPeakSample() {
        return peakSample;
    }

    public String getLabel() {
        return label;
    }

    public int durationSamples() {
        return endSample - startSample + 1;
    }

    @Override
    public String toString() {
        return String.format("%s[%d..%d], пик=%d", label, startSample, endSample, peakSample);
    }
}
