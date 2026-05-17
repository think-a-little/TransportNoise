package org.example.transport_noise.chart;

import org.example.transport_noise.model.FileAnalysisResult;
import org.jfree.chart.*;
import org.jfree.chart.annotations.XYPointerAnnotation;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.panel.CrosshairOverlay;
import org.jfree.chart.plot.ValueMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.text.DecimalFormat;
import java.util.List;

public class ChartBuilder {

    // ==================== ОСНОВНЫЕ ГРАФИКИ ====================

    public JPanel createVarianceChart(FileAnalysisResult result) {
        XYSeries series = new XYSeries("Дисперсия");
        List<Double> variances = result.getVariances();

        for (int i = 0; i < variances.size(); i++) {
            series.add((double)(i + 1), (double) variances.get(i));
        }

        XYSeriesCollection dataset = new XYSeriesCollection(series);
        JFreeChart chart = ChartFactory.createXYLineChart(
                "Дисперсия сигнала по секундам - " + result.getFileName(),
                "Время (секунды)", "Дисперсия",
                dataset, PlotOrientation.VERTICAL, true, true, false);

        customizeChart(chart);
        ChartPanel chartPanel = new ChartPanel(chart);
        configureZooming(chartPanel);
        return chartPanel;
    }

    public JPanel createSignalChart(FileAnalysisResult result, List<Double> rawSamples) {
        XYSeries series = new XYSeries("Сигнал");
        int sampleRate = result.getSampleRate();
        int maxSamples = Math.min(5000, rawSamples.size());

        for (int i = 0; i < maxSamples; i++) {
            series.add((double) i / sampleRate, (double) rawSamples.get(i));
        }

        XYSeriesCollection dataset = new XYSeriesCollection(series);
        JFreeChart chart = ChartFactory.createXYLineChart(
                "Сигнал (первые " + maxSamples + " отсчётов) - " + result.getFileName(),
                "Время (секунды)", "Амплитуда",
                dataset, PlotOrientation.VERTICAL, true, true, false);

        customizeChart(chart);
        ChartPanel chartPanel = new ChartPanel(chart);
        configureZooming(chartPanel);
        return chartPanel;
    }

    public JPanel createFullSignalChart(FileAnalysisResult result, List<Double> rawSamples) {
        XYSeries series = new XYSeries("Сигнал");
        int sampleRate = result.getSampleRate();
        int step = rawSamples.size() > 100000 ? rawSamples.size() / 50000 : 1;

        for (int i = 0; i < rawSamples.size(); i += step) {
            series.add((double) i / sampleRate, (double) rawSamples.get(i));
        }

        XYSeriesCollection dataset = new XYSeriesCollection(series);
        JFreeChart chart = ChartFactory.createXYLineChart(
                "Полный сигнал - " + result.getFileName(),
                "Время (секунды)", "Амплитуда",
                dataset, PlotOrientation.VERTICAL, true, true, false);

        customizeChart(chart);
        XYPlot plot = chart.getXYPlot();
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(false, false);
        plot.setRenderer(renderer);

        ChartPanel chartPanel = new ChartPanel(chart);
        configureZooming(chartPanel);
        return chartPanel;
    }

    public JFrame createComparisonWindow(List<FileAnalysisResult> results) {
        XYSeriesCollection dataset = new XYSeriesCollection();
        for (FileAnalysisResult result : results) {
            XYSeries series = new XYSeries(result.getFileName());
            List<Double> variances = result.getVariances();
            for (int i = 0; i < variances.size(); i++) {
                series.add((double)(i + 1), (double) variances.get(i));
            }
            dataset.addSeries(series);
        }
        JFreeChart chart = ChartFactory.createXYLineChart(
                "Сравнение дисперсий - все файлы", "Время (секунды)", "Дисперсия",
                dataset, PlotOrientation.VERTICAL, true, true, false);
        customizeChart(chart);
        ChartPanel chartPanel = new ChartPanel(chart);
        configureZooming(chartPanel);
        JFrame frame = new JFrame("Сравнение дисперсий");
        frame.setSize(1200, 700);
        frame.add(chartPanel);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        return frame;
    }

