package org.example.transport_noise.gui;

import org.example.transport_noise.chart.ChartBuilder;
import org.example.transport_noise.model.DetectionEvent;
import org.example.transport_noise.service.ButterworthBandpass;
import org.example.transport_noise.service.DatabaseService;
import org.example.transport_noise.service.DetectionReportFormatter;
import org.example.transport_noise.service.StaLtaEventDetector;
import org.example.transport_noise.service.ThreeComponentAnalyzer;
import org.example.transport_noise.service.TransportDetectionTuning;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class MainFrame extends JFrame {
    private ChartBuilder chartBuilder;
    private DatabaseService dbService;
    private JComboBox<String> fileNameSelect;
    private JComboBox<String> traceSelect;
    private JTextField startTimeField;
    private JTextField endTimeField;
    private JTextField staField;
    private JTextField ltaField;
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

    private double staThreshold = TransportDetectionTuning.defaultThreshold();
    private JTextField thresholdField;

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
                } catch (Exception e) {
                    System.err.println("Ошибка: " + e.getMessage());
                }
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
                if (newFileNames.isEmpty()) {
                    fileNameSelect.addItem("Нет данных");
                } else {
                    for (String name : newFileNames) fileNameSelect.addItem(name);
                    if (selectedFile != null && newFileNames.contains(selectedFile))
                        fileNameSelect.setSelectedItem(selectedFile);
                }
                fileNames = newFileNames;
            });
        } catch (SQLException e) {
            System.err.println("Ошибка: " + e.getMessage());
        }
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
        } catch (SQLException e) {
            System.err.println("Ошибка: " + e.getMessage());
        }
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
        } catch (SQLException e) {
            sb.append("Ошибка: ").append(e.getMessage()).append("\n");
        }
        if (cachedThreeComponentReport != null && !cachedThreeComponentReport.isBlank())
            sb.append("\n").append(cachedThreeComponentReport);
        final String text = sb.toString();
        SwingUtilities.invokeLater(() -> { infoArea.setText(text); infoArea.setCaretPosition(0); });
    }

    private void updateStatus(String message) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(message));
    }

    // ==================== TOP PANEL ====================

    private JPanel  createTopPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.setBackground(DARKER_BG);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        // Файл
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(label("Файл:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        fileNameSelect = new JComboBox<>();
        fileNameSelect.setPreferredSize(new Dimension(200, 30));
        fileNameSelect.addActionListener(e -> onFileSelected());
        panel.add(fileNameSelect, gbc);

        // Трасса
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1;
        panel.add(label("Трасса:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        traceSelect = new JComboBox<>();
        traceSelect.setPreferredSize(new Dimension(100, 30));
        traceSelect.addActionListener(e -> onTraceSelected());
        panel.add(traceSelect, gbc);

        // Начало
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1;
        panel.add(label("Начало:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        startTimeField = textField("2025-01-01 00:00:00", 200);
        panel.add(startTimeField, gbc);

        // Конец
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 1;
        panel.add(label("Конец:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        endTimeField = textField("2025-12-31 23:59:59", 200);
        panel.add(endTimeField, gbc);

        // Кнопки
        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 3;

        JPanel buttonRow1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        buttonRow1.setBackground(DARKER_BG);

        // STA
        buttonRow1.add(label("STA(с):"));
        staField = new JTextField(String.valueOf(TransportDetectionTuning.defaultStaSec()), 4);
        staField.setPreferredSize(new Dimension(50, 30));
        staField.setBackground(DARK_BG);
        staField.setForeground(Color.WHITE);
        staField.setToolTipText("Короткое окно STA в секундах");
        buttonRow1.add(staField);

        // LTA
        buttonRow1.add(label("LTA(с):"));
        ltaField = new JTextField(String.valueOf(TransportDetectionTuning.defaultLtaSec()), 4);
        ltaField.setPreferredSize(new Dimension(50, 30));
        ltaField.setBackground(DARK_BG);
        ltaField.setForeground(Color.WHITE);
        ltaField.setToolTipText("Длинное окно LTA в секундах");
        buttonRow1.add(ltaField);

        // Порог
        buttonRow1.add(label("Порог:"));
        thresholdField = new JTextField(String.format("%.2f", TransportDetectionTuning.defaultThreshold()), 5);
        thresholdField.setPreferredSize(new Dimension(60, 30));
        thresholdField.setBackground(DARK_BG);
        thresholdField.setForeground(Color.WHITE);
        buttonRow1.add(thresholdField);

        JButton applyBtn = smallButton("✓", new Color(0, 150, 100));
        applyBtn.addActionListener(e -> {
            try {
                double v = Double.parseDouble(thresholdField.getText().trim());
                if (v > 0 && v <= 10.0) { updateThreshold(v); thresholdField.setBackground(DARK_BG); }
                else { thresholdField.setBackground(new Color(150, 50, 50)); }
            } catch (NumberFormatException ex) { thresholdField.setBackground(new Color(150, 50, 50)); }
        });
        buttonRow1.add(applyBtn);
        buttonRow1.add(new JLabel("  "));

        buttonRow1.add(styledButton("📈 Сигнал", ACCENT_COLOR, e -> loadAndShowSignal()));
        buttonRow1.add(styledButton("📊 Дисперсия", new Color(70, 150, 70), e -> loadAndShowVariance()));
        buttonRow1.add(styledButton("📋 Сигнал + Дисперсия", new Color(200, 150, 50), e -> loadAndShowBoth()));
        buttonRow1.add(styledButton("🔬 3D Анализ", new Color(120, 60, 180), e -> showThreeComponentResults()));

        JPanel buttonRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        buttonRow2.setBackground(DARKER_BG);

        buttonRow2.add(styledButton("📉 STA/LTA 1 трасса", new Color(70, 130, 200), e -> showSingleTraceStaLta()));
        buttonRow2.add(styledButton("📊 Сравнение методов", new Color(0, 100, 100), e -> showComparisonReport()));
        buttonRow2.add(styledButton("📋 Отчет", new Color(0, 150, 136), e -> showComparisonReport()));
        buttonRow2.add(styledButton("🗑️ Очистить БД", DANGER_COLOR, e -> clearDatabase()));

        JPanel allButtons = new JPanel(new GridLayout(2, 1, 0, 3));
        allButtons.setBackground(DARKER_BG);
        allButtons.add(buttonRow1);
        allButtons.add(buttonRow2);

        panel.add(allButtons, gbc);
        return panel;
    }

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
        b.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent evt) { b.setBackground(bg.brighter()); }
            public void mouseExited(java.awt.event.MouseEvent evt) { b.setBackground(bg); }
        });
        return b;
    }

    private JButton smallButton(String text, Color bg) {
        JButton b = new JButton(text); b.setFont(new Font("Segoe UI", Font.BOLD, 12));
        b.setBackground(bg); b.setForeground(Color.WHITE); b.setFocusPainted(false);
        b.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return b;
    }

    // ==================== CENTER PANEL ====================

    private JPanel createCenterPanel() {
        JTabbedPane tabs = new JTabbedPane();
        chartPanel = new JPanel(new BorderLayout());
        chartPanel.add(new JLabel("Выберите файл, трассу и нажмите кнопку", SwingConstants.CENTER));
        tabs.addTab("Графики", chartPanel);

        tableModel = new DefaultTableModel(new String[]{"Файл", "Трасса", "Начало", "Отсчётов", "Координаты"}, 0) {
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
        String fn = getSelectedFile(); int tr = getSelectedTrace();
        if (fn == null || tr == 0) { JOptionPane.showMessageDialog(this, "Выберите файл и трассу"); return; }
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() throws Exception {
                chartPanel.removeAll();
                List<Double> d = dbService.getSignalData(fn, tr, startTimeField.getText().trim(), endTimeField.getText().trim());
                chartPanel.add(d.isEmpty() ? new JLabel("Нет данных", SwingConstants.CENTER) :
                        chartBuilder.createSimpleSignalChart(d, 1000, fn + " тр." + tr));
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
                chartPanel.add(d.isEmpty() ? new JLabel("Нет данных", SwingConstants.CENTER) :
                        chartBuilder.createSimpleVarianceChart(d, fn + " тр." + tr));
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
                chartPanel.add(s.isEmpty() && v.isEmpty() ? new JLabel("Нет данных", SwingConstants.CENTER) : c);
                return null;
            }
            protected void done() { chartPanel.revalidate(); chartPanel.repaint(); setCursor(Cursor.getDefaultCursor()); }
        }.execute();
    }

    // ==================== 3D / STA/LTA ====================

    private void syncThresholdFromField() {
        if (thresholdField == null) return;
        try {
            double t = Double.parseDouble(thresholdField.getText().trim());
            staThreshold = t;
        } catch (NumberFormatException ignored) {}
    }

    private void updateThreshold(double t) {
        staThreshold = t;
        String fn = getSelectedFile();
        if (fn != null) reloadWithNewThreshold(fn, t);
    }

    private void reloadWithNewThreshold(String fn, double thr) {
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<ThreeDAnalysisPack, Void>() {
            protected ThreeDAnalysisPack doInBackground() { return buildThreeDAnalysisPack(fn); }
            protected void done() {
                try {
                    ThreeDAnalysisPack p = get();
                    chartPanel.removeAll();
                    if (p.panel != null) chartPanel.add(p.panel, BorderLayout.CENTER);
                    if (p.report != null && !p.report.isEmpty()) {
                        cachedThreeComponentReport = p.report;
                        infoArea.setText(p.report); infoArea.setCaretPosition(0);
                    }
                } catch (Exception ex) { System.err.println(ex.getMessage()); }
                chartPanel.revalidate(); chartPanel.repaint(); setCursor(Cursor.getDefaultCursor());
            }
        }.execute();
    }

    private void showThreeComponentResults() {
        String fn = getSelectedFile();
        if (fn == null) { JOptionPane.showMessageDialog(this, "Выберите файл"); return; }
        syncThresholdFromField();
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<ThreeDAnalysisPack, Void>() {
            protected ThreeDAnalysisPack doInBackground() { return buildThreeDAnalysisPack(fn); }
            protected void done() {
                try {
                    ThreeDAnalysisPack p = get();
                    chartPanel.removeAll();
                    if (p.panel != null) chartPanel.add(p.panel, BorderLayout.CENTER);
                    else chartPanel.add(new JLabel("Нет данных для " + fn, SwingConstants.CENTER));
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
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<String, Void>() {
            protected String doInBackground() throws Exception {
                try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5432/noisedb", "postgres", "32676")) {
                    List<Double> x = loadTraceForBase(c, fn, 1, 100000);
                    List<Double> y = loadTraceForBase(c, fn, 2, 100000);
                    List<Double> z = loadTraceForBase(c, fn, 3, 100000);
                    if (x.isEmpty() || y.isEmpty() || z.isEmpty()) return "❌ Нет данных для " + fn;
                    int m = Math.min(Math.min(x.size(), y.size()), z.size());
                    ThreeComponentAnalyzer an = new ThreeComponentAnalyzer();
                    double staSec = Double.parseDouble(staField.getText().trim());
                    double ltaSec = Double.parseDouble(ltaField.getText().trim());
                    return an.compareAllMethods(x.subList(0, m), y.subList(0, m), z.subList(0, m), 1000, staSec, ltaSec, staThreshold).toComparisonTable();
                }
            }
            protected void done() {
                try { infoArea.setText(get()); infoArea.setCaretPosition(0); }
                catch (Exception e) { infoArea.setText("Ошибка: " + e.getMessage()); }
                setCursor(Cursor.getDefaultCursor());
            }
        }.execute();
    }

    private void showSingleTraceStaLta() {
        String fileName = getSelectedFile();
        int trace = getSelectedTrace();
        if (fileName == null) { JOptionPane.showMessageDialog(this, "Выберите файл"); return; }
        if (trace < 1 || trace > 3) { JOptionPane.showMessageDialog(this, "Выберите трассу 1-3"); return; }
        syncThresholdFromField();
        String startTime = startTimeField.getText().trim();
        String endTime = endTimeField.getText().trim();
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        final String fn = fileName;
        final int tr = trace;
        new SwingWorker<JComponent, Void>() {
            protected JComponent doInBackground() throws Exception {
                DatabaseService.TimedSignal ts = dbService.getSignalDataTimed(fn, tr, startTime, endTime);
                if (ts.values.isEmpty()) return new JLabel("Нет данных", SwingConstants.CENTER);
                int fs = estimateFsFromTimed(ts);

                double staSec = Double.parseDouble(staField.getText().trim());
                double ltaSec = Double.parseDouble(ltaField.getText().trim());
                int sta = Math.max(2, (int)(staSec * fs));
                int lta = Math.max(sta + 1, (int)(ltaSec * fs));

                double lowCut = Math.max(0.5, fs / 500.0);
                List<Double> filtered = ButterworthBandpass.filter(new ArrayList<>(ts.values), fs, lowCut, 100.0);
                int m = Math.min(filtered.size(), Math.min(ts.timeSeconds.size(), ts.values.size()));
                List<Double> tt = new ArrayList<>(ts.timeSeconds.subList(0, m));
                List<Double> raw = new ArrayList<>(ts.values.subList(0, m));
                List<Double> filt = new ArrayList<>(filtered.subList(0, m));
                ThreeComponentAnalyzer an = new ThreeComponentAnalyzer();
                List<DetectionEvent> ev = an.detectDominantStaLtaOnSignal(filt, fs, staSec, ltaSec, staThreshold);                Double tS = null, tE = null;
                if (!ev.isEmpty()) {
                    tS = tt.get(Math.min(Math.max(0, ev.get(0).getStartSample()), m - 1));
                    tE = tt.get(Math.min(Math.max(0, ev.get(0).getEndSample()), m - 1));
                }
                String[] comp = {"X (N–S), тр.1", "Y (E–W), тр.2", "Z, тр.3"};
                JTabbedPane root = new JTabbedPane();
                JPanel p1 = new JPanel(new GridLayout(2, 1));
                p1.add(chartBuilder.createRawVsFilteredChart(tt, raw, filt, "Баттерворт " + String.format("%.2f", lowCut) + "–100 Гц", "До", "После"));
                p1.add(chartBuilder.createStaLtaEnergyComponentsChart(tt, filt, sta, lta, "Энергия x²"));
                root.addTab("Фильтр и энергия", p1);
                root.addTab("STA/LTA", chartBuilder.createAmplitudeWithEventMarkers(tt, filt, tS, tE, fn + " / " + comp[tr - 1]));
                return root;
            }
            protected void done() {
                try { chartPanel.removeAll(); chartPanel.add(get(), BorderLayout.CENTER); chartPanel.revalidate(); chartPanel.repaint(); }
                catch (Exception ex) { JOptionPane.showMessageDialog(MainFrame.this, "Ошибка: " + ex.getMessage()); }
                setCursor(Cursor.getDefaultCursor());
            }
        }.execute();
    }

    private void clearDatabase() {
        if (JOptionPane.showConfirmDialog(this, "⚠️ Удалить ВСЕ данные из БД?", "Подтверждение", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            dbService.clearAllData();
            lastRecordCount = 0;
            cachedThreeComponentReport = null;
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

            double staSec = Double.parseDouble(staField.getText().trim());
            double ltaSec = Double.parseDouble(ltaField.getText().trim());
            int sta = Math.max(2, (int)(staSec * fs));
            int lta = Math.max(sta + 1, (int)(ltaSec * fs));

            List<Double> x = loadTraceForBase(c, fileName, 1, amplitudes.size());
            List<Double> y = loadTraceForBase(c, fileName, 2, amplitudes.size());
            List<Double> z = loadTraceForBase(c, fileName, 3, amplitudes.size());
            ThreeComponentAnalyzer an = new ThreeComponentAnalyzer();
            List<DetectionEvent> evR = an.detectDominantStaLtaOnSignal(amplitudes, fs, staSec, ltaSec, staThreshold);
            Double tS = null, tE = null;
            if (!evR.isEmpty()) { tS = evR.get(0).getStartSample() / (double) fs; tE = evR.get(0).getEndSample() / (double) fs; }
            JTabbedPane tabs = new JTabbedPane();

            JPanel signalPanel = new JPanel(new BorderLayout());
            signalPanel.add(chartBuilder.createSimpleSignalChart(amplitudes, fs, "Сигнал - " + fileName), BorderLayout.CENTER);

            JPanel staLtaPanel = new JPanel(new BorderLayout());
            staLtaPanel.add(chartBuilder.createAmplitudeWithEventMarkers(times, amplitudes, tS, tE, "STA/LTA (R) - " + fileName), BorderLayout.CENTER);

            JPanel overview = new JPanel(new GridLayout(2, 1));
            overview.add(signalPanel);
            overview.add(staLtaPanel);
            tabs.addTab("Обзор", overview);

            if (x.size() == amplitudes.size() && y.size() == amplitudes.size() && z.size() == amplitudes.size()) {
                double low = Math.max(0.5, fs / 500.0);
                List<Double> xf = ButterworthBandpass.filter(new ArrayList<>(x), fs, low, 100.0);
                int m = Math.min(xf.size(), Math.min(times.size(), x.size()));
                JPanel pp = new JPanel(new GridLayout(2, 1));
                pp.add(chartBuilder.createRawVsFilteredChart(new ArrayList<>(times.subList(0, m)), new ArrayList<>(x.subList(0, m)), new ArrayList<>(xf.subList(0, m)), "Баттерворт " + String.format("%.2f", low) + "–100 Гц (X)", "До", "После"));
                pp.add(chartBuilder.createStaLtaEnergyComponentsChart(new ArrayList<>(times.subList(0, m)), new ArrayList<>(xf.subList(0, m)), sta, lta, "Энергия x²"));
                tabs.addTab("Фильтр и энергия", pp);

                double[] ratioX = StaLtaEventDetector.absStaLtaRatio(x, sta, lta);
                double[] ratioY = StaLtaEventDetector.absStaLtaRatio(y, sta, lta);
                double[] ratioZ = StaLtaEventDetector.absStaLtaRatio(z, sta, lta);

                JPanel perComp = new JPanel(new GridLayout(3, 1, 4, 4));
                perComp.add(chartBuilder.createStaLtaRatioChart(times, ratioX, staThreshold, null, null, fileName + " - X (N-S)", "STA/LTA |X|", "Отношение"));
                perComp.add(chartBuilder.createStaLtaRatioChart(times, ratioY, staThreshold, null, null, fileName + " - Y (E-W)", "STA/LTA |Y|", "Отношение"));
                perComp.add(chartBuilder.createStaLtaRatioChart(times, ratioZ, staThreshold, null, null, fileName + " - Z (верт.)", "STA/LTA |Z|", "Отношение"));
                tabs.addTab("STA/LTA: X, Y, Z", perComp);
            }
            return new ThreeDAnalysisPack(tabs, "");
        } catch (SQLException e) {
            return new ThreeDAnalysisPack(null, "Ошибка БД: " + e.getMessage());
        }
    }

}