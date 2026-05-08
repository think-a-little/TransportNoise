package org.example.transport_noise.chart;

import org.example.transport_noise.model.FileAnalysisResult;
import org.jfree.chart.*;
import org.jfree.chart.annotations.XYPointerAnnotation;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.panel.CrosshairOverlay;
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
import java.awt.geom.Rectangle2D;
import java.text.DecimalFormat;
import java.util.List;

public class ChartBuilder {

    // График дисперсии по секундам (основной график для анализа шума)
    public JPanel createVarianceChart(FileAnalysisResult result) {
        XYSeries series = new XYSeries("Дисперсия");
        List<Double> variances = result.getVariances();
        int sampleRate = result.getSampleRate();

        // По оси X - секунды (время)
        for (int i = 0; i < variances.size(); i++) {
            series.add(i + 1, variances.get(i)); // i+1 = номер секунды
        }

        XYSeriesCollection dataset = new XYSeriesCollection(series);
        JFreeChart chart = ChartFactory.createXYLineChart(
                "Дисперсия сигнала по секундам - " + result.getFileName(),
                "Время (секунды)",
                "Дисперсия",
                dataset,
                PlotOrientation.VERTICAL,
                true,
                true,
                false
        );

        customizeChart(chart);

        ChartPanel chartPanel = new ChartPanel(chart);
        configureZooming(chartPanel);

        // Устанавливаем подсказку для графика
        chartPanel.setToolTipText(
                "<html><b>График дисперсии:</b><br>" +
                        "• Показывает энергию шума в каждую секунду<br>" +
                        "• Чем выше значение - тем громче шум в эту секунду<br>" +
                        "• Используйте для поиска моментов с максимальным шумом</html>"
        );

        return chartPanel;
    }

    // График сигнала с отображением секунд по оси X
    public JPanel createSignalChart(FileAnalysisResult result, List<Double> rawSamples) {
        XYSeries series = new XYSeries("Сигнал");
        int sampleRate = result.getSampleRate();

        // Показываем первые 5000 отсчётов для производительности
        int maxSamples = Math.min(5000, rawSamples.size());
        for (int i = 0; i < maxSamples; i++) {
            // Переводим номер отсчёта в секунды
            double timeInSeconds = (double) i / sampleRate;
            series.add(timeInSeconds, rawSamples.get(i));
        }

        XYSeriesCollection dataset = new XYSeriesCollection(series);
        JFreeChart chart = ChartFactory.createXYLineChart(
                "Сигнал (первые " + maxSamples + " отсчётов) - " + result.getFileName(),
                "Время (секунды)",
                "Амплитуда",
                dataset,
                PlotOrientation.VERTICAL,
                true,
                true,
                false
        );

        customizeChart(chart);

        ChartPanel chartPanel = new ChartPanel(chart);
        configureZooming(chartPanel);

        chartPanel.setToolTipText(
                "<html><b>График сигнала:</b><br>" +
                        "• Показывает форму звуковой волны<br>" +
                        "• Амплитуда - размах колебаний звукового давления<br>" +
                        "• Шум проявляется как хаотичные колебания</html>"
        );

        return chartPanel;
    }

    // Полный график сигнала с секундами на оси X
    public JPanel createFullSignalChart(FileAnalysisResult result, List<Double> rawSamples) {
        XYSeries series = new XYSeries("Сигнал");
        int sampleRate = result.getSampleRate();

        System.out.println("Создание полного графика сигнала...");
        System.out.println("  Всего отсчётов: " + rawSamples.size());
        System.out.println("  Частота дискретизации: " + sampleRate + " Гц");

        // Добавляем все отсчёты, но с прореживанием для больших файлов
        int step = 1;
        if (rawSamples.size() > 100000) {
            step = rawSamples.size() / 50000; // Показываем не более 50000 точек
            System.out.println("  Прореживание: каждый " + step + "-й отсчёт");
        }

        int pointsAdded = 0;
        for (int i = 0; i < rawSamples.size(); i += step) {
            double timeInSeconds = (double) i / sampleRate;
            series.add(timeInSeconds, rawSamples.get(i));
            pointsAdded++;
        }

        System.out.println("  Добавлено точек: " + pointsAdded);

        XYSeriesCollection dataset = new XYSeriesCollection(series);
        JFreeChart chart = ChartFactory.createXYLineChart(
                "Полный сигнал - " + result.getFileName() +
                        " (длительность: " + String.format("%.2f", (double)rawSamples.size()/sampleRate) + " сек)",
                "Время (секунды)",
                "Амплитуда",
                dataset,
                PlotOrientation.VERTICAL,
                true,
                true,
                false
        );

        customizeChart(chart);

        // Для полного графика используем быстрый рендерер
        XYPlot plot = chart.getXYPlot();
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, false);
        renderer.setDefaultShapesVisible(false); // Не показываем точки, только линию
        plot.setRenderer(renderer);