    public JPanel createCombinedChart(FileAnalysisResult result, List<Double> rawSamples) {
        JPanel combinedPanel = new JPanel(new GridLayout(2, 1));
        combinedPanel.add(createSignalChartForTimeRange(result, rawSamples, 0, 2));
        combinedPanel.add(createVarianceChart(result));
        return combinedPanel;
    }

    private JPanel createSignalChartForTimeRange(FileAnalysisResult result,
                                                 List<Double> rawSamples,
                                                 double startTime, double endTime) {
        XYSeries series = new XYSeries("Сигнал");
        int sampleRate = result.getSampleRate();
        int startSample = Math.max(0, (int)(startTime * sampleRate));
        int endSample = Math.min(rawSamples.size(), (int)(endTime * sampleRate));

        for (int i = startSample; i < endSample; i++) {
            series.add((double) i / sampleRate, (double) rawSamples.get(i));
        }

        XYSeriesCollection dataset = new XYSeriesCollection(series);
        JFreeChart chart = ChartFactory.createXYLineChart(
                "Сигнал (" + String.format("%.1f", startTime) + " - " + String.format("%.1f", endTime) + " сек)",
                "Время (секунды)", "Амплитуда",
                dataset, PlotOrientation.VERTICAL, false, true, false);
        customizeChart(chart);
        ChartPanel chartPanel = new ChartPanel(chart);
        configureZooming(chartPanel);
        return chartPanel;
    }

    // ==================== ПРОСТЫЕ ГРАФИКИ ====================

    public JPanel createSimpleSignalChart(List<Double> data, int sampleRate, String title) {
        XYSeries series = new XYSeries("Сигнал");
        int step = Math.max(1, data.size() / 10000);

        for (int i = 0; i < data.size(); i += step) {
            series.add((double) i / sampleRate, (double) data.get(i));
        }

        XYSeriesCollection dataset = new XYSeriesCollection(series);
        JFreeChart chart = ChartFactory.createXYLineChart(
                title, "Время (секунды)", "Амплитуда",
                dataset, PlotOrientation.VERTICAL, true, true, false);
        customizeChart(chart);
        ChartPanel chartPanel = new ChartPanel(chart);
        configureZooming(chartPanel);
        return chartPanel;
    }

    public JPanel createSimpleVarianceChart(List<Double> variances, String title) {
        XYSeries series = new XYSeries("Дисперсия");

        for (int i = 0; i < variances.size(); i++) {
            series.add((double)(i + 1), (double) variances.get(i));
        }

        XYSeriesCollection dataset = new XYSeriesCollection(series);
        JFreeChart chart = ChartFactory.createXYLineChart(
                title, "Секунда", "Дисперсия",
                dataset, PlotOrientation.VERTICAL, true, true, false);
        customizeChart(chart);
        ChartPanel chartPanel = new ChartPanel(chart);
        configureZooming(chartPanel);
        return chartPanel;
    }

    // ==================== ГРАФИКИ STA/LTA И СОБЫТИЙ ====================

    public JPanel createStaLtaRatioChart(List<Double> times, double[] ratio, double threshold,
                                         Double eventStartSec, Double eventEndSec, String title) {
        return createStaLtaRatioChart(times, ratio, threshold, eventStartSec, eventEndSec, title, "STA/LTA", "Отношение STA/LTA");
    }

    public JPanel createStaLtaRatioChart(List<Double> times, double[] ratio, double threshold,
                                         Double eventStartSec, Double eventEndSec, String title,
                                         String seriesName, String yAxisLabel) {
        XYSeries ratioSeries = new XYSeries(seriesName);
        int n = Math.min(times.size(), ratio != null ? ratio.length : 0);
        for (int i = 0; i < n; i++) {
            ratioSeries.add((double) times.get(i), ratio[i]);
        }
        XYSeries thrSeries = new XYSeries("Порог");
        if (n > 0) {
            thrSeries.add((double) times.get(0), threshold);
            thrSeries.add((double) times.get(n - 1), threshold);
        }
        XYSeriesCollection dataset = new XYSeriesCollection();
        dataset.addSeries(ratioSeries);
        dataset.addSeries(thrSeries);

        JFreeChart chart = ChartFactory.createXYLineChart(
                "STA/LTA по R — " + title, "Время (с)", yAxisLabel,
                dataset, PlotOrientation.VERTICAL, true, true, false);
        customizeChart(chart);

        XYPlot plot = chart.getXYPlot();
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer();
        renderer.setSeriesPaint(0, new Color(0, 80, 160));
        renderer.setSeriesPaint(1, Color.RED);
        renderer.setSeriesStroke(1, new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{6f, 6f}, 0f));
        plot.setRenderer(renderer);
        addVerticalDomainMarkers(plot, eventStartSec, eventEndSec);

        ChartPanel chartPanel = new ChartPanel(chart);
        configureZooming(chartPanel);
        return chartPanel;
    }

    public JPanel createAmplitudeWithEventMarkers(List<Double> times, List<Double> amplitudes,
                                                  Double eventStartSec, Double eventEndSec, String title) {
        XYSeries signalSeries = new XYSeries("R = √(X²+Y²+Z²)");
        int size = Math.min(times.size(), amplitudes.size());
        for (int i = 0; i < size; i++) {
            signalSeries.add((double) times.get(i), (double) amplitudes.get(i));
        }
        XYSeriesCollection dataset = new XYSeriesCollection(signalSeries);

        JFreeChart chart = ChartFactory.createXYLineChart(
                "Результирующая амплитуда — " + title, "Время (с)", "Амплитуда",
                dataset, PlotOrientation.VERTICAL, true, true, false);
        customizeChart(chart);

        XYPlot plot = chart.getXYPlot();
        addVerticalDomainMarkers(plot, eventStartSec, eventEndSec);

        ChartPanel chartPanel = new ChartPanel(chart);
        configureZooming(chartPanel);
        return chartPanel;
    }

    public JPanel createRawVsFilteredChart(List<Double> times, List<Double> raw, List<Double> filtered,
                                           String title, String rawName, String filteredName) {
        XYSeries rawSeries = new XYSeries(rawName);
        XYSeries filtSeries = new XYSeries(filteredName);
        int n = Math.min(times.size(), Math.min(raw.size(), filtered.size()));
        for (int i = 0; i < n; i++) {
            rawSeries.add((double) times.get(i), (double) raw.get(i));
            filtSeries.add((double) times.get(i), (double) filtered.get(i));
        }
        XYSeriesCollection dataset = new XYSeriesCollection();
        dataset.addSeries(rawSeries);
        dataset.addSeries(filtSeries);

        JFreeChart chart = ChartFactory.createXYLineChart(
                title, "Время (с)", "Амплитуда",
                dataset, PlotOrientation.VERTICAL, true, true, false);
        customizeChart(chart);
        XYPlot plot = chart.getXYPlot();
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer();
        renderer.setSeriesPaint(0, new Color(150, 150, 150));
        renderer.setSeriesPaint(1, new Color(0, 80, 200));
        plot.setRenderer(renderer);

        ChartPanel chartPanel = new ChartPanel(chart);
        configureZooming(chartPanel);
        return chartPanel;
    }

    public JPanel createStaLtaEnergyComponentsChart(List<Double> times, List<Double> signal,
                                                    int staWindow, int ltaWindow, String title) {
        XYSeries staSeries = new XYSeries("STA (среднее x²)");
        XYSeries ltaSeries = new XYSeries("LTA (среднее x²)");
        int n = times.size();
        double[] staVals = new double[n];
        double[] ltaVals = new double[n];

        for (int i = 0; i < n; i++) {
            int staStart = Math.max(0, i - staWindow);
            double staSum = 0;
            for (int j = staStart; j <= i; j++) staSum += signal.get(j) * signal.get(j);
            staVals[i] = staSum / (i - staStart + 1);

            int ltaStart = Math.max(0, i - ltaWindow);
            double ltaSum = 0;
            for (int j = ltaStart; j <= i; j++) ltaSum += signal.get(j) * signal.get(j);
            ltaVals[i] = ltaSum / (i - ltaStart + 1);
        }

        for (int i = 0; i < n; i++) {
            staSeries.add((double) times.get(i), staVals[i]);
            ltaSeries.add((double) times.get(i), ltaVals[i]);
        }

        XYSeriesCollection dataset = new XYSeriesCollection();
        dataset.addSeries(staSeries);
        dataset.addSeries(ltaSeries);

        JFreeChart chart = ChartFactory.createXYLineChart(
                title, "Время (с)", "Энергия (x²)",
                dataset, PlotOrientation.VERTICAL, true, true, false);
        customizeChart(chart);
        XYPlot plot = chart.getXYPlot();
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer();
        renderer.setSeriesPaint(0, new Color(200, 100, 0));
        renderer.setSeriesPaint(1, new Color(0, 100, 200));
        plot.setRenderer(renderer);

        ChartPanel chartPanel = new ChartPanel(chart);
        configureZooming(chartPanel);
        return chartPanel;
    }

    private void addVerticalDomainMarkers(XYPlot plot, Double eventStartSec, Double eventEndSec) {
        if (eventStartSec != null) {
            ValueMarker m = new ValueMarker(eventStartSec);
            m.setPaint(new Color(0, 140, 0));
            m.setStroke(new BasicStroke(2f));
            plot.addDomainMarker(m);
        }
        if (eventEndSec != null) {
            ValueMarker m = new ValueMarker(eventEndSec);
            m.setPaint(new Color(180, 0, 0));
            m.setStroke(new BasicStroke(2f));
            plot.addDomainMarker(m);
        }
    }

    // ==================== НАСТРОЙКИ ====================

    private void customizeChart(JFreeChart chart) {
        chart.setBackgroundPaint(Color.WHITE);
        chart.setAntiAlias(true);
        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(new Color(245, 245, 245));
        plot.setDomainGridlinePaint(Color.LIGHT_GRAY);
        plot.setRangeGridlinePaint(Color.LIGHT_GRAY);
        NumberAxis domainAxis = (NumberAxis) plot.getDomainAxis();
        domainAxis.setNumberFormatOverride(new DecimalFormat("0.00"));
        domainAxis.setAutoRangeIncludesZero(true);
        domainAxis.setLabelFont(new Font("SansSerif", Font.PLAIN, 12));
        NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setNumberFormatOverride(new DecimalFormat("0.000"));
        rangeAxis.setLabelFont(new Font("SansSerif", Font.PLAIN, 12));
    }

    private void configureZooming(ChartPanel chartPanel) {
        chartPanel.setMouseWheelEnabled(true);
        chartPanel.setDomainZoomable(true);
        chartPanel.setRangeZoomable(true);
        chartPanel.setHorizontalAxisTrace(false);
        chartPanel.setVerticalAxisTrace(false);
        chartPanel.setZoomOutlinePaint(new Color(0, 0, 255, 100));
        chartPanel.setZoomFillPaint(new Color(0, 0, 255, 30));
        chartPanel.setPopupMenu(null);
        chartPanel.setDisplayToolTips(true);

        final JFreeChart chart = chartPanel.getChart();
        if (chart != null) {
            XYPlot plot = chart.getXYPlot();
            plot.addAnnotation(new XYPointerAnnotation("", 0, 0, 0));
        }

        chartPanel.addChartMouseListener(new ChartMouseListener() {
            @Override
            public void chartMouseClicked(ChartMouseEvent event) {
                if (event.getTrigger().getButton() == MouseEvent.BUTTON3) {
                    chartPanel.restoreAutoBounds();
                    chartPanel.repaint();
                    chartPanel.setToolTipText("Масштаб сброшен");
                }
            }

            @Override
            public void chartMouseMoved(ChartMouseEvent event) {
                if (chart != null) {
                    XYPlot plot = (XYPlot) chart.getPlot();
                    Rectangle2D dataArea = chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea();
                    int mouseX = event.getTrigger().getX();
                    int mouseY = event.getTrigger().getY();

                    if (dataArea.contains(mouseX, mouseY)) {
                        double x = plot.getDomainAxis().java2DToValue(mouseX, dataArea, plot.getDomainAxisEdge());
                        double y = plot.getRangeAxis().java2DToValue(mouseY, dataArea, plot.getRangeAxisEdge());
                        chartPanel.setToolTipText(String.format(
                                "<html>Время: <b>%.3f сек</b><br>Значение: <b>%.6f</b></html>", x, y));
                        chartPanel.paintImmediately(dataArea.getBounds());
                    }
                }
            }
        });

        chartPanel.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                SwingUtilities.invokeLater(() -> chartPanel.repaint());
            }
        });

        chartPanel.setRefreshBuffer(true);
    }
}