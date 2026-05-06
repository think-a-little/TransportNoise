package org.example.transport_noise.chart;

import org.example.transport_noise.model.FileAnalysisResult;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.ValueAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import javax.swing.*;
import java.awt.*;
import java.util.List;

public class ChartBuilder {

    // График дисперсии по секундам
    public JPanel createVarianceChart(FileAnalysisResult result) {
        XYSeries series = new XYSeries("Дисперсия");
        List<Double> variances = result.getVariances();

        for (int i = 0; i < variances.size(); i++) {
            series.add(i + 1, variances.get(i));
        }

        XYSeriesCollection dataset = new XYSeriesCollection(series);
        JFreeChart chart = ChartFactory.createXYLineChart(
                "Дисперсия сигнала по секундам - " + result.getFileName(),
                "Секунда",
                "Дисперсия",
                dataset,
                PlotOrientation.VERTICAL,
                true,
                true,
                false
        );

        ChartPanel chartPanel = new ChartPanel(chart);
        configureZooming(chartPanel);
        return chartPanel;
    }

    // График чистого сигнала (все отсчёты)
    public JPanel createSignalChart(FileAnalysisResult result, List<Double> rawSamples) {
        XYSeries series = new XYSeries("Сигнал");

        // Показываем первые 5000 отсчётов для производительности (можно настроить)
        int maxSamples = Math.min(5000, rawSamples.size());
        for (int i = 0; i < maxSamples; i++) {
            series.add(i + 1, rawSamples.get(i));
        }

        XYSeriesCollection dataset = new XYSeriesCollection(series);
        JFreeChart chart = ChartFactory.createXYLineChart(
                "Сигнал (временной ряд) - " + result.getFileName(),
                "Отсчёт",
                "Амплитуда",
                dataset,
                PlotOrientation.VERTICAL,
                true,
                true,
                false
        );

        ChartPanel chartPanel = new ChartPanel(chart);
        configureZooming(chartPanel);
        return chartPanel;
    }

    // Полный график сигнала с возможностью выбора диапазона
    public JPanel createFullSignalChart(FileAnalysisResult result, List<Double> rawSamples) {
        XYSeries series = new XYSeries("Сигнал");

        // Добавляем все отсчёты
        for (int i = 0; i < rawSamples.size(); i++) {
            series.add(i + 1, rawSamples.get(i));
        }

        XYSeriesCollection dataset = new XYSeriesCollection(series);
        JFreeChart chart = ChartFactory.createXYLineChart(
                "Полный сигнал - " + result.getFileName() + " (всего " + rawSamples.size() + " отсчётов)",
                "Отсчёт",
                "Амплитуда",
                dataset,
                PlotOrientation.VERTICAL,
                true,
                true,
                false
        );

        ChartPanel chartPanel = new ChartPanel(chart);
        configureZooming(chartPanel);

        // Добавляем информацию о масштабировании
        chartPanel.setToolTipText("Используйте мышь для масштабирования:\n" +
                "• Выделите область - увеличить\n" +
                "• Правый клик - вернуть исходный масштаб\n" +
                "• Колесо мыши - масштабирование по вертикали/горизонтали");

        return chartPanel;
    }

    // Сравнение нескольких файлов (дисперсия)
    public JFrame createComparisonWindow(List<FileAnalysisResult> results) {
        XYSeriesCollection dataset = new XYSeriesCollection();

        for (FileAnalysisResult result : results) {
            XYSeries series = new XYSeries(result.getFileName());
            List<Double> variances = result.getVariances();
            for (int i = 0; i < variances.size(); i++) {
                series.add(i + 1, variances.get(i));
            }
            dataset.addSeries(series);
        }

        JFreeChart chart = ChartFactory.createXYLineChart(
                "Сравнение дисперсий всех файлов",
                "Секунда",
                "Дисперсия",
                dataset,
                PlotOrientation.VERTICAL,
                true,
                true,
                false
        );

        ChartPanel chartPanel = new ChartPanel(chart);
        configureZooming(chartPanel);

        JFrame frame = new JFrame("Сравнение файлов");
        frame.setSize(1200, 700);
        frame.add(chartPanel);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        return frame;
    }