        // Настройка диапазона оси X для отображения всего сигнала
        NumberAxis domainAxis = (NumberAxis) plot.getDomainAxis();
        double totalTime = (double) rawSamples.size() / sampleRate;
        domainAxis.setRange(0, totalTime);

        ChartPanel chartPanel = new ChartPanel(chart);
        configureZooming(chartPanel);

        chartPanel.setToolTipText(
                "<html><b>Полный сигнал:</b><br>" +
                        "• Показывает всю запись целиком<br>" +
                        "• Используйте зум для детального просмотра<br>" +
                        "• Правый клик - сброс масштаба</html>"
        );

        return chartPanel;
    }

    // Сравнение дисперсий нескольких файлов
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
                "Сравнение дисперсий - все файлы",
                "Время (секунды)",
                "Дисперсия",
                dataset,
                PlotOrientation.VERTICAL,
                true,
                true,
                false
        );

        customizeChart(chart);

        ChartPanel chartPanel = new ChartPanel(chart);
        configureZooming(chartPanel);

        chartPanel.setToolTipText(
                "<html><b>Сравнение дисперсий:</b><br>" +
                        "• Каждая линия - отдельный файл (точка измерения)<br>" +
                        "• Сравнивает уровни шума в разных точках<br>" +
                        "• Позволяет найти наиболее шумные места</html>"
        );

        JFrame frame = new JFrame("Сравнение дисперсий");
        frame.setSize(1200, 700);
        frame.add(chartPanel);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        return frame;
    }

    // Комбинированный график (сигнал + дисперсия)
    public JPanel createCombinedChart(FileAnalysisResult result, List<Double> rawSamples) {
        JPanel combinedPanel = new JPanel(new GridLayout(2, 1));

        // Верхний график - сигнал (первые 2 секунды для наглядности)
        JPanel signalPanel = createSignalChartForTimeRange(result, rawSamples, 0, 2);
        combinedPanel.add(signalPanel);

        // Нижний график - дисперсия
        combinedPanel.add(createVarianceChart(result));

        return combinedPanel;
    }

    // Вспомогательный метод для создания графика сигнала за определенный период
    private JPanel createSignalChartForTimeRange(FileAnalysisResult result,
                                                 List<Double> rawSamples,
                                                 double startTime,
                                                 double endTime) {
        XYSeries series = new XYSeries("Сигнал");
        int sampleRate = result.getSampleRate();

        int startSample = (int)(startTime * sampleRate);
        int endSample = (int)(endTime * sampleRate);

        startSample = Math.max(0, startSample);
        endSample = Math.min(rawSamples.size(), endSample);

        for (int i = startSample; i < endSample; i++) {
            double timeInSeconds = (double) i / sampleRate;
            series.add(timeInSeconds, rawSamples.get(i));
        }

        XYSeriesCollection dataset = new XYSeriesCollection(series);
        JFreeChart chart = ChartFactory.createXYLineChart(
                "Сигнал (" + String.format("%.1f", startTime) + " - " +
                        String.format("%.1f", endTime) + " сек)",
                "Время (секунды)",
                "Амплитуда",
                dataset,
                PlotOrientation.VERTICAL,
                false,  // без легенды
                true,   // с тултипами
                false
        );

        customizeChart(chart);

        ChartPanel chartPanel = new ChartPanel(chart);
        configureZooming(chartPanel);

        return chartPanel;
    }

    // Настройка внешнего вида графика
    private void customizeChart(JFreeChart chart) {
        chart.setBackgroundPaint(Color.WHITE);
        chart.setAntiAlias(true);

        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(new Color(245, 245, 245));
        plot.setDomainGridlinePaint(Color.LIGHT_GRAY);
        plot.setRangeGridlinePaint(Color.LIGHT_GRAY);

        // Настройка осей
        NumberAxis domainAxis = (NumberAxis) plot.getDomainAxis();
        domainAxis.setNumberFormatOverride(new DecimalFormat("0.00"));
        domainAxis.setAutoRangeIncludesZero(true);
        domainAxis.setLabelFont(new Font("SansSerif", Font.PLAIN, 12));

        NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setNumberFormatOverride(new DecimalFormat("0.000"));
        rangeAxis.setLabelFont(new Font("SansSerif", Font.PLAIN, 12));
    }

    // Настройка масштабирования с правильным прицелом
