package org.example.transport_noise.gui;

import org.example.transport_noise.chart.ChartBuilder;
import org.example.transport_noise.model.DetectionEvent;
import org.example.transport_noise.service.*;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartMouseEvent;
import org.jfree.chart.ChartMouseListener;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

public class MainFrame extends JFrame {
    private ChartBuilder chartBuilder;
    private DatabaseService dbService;
    private JComboBox<String> fileNameSelect;
    private JComboBox<String> traceSelect;
    private JTextField startTimeField;
    private JTextField endTimeField;
    private JPanel chartPanel;
    private JTextArea infoArea;
    private MapPanel mapPanel;
    private JTable dataTable;
    private DefaultTableModel tableModel;
    private List<Integer> traceNumbers = new ArrayList<>();
    private List<String> fileNames = new ArrayList<>();
    private JLabel statusLabel;

    private Timer refreshTimer;
    private volatile boolean isRefreshing = false;
    private long lastRecordCount = 0;
    private volatile String cachedThreeComponentReport;

    private static final Color DARK_BG = new Color(43, 43, 43);
    private static final Color DARKER_BG = new Color(30, 30, 30);
    private static final Color ACCENT_COLOR = new Color(75, 110, 175);
    private static final Color DANGER_COLOR = new Color(200, 80, 80);
    private static final Color APPLY_COLOR = new Color(0, 180, 100);

    // Параметры STA/LTA
    private double currentStaSec = TransportDetectionTuning.defaultStaSec();
    private double currentLtaSec = TransportDetectionTuning.defaultLtaSec();
    private double currentStaThreshold = TransportDetectionTuning.defaultThreshold();

    // Параметры энергетического метода
    private double currentEnergyWindowSec = 0.5;
    private double currentEnergyThreshold = 1e-10;

    // Параметры SNR
    private double currentSnrStaSec = 0.5;
    private double currentSnrLtaSec = 5.0;
    private double currentSnrThreshold = 2.0;

    // Фильтр
    private boolean currentFilterEnabled = true;
    private double currentLowCutHz = 2.0;
    private double currentHighCutHz = 100.0;

    // Режим работы
    private boolean useRawSignal = false;  // false = дисперсия (по умолчанию), true = сырой сигнал
    private double autoK = 3.0;            // коэффициент k для авто-порога
    private int varianceWindowSec = 1;     // окно для дисперсии (сек)

    // Поля ввода
    private JTextField staField;
    private JTextField ltaField;
    private JTextField staThresholdField;
    private JTextField energyWindowField;
    private JTextField energyThresholdField;
    private JTextField snrStaField;
    private JTextField snrLtaField;
    private JTextField snrThresholdField;
    private JCheckBox rawSignalCheck;
    private JTextField kField;
    private JTextField varianceWindowField;
    private JCheckBox filterEnabled;
    private JTextField lowCutField;
    private JTextField highCutField;
    private JScrollPane chartScrollPane;

    private String activeAnalysisMode = null;

    public MainFrame() {
        chartBuilder = new ChartBuilder();
        dbService = DatabaseService.getInstance();

        setTitle("Анализ транспортного шума");
        setSize(1200, 800);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        add(createTopPanel(), BorderLayout.NORTH);
        add(createCenterPanel(), BorderLayout.CENTER);
        add(createStatusBar(), BorderLayout.SOUTH);

        refreshAllDataAsync();
        startAutoRefresh();
    }

    private void startAutoRefresh() {
        refreshTimer = new Timer(10000, e -> {
            if (!isRefreshing) checkAndRefreshIfNeeded();
        });
        refreshTimer.start();
    }

    private void checkAndRefreshIfNeeded() {
        new SwingWorker<Long, Void>() {
            @Override
            protected Long doInBackground() throws Exception {
                try (Connection conn = DriverManager.getConnection(
                        "jdbc:postgresql://localhost:5432/noisedb", "postgres", "32676");
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM signal_data")) {
                    if (rs.next()) return rs.getLong(1);
                }
                return 0L;
            }
            @Override
            protected void done() {
                try {
                    long newCount = get();
                    if (newCount != lastRecordCount) {
                        lastRecordCount = newCount;
                        updateStatus("Обновление...");
                        refreshAllDataAsync();
                    }
                } catch (Exception e) { System.err.println("Ошибка: " + e.getMessage()); }
            }
        }.execute();
    }

    private void refreshAllDataAsync() {
        if (isRefreshing) return;
        isRefreshing = true;
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                refreshFileList();
                refreshDataTable();
                updateInfoPanelData();
                return null;
            }
            @Override
            protected void done() {
                isRefreshing = false;
                updateStatus("✅ Готово | Записей: " + lastRecordCount);
            }
        }.execute();
    }

    private void refreshFileList() {
        try {
            List<String> newFileNames = dbService.getFileNames();
            SwingUtilities.invokeLater(() -> {
                String selectedFile = (String) fileNameSelect.getSelectedItem();
                fileNameSelect.removeAllItems();
                if (newFileNames.isEmpty()) fileNameSelect.addItem("Нет данных");
                else {
                    for (String name : newFileNames) fileNameSelect.addItem(name);
                    if (selectedFile != null && newFileNames.contains(selectedFile))
                        fileNameSelect.setSelectedItem(selectedFile);
                }
                fileNames = newFileNames;
            });
        } catch (SQLException e) { System.err.println("Ошибка: " + e.getMessage()); }
    }

    private void refreshDataTable() {
        String sql = "SELECT SUBSTRING(d.file_name FROM '^(.*?)\\.') as base_name, " +
                "d.trace_number, MIN(d.record_time) as start_time, COUNT(*) as samples, " +
                "d.latitude, d.longitude FROM signal_data d " +
                "GROUP BY base_name, d.trace_number, d.latitude, d.longitude " +
                "ORDER BY base_name, d.trace_number LIMIT 100";
        try (Connection conn = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/noisedb", "postgres", "32676");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            List<Object[]> rows = new ArrayList<>();
            while (rs.next()) {
                double lat = rs.getDouble("latitude"), lon = rs.getDouble("longitude");
                String coords = (lat != 0 || lon != 0) ? String.format("%.4f, %.4f", lat, lon) : "—";
                rows.add(new Object[]{rs.getString("base_name"), rs.getInt("trace_number"),
                        rs.getTimestamp("start_time").toString(), String.format("%,d", rs.getInt("samples")), coords});
            }
            SwingUtilities.invokeLater(() -> {
                tableModel.setRowCount(0);
                for (Object[] row : rows) tableModel.addRow(row);
            });
        } catch (SQLException e) { System.err.println("Ошибка: " + e.getMessage()); }
    }

    private void updateInfoPanelData() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ИНФОРМАЦИЯ О ДАННЫХ ===\n\n");
        try (Connection conn = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/noisedb", "postgres", "32676")) {
            String sql = "SELECT COUNT(DISTINCT SUBSTRING(file_name FROM '^(.*?)\\.')) as files, " +
                    "COUNT(DISTINCT trace_number) as traces, COUNT(*) as total, " +
                    "MIN(record_time) as first, MAX(record_time) as last FROM signal_data";
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    sb.append("Файлов: ").append(rs.getInt("files")).append("\n");
                    sb.append("Трасс: ").append(rs.getInt("traces")).append("\n");
                    sb.append("Отсчётов: ").append(String.format("%,d", rs.getInt("total"))).append("\n");
                }
            }
        } catch (SQLException e) { sb.append("Ошибка: ").append(e.getMessage()).append("\n"); }
        if (cachedThreeComponentReport != null && !cachedThreeComponentReport.isBlank())
            sb.append("\n").append(cachedThreeComponentReport);
        final String text = sb.toString();
        SwingUtilities.invokeLater(() -> { infoArea.setText(text); infoArea.setCaretPosition(0); });
    }

    private void updateStatus(String message) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(message));
    }

    // ==================== TOP PANEL ====================

    private JPanel createTopPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.setBackground(DARKER_BG);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(label("Файл:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        fileNameSelect = new JComboBox<>();
        fileNameSelect.setPreferredSize(new Dimension(200, 30));
        fileNameSelect.addActionListener(e -> onFileSelected());
        panel.add(fileNameSelect, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1;
        panel.add(label("Компонента:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        traceSelect = new JComboBox<>();
        traceSelect.setPreferredSize(new Dimension(100, 30));
        traceSelect.addActionListener(e -> onTraceSelected());
        panel.add(traceSelect, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1;
        panel.add(label("Начало:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        startTimeField = textField("2025-01-01 00:00:00", 200);
        panel.add(startTimeField, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 1;
        panel.add(label("Конец:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        endTimeField = textField("2025-12-31 23:59:59", 200);
        panel.add(endTimeField, gbc);

        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 3;
        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        buttonRow.setBackground(DARKER_BG);
        buttonRow.add(styledButton("📈 Сигнал", ACCENT_COLOR, e -> loadAndShowSignal()));
        buttonRow.add(styledButton("📊 Дисперсия", new Color(70, 150, 70), e -> loadAndShowVariance()));
        buttonRow.add(styledButton("📋 Сигнал + Дисперсия", new Color(200, 150, 50), e -> loadAndShowBoth()));
        buttonRow.add(styledButton("🔬 3 компонентный Анализ", new Color(120, 60, 180), e -> showThreeComponentResults()));
        buttonRow.add(styledButton("📉 Анализ 1 компоненты", new Color(70, 130, 200), e -> showSingleTraceStaLta()));
        buttonRow.add(styledButton("📉 График метода", new Color(70, 130, 200), e -> showStaLtaThresholdChart()));
        buttonRow.add(styledButton("📋 Отчет", new Color(0, 150, 136), e -> showComparisonReport()));
        buttonRow.add(styledButton("🗑️ Очистить БД", DANGER_COLOR, e -> clearDatabase()));
        panel.add(buttonRow, gbc);
        return panel;
    }

    // ==================== ПАНЕЛЬ ПАРАМЕТРОВ ====================

    private JPanel createAnalysisParamsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(DARKER_BG);

        // Режим работы
        JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        modeRow.setBackground(DARKER_BG);
        rawSignalCheck = new JCheckBox("Сырой сигнал");
        rawSignalCheck.setSelected(useRawSignal);
        rawSignalCheck.setBackground(DARKER_BG); rawSignalCheck.setForeground(Color.WHITE);
        rawSignalCheck.addActionListener(e -> toggleModeFields());
        modeRow.add(rawSignalCheck);
        modeRow.add(label("k:"));
        kField = new JTextField(String.valueOf(autoK), 3);
        kField.setPreferredSize(new Dimension(40, 28));
        kField.setBackground(DARK_BG); kField.setForeground(Color.WHITE);
        modeRow.add(kField);
        modeRow.add(label("Окно дисп.(с):"));
        varianceWindowField = new JTextField(String.valueOf(varianceWindowSec), 3);
        varianceWindowField.setPreferredSize(new Dimension(40, 28));
        varianceWindowField.setBackground(DARK_BG); varianceWindowField.setForeground(Color.WHITE);
        modeRow.add(varianceWindowField);
        panel.add(modeRow);

        // STA/LTA
        JPanel staLtaRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        staLtaRow.setBackground(DARKER_BG);
        staLtaRow.add(label("STA(с):"));
        staField = new JTextField(String.valueOf(currentStaSec), 4);
        staField.setPreferredSize(new Dimension(50, 28));
        staField.setBackground(DARK_BG); staField.setForeground(Color.WHITE);
        staLtaRow.add(staField);
        staLtaRow.add(label("LTA(с):"));
        ltaField = new JTextField(String.valueOf(currentLtaSec), 4);
        ltaField.setPreferredSize(new Dimension(50, 28));
        ltaField.setBackground(DARK_BG); ltaField.setForeground(Color.WHITE);
        staLtaRow.add(ltaField);
        staLtaRow.add(label("Порог STA/LTA:"));
        staThresholdField = new JTextField(String.format("%.2f", currentStaThreshold), 4);
        staThresholdField.setPreferredSize(new Dimension(50, 28));
        staThresholdField.setBackground(DARK_BG); staThresholdField.setForeground(Color.WHITE);
        staLtaRow.add(staThresholdField);
        panel.add(staLtaRow);

        // Энергия
        JPanel energyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        energyRow.setBackground(DARKER_BG);
        energyRow.add(label("Окно энергии(с):"));
        energyWindowField = new JTextField(String.valueOf(currentEnergyWindowSec), 4);
        energyWindowField.setPreferredSize(new Dimension(50, 28));
        energyWindowField.setBackground(DARK_BG); energyWindowField.setForeground(Color.WHITE);
        energyRow.add(energyWindowField);
        energyRow.add(label("Порог энергии:"));
        energyThresholdField = new JTextField(String.valueOf(currentEnergyThreshold), 10);
        energyThresholdField.setPreferredSize(new Dimension(100, 28));
        energyThresholdField.setBackground(DARK_BG); energyThresholdField.setForeground(Color.WHITE);
        energyRow.add(energyThresholdField);
        panel.add(energyRow);

        // SNR
        JPanel snrRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        snrRow.setBackground(DARKER_BG);
        snrRow.add(label("SNR STA/2(с):"));
        snrStaField = new JTextField(String.valueOf(currentSnrStaSec), 4);
        snrStaField.setPreferredSize(new Dimension(50, 28));
        snrStaField.setBackground(DARK_BG); snrStaField.setForeground(Color.WHITE);
        snrRow.add(snrStaField);
        snrRow.add(label("SNR LTA/2(с):"));
        snrLtaField = new JTextField(String.valueOf(currentSnrLtaSec), 4);
        snrLtaField.setPreferredSize(new Dimension(50, 28));
        snrLtaField.setBackground(DARK_BG); snrLtaField.setForeground(Color.WHITE);
        snrRow.add(snrLtaField);
        snrRow.add(label("Порог SNR:"));
        snrThresholdField = new JTextField(String.valueOf(currentSnrThreshold), 4);
        snrThresholdField.setPreferredSize(new Dimension(50, 28));
        snrThresholdField.setBackground(DARK_BG); snrThresholdField.setForeground(Color.WHITE);
        snrRow.add(snrThresholdField);
        panel.add(snrRow);

        // Фильтр
        JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        filterRow.setBackground(DARKER_BG);
        filterEnabled = new JCheckBox("Фильтр Баттерворта");
        filterEnabled.setSelected(currentFilterEnabled);
        filterEnabled.setBackground(DARKER_BG); filterEnabled.setForeground(Color.WHITE);
        filterRow.add(filterEnabled);
        filterRow.add(label("Низ(Гц):"));
        lowCutField = new JTextField(String.valueOf(currentLowCutHz), 4);
        lowCutField.setPreferredSize(new Dimension(50, 28));
        lowCutField.setBackground(DARK_BG); lowCutField.setForeground(Color.WHITE);
        filterRow.add(lowCutField);
        filterRow.add(label("Верх(Гц):"));
        highCutField = new JTextField(String.valueOf(currentHighCutHz), 4);
        highCutField.setPreferredSize(new Dimension(50, 28));
        highCutField.setBackground(DARK_BG); highCutField.setForeground(Color.WHITE);
        filterRow.add(highCutField);
        filterRow.add(new JLabel("  "));
        JButton applyBtn = new JButton("✓ Применить");
        applyBtn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        applyBtn.setBackground(APPLY_COLOR); applyBtn.setForeground(Color.WHITE);
        applyBtn.setFocusPainted(false); applyBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        applyBtn.addActionListener(e -> applyParams());
        filterRow.add(applyBtn);
        panel.add(filterRow);

        toggleModeFields();
        return panel;
    }

    private void toggleModeFields() {
        boolean raw = rawSignalCheck.isSelected();
        staThresholdField.setVisible(raw);
        energyThresholdField.setVisible(raw);
        snrThresholdField.setVisible(raw);
        kField.setVisible(!raw);
        varianceWindowField.setVisible(!raw);
    }

    private void applyParams() {
        try { currentStaSec = Double.parseDouble(staField.getText().trim()); if (currentStaSec <= 0) currentStaSec = 1.0; } catch (NumberFormatException e) {}
        try { currentLtaSec = Double.parseDouble(ltaField.getText().trim()); if (currentLtaSec <= currentStaSec) currentLtaSec = currentStaSec + 0.1; } catch (NumberFormatException e) {}
        try { currentStaThreshold = Double.parseDouble(staThresholdField.getText().trim()); if (currentStaThreshold <= 0) currentStaThreshold = 2.0; } catch (NumberFormatException e) {}
        try { currentEnergyWindowSec = Double.parseDouble(energyWindowField.getText().trim()); if (currentEnergyWindowSec <= 0) currentEnergyWindowSec = 0.5; } catch (NumberFormatException e) {}
        try { currentEnergyThreshold = Double.parseDouble(energyThresholdField.getText().trim()); if (currentEnergyThreshold <= 0) currentEnergyThreshold = 1e-10; } catch (NumberFormatException e) {}
        try { currentSnrStaSec = Double.parseDouble(snrStaField.getText().trim()); if (currentSnrStaSec <= 0) currentSnrStaSec = 0.5; } catch (NumberFormatException e) {}
        try { currentSnrLtaSec = Double.parseDouble(snrLtaField.getText().trim()); if (currentSnrLtaSec <= currentSnrStaSec) currentSnrLtaSec = currentSnrStaSec + 0.1; } catch (NumberFormatException e) {}
        try { currentSnrThreshold = Double.parseDouble(snrThresholdField.getText().trim()); if (currentSnrThreshold <= 0) currentSnrThreshold = 2.0; } catch (NumberFormatException e) {}
        currentFilterEnabled = filterEnabled.isSelected();
        try { currentLowCutHz = Double.parseDouble(lowCutField.getText().trim()); if (currentLowCutHz < 0.1) currentLowCutHz = 0.5; } catch (NumberFormatException e) {}
        try { currentHighCutHz = Double.parseDouble(highCutField.getText().trim()); if (currentHighCutHz <= currentLowCutHz) currentHighCutHz = currentLowCutHz + 10; } catch (NumberFormatException e) {}
        useRawSignal = rawSignalCheck.isSelected();
        try { autoK = Double.parseDouble(kField.getText().trim()); if (autoK <= 0) autoK = 3.0; } catch (NumberFormatException e) {}
        try { varianceWindowSec = Integer.parseInt(varianceWindowField.getText().trim()); if (varianceWindowSec <= 0) varianceWindowSec = 1; } catch (NumberFormatException e) {}

        updateStatus("✅ Параметры применены");
        reloadCurrentChart();
    }

    // ==================== ОБРАБОТКА СИГНАЛА ====================

    /**
     * Преобразует сырой сигнал в дисперсию в скользящем окне.
     */
    private List<Double> signalToVariance(List<Double> signal, int windowSamples) {
        List<Double> variance = new ArrayList<>();
        if (signal.size() < windowSamples) return variance;
        for (int i = windowSamples; i < signal.size(); i++) {
            double sum = 0, sumSq = 0;
            for (int j = i - windowSamples; j <= i; j++) {
                double v = signal.get(j);
                sum += v;
                sumSq += v * v;
            }
            double mean = sum / windowSamples;
            double var = sumSq / windowSamples - mean * mean;
            variance.add(Math.abs(var));
        }
        return variance;
    }

    /**
     * Вычисляет автоматический порог по медиане и k·σ.
     */
    private double computeAutoThreshold(List<Double> signal, double k) {
        List<Double> sorted = new ArrayList<>(signal);
        Collections.sort(sorted);
        double median = sorted.get(sorted.size() / 2);
        double q1 = sorted.get(sorted.size() / 4);
        double q3 = sorted.get(3 * sorted.size() / 4);
        double sigma = (q3 - q1) / 1.35;
        double threshold = median + k * sigma;
        System.out.printf("  📊 Авто-порог (размерный): медиана=%.3e, σ=%.3e, k=%.1f, порог=%.3e%n",
                median, sigma, k, threshold);
        return threshold;
    }

    /**
     * Вычисляет автоматический порог из безразмерного отношения (STA/LTA, SNR).
     * Медиана и σ считаются по самому отношению, а не по дисперсии.
     */
    private double computeAutoThresholdFromRatio(double[] ratio, double k) {
        List<Double> values = new ArrayList<>();
        for (double r : ratio) {
            if (r > 0 && !Double.isNaN(r) && !Double.isInfinite(r)) {
                values.add(r);
            }
        }
        if (values.isEmpty()) return 2.0;

        Collections.sort(values);
        double median = values.get(values.size() / 2);
        double q1 = values.get(values.size() / 4);
        double q3 = values.get(3 * values.size() / 4);
        double sigma = (q3 - q1) / 1.35;

        double threshold = median + k * sigma;
        System.out.printf("  📊 Авто-порог (безразмерный): медиана=%.3f, σ=%.3f, k=%.1f, порог=%.3f%n",
                median, sigma, k, threshold);
        return threshold;
    }

    /**
     * Подготавливает сигнал и пороги для детекторов.
     */
    private ProcessedSignal prepareSignal(List<Double> rawSignal, int fs) {
        if (useRawSignal) {
            return new ProcessedSignal(rawSignal, currentStaThreshold, currentEnergyThreshold, currentSnrThreshold);
        } else {
            int windowSamples = varianceWindowSec * fs;
            List<Double> varSignal = signalToVariance(rawSignal, windowSamples);

            // 1. STA/LTA — безразмерный порог из отношения
            int sta = TransportDetectionTuning.staSamples(fs, currentStaSec);
            int lta = TransportDetectionTuning.ltaSamples(fs, currentStaSec, currentLtaSec);
            double[] ratio = StaLtaEventDetector.absStaLtaRatio(varSignal, sta, lta);
            double staThr = computeAutoThresholdFromRatio(ratio, autoK);

            // 2. Энергия — безразмерный порог из отношения к фону
            double energyThr = computeAutoThresholdFromRatio(
                    normalizedEnergy(varSignal, windowSamples), autoK);

            // 3. SNR — безразмерный порог из отношения
            int halfSta = Math.max(1, (int)(currentSnrStaSec * fs / 2));
            int halfLta = Math.max(halfSta + 1, (int)(currentSnrLtaSec * fs / 2));
            double[] snrRatio = SnrDetector.snrRatio(varSignal, halfSta, halfLta);
            double snrThr = computeAutoThresholdFromRatio(snrRatio, autoK);

            System.out.printf("  📊 Пороги: STA/LTA=%.3f, Энергия=%.3f, SNR=%.3f%n",
                    staThr, energyThr, snrThr);

            return new ProcessedSignal(varSignal, staThr, energyThr, snrThr);
        }
    }

    /**
     * Нормализует энергию: E(i) / E_фон (безразмерная).
     */
    private double[] normalizedEnergy(List<Double> signal, int windowSamples) {
        int backgroundSamples = Math.max(windowSamples, signal.size() / 10);
        double backgroundEnergy = 0;
        for (int i = 0; i < backgroundSamples; i++) {
            double v = signal.get(i);
            backgroundEnergy += v * v;
        }
        backgroundEnergy /= backgroundSamples;
        if (backgroundEnergy < 1e-20) backgroundEnergy = 1e-20;

        double[] normalized = new double[signal.size()];
        for (int i = 0; i < signal.size(); i++) {
            int start = Math.max(0, i - windowSamples);
            double sum = 0;
            for (int j = start; j <= i; j++) {
                double v = signal.get(j);
                sum += v * v;
            }
            double energy = sum / (i - start + 1);
            normalized[i] = energy / backgroundEnergy;
        }
        return normalized;
    }

    private record ProcessedSignal(List<Double> signal, double staThr, double energyThr, double snrThr) {}

    // ==================== ОБЕРТКА ГРАФИКА ====================

    private JPanel wrapChartWithParams(JComponent chartContent, boolean showParams) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(DARKER_BG);
        if (showParams) wrapper.add(createAnalysisParamsPanel(), BorderLayout.NORTH);
        wrapper.add(chartContent, BorderLayout.CENTER);
        return wrapper;
    }

    private void showChartWithParams(JComponent chartContent, boolean showParams) {
        chartPanel.removeAll();
        chartPanel.add(wrapChartWithParams(chartContent, showParams), BorderLayout.CENTER);
        chartPanel.revalidate();
        chartPanel.repaint();
    }

    // ==================== ФИЛЬТР ====================

    private List<Double> applyFilterIfEnabled(List<Double> signal, int fs) {
        if (!currentFilterEnabled) return signal;
        return ButterworthBandpass.filter(new ArrayList<>(signal), fs, currentLowCutHz, currentHighCutHz);
    }

    private double getLowCutHzForDisplay(int fs) {
        return currentFilterEnabled ? currentLowCutHz : Math.max(0.5, fs / 500.0);
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================

    private JLabel label(String t) { JLabel l = new JLabel(t); l.setForeground(Color.WHITE); return l; }

    private JTextField textField(String t, int w) {
        JTextField f = new JTextField(t); f.setPreferredSize(new Dimension(w, 30));
        f.setBackground(DARK_BG); f.setForeground(Color.WHITE); f.setCaretColor(Color.WHITE);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)));
        return f;
    }

    private JButton styledButton(String text, Color bg, java.awt.event.ActionListener al) {
        JButton b = new JButton(text); b.setFont(new Font("Segoe UI", Font.BOLD, 12));
        b.setBackground(bg); b.setForeground(Color.WHITE);
        b.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
        b.setFocusPainted(false); b.setCursor(new Cursor(Cursor.HAND_CURSOR));
        b.addActionListener(al);
        return b;
    }

    // ==================== CENTER PANEL ====================

    private JPanel createCenterPanel() {
        JTabbedPane tabs = new JTabbedPane();
        chartPanel = new JPanel(new BorderLayout());
        chartScrollPane = new JScrollPane(chartPanel);
        chartScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        chartScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        tabs.addTab("Графики", chartScrollPane);
        tableModel = new DefaultTableModel(new String[]{"Файл", "Компонента", "Начало", "Отсчётов", "Координаты"}, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
        };
        dataTable = new JTable(tableModel);
        tabs.addTab("Данные", new JScrollPane(dataTable));
        infoArea = new JTextArea(); infoArea.setEditable(false);
        infoArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        tabs.addTab("Информация", new JScrollPane(infoArea));
        mapPanel = new MapPanel();
        tabs.addTab("Карта", mapPanel);
        JPanel p = new JPanel(new BorderLayout());
        p.add(tabs, BorderLayout.CENTER);
        return p;
    }

    private JPanel createStatusBar() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
        statusLabel = new JLabel("✅ Готов");
        p.add(statusLabel, BorderLayout.WEST);
        return p;
    }

    // ==================== FILE / TRACE ====================

    private void onFileSelected() {
        String f = getSelectedFile();
        if (f == null) return;
        try {
            List<Integer> tr = dbService.getTraceNumbersForFile(f);
            traceNumbers = tr;
            traceSelect.removeAllItems();
            if (tr.isEmpty()) traceSelect.addItem("Нет данных");
            else for (Integer t : tr) traceSelect.addItem(String.valueOf(t));
        } catch (SQLException e) { System.err.println(e.getMessage()); }
    }

    private void onTraceSelected() {
        String f = getSelectedFile(); String ts = (String) traceSelect.getSelectedItem();
        if (f == null || ts == null) return;
        try {
            int tn = Integer.parseInt(ts);
            String sql = "SELECT latitude, longitude FROM signal_data WHERE file_name LIKE ? AND trace_number = ? LIMIT 1";
            try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5432/noisedb", "postgres", "32676");
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, f + ".%"); ps.setInt(2, tn);
                ResultSet rs = ps.executeQuery();
                if (rs.next() && mapPanel != null) {
                    double lat = rs.getDouble("latitude"), lon = rs.getDouble("longitude");
                    if (lat != 0 || lon != 0) mapPanel.showLocation(lat, lon, f + " тр." + tn);
                }
            }
        } catch (Exception e) { System.err.println(e.getMessage()); }
    }

    private String getSelectedFile() {
        String s = (String) fileNameSelect.getSelectedItem();
        return (s != null && !s.equals("Нет данных")) ? s : null;
    }

    private int getSelectedTrace() {
        String s = (String) traceSelect.getSelectedItem();
        if (s == null || s.equals("Нет данных")) return 0;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    // ==================== LOAD & SHOW ====================

    private void loadAndShowSignal() {
        activeAnalysisMode = "signal";
        String fn = getSelectedFile(); int tr = getSelectedTrace();
        if (fn == null || tr == 0) { JOptionPane.showMessageDialog(this, "Выберите файл и трассу"); return; }
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() throws Exception {
                chartPanel.removeAll();
                List<Double> d = dbService.getSignalData(fn, tr, startTimeField.getText().trim(), endTimeField.getText().trim());
                if (d.isEmpty()) { showChartWithParams(new JLabel("Нет данных", SwingConstants.CENTER), true); return null; }
                int fs = 1000;
                List<Double> filt = applyFilterIfEnabled(d, fs);

                // Подготавливаем сигнал и пороги
                ProcessedSignal ps = prepareSignal(filt, fs);
                List<Double> procSignal = ps.signal();
                double staThr = ps.staThr();
                double energyThr = ps.energyThr();
                double snrThr = ps.snrThr();

                // Временные метки для обработанного сигнала
                List<Double> tt = new ArrayList<>();
                int offset = useRawSignal ? 0 : varianceWindowSec * fs;
                for (int i = 0; i < procSignal.size(); i++) {
                    tt.add((double) (i + offset) / fs);
                }

                // STA/LTA
                int sta = TransportDetectionTuning.staSamples(fs, currentStaSec);
                int lta = TransportDetectionTuning.ltaSamples(fs, currentStaSec, currentLtaSec);
                ThreeComponentAnalyzer.DetectionParams p = TransportDetectionTuning.params(fs, currentStaSec, currentLtaSec, staThr);
                double[] ratio = StaLtaEventDetector.absStaLtaRatio(procSignal, sta, lta);
                List<DetectionEvent> staLtaEvents = StaLtaEventDetector.pickEventsFromRatio(
                        ratio, lta, p.staLtaThreshold, p.offRatio, p.hangSamples, p.minDurationSamples, p.cooldownSamples, fn + " тр." + tr);

                // Энергетический
                List<DetectionEvent> energyEvents = EnergyDetector.detect(procSignal, fs, currentEnergyWindowSec, energyThr, fn + " тр." + tr);

                // SNR
                List<DetectionEvent> snrEvents = SnrDetector.detect(procSignal, fs, currentSnrStaSec, currentSnrLtaSec, snrThr, fn + " тр." + tr);

                JTabbedPane tabs = new JTabbedPane();
                String modeLabel = useRawSignal ? "сырой" : "дисперсия";
                tabs.addTab("Сигнал", chartBuilder.createSignalWithAllEvents(tt, procSignal, staLtaEvents, "Сигнал (" + modeLabel + ") - " + fn + " тр." + tr));
                tabs.addTab("Энергия", chartBuilder.createSignalWithAllEvents(tt, procSignal, energyEvents, "Энергетический (" + modeLabel + ") - " + fn + " тр." + tr));
                tabs.addTab("SNR", chartBuilder.createSignalWithAllEvents(tt, procSignal, snrEvents, "SNR (" + modeLabel + ") - " + fn + " тр." + tr));
                showChartWithParams(tabs, true);
                return null;
            }
            protected void done() { chartPanel.revalidate(); chartPanel.repaint(); setCursor(Cursor.getDefaultCursor()); }
        }.execute();
    }

    private void loadAndShowVariance() {
        String fn = getSelectedFile(); int tr = getSelectedTrace();
        if (fn == null || tr == 0) { JOptionPane.showMessageDialog(this, "Выберите файл и трассу"); return; }
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() throws Exception {
                chartPanel.removeAll();
                List<Double> d = dbService.getVarianceData(fn, tr, startTimeField.getText().trim(), endTimeField.getText().trim());

                // ВЫВОД ДИАГНОСТИКИ В КОНСОЛЬ
                System.out.println("\n📊 ДИАГНОСТИКА ДИСПЕРСИИ:");
                System.out.println("  Файл: " + fn);
                System.out.println("  Трасса: " + tr);
                System.out.println("  Записей дисперсии: " + d.size());

                // Прямой SQL-запрос для проверки
                try (Connection conn = DriverManager.getConnection(
                        "jdbc:postgresql://localhost:5432/noisedb", "postgres", "32676");
                     Statement stmt = conn.createStatement()) {

                    // Сколько всего записей в signal_statistics для этого файла
                    String sql1 = "SELECT COUNT(*) FROM signal_statistics WHERE file_name LIKE '" + fn + ".%'";
                    ResultSet rs1 = stmt.executeQuery(sql1);
                    if (rs1.next()) System.out.println("  Всего в signal_statistics: " + rs1.getInt(1));
                    rs1.close();

                    // Первые 5 записей
                    String sql2 = "SELECT file_name, second_number, variance FROM signal_statistics WHERE file_name LIKE '" + fn + ".%' ORDER BY second_number LIMIT 5";
                    ResultSet rs2 = stmt.executeQuery(sql2);
                    System.out.println("  Первые 5 записей:");
                    while (rs2.next()) {
                        System.out.printf("    %s | сек=%d | дисп=%.10f%n",
                                rs2.getString("file_name"),
                                rs2.getInt("second_number"),
                                rs2.getDouble("variance"));
                    }
                    rs2.close();

                    // Группировка по file_name
                    String sql3 = "SELECT file_name, COUNT(*) as cnt FROM signal_statistics GROUP BY file_name";
                    ResultSet rs3 = stmt.executeQuery(sql3);
                    System.out.println("  Группировка по файлам:");
                    while (rs3.next()) {
                        System.out.printf("    %s: %d записей%n", rs3.getString("file_name"), rs3.getInt("cnt"));
                    }
                    rs3.close();
                }

                JPanel chart = d.isEmpty() ? new JPanel() : chartBuilder.createSimpleVarianceChart(d, fn + " тр." + tr);
                showChartWithParams(chart, false);
                return null;
            }
            protected void done() { chartPanel.revalidate(); chartPanel.repaint(); setCursor(Cursor.getDefaultCursor()); }
        }.execute();
    }

    private void loadAndShowBoth() {
        String fn = getSelectedFile(); int tr = getSelectedTrace();
        if (fn == null || tr == 0) return;
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() throws Exception {
                chartPanel.removeAll();
                List<Double> s = dbService.getSignalData(fn, tr, startTimeField.getText().trim(), endTimeField.getText().trim());
                List<Double> v = dbService.getVarianceData(fn, tr, startTimeField.getText().trim(), endTimeField.getText().trim());
                JPanel c = new JPanel(new GridLayout(2, 1));
                if (!s.isEmpty()) c.add(chartBuilder.createSimpleSignalChart(s, 1000, "Сигнал"));
                if (!v.isEmpty()) c.add(chartBuilder.createSimpleVarianceChart(v, "Дисперсия"));
                showChartWithParams(c, false);
                return null;
            }
            protected void done() { chartPanel.revalidate(); chartPanel.repaint(); setCursor(Cursor.getDefaultCursor()); }
        }.execute();
    }

    // ==================== 3D / STA/LTA ====================

    private void showThreeComponentResults() {
        activeAnalysisMode = "3d";
        String fn = getSelectedFile();
        if (fn == null) { JOptionPane.showMessageDialog(this, "Выберите файл"); return; }
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<ThreeDAnalysisPack, Void>() {
            protected ThreeDAnalysisPack doInBackground() {
                // СНАЧАЛА бенчмарк (холодный запуск)
                benchmarkAnalysis(fn, 1000);
                // ПОТОМ графики
                return buildThreeDAnalysisPack(fn);
            }
            protected void done() {
                try {
                    ThreeDAnalysisPack p = get();
                    chartPanel.removeAll();
                    if (p.panel != null) showChartWithParams(p.panel, true);
                    else showChartWithParams(new JLabel("Нет данных", SwingConstants.CENTER), true);
                    if (p.report != null && !p.report.isEmpty()) {
                        cachedThreeComponentReport = p.report;
                        infoArea.setText(p.report); infoArea.setCaretPosition(0);
                    }
                } catch (Exception ex) { System.err.println(ex.getMessage()); }
                chartPanel.revalidate(); chartPanel.repaint(); setCursor(Cursor.getDefaultCursor());
            }
        }.execute();
    }

    private void benchmarkAnalysis(String fileName, int fs) {
        new Thread(() -> {
            try {
                System.out.println("\n⏱️  ═══════════════════════════════════════════");
                System.out.println("⏱️  ЗАМЕРЫ ВРЕМЕНИ ВЫПОЛНЕНИЯ");
                System.out.println("⏱️  ═══════════════════════════════════════════");

                try (Connection c = DriverManager.getConnection(
                        "jdbc:postgresql://localhost:5432/noisedb", "postgres", "32676")) {

                    List<Double> x1 = loadTraceForBase(c, fileName, 1);
                    List<Double> y1 = loadTraceForBase(c, fileName, 2);
                    List<Double> z1 = loadTraceForBase(c, fileName, 3);

                    if (x1.isEmpty()) {
                        System.out.println("  ❌ Нет данных для замеров");
                        return;
                    }

                    int m = Math.min(Math.min(x1.size(), y1.size()), z1.size());
                    x1 = x1.subList(0, m);
                    y1 = y1.subList(0, m);
                    z1 = z1.subList(0, m);

                    // --- СЛИЯНИЕ ТРЁХ КОМПОНЕНТ ---
                    long t0 = System.nanoTime();
                    List<Double> R = new ArrayList<>();
                    for (int i = 0; i < m; i++) {
                        R.add(Math.sqrt(x1.get(i)*x1.get(i) + y1.get(i)*y1.get(i) + z1.get(i)*z1.get(i)));
                    }
                    long mergeTime = System.nanoTime() - t0;

                    // Фильтрация
                    List<Double> xFilt = applyFilterIfEnabled(x1, fs);
                    List<Double> rFilt = applyFilterIfEnabled(R, fs);

                    // --- prepareSignal ДЛЯ 3 КОМПОНЕНТ (R) — ПЕРВЫМ ---
                    t0 = System.nanoTime();
                    ProcessedSignal ps3 = prepareSignal(rFilt, fs);
                    long prepTime3 = System.nanoTime() - t0;

                    // --- prepareSignal ДЛЯ 1 КОМПОНЕНТЫ — ВТОРЫМ ---
                    t0 = System.nanoTime();
                    ProcessedSignal ps1 = prepareSignal(xFilt, fs);
                    long prepTime1 = System.nanoTime() - t0;

                    // Параметры STA/LTA
                    int sta = TransportDetectionTuning.staSamples(fs, currentStaSec);
                    int lta = TransportDetectionTuning.ltaSamples(fs, currentStaSec, currentLtaSec);
                    ThreeComponentAnalyzer.DetectionParams p1 = TransportDetectionTuning.params(
                            fs, currentStaSec, currentLtaSec, ps1.staThr());
                    ThreeComponentAnalyzer.DetectionParams p3 = TransportDetectionTuning.params(
                            fs, currentStaSec, currentLtaSec, ps3.staThr());

                    // --- ТРЁХКОМПОНЕНТНЫЙ АНАЛИЗ (R) — ПЕРВЫМ ---
                    System.out.println("\n── Трёхкомпонентный анализ (R) ──");
                    System.out.printf("  Слияние X+Y+Z→R: %.1f мс%n", mergeTime / 1_000_000.0);
                    System.out.printf("  prepareSignal (дисперсия + пороги): %.1f мс%n", prepTime3 / 1_000_000.0);

                    t0 = System.nanoTime();
                    double[] ratio3 = StaLtaEventDetector.absStaLtaRatio(ps3.signal(), sta, lta);
                    StaLtaEventDetector.pickEventsFromRatio(ratio3, lta, p3.staLtaThreshold, p3.offRatio,
                            p3.hangSamples, p3.minDurationSamples, p3.cooldownSamples, "R");
                    long staLta3 = System.nanoTime() - t0;
                    System.out.printf("  STA/LTA: %.1f мс%n", staLta3 / 1_000_000.0);

                    t0 = System.nanoTime();
                    EnergyDetector.detect(ps3.signal(), fs, currentEnergyWindowSec, ps3.energyThr(), "R");
                    long energy3 = System.nanoTime() - t0;
                    System.out.printf("  Энергетический: %.1f мс%n", energy3 / 1_000_000.0);

                    t0 = System.nanoTime();
                    SnrDetector.detect(ps3.signal(), fs, currentSnrStaSec, currentSnrLtaSec, ps3.snrThr(), "R");
                    long snr3 = System.nanoTime() - t0;
                    System.out.printf("  SNR: %.1f мс%n", snr3 / 1_000_000.0);

                    long total3 = mergeTime + prepTime3 + staLta3 + energy3 + snr3;
                    System.out.printf("  ИТОГО (3 комп + слияние + подготовка): %.1f мс%n", total3 / 1_000_000.0);

                    // --- ОДНОКОМПОНЕНТНЫЙ АНАЛИЗ (X) — ВТОРЫМ ---
                    System.out.println("\n── Однокомпонентный анализ (X) ──");
                    System.out.printf("  prepareSignal (дисперсия + пороги): %.1f мс%n", prepTime1 / 1_000_000.0);

                    t0 = System.nanoTime();
                    double[] ratio1 = StaLtaEventDetector.absStaLtaRatio(ps1.signal(), sta, lta);
                    StaLtaEventDetector.pickEventsFromRatio(ratio1, lta, p1.staLtaThreshold, p1.offRatio,
                            p1.hangSamples, p1.minDurationSamples, p1.cooldownSamples, "X");
                    long staLta1 = System.nanoTime() - t0;
                    System.out.printf("  STA/LTA: %.1f мс%n", staLta1 / 1_000_000.0);

                    t0 = System.nanoTime();
                    EnergyDetector.detect(ps1.signal(), fs, currentEnergyWindowSec, ps1.energyThr(), "X");
                    long energy1 = System.nanoTime() - t0;
                    System.out.printf("  Энергетический: %.1f мс%n", energy1 / 1_000_000.0);

                    t0 = System.nanoTime();
                    SnrDetector.detect(ps1.signal(), fs, currentSnrStaSec, currentSnrLtaSec, ps1.snrThr(), "X");
                    long snr1 = System.nanoTime() - t0;
                    System.out.printf("  SNR: %.1f мс%n", snr1 / 1_000_000.0);

                    long total1 = prepTime1 + staLta1 + energy1 + snr1;
                    System.out.printf("  ИТОГО (1 комп): %.1f мс%n", total1 / 1_000_000.0);

                    // --- СРАВНЕНИЕ ---
                    System.out.println("\n── СРАВНЕНИЕ 1 комп vs 3 комп ──");
                    System.out.printf("  Слияние R: %.1f мс (%.1f%% от общего 3 комп)%n",
                            mergeTime / 1_000_000.0, 100.0 * mergeTime / total3);
                    System.out.printf("  prepareSignal: 1 комп=%.1f мс, 3 комп=%.1f мс (%.2fx)%n",
                            prepTime1 / 1_000_000.0, prepTime3 / 1_000_000.0, (double) prepTime1 / prepTime3);
                    System.out.printf("  STA/LTA:  1 комп=%.1f мс, 3 комп=%.1f мс (%.2fx)%n",
                            staLta1 / 1_000_000.0, staLta3 / 1_000_000.0, (double) staLta1 / staLta3);
                    System.out.printf("  Энергия:   1 комп=%.1f мс, 3 комп=%.1f мс (%.2fx)%n",
                            energy1 / 1_000_000.0, energy3 / 1_000_000.0, (double) energy1 / energy3);
                    System.out.printf("  SNR:       1 комп=%.1f мс, 3 комп=%.1f мс (%.2fx)%n",
                            snr1 / 1_000_000.0, snr3 / 1_000_000.0, (double) snr1 / snr3);
                    System.out.printf("  ПОЛНОЕ:    1 комп=%.1f мс, 3 комп=%.1f мс (%.2fx)%n",
                            total1 / 1_000_000.0, total3 / 1_000_000.0, (double) total1 / total3);
                    System.out.println("⏱️  ═══════════════════════════════════════════");

                }
            } catch (Exception e) {
                System.err.println("Ошибка замеров: " + e.getMessage());
            }
        }).start();
    }

    private void showComparisonReport() {
        String fn = getSelectedFile();
        if (fn == null) { JOptionPane.showMessageDialog(this, "Выберите файл"); return; }
        infoArea.setText("Функция сравнения методов временно отключена.");
    }

    private void showSingleTraceStaLta() {
        activeAnalysisMode = "stalta";
        String fn = getSelectedFile(); int tr = getSelectedTrace();
        if (fn == null || tr < 1 || tr > 3) { JOptionPane.showMessageDialog(this, "Выберите файл и трассу 1-3"); return; }
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() throws Exception {
                DatabaseService.TimedSignal ts = dbService.getSignalDataTimed(fn, tr, startTimeField.getText().trim(), endTimeField.getText().trim());
                if (ts.values.isEmpty()) { showChartWithParams(new JLabel("Нет данных", SwingConstants.CENTER), true); return null; }
                int fs = estimateFsFromTimed(ts);
                List<Double> filt = applyFilterIfEnabled(ts.values, fs);

                // Подготавливаем сигнал и пороги
                ProcessedSignal ps = prepareSignal(filt, fs);
                List<Double> procSignal = ps.signal();
                double staThr = ps.staThr();
                double energyThr = ps.energyThr();
                double snrThr = ps.snrThr();

                int offset = useRawSignal ? 0 : varianceWindowSec * fs;
                int m = Math.min(procSignal.size(), ts.timeSeconds.size() - offset);
                List<Double> tt = new ArrayList<>(ts.timeSeconds.subList(offset, offset + m));
                List<Double> raw = new ArrayList<>(filt.subList(offset, offset + m));
                List<Double> ff = new ArrayList<>(procSignal.subList(0, m));

                ThreeComponentAnalyzer an = new ThreeComponentAnalyzer();
                List<DetectionEvent> staLtaEv = an.detectDominantStaLtaOnSignal(ff, fs, currentStaSec, currentLtaSec, staThr);
                Double tS = null, tE = null;
                if (!staLtaEv.isEmpty()) {
                    tS = tt.get(Math.min(Math.max(0, staLtaEv.get(0).getStartSample()), m - 1));
                    tE = tt.get(Math.min(Math.max(0, staLtaEv.get(0).getEndSample()), m - 1));
                }

                List<DetectionEvent> energyEv = EnergyDetector.detect(ff, fs, currentEnergyWindowSec, energyThr, fn + " тр." + tr);
                Double etS = null, etE = null;
                if (!energyEv.isEmpty()) {
                    etS = tt.get(Math.min(Math.max(0, energyEv.get(0).getStartSample()), m - 1));
                    etE = tt.get(Math.min(Math.max(0, energyEv.get(0).getEndSample()), m - 1));
                }

                List<DetectionEvent> snrEv = SnrDetector.detect(ff, fs, currentSnrStaSec, currentSnrLtaSec, snrThr, fn + " тр." + tr);
                Double snrS = null, snrE = null;
                if (!snrEv.isEmpty()) {
                    snrS = tt.get(Math.min(Math.max(0, snrEv.get(0).getStartSample()), m - 1));
                    snrE = tt.get(Math.min(Math.max(0, snrEv.get(0).getEndSample()), m - 1));
                }

                String[] comp = {"X (N–S), тр.1", "Y (E–W), тр.2", "Z, тр.3"};
                int sta = TransportDetectionTuning.staSamples(fs, currentStaSec);
                int lta = TransportDetectionTuning.ltaSamples(fs, currentStaSec, currentLtaSec);
                String modeLabel = useRawSignal ? "сырой" : "дисперсия";

                JTabbedPane root = new JTabbedPane();
                JPanel filterPanel = new JPanel(new GridLayout(2, 1));
                filterPanel.add(chartBuilder.createRawVsFilteredChart(tt, raw, ff, "Баттерворт " + String.format("%.2f", getLowCutHzForDisplay(fs)) + "–" + String.format("%.0f", currentHighCutHz) + " Гц", "До", "После"));
                filterPanel.add(chartBuilder.createStaLtaEnergyComponentsChart(tt, ff, sta, lta, "Энергия x²"));
                root.addTab("Фильтр", filterPanel);
                root.addTab("STA/LTA", chartBuilder.createAmplitudeWithEventMarkers(tt, ff, tS, tE, fn + " / " + comp[tr - 1] + " (" + modeLabel + ")"));
                root.addTab("Энергия", chartBuilder.createAmplitudeWithEventMarkers(tt, ff, etS, etE, "Энергия - " + fn + " / " + comp[tr - 1] + " (" + modeLabel + ")"));
                root.addTab("SNR", chartBuilder.createAmplitudeWithEventMarkers(tt, ff, snrS, snrE, "SNR - " + fn + " / " + comp[tr - 1] + " (" + modeLabel + ")"));
                showChartWithParams(root, true);
                return null;
            }
            protected void done() { chartPanel.revalidate(); chartPanel.repaint(); setCursor(Cursor.getDefaultCursor()); }
        }.execute();
    }

    private void showStaLtaThresholdChart() {
        activeAnalysisMode = "stalta_threshold";
        String fn = getSelectedFile(); int tr = getSelectedTrace();
        if (fn == null || tr == 0) { JOptionPane.showMessageDialog(this, "Выберите файл и трассу"); return; }
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() throws Exception {
                JTabbedPane root = new JTabbedPane();
                root.addTab("Трасса " + tr, buildStaLtaForTrace(fn, tr));
                root.addTab("Результирующая R", buildStaLtaForResultant(fn));
                showChartWithParams(root, true);
                return null;
            }
            protected void done() { chartPanel.revalidate(); chartPanel.repaint(); setCursor(Cursor.getDefaultCursor()); }
        }.execute();
    }

    private JComponent buildStaLtaForTrace(String fn, int trace) {
        try {
            DatabaseService.TimedSignal ts = dbService.getSignalDataTimed(fn, trace,
                    startTimeField.getText().trim(), endTimeField.getText().trim());
            if (ts.values.isEmpty()) return new JPanel();

            int fs = estimateFsFromTimed(ts);
            List<Double> sig = applyFilterIfEnabled(ts.values, fs);

            // Подготавливаем сигнал
            ProcessedSignal ps = prepareSignal(sig, fs);
            List<Double> procSignal = ps.signal();
            double staThr = ps.staThr();
            double energyThr = ps.energyThr();
            double snrThr = ps.snrThr();

            int offset = useRawSignal ? 0 : varianceWindowSec * fs;
            int m = Math.min(procSignal.size(), ts.timeSeconds.size() - offset);
            List<Double> tt = new ArrayList<>(ts.timeSeconds.subList(offset, offset + m));
            List<Double> ff = new ArrayList<>(procSignal.subList(0, m));

            int sta = TransportDetectionTuning.staSamples(fs, currentStaSec);
            int lta = TransportDetectionTuning.ltaSamples(fs, currentStaSec, currentLtaSec);

            JPanel staLtaPanel = buildThreeStaLtaCharts(tt, ff, sta, lta, staThr, fn + " тр." + trace);
            JPanel energyPanel = buildEnergyCharts(tt, ff, fs, energyThr, fn + " тр." + trace);
            JPanel snrPanel = buildSnrCharts(tt, ff, fs, snrThr, fn + " тр." + trace);

            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("STA/LTA", staLtaPanel);
            tabs.addTab("Энергия", energyPanel);
            tabs.addTab("SNR", snrPanel);
            return tabs;
        } catch (SQLException e) { return new JPanel(); }
    }

    private JComponent buildStaLtaForResultant(String fn) {
        try (Connection conn = DriverManager.getConnection("jdbc:postgresql://localhost:5432/noisedb", "postgres", "32676")) {
            DatabaseService.TimedSignal ts1 = dbService.getSignalDataTimed(fn, 1, startTimeField.getText().trim(), endTimeField.getText().trim());
            DatabaseService.TimedSignal ts2 = dbService.getSignalDataTimed(fn, 2, startTimeField.getText().trim(), endTimeField.getText().trim());
            DatabaseService.TimedSignal ts3 = dbService.getSignalDataTimed(fn, 3, startTimeField.getText().trim(), endTimeField.getText().trim());

            if (ts1.values.isEmpty() || ts2.values.isEmpty() || ts3.values.isEmpty()) {
                JPanel p = new JPanel(new BorderLayout());
                p.add(new JLabel("Нет данных для всех трех трасс", SwingConstants.CENTER), BorderLayout.CENTER);
                return p;
            }

            int fs = estimateFsFromTimed(ts1);
            int m = Math.min(Math.min(ts1.values.size(), ts2.values.size()), ts3.values.size());
            List<Double> x = new ArrayList<>(ts1.values.subList(0, m));
            List<Double> y = new ArrayList<>(ts2.values.subList(0, m));
            List<Double> z = new ArrayList<>(ts3.values.subList(0, m));

            List<Double> R = new ArrayList<>();
            for (int i = 0; i < m; i++) {
                R.add(Math.sqrt(x.get(i)*x.get(i) + y.get(i)*y.get(i) + z.get(i)*z.get(i)));
            }

            // Подготавливаем сигнал
            ProcessedSignal ps = prepareSignal(R, fs);
            List<Double> procSignal = ps.signal();
            double staThr = ps.staThr();
            double energyThr = ps.energyThr();
            double snrThr = ps.snrThr();

            int offset = useRawSignal ? 0 : varianceWindowSec * fs;
            List<Double> tt = new ArrayList<>(ts1.timeSeconds.subList(offset, offset + Math.min(procSignal.size(), ts1.timeSeconds.size() - offset)));
            List<Double> ff = new ArrayList<>(procSignal.subList(0, Math.min(procSignal.size(), tt.size())));

            int sta = TransportDetectionTuning.staSamples(fs, currentStaSec);
            int lta = TransportDetectionTuning.ltaSamples(fs, currentStaSec, currentLtaSec);

            JPanel staLtaPanel = buildThreeStaLtaCharts(tt, ff, sta, lta, staThr, fn + " (R)");
            JPanel energyPanel = buildEnergyCharts(tt, ff, fs, energyThr, fn + " (R)");
            JPanel snrPanel = buildSnrCharts(tt, ff, fs, snrThr, fn + " (R)");

            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("STA/LTA", staLtaPanel);
            tabs.addTab("Энергия", energyPanel);
            tabs.addTab("SNR", snrPanel);
            return tabs;

        } catch (Exception e) {
            JPanel p = new JPanel(new BorderLayout());
            p.add(new JLabel("Ошибка: " + e.getMessage(), SwingConstants.CENTER), BorderLayout.CENTER);
            return p;
        }
    }

    private JPanel buildThreeStaLtaCharts(List<Double> tt, List<Double> sig, int staSamples, int ltaSamples, double threshold, String label) {
        int n = sig.size();
        double[] staVals = new double[n], ltaVals = new double[n], ratioVals = new double[n];
        for (int i = 0; i < n; i++) {
            int staStart = Math.max(0, i - staSamples);
            double staSum = 0;
            for (int j = staStart; j <= i; j++) staSum += Math.abs(sig.get(j));
            staVals[i] = staSum / (i - staStart + 1);
            int ltaStart = Math.max(0, i - ltaSamples);
            double ltaSum = 0;
            for (int j = ltaStart; j <= i; j++) ltaSum += Math.abs(sig.get(j));
            ltaVals[i] = ltaSum / (i - ltaStart + 1);
            ratioVals[i] = ltaVals[i] > 1e-20 ? staVals[i] / ltaVals[i] : 0;
        }
        JPanel panel = new JPanel(new GridLayout(3, 1, 4, 4));
        panel.add(createSingleLineChart(tt, staVals, "STA — " + label, "Время (с)", "STA", new Color(200, 100, 0)));
        panel.add(createSingleLineChart(tt, ltaVals, "LTA — " + label, "Время (с)", "LTA", new Color(0, 100, 200)));
        panel.add(chartBuilder.createStaLtaRatioChart(tt, ratioVals, threshold, null, null, label, "STA/LTA", "Отношение"));
        return panel;
    }

    private JPanel buildEnergyCharts(List<Double> tt, List<Double> sig, int fs, double threshold, String label) {
        int windowSamples = Math.max(2, (int)(currentEnergyWindowSec * fs));
        int n = sig.size();
        double[] energyVals = new double[n];

        for (int i = 0; i < n; i++) {
            int start = Math.max(0, i - windowSamples);
            double sum = 0;
            for (int j = start; j <= i; j++) {
                double v = sig.get(j);
                sum += v * v;
            }
            energyVals[i] = sum / (i - start + 1);
        }

        JPanel energyChart = createSingleLineChart(tt, energyVals,
                "Энергия (окно " + String.format("%.2f", currentEnergyWindowSec) + "с) — " + label,
                "Время (с)", "Энергия (x²)", new Color(200, 100, 0));

        return energyChart;
    }

    private JPanel buildSnrCharts(List<Double> tt, List<Double> sig, int fs, double threshold, String label) {
        int halfSta = Math.max(1, (int)(currentSnrStaSec * fs / 2));
        int halfLta = Math.max(halfSta + 1, (int)(currentSnrLtaSec * fs / 2));
        int n = sig.size();

        double[] snrStaVals = new double[n];
        double[] snrLtaVals = new double[n];
        double[] snrVals = new double[n];
        double normA = (2.0 * (halfLta - halfSta)) / (2.0 * halfSta + 1);

        for (int i = 0; i < n; i++) {
            int staStart = Math.max(0, i - halfSta);
            int staEnd = Math.min(n - 1, i + halfSta);
            double staSum = 0;
            for (int j = staStart; j <= staEnd; j++) staSum += Math.abs(sig.get(j));
            snrStaVals[i] = staSum / (staEnd - staStart + 1);

            int ltaStart = Math.max(0, i - halfLta);
            int ltaEnd = Math.min(n - 1, i + halfLta);
            double ltaSum = 0;
            int ltaCount = 0;
            for (int j = ltaStart; j <= ltaEnd; j++) {
                if (j < i - halfSta || j > i + halfSta) {
                    ltaSum += Math.abs(sig.get(j));
                    ltaCount++;
                }
            }
            snrLtaVals[i] = ltaCount > 0 ? ltaSum / ltaCount : 0;
            snrVals[i] = snrLtaVals[i] > 0 ? normA * snrStaVals[i] / snrLtaVals[i] : 0;
        }

        JPanel staChart = createSingleLineChart(tt, snrStaVals,
                "SNR STA (сигнал в окне " + String.format("%.2f", currentSnrStaSec) + "с) — " + label,
                "Время (с)", "|x| среднее", new Color(200, 100, 0));

        JPanel ltaChart = createSingleLineChart(tt, snrLtaVals,
                "SNR LTA (шум в крыльях " + String.format("%.2f", currentSnrLtaSec) + "с) — " + label,
                "Время (с)", "|x| среднее", new Color(0, 100, 200));

        XYSeries snrSeries = new XYSeries("SNR");
        XYSeries thrSeries = new XYSeries("Порог");
        for (int i = 0; i < n; i++) {
            snrSeries.add((double) tt.get(i), snrVals[i]);
        }
        if (n > 0) {
            thrSeries.add((double) tt.get(0), threshold);
            thrSeries.add((double) tt.get(n - 1), threshold);
        }
        XYSeriesCollection dataset = new XYSeriesCollection();
        dataset.addSeries(snrSeries);
        dataset.addSeries(thrSeries);

        JFreeChart chart = ChartFactory.createXYLineChart(
                "SNR + порог — " + label, "Время (с)", "SNR",
                dataset, PlotOrientation.VERTICAL, true, true, false);
        chart.setBackgroundPaint(Color.WHITE);
        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(new Color(245, 245, 245));
        plot.setDomainGridlinePaint(Color.LIGHT_GRAY);
        plot.setRangeGridlinePaint(Color.LIGHT_GRAY);

        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer();
        renderer.setSeriesPaint(0, new Color(150, 50, 200));
        renderer.setSeriesPaint(1, Color.RED);
        renderer.setSeriesStroke(1, new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{6f, 6f}, 0f));
        plot.setRenderer(renderer);

        ChartPanel cp = new ChartPanel(chart);
        cp.setMouseWheelEnabled(true); cp.setDomainZoomable(true); cp.setRangeZoomable(true);
        cp.setDisplayToolTips(true);
        JPanel ratioPanel = new JPanel(new BorderLayout());
        ratioPanel.add(cp, BorderLayout.CENTER);

        JPanel panel = new JPanel(new GridLayout(3, 1, 4, 4));
        panel.add(staChart);
        panel.add(ltaChart);
        panel.add(ratioPanel);
        return panel;
    }

    private JPanel createSingleLineChart(List<Double> times, double[] values, String title, String xLabel, String yLabel, Color color) {
        XYSeries series = new XYSeries(title);
        int n = Math.min(times.size(), values.length);
        for (int i = 0; i < n; i++) {
            double t = times.get(i); double v = values[i];
            series.add((double) t, (double) v);
        }
        XYSeriesCollection dataset = new XYSeriesCollection(series);
        JFreeChart chart = ChartFactory.createXYLineChart(title, xLabel, yLabel, dataset, PlotOrientation.VERTICAL, true, true, false);
        chart.setBackgroundPaint(Color.WHITE);
        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(new Color(245, 245, 245));
        plot.setDomainGridlinePaint(Color.LIGHT_GRAY);
        plot.setRangeGridlinePaint(Color.LIGHT_GRAY);
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer();
        renderer.setSeriesPaint(0, color);
        plot.setRenderer(renderer);
        ChartPanel cp = new ChartPanel(chart);
        cp.setMouseWheelEnabled(true); cp.setDomainZoomable(true); cp.setRangeZoomable(true);
        cp.setDisplayToolTips(true);
        cp.addChartMouseListener(new ChartMouseListener() {
            public void chartMouseClicked(ChartMouseEvent e) { if (e.getTrigger().getButton() == MouseEvent.BUTTON3) cp.restoreAutoBounds(); }
            public void chartMouseMoved(ChartMouseEvent e) {
                XYPlot p = (XYPlot) e.getChart().getPlot();
                Rectangle2D da = cp.getChartRenderingInfo().getPlotInfo().getDataArea();
                int mx = e.getTrigger().getX(), my = e.getTrigger().getY();
                if (da.contains(mx, my)) {
                    double x = p.getDomainAxis().java2DToValue(mx, da, p.getDomainAxisEdge());
                    double y = p.getRangeAxis().java2DToValue(my, da, p.getRangeAxisEdge());
                    cp.setToolTipText(String.format("<html>Время: <b>%.3f сек</b><br>Значение: <b>%.6f</b></html>", x, y));
                }
            }
        });
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(cp, BorderLayout.CENTER);
        return panel;
    }

    // ==================== RELOAD ====================

    private void reloadCurrentChart() {
        if (activeAnalysisMode == null) return;
        switch (activeAnalysisMode) {
            case "signal": loadAndShowSignal(); break;
            case "stalta": showSingleTraceStaLta(); break;
            case "3d": showThreeComponentResults(); break;
            case "stalta_threshold": showStaLtaThresholdChart(); break;
        }
    }

    private void clearDatabase() {
        if (JOptionPane.showConfirmDialog(this, "⚠️ Удалить ВСЕ данные из БД?", "Подтверждение", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            dbService.clearAllData();
            lastRecordCount = 0; cachedThreeComponentReport = null;
            refreshAllDataAsync();
            chartPanel.removeAll(); chartPanel.revalidate(); chartPanel.repaint();
        }
    }

    // ==================== HELPERS ====================

    private record ThreeDAnalysisPack(JComponent panel, String report) {}

    private int estimateSampleRateFromTimes(List<Double> times) {
        if (times == null || times.size() < 2) return 1000;
        double dt = times.get(1) - times.get(0);
        return (dt <= 1e-9) ? 1000 : (int) Math.max(200, Math.min(100_000, Math.round(1.0 / dt)));
    }

    private int estimateFsFromTimed(DatabaseService.TimedSignal ts) {
        if (ts.timeSeconds.size() < 2) return 1000;
        double dt = ts.timeSeconds.get(1) - ts.timeSeconds.get(0);
        return (dt <= 1e-9) ? 1000 : (int) Math.max(200, Math.min(100_000, Math.round(1.0 / dt)));
    }

    private List<Double> loadTraceForBase(Connection conn, String base, int trace) throws SQLException {
        List<Double> out = new ArrayList<>();
        String sql = "SELECT value FROM signal_data WHERE SUBSTRING(file_name FROM '^(.*?)\\.') = ? AND trace_number = ? ORDER BY record_time LIMIT 50000";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, base); ps.setInt(2, trace);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(rs.getDouble(1)); }
        }
        return out;
    }

    private ThreeDAnalysisPack buildThreeDAnalysisPack(String fileName) {
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5432/noisedb", "postgres", "32676")) {

            // Загружаем три трассы через TimedSignal (как в однокомпонентном)
            DatabaseService.TimedSignal ts1 = dbService.getSignalDataTimed(fileName, 1,
                    startTimeField.getText().trim(), endTimeField.getText().trim());
            DatabaseService.TimedSignal ts2 = dbService.getSignalDataTimed(fileName, 2,
                    startTimeField.getText().trim(), endTimeField.getText().trim());
            DatabaseService.TimedSignal ts3 = dbService.getSignalDataTimed(fileName, 3,
                    startTimeField.getText().trim(), endTimeField.getText().trim());

            if (ts1.values.isEmpty() || ts2.values.isEmpty() || ts3.values.isEmpty()) {
                return new ThreeDAnalysisPack(null, "Нет данных для всех трех трасс: " + fileName);
            }

            // Вычисляем fs из данных (как в однокомпонентном)
            int fs = estimateFsFromTimed(ts1);

            // Приводим к одной длине и фильтруем
            int m = Math.min(Math.min(ts1.values.size(), ts2.values.size()), ts3.values.size());
            List<Double> x = applyFilterIfEnabled(new ArrayList<>(ts1.values.subList(0, m)), fs);
            List<Double> y = applyFilterIfEnabled(new ArrayList<>(ts2.values.subList(0, m)), fs);
            List<Double> z = applyFilterIfEnabled(new ArrayList<>(ts3.values.subList(0, m)), fs);

            // Вычисляем результирующую R
            List<Double> R = new ArrayList<>();
            for (int i = 0; i < m; i++) {
                R.add(Math.sqrt(x.get(i)*x.get(i) + y.get(i)*y.get(i) + z.get(i)*z.get(i)));
            }

            // Подготавливаем сигнал (дисперсия + авто-порог)
            ProcessedSignal ps = prepareSignal(R, fs);
            List<Double> procSignal = ps.signal();
            double staThr = ps.staThr(), energyThr = ps.energyThr(), snrThr = ps.snrThr();

            // Временные метки из ts1 (правильные, из БД)
            List<Double> tt = new ArrayList<>(ts1.timeSeconds.subList(0, m));
            // Корректируем на смещение из-за окна дисперсии
            if (!useRawSignal) {
                int offset = varianceWindowSec * fs;
                if (offset < m) {
                    tt = new ArrayList<>(tt.subList(offset, m));
                    // Обрезаем procSignal до размера tt
                    int newSize = Math.min(procSignal.size(), tt.size());
                    procSignal = new ArrayList<>(procSignal.subList(0, newSize));
                }
            }

            // Детекторы
            ThreeComponentAnalyzer an = new ThreeComponentAnalyzer();
            List<DetectionEvent> evR = an.detectDominantStaLtaOnSignal(procSignal, fs, currentStaSec, currentLtaSec, staThr);
            Double tS = null, tE = null;
            if (!evR.isEmpty()) { tS = evR.get(0).getStartSample() / (double) fs; tE = evR.get(0).getEndSample() / (double) fs; }

            List<DetectionEvent> energyEv = EnergyDetector.detect(procSignal, fs, currentEnergyWindowSec, energyThr, fileName);
            Double etS = null, etE = null;
            if (!energyEv.isEmpty()) { etS = energyEv.get(0).getStartSample() / (double) fs; etE = energyEv.get(0).getEndSample() / (double) fs; }

            List<DetectionEvent> snrEv = SnrDetector.detect(procSignal, fs, currentSnrStaSec, currentSnrLtaSec, snrThr, fileName);
            Double snrS = null, snrE = null;
            if (!snrEv.isEmpty()) { snrS = snrEv.get(0).getStartSample() / (double) fs; snrE = snrEv.get(0).getEndSample() / (double) fs; }

            String modeLabel = useRawSignal ? "сырой" : "дисперсия";
            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("Обзор", chartBuilder.createAmplitudeWithEventMarkers(tt, procSignal, tS, tE,
                    "STA/LTA (R) - " + fileName + " (" + modeLabel + ")"));
            tabs.addTab("Энергия", chartBuilder.createAmplitudeWithEventMarkers(tt, procSignal, etS, etE,
                    "Энергия (R) - " + fileName + " (" + modeLabel + ")"));
            tabs.addTab("SNR", chartBuilder.createAmplitudeWithEventMarkers(tt, procSignal, snrS, snrE,
                    "SNR (R) - " + fileName + " (" + modeLabel + ")"));

            // Вкладка Фильтр (сравнение до/после для X)
            double low = getLowCutHzForDisplay(fs);
            List<Double> xBefore = new ArrayList<>(ts1.values.subList(0, m));
            List<Double> xAfter = x;
            int mf = Math.min(Math.min(xBefore.size(), xAfter.size()), tt.size());
            JPanel pp = new JPanel(new GridLayout(2, 1));
            pp.add(chartBuilder.createRawVsFilteredChart(
                    new ArrayList<>(tt.subList(0, mf)),
                    new ArrayList<>(xBefore.subList(0, mf)),
                    new ArrayList<>(xAfter.subList(0, mf)),
                    "Баттерворт " + String.format("%.2f", low) + "–" + String.format("%.0f", currentHighCutHz) + " Гц (X)",
                    "До", "После"));
            tabs.addTab("Фильтр", pp);

            return new ThreeDAnalysisPack(tabs, "");

        } catch (SQLException e) {
            return new ThreeDAnalysisPack(null, "Ошибка БД: " + e.getMessage());
        }
    }

    private List<Double> buildTimeArray(int size, int sampleRate) {
        List<Double> times = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            times.add((double) i / sampleRate);
        }
        return times;
    }
}