    // Сравнение сигналов нескольких файлов
    public JFrame createSignalComparisonWindow(List<FileAnalysisResult> results, List<List<Double>> rawSamplesList) {
        XYSeriesCollection dataset = new XYSeriesCollection();

        for (int idx = 0; idx < results.size(); idx++) {
            FileAnalysisResult result = results.get(idx);
            List<Double> rawSamples = rawSamplesList.get(idx);

            XYSeries series = new XYSeries(result.getFileName());

            // Показываем первые 2000 отсчётов для сравнения
            int maxSamples = Math.min(2000, rawSamples.size());
            for (int i = 0; i < maxSamples; i++) {
                series.add(i + 1, rawSamples.get(i));
            }
            dataset.addSeries(series);
        }

        JFreeChart chart = ChartFactory.createXYLineChart(
                "Сравнение сигналов (первые 2000 отсчётов)",
                "Отсчёт",
                "Амплитуда",
                dataset,
                PlotOrientation.VERTICAL,
                true,
                true,
                false
        );

        ChartPanel chartPanel = new ChartPanel(chart);
        configureZooming(chartPanel);

        JFrame frame = new JFrame("Сравнение сигналов");
        frame.setSize(1200, 700);
        frame.add(chartPanel);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        return frame;
    }

    // Настройка масштабирования для графика
    private void configureZooming(ChartPanel chartPanel) {
        // Включаем масштабирование мышью
        chartPanel.setMouseWheelEnabled(true);
        chartPanel.setZoomOutlinePaint(Color.BLUE);
        chartPanel.setZoomFillPaint(new Color(0, 0, 255, 50));

        // Настройки масштабирования
        chartPanel.setHorizontalAxisTrace(true);
        chartPanel.setVerticalAxisTrace(true);

        // Устанавливаем формат отображения координат
        chartPanel.setDomainZoomable(true);   // Масштабирование по X
        chartPanel.setRangeZoomable(true);    // Масштабирование по Y

        // Добавляем обработчик для сброса масштаба по правому клику
        chartPanel.setPopupMenu(null); // Отключаем стандартное меню
        chartPanel.addChartMouseListener(new org.jfree.chart.ChartMouseListener() {
            @Override
            public void chartMouseClicked(org.jfree.chart.ChartMouseEvent event) {
                if (event.getTrigger().getButton() == java.awt.event.MouseEvent.BUTTON3) {
                    // Правый клик - сброс масштаба
                    chartPanel.restoreAutoBounds();
                }
            }

            @Override
            public void chartMouseMoved(org.jfree.chart.ChartMouseEvent event) {
                // Показываем координаты
                double x = chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea().getCenterX();
                double y = chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea().getCenterY();
            }
        });
    }

    // Создание панели с двумя графиками (сигнал + дисперсия)
    public JPanel createCombinedChart(FileAnalysisResult result, List<Double> rawSamples) {
        JPanel combinedPanel = new JPanel(new GridLayout(2, 1));

        // Верхний график - сигнал
        combinedPanel.add(createSignalChart(result, rawSamples));

        // Нижний график - дисперсия
        combinedPanel.add(createVarianceChart(result));

        return combinedPanel;
    }

    // График с возможностью выбора участка (спектрограмма)
    public JPanel createSelectableSignalChart(FileAnalysisResult result, List<Double> rawSamples) {
        XYSeries series = new XYSeries("Сигнал");

        // Показываем средние 10000 отсчётов или все если меньше
        int startIdx = Math.max(0, (rawSamples.size() - 10000) / 2);
        int endIdx = Math.min(rawSamples.size(), startIdx + 10000);

        for (int i = startIdx; i < endIdx; i++) {
            series.add(i + 1, rawSamples.get(i));
        }

        XYSeriesCollection dataset = new XYSeriesCollection(series);
        JFreeChart chart = ChartFactory.createXYLineChart(
                "Сигнал (отсчёты " + (startIdx + 1) + " - " + endIdx + ") - " + result.getFileName(),
                "Отсчёт",
                "Амплитуда",
                dataset,
                PlotOrientation.VERTICAL,
                true,
                true,
                false
        );

        ChartPanel chartPanel = new ChartPanel(chart);
        configureZooming(chartPanel);

        // Добавляем панель управления
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton resetViewBtn = new JButton("Сбросить масштаб");
        JButton showAllBtn = new JButton("Показать всё");

        resetViewBtn.addActionListener(e -> chartPanel.restoreAutoBounds());
        showAllBtn.addActionListener(e -> {
            chartPanel.restoreAutoBounds();
            // Можно также сбросить выделение
        });

        controlPanel.add(resetViewBtn);
        controlPanel.add(showAllBtn);
        controlPanel.add(new JLabel(" | Правый клик - сброс масштаба | Колесо мыши - зум"));

        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.add(controlPanel, BorderLayout.NORTH);
        mainPanel.add(chartPanel, BorderLayout.CENTER);

        return mainPanel;
    }
}