// Настройка масштабирования с кастомным перекрестием без следов
    private void configureZooming(ChartPanel chartPanel) {
        // Включаем масштабирование
        chartPanel.setMouseWheelEnabled(true);
        chartPanel.setDomainZoomable(true);
        chartPanel.setRangeZoomable(true);

        // ОТКЛЮЧАЕМ встроенное перекрестие JFreeChart
        chartPanel.setHorizontalAxisTrace(false);
        chartPanel.setVerticalAxisTrace(false);

        // Настройка зума
        chartPanel.setZoomOutlinePaint(new Color(0, 0, 255, 100));
        chartPanel.setZoomFillPaint(new Color(0, 0, 255, 30));

        // Отключаем стандартное контекстное меню
        chartPanel.setPopupMenu(null);

        // Включаем отображение координат
        chartPanel.setDisplayToolTips(true);

        // Создаем кастомное перекрестие
        final CrosshairOverlay crosshairOverlay = new CrosshairOverlay();
        final JFreeChart chart = chartPanel.getChart();

        if (chart != null) {
            XYPlot plot = chart.getXYPlot();

            // Устанавливаем наш оверлей вместо стандартного
            plot.addAnnotation(new XYPointerAnnotation("", 0, 0, 0));
        }

        // Обработчик для отображения координат и управления
        chartPanel.addChartMouseListener(new ChartMouseListener() {
            @Override
            public void chartMouseClicked(ChartMouseEvent event) {
                if (event.getTrigger().getButton() == MouseEvent.BUTTON3) {
                    // Правый клик - сброс масштаба
                    chartPanel.restoreAutoBounds();
                    chartPanel.repaint();

                    // Показываем сообщение
                    chartPanel.setToolTipText("Масштаб сброшен");
                }
            }

            @Override
            public void chartMouseMoved(ChartMouseEvent event) {
                // Получаем координаты мыши
                int mouseX = event.getTrigger().getX();
                int mouseY = event.getTrigger().getY();

                // Получаем график и его область отрисовки
                if (chart != null) {
                    XYPlot plot = (XYPlot) chart.getPlot();
                    Rectangle2D dataArea = chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea();

                    if (dataArea.contains(mouseX, mouseY)) {
                        // Конвертируем координаты мыши в значения графика
                        double x = plot.getDomainAxis().java2DToValue(
                                mouseX, dataArea, plot.getDomainAxisEdge());
                        double y = plot.getRangeAxis().java2DToValue(
                                mouseY, dataArea, plot.getRangeAxisEdge());

                        // Показываем координаты в тултипе
                        chartPanel.setToolTipText(String.format(
                                "<html>Время: <b>%.3f сек</b><br>Значение: <b>%.6f</b></html>", x, y));

                        // Перерисовываем только содержимое (без накопления)
                        chartPanel.paintImmediately(dataArea.getBounds());
                    }
                }
            }
        });

        // Добавляем обработчик для очистки при изменении размера
        chartPanel.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                SwingUtilities.invokeLater(() -> {
                    chartPanel.repaint();
                });
            }
        });

        // Устанавливаем режим двойной буферизации
        chartPanel.setRefreshBuffer(true);
    }
    public JPanel createSimpleSignalChart(List<Double> data, int sampleRate, String title) {
        XYSeries series = new XYSeries("Сигнал");

        int step = Math.max(1, data.size() / 10000); // Ограничиваем количество точек

        for (int i = 0; i < data.size(); i += step) {
            double timeInSeconds = (double) i / sampleRate;
            series.add(timeInSeconds, data.get(i));
        }

        XYSeriesCollection dataset = new XYSeriesCollection(series);
        JFreeChart chart = ChartFactory.createXYLineChart(
                title,
                "Время (секунды)",
                "Амплитуда",
                dataset,
                PlotOrientation.VERTICAL,
                true, true, false
        );

        customizeChart(chart);

        ChartPanel chartPanel = new ChartPanel(chart);
        configureZooming(chartPanel);

        return chartPanel;
    }
    public JPanel createSimpleVarianceChart(List<Double> variances, String title) {
        XYSeries series = new XYSeries("Дисперсия");

        for (int i = 0; i < variances.size(); i++) {
            series.add(i + 1, variances.get(i));
        }

        XYSeriesCollection dataset = new XYSeriesCollection(series);
        JFreeChart chart = ChartFactory.createXYLineChart(
                title,
                "Секунда",
                "Дисперсия",
                dataset,
                PlotOrientation.VERTICAL,
                true, true, false
        );

        customizeChart(chart);

        ChartPanel chartPanel = new ChartPanel(chart);
        configureZooming(chartPanel);

        return chartPanel;
    }
}