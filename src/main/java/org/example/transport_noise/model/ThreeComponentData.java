package org.example.transport_noise.model;

import java.util.List;

/**
 * Модель для хранения трехкомпонентных данных
 */
public class ThreeComponentData {
    private String stationName;
    private List<Double> componentX; // N-S компонента (North)
    private List<Double> componentY; // E-W компонента (East)
    private List<Double> componentZ; // Вертикальная компонента
    private int sampleRate;
    private double latitude;
    private double longitude;

    public ThreeComponentData(String stationName,
                              List<Double> rawData,
                              int sampleRate,
                              double latitude,
                              double longitude) {
        this.stationName = stationName;
        this.sampleRate = sampleRate;
        this.latitude = latitude;
        this.longitude = longitude;
        // rawData будет установлено позже через setComponent
    }

    // Сеттеры для каждой компоненты
    public void setComponentX(List<Double> x) { this.componentX = x; }
    public void setComponentY(List<Double> y) { this.componentY = y; }
    public void setComponentZ(List<Double> z) { this.componentZ = z; }

    // Геттеры
    public String getStationName() { return stationName; }
    public List<Double> getComponentX() { return componentX; }
    public List<Double> getComponentY() { return componentY; }
    public List<Double> getComponentZ() { return componentZ; }
    public int getSampleRate() { return sampleRate; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }

    /**
     * Проверка, все ли компоненты загружены
     */
    public boolean isComplete() {
        return componentX != null && componentY != null && componentZ != null;
    }
}