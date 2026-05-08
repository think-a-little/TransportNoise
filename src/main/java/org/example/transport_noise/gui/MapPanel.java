package org.example.transport_noise.gui;

import org.example.transport_noise.model.FileAnalysisResult;
import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.OSMTileFactoryInfo;
import org.jxmapviewer.viewer.DefaultTileFactory;
import org.jxmapviewer.viewer.DefaultWaypoint;
import org.jxmapviewer.viewer.GeoPosition;
import org.jxmapviewer.viewer.WaypointPainter;
import org.jxmapviewer.input.PanMouseInputListener;
import org.jxmapviewer.input.ZoomMouseWheelListenerCursor;

import javax.swing.*;
import java.awt.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MapPanel extends JPanel {
    private JXMapViewer mapViewer;
    private Set<DefaultWaypoint> waypoints;
    private JLabel statusLabel;
    private List<FileAnalysisResult> currentResults;
    private int currentIndex;

    public MapPanel() {
        setLayout(new BorderLayout());
        currentIndex = -1;
        initMap();
        initStatusBar();
    }

    private void initMap() {
        mapViewer = new JXMapViewer();

        // Используем HTTPS для загрузки карт OpenStreetMap
        OSMTileFactoryInfo info = new OSMTileFactoryInfo(
                "OpenStreetMap",
                "https://tile.openstreetmap.org"
        );
        DefaultTileFactory tileFactory = new DefaultTileFactory(info);
        mapViewer.setTileFactory(tileFactory);

        // Начальная позиция (центр России)
        GeoPosition startPos = new GeoPosition(55.751244, 37.618423);
        mapViewer.setAddressLocation(startPos);
        mapViewer.setZoom(4);

        // Добавляем управление мышью
        PanMouseInputListener panListener = new PanMouseInputListener(mapViewer);
        mapViewer.addMouseListener(panListener);
        mapViewer.addMouseMotionListener(panListener);
        mapViewer.addMouseWheelListener(new ZoomMouseWheelListenerCursor(mapViewer));

        waypoints = new HashSet<>();

        add(mapViewer, BorderLayout.CENTER);
    }

    private void initStatusBar() {
        statusLabel = new JLabel(" Готов. Загрузите файлы с координатами");
        statusLabel.setBorder(BorderFactory.createLoweredBevelBorder());
        add(statusLabel, BorderLayout.SOUTH);
    }

    public void showLocation(double lat, double lon, String label) {
        if (lat == 0 && lon == 0) {
            statusLabel.setText("Координаты не заданы");
            return;
        }

        clearWaypoints();
        addWaypoint(lat, lon);

        SwingUtilities.invokeLater(() -> {
            mapViewer.setAddressLocation(new GeoPosition(lat, lon));
            mapViewer.setZoom(10);
            mapViewer.repaint();
        });

        statusLabel.setText(String.format("%s | Lat: %.6f | Lon: %.6f", label, lat, lon));
    }

    public void showMultipleLocations(List<FileAnalysisResult> results) {
        clearWaypoints();

        // Используем массив вместо простой переменной
        int[] validCount = {0};

        for (FileAnalysisResult result : results) {
            if (result.getHeader() != null) {
                float lat = result.getHeader().getLat();
                float lon = result.getHeader().getLon();

                if (lat != 0 || lon != 0) {
                    addWaypoint(lat, lon);
                    validCount[0]++;
                }
            }
        }

        if (validCount[0] > 0) {
            statusLabel.setText(String.format(" Загружено точек: %d из %d", validCount[0], results.size()));

            SwingUtilities.invokeLater(() -> {
                centerOnAllPoints();
                JOptionPane.showMessageDialog(this,
                        "Добавлено " + validCount[0] + " точек на карту",
                        "Информация", JOptionPane.INFORMATION_MESSAGE);
            });
        } else {
            statusLabel.setText(" Нет файлов с корректными координатами");
            JOptionPane.showMessageDialog(this,
                    "Ни один файл не содержит корректных координат",
                    "Предупреждение", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void addWaypoint(double lat, double lon) {
        GeoPosition position = new GeoPosition(lat, lon);
        waypoints.add(new DefaultWaypoint(position));
        updateWaypoints();
    }

    private void clearWaypoints() {
        waypoints.clear();
        updateWaypoints();
    }

    private void updateWaypoints() {
        WaypointPainter<DefaultWaypoint> painter = new WaypointPainter<>();
        painter.setWaypoints(waypoints);
        mapViewer.setOverlayPainter(painter);

        // Принудительно перерисовываем
        mapViewer.repaint();
        revalidate();
    }
    public void refreshMap() {
        SwingUtilities.invokeLater(() -> {
            updateWaypoints();
            mapViewer.repaint();
            revalidate();
        });
    }
    // Добавь этот метод для обновления текущей позиции
    public void updateCurrentLocation(FileAnalysisResult result) {
        if (result == null || result.getHeader() == null) {
            return;
        }

        float lat = result.getHeader().getLat();
        float lon = result.getHeader().getLon();

        if (lat != 0 || lon != 0) {
            SwingUtilities.invokeLater(() -> {
                mapViewer.setAddressLocation(new GeoPosition(lat, lon));
                mapViewer.setZoom(10);
                mapViewer.repaint();
            });
        }
    }

    // Добавь метод для центрирования на всех точках
    public void centerOnAllPoints() {
        if (waypoints.isEmpty()) {
            return;
        }

        SwingUtilities.invokeLater(() -> {
            // Вычисляем центр всех точек
            double sumLat = 0, sumLon = 0;
            for (DefaultWaypoint wp : waypoints) {
                sumLat += wp.getPosition().getLatitude();
                sumLon += wp.getPosition().getLongitude();
            }
            double centerLat = sumLat / waypoints.size();
            double centerLon = sumLon / waypoints.size();

            mapViewer.setAddressLocation(new GeoPosition(centerLat, centerLon));
            mapViewer.setZoom(8);
            mapViewer.repaint();
        });
    }
}