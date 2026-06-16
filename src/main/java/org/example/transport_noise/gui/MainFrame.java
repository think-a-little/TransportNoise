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
import java.util.List;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartMouseEvent;
import org.jfree.chart.ChartMouseListener;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.plot.ValueMarker;
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

    // STA/LTA
    private double currentStaSec = TransportDetectionTuning.defaultStaSec();
    private double currentLtaSec = TransportDetectionTuning.defaultLtaSec();
    private double currentStaThreshold = TransportDetectionTuning.defaultThreshold();

    // Энергетический
    private double currentEnergyWindowSec = 0.5;
    private double currentEnergyThreshold = 1e-10;

    // SNR
    private double currentSnrStaSec = 0.5;
    private double currentSnrLtaSec = 5.0;
    private double currentSnrThreshold = 2.0;

    // Фильтр
    private boolean currentFilterEnabled = true;
    private double currentLowCutHz = 2.0;
    private double currentHighCutHz = 100.0;

    // Поля ввода
    private JTextField staField;
    private JTextField ltaField;
    private JTextField staThresholdField;
    private JTextField energyWindowField;
    private JTextField energyThresholdField;
    private JTextField snrStaField;
    private JTextField snrLtaField;
    private JTextField snrThresholdField;
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
        buttonRow.add(styledButton("Сигнал", ACCENT_COLOR, e -> loadAndShowSignal()));
        buttonRow.add(styledButton("Дисперсия", new Color(70, 150, 70), e -> loadAndShowVariance()));
        buttonRow.add(styledButton("Сигнал + Дисперсия", new Color(200, 150, 50), e -> loadAndShowBoth()));
        buttonRow.add(styledButton("3 компонентный Анализ", new Color(120, 60, 180), e -> showThreeComponentResults()));
        buttonRow.add(styledButton("Анализ 1 компоненты", new Color(70, 130, 200), e -> showSingleTraceStaLta()));
        buttonRow.add(styledButton("График метода", new Color(70, 130, 200), e -> showStaLtaThresholdChart()));
//        buttonRow.add(styledButton("Отчет", new Color(0, 150, 136), e -> showComparisonReport()));
//        buttonRow.add(styledButton("🗑Очистить БД", DANGER_COLOR, e -> clearDatabase()));
        panel.add(buttonRow, gbc);
        return panel;
    }

    // ==================== ПАНЕЛЬ ПАРАМЕТРОВ ====================

    private JPanel createAnalysisParamsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(DARKER_BG);

        // Ряд STA/LTA
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

        // Ряд Энергия
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

        // Ряд SNR
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

        // Ряд Фильтр
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

        return panel;
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

        staField.setText(String.valueOf(currentStaSec));
        ltaField.setText(String.valueOf(currentLtaSec));
        staThresholdField.setText(String.format("%.2f", currentStaThreshold));
        energyWindowField.setText(String.valueOf(currentEnergyWindowSec));
        energyThresholdField.setText(String.valueOf(currentEnergyThreshold));
        snrStaField.setText(String.valueOf(currentSnrStaSec));
        snrLtaField.setText(String.valueOf(currentSnrLtaSec));
        snrThresholdField.setText(String.valueOf(currentSnrThreshold));
        filterEnabled.setSelected(currentFilterEnabled);
        lowCutField.setText(String.valueOf(currentLowCutHz));
        highCutField.setText(String.valueOf(currentHighCutHz));

        updateStatus("✅ Параметры применены");
        reloadCurrentChart();
    }

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
                List<Double> tt = new ArrayList<>();
                for (int i = 0; i < filt.size(); i++) tt.add((double) i / fs);

                int sta = TransportDetectionTuning.staSamples(fs, currentStaSec);
                int lta = TransportDetectionTuning.ltaSamples(fs, currentStaSec, currentLtaSec);
                ThreeComponentAnalyzer.DetectionParams p = TransportDetectionTuning.params(fs, currentStaSec, currentLtaSec, currentStaThreshold);
                double[] ratio = StaLtaEventDetector.absStaLtaRatio(filt, sta, lta);
                List<DetectionEvent> staLtaEvents = StaLtaEventDetector.pickEventsFromRatio(
                        ratio, lta, p.staLtaThreshold, p.offRatio, p.hangSamples, p.minDurationSamples, p.cooldownSamples, fn + " тр." + tr);

                List<DetectionEvent> energyEvents = EnergyDetector.detect(
                        filt, fs, currentEnergyWindowSec, currentEnergyThreshold, fn + " тр." + tr);

                List<DetectionEvent> snrEvents = SnrDetector.detect(
                        filt, fs, currentSnrStaSec, currentSnrLtaSec, currentSnrThreshold, fn + " тр." + tr);

                JTabbedPane tabs = new JTabbedPane();
                tabs.addTab("STA/LTA", chartBuilder.createSignalWithAllEvents(tt, filt, staLtaEvents, "STA/LTA - " + fn + " тр." + tr));
                tabs.addTab("Энергия", chartBuilder.createSignalWithAllEvents(tt, filt, energyEvents, "Энергетический - " + fn + " тр." + tr));
                tabs.addTab("SNR", chartBuilder.createSignalWithAllEvents(tt, filt, snrEvents, "SNR - " + fn + " тр." + tr));
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
            protected ThreeDAnalysisPack doInBackground() { return buildThreeDAnalysisPack(fn); }
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
                int m = Math.min(filt.size(), ts.timeSeconds.size());
                List<Double> tt = new ArrayList<>(ts.timeSeconds.subList(0, m));
                List<Double> raw = new ArrayList<>(ts.values.subList(0, m));
                List<Double> ff = new ArrayList<>(filt.subList(0, m));

                ThreeComponentAnalyzer an = new ThreeComponentAnalyzer();
                List<DetectionEvent> staLtaEv = an.detectDominantStaLtaOnSignal(ff, fs, currentStaSec, currentLtaSec, currentStaThreshold);
                Double tS = null, tE = null;
                if (!staLtaEv.isEmpty()) {
                    tS = tt.get(Math.min(Math.max(0, staLtaEv.get(0).getStartSample()), m - 1));
                    tE = tt.get(Math.min(Math.max(0, staLtaEv.get(0).getEndSample()), m - 1));
                }

                List<DetectionEvent> energyEv = EnergyDetector.detect(
                        filt, fs, currentEnergyWindowSec, currentEnergyThreshold, fn + " тр." + tr);
                Double etS = null, etE = null;
                if (!energyEv.isEmpty()) {
                    etS = tt.get(Math.min(Math.max(0, energyEv.get(0).getStartSample()), m - 1));
                    etE = tt.get(Math.min(Math.max(0, energyEv.get(0).getEndSample()), m - 1));
                }

                List<DetectionEvent> snrEv = SnrDetector.detect(
                        filt, fs, currentSnrStaSec, currentSnrLtaSec, currentSnrThreshold, fn + " тр." + tr);
                Double snrS = null, snrE = null;
                if (!snrEv.isEmpty()) {
                    snrS = tt.get(Math.min(Math.max(0, snrEv.get(0).getStartSample()), m - 1));
                    snrE = tt.get(Math.min(Math.max(0, snrEv.get(0).getEndSample()), m - 1));
                }

                String[] comp = {"X (N–S), тр.1", "Y (E–W), тр.2", "Z, тр.3"};
                int sta = TransportDetectionTuning.staSamples(fs, currentStaSec);
                int lta = TransportDetectionTuning.ltaSamples(fs, currentStaSec, currentLtaSec);

                JTabbedPane root = new JTabbedPane();
                JPanel filterPanel = new JPanel(new GridLayout(2, 1));
                filterPanel.add(chartBuilder.createRawVsFilteredChart(tt, raw, ff, "Баттерворт " + String.format("%.2f", getLowCutHzForDisplay(fs)) + "–" + String.format("%.0f", currentHighCutHz) + " Гц", "До", "После"));
                filterPanel.add(chartBuilder.createStaLtaEnergyComponentsChart(tt, ff, sta, lta, "Энергия x²"));
                root.addTab("Фильтр", filterPanel);
                root.addTab("STA/LTA", chartBuilder.createAmplitudeWithEventMarkers(tt, ff, tS, tE, fn + " / " + comp[tr - 1]));
                root.addTab("Энергия", chartBuilder.createAmplitudeWithEventMarkers(tt, ff, etS, etE, "Энергия - " + fn + " / " + comp[tr - 1]));
                root.addTab("SNR", chartBuilder.createAmplitudeWithEventMarkers(tt, ff, snrS, snrE, "SNR - " + fn + " / " + comp[tr - 1]));
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
            int m = Math.min(sig.size(), ts.timeSeconds.size());
            List<Double> tt = new ArrayList<>(ts.timeSeconds.subList(0, m));
            List<Double> ff = new ArrayList<>(sig.subList(0, m));
            int sta = TransportDetectionTuning.staSamples(fs, currentStaSec);
            int lta = TransportDetectionTuning.ltaSamples(fs, currentStaSec, currentLtaSec);

            JPanel staLtaPanel = buildThreeStaLtaCharts(tt, ff, sta, lta, fn + " тр." + trace);
            JPanel energyPanel = buildEnergyCharts(tt, ff, fs, fn + " тр." + trace);

            // SNR: график энергии + порог
            JPanel snrPanel = buildSnrCharts(tt, ff, fs, fn + " тр." + trace);

            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("STA/LTA", staLtaPanel);
            tabs.addTab("Энергия", energyPanel);
            tabs.addTab("SNR", snrPanel);
            return tabs;
        } catch (SQLException e) { return new JPanel(); }
    }

    private JPanel buildSnrCharts(List<Double> tt, List<Double> sig, int fs, String label) {
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
            thrSeries.add((double) tt.get(0), currentSnrThreshold);
            thrSeries.add((double) tt.get(n - 1), currentSnrThreshold);
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

    private JPanel buildEnergyCharts(List<Double> tt, List<Double> sig, int fs, String label) {
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

        XYSeries thrLine = new XYSeries("Порог");
        if (n > 0) {
            thrLine.add((double) tt.get(0), currentEnergyThreshold);
            thrLine.add((double) tt.get(n - 1), currentEnergyThreshold);
        }
        XYSeriesCollection thrDataset = new XYSeriesCollection(thrLine);
        JFreeChart thrChart = ChartFactory.createXYLineChart(
                "Порог энергии — " + label, "Время (с)", "Энергия (x²)",
                thrDataset, PlotOrientation.VERTICAL, true, true, false);
        thrChart.setBackgroundPaint(Color.WHITE);
        XYPlot thrPlot = thrChart.getXYPlot();
        thrPlot.setBackgroundPaint(new Color(245, 245, 245));
        thrPlot.setDomainGridlinePaint(Color.LIGHT_GRAY);
        thrPlot.setRangeGridlinePaint(Color.LIGHT_GRAY);
        XYLineAndShapeRenderer thrRenderer = new XYLineAndShapeRenderer();
        thrRenderer.setSeriesPaint(0, Color.RED);
        thrRenderer.setSeriesStroke(0, new BasicStroke(2f));
        thrPlot.setRenderer(thrRenderer);

        ChartPanel thrCp = new ChartPanel(thrChart);
        thrCp.setMouseWheelEnabled(true); thrCp.setDomainZoomable(true); thrCp.setRangeZoomable(true);
        thrCp.setDisplayToolTips(true);
        JPanel thresholdPanel = new JPanel(new BorderLayout());
        thresholdPanel.add(thrCp, BorderLayout.CENTER);

        JPanel panel = new JPanel(new GridLayout(2, 1, 4, 4));
        panel.add(energyChart);
        panel.add(thresholdPanel);
        return panel;
    }

    private JComponent buildStaLtaForResultant(String fn) {
        try (Connection conn = DriverManager.getConnection("jdbc:postgresql://localhost:5432/noisedb", "postgres", "32676")) {

            DatabaseService.TimedSignal ts1 = dbService.getSignalDataTimed(fn, 1,
                    startTimeField.getText().trim(), endTimeField.getText().trim());
            DatabaseService.TimedSignal ts2 = dbService.getSignalDataTimed(fn, 2,
                    startTimeField.getText().trim(), endTimeField.getText().trim());
            DatabaseService.TimedSignal ts3 = dbService.getSignalDataTimed(fn, 3,
                    startTimeField.getText().trim(), endTimeField.getText().trim());

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

            List<Double> tt = new ArrayList<>(ts1.timeSeconds.subList(0, m));
            List<Double> filt = applyFilterIfEnabled(R, fs);
            int mf = Math.min(filt.size(), tt.size());
            List<Double> ttF = new ArrayList<>(tt.subList(0, mf));
            List<Double> ff = new ArrayList<>(filt.subList(0, mf));

            int sta = TransportDetectionTuning.staSamples(fs, currentStaSec);
            int lta = TransportDetectionTuning.ltaSamples(fs, currentStaSec, currentLtaSec);

            JPanel staLtaPanel = buildThreeStaLtaCharts(ttF, ff, sta, lta, fn + " (R)");
            JPanel energyPanel = buildEnergyCharts(ttF, ff, fs, fn + " (R)");
            JPanel snrPanel = buildSnrCharts(ttF, ff, fs, fn + " (R)");

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

    private JPanel buildThreeStaLtaCharts(List<Double> tt, List<Double> sig, int staSamples, int ltaSamples, String label) {
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
        panel.add(chartBuilder.createStaLtaRatioChart(tt, ratioVals, currentStaThreshold, null, null, label, "STA/LTA", "Отношение"));
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

    private List<Double> loadTraceForBase(Connection conn, String base, int trace, int limit) throws SQLException {
        List<Double> out = new ArrayList<>();
        String sql = "SELECT value FROM signal_data WHERE SUBSTRING(file_name FROM '^(.*?)\\.') = ? AND trace_number = ? ORDER BY record_time LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, base); ps.setInt(2, trace); ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(rs.getDouble(1)); }
        }
        return out;
    }

    private ThreeDAnalysisPack buildThreeDAnalysisPack(String fileName) {
        List<Double> times = new ArrayList<>(), amplitudes = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5432/noisedb", "postgres", "32676");
             PreparedStatement ps = c.prepareStatement("SELECT result_amplitude, time_seconds FROM three_component_analysis WHERE station_name LIKE ? ORDER BY time_seconds")) {
            ps.setString(1, "%" + fileName + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) { amplitudes.add(rs.getDouble(1)); times.add(rs.getDouble(2)); }
            }
            if (amplitudes.isEmpty()) return new ThreeDAnalysisPack(null, "Нет данных для " + fileName);
            int fs = estimateSampleRateFromTimes(times);
            int sta = TransportDetectionTuning.staSamples(fs, currentStaSec), lta = TransportDetectionTuning.ltaSamples(fs, currentStaSec, currentLtaSec);
            List<Double> x = loadTraceForBase(c, fileName, 1, amplitudes.size());
            List<Double> y = loadTraceForBase(c, fileName, 2, amplitudes.size());
            List<Double> z = loadTraceForBase(c, fileName, 3, amplitudes.size());
            ThreeComponentAnalyzer an = new ThreeComponentAnalyzer();
            List<DetectionEvent> evR = an.detectDominantStaLtaOnSignal(amplitudes, fs, currentStaSec, currentLtaSec, currentStaThreshold);
            Double tS = null, tE = null;
            if (!evR.isEmpty()) { tS = evR.get(0).getStartSample() / (double) fs; tE = evR.get(0).getEndSample() / (double) fs; }

            List<DetectionEvent> energyEv = EnergyDetector.detect(
                    amplitudes, fs, currentEnergyWindowSec, currentEnergyThreshold, fileName);
            Double etS = null, etE = null;
            if (!energyEv.isEmpty()) { etS = energyEv.get(0).getStartSample() / (double) fs; etE = energyEv.get(0).getEndSample() / (double) fs; }

            List<DetectionEvent> snrEv = SnrDetector.detect(
                    amplitudes, fs, currentSnrStaSec, currentSnrLtaSec, currentSnrThreshold, fileName);
            Double snrS = null, snrE = null;
            if (!snrEv.isEmpty()) { snrS = snrEv.get(0).getStartSample() / (double) fs; snrE = snrEv.get(0).getEndSample() / (double) fs; }

            JTabbedPane tabs = new JTabbedPane();
            JPanel overview = new JPanel(new BorderLayout());
            overview.add(chartBuilder.createAmplitudeWithEventMarkers(
                    buildTimeArray(amplitudes.size(), fs), amplitudes, tS, tE,
                    "STA/LTA (R) - " + fileName), BorderLayout.CENTER);
            tabs.addTab("STA/LTA", overview);
            tabs.addTab("Энергия", chartBuilder.createAmplitudeWithEventMarkers(times, amplitudes, etS, etE, "Энергия (R) - " + fileName));
            tabs.addTab("SNR", chartBuilder.createAmplitudeWithEventMarkers(times, amplitudes, snrS, snrE, "SNR (R) - " + fileName));

            if (x.size() == amplitudes.size() && y.size() == amplitudes.size() && z.size() == amplitudes.size()) {
                double low = getLowCutHzForDisplay(fs);
                List<Double> xf = applyFilterIfEnabled(x, fs);
                int m = Math.min(xf.size(), Math.min(times.size(), x.size()));
                JPanel pp = new JPanel(new GridLayout(2, 1));
                pp.add(chartBuilder.createRawVsFilteredChart(new ArrayList<>(times.subList(0, m)), new ArrayList<>(x.subList(0, m)), new ArrayList<>(xf.subList(0, m)), "Баттерворт " + String.format("%.2f", low) + "–" + String.format("%.0f", currentHighCutHz) + " Гц (X)", "До", "После"));
                tabs.addTab("Фильтр", pp);
            }
            return new ThreeDAnalysisPack(tabs, "");
        } catch (SQLException e) { return new ThreeDAnalysisPack(null, "Ошибка БД: " + e.getMessage()); }
    }

    private List<Double> buildTimeArray(int size, int sampleRate) {
        List<Double> times = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            times.add((double) i / sampleRate);
        }
        return times;
    }
}