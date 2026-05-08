package org.example.transport_noise.gui;

import org.example.transport_noise.chart.ChartBuilder;
import org.example.transport_noise.service.DatabaseService;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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
            if (!isRefreshing) {
                checkAndRefreshIfNeeded();
            }
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
                    if (rs.next()) {
                        return rs.getLong(1);
                    }
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
                    for (String name : newFileNames) {
                        fileNameSelect.addItem(name);
                    }
                    if (selectedFile != null && newFileNames.contains(selectedFile)) {
                        fileNameSelect.setSelectedItem(selectedFile);
                    }
                }
                fileNames = newFileNames;
            });
        } catch (SQLException e) {
            System.err.println("Ошибка: " + e.getMessage());
        }
    }

    private void refreshDataTable() {
        String sql = "SELECT " +
                "    SUBSTRING(d.file_name FROM '^(.*?)\\.') as base_name, " +
                "    d.trace_number, " +
                "    MIN(d.record_time) as start_time, " +
                "    COUNT(*) as samples, " +
                "    d.latitude, d.longitude " +
                "FROM signal_data d " +
                "GROUP BY base_name, d.trace_number, d.latitude, d.longitude " +
                "ORDER BY base_name, d.trace_number " +
                "LIMIT 100";

        try (Connection conn = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/noisedb", "postgres", "32676");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            List<Object[]> rows = new ArrayList<>();
            while (rs.next()) {
                double lat = rs.getDouble("latitude");
                double lon = rs.getDouble("longitude");
                String coords = (lat != 0 || lon != 0) ?
                        String.format("%.4f, %.4f", lat, lon) : "—";

                rows.add(new Object[]{
                        rs.getString("base_name"),
                        rs.getInt("trace_number"),
                        rs.getTimestamp("start_time").toString(),
                        String.format("%,d", rs.getInt("samples")),
                        coords
                });
            }

            SwingUtilities.invokeLater(() -> {
                tableModel.setRowCount(0);
                for (Object[] row : rows) {
                    tableModel.addRow(row);
                }
            });

        } catch (SQLException e) {
            System.err.println("Ошибка: " + e.getMessage());
        }
    }

    private void updateInfoPanelData() {
        StringBuilder sb = new StringBuilder();
        sb.append("╔════════════════════════════════════════╗\n");
        sb.append("║     ИНФОРМАЦИЯ О ДАННЫХ               ║\n");
        sb.append("╚════════════════════════════════════════╝\n\n");

        try (Connection conn = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/noisedb", "postgres", "32676")) {

            // Основная статистика
            String mainSql = "SELECT " +
                    "   COUNT(DISTINCT SUBSTRING(file_name FROM '^(.*?)\\.')) as files, " +
                    "   COUNT(DISTINCT trace_number) as traces, " +
                    "   COUNT(*) as total_samples, " +
                    "   MIN(record_time) as first_record, " +
                    "   MAX(record_time) as last_record " +
                    "FROM signal_data";

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(mainSql)) {

                if (rs.next()) {
                    sb.append("📊 СТАТИСТИКА БАЗЫ ДАННЫХ:\n");
                    sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

                    int files = rs.getInt("files");
                    int traces = rs.getInt("traces");
                    int samples = rs.getInt("total_samples");

                    sb.append("   Файлов: ").append(files).append("\n");
                    sb.append("   Трасс: ").append(traces).append("\n");
                    sb.append("   Отсчётов: ").append(String.format("%,d", samples)).append("\n");

                    Timestamp first = rs.getTimestamp("first_record");
                    Timestamp last = rs.getTimestamp("last_record");

                    if (first != null && last != null) {
                        sb.append("   Первая запись: ").append(first).append("\n");
                        sb.append("   Последняя запись: ").append(last).append("\n");

                        // Вычисляем общую длительность
                        long durationMs = last.getTime() - first.getTime();
                        long seconds = durationMs / 1000;
                        long hours = seconds / 3600;
                        long minutes = (seconds % 3600) / 60;
                        seconds = seconds % 60;

                        sb.append("   Общая длительность: ");
                        if (hours > 0) sb.append(hours).append(" ч ");
                        if (minutes > 0) sb.append(minutes).append(" мин ");
                        sb.append(seconds).append(" сек\n");
                    }
                }
            }

            // Статистика по файлам
            sb.append("\n📋 ДАННЫЕ ПО ФАЙЛАМ:\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

            String filesSql = "SELECT " +
                    "    SUBSTRING(d.file_name FROM '^(.*?)\\.') as base_name, " +
                    "    COUNT(DISTINCT d.trace_number) as trace_count, " +
                    "    COUNT(*) as sample_count, " +
                    "    MIN(d.record_time) as start_time, " +
                    "    MAX(d.record_time) as end_time, " +
                    "    d.latitude, d.longitude " +
                    "FROM signal_data d " +
                    "GROUP BY base_name, d.latitude, d.longitude " +
                    "ORDER BY base_name";

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(filesSql)) {

                while (rs.next()) {
                    String fileName = rs.getString("base_name");
                    int traceCount = rs.getInt("trace_count");
                    int sampleCount = rs.getInt("sample_count");
                    double lat = rs.getDouble("latitude");
                    double lon = rs.getDouble("longitude");

                    sb.append("\n   📄 ").append(fileName).append("\n");
                    sb.append("      Трасс: ").append(traceCount).append("\n");
                    sb.append("      Отсчётов: ").append(String.format("%,d", sampleCount)).append("\n");

                    if (lat != 0 || lon != 0) {
                        sb.append("      📍 Координаты: ")
                                .append(String.format("%.6f, %.6f", lat, lon)).append("\n");
                    }

                    // Статистика по трассам внутри файла
                    String traceSql = "SELECT trace_number, " +
                            "COUNT(*) as cnt, " +
                            "MIN(record_time) as t_start, " +
                            "MAX(record_time) as t_end " +
                            "FROM signal_data " +
                            "WHERE file_name LIKE ? " +
                            "GROUP BY trace_number " +
                            "ORDER BY trace_number";

                    try (PreparedStatement pstmt = conn.prepareStatement(traceSql)) {
                        pstmt.setString(1, fileName + ".%");
                        ResultSet traceRs = pstmt.executeQuery();

                        boolean firstTrace = true;
                        while (traceRs.next()) {
                            if (firstTrace) {
                                sb.append("      Трассы:\n");
                                firstTrace = false;
                            }

                            int traceNum = traceRs.getInt("trace_number");
                            int cnt = traceRs.getInt("cnt");
                            Timestamp tStart = traceRs.getTimestamp("t_start");
                            Timestamp tEnd = traceRs.getTimestamp("t_end");

                            sb.append("        • Трасса ").append(traceNum)
                                    .append(": ").append(String.format("%,d", cnt)).append(" отсчётов");

                            if (tStart != null && tEnd != null) {
                                long durMs = tEnd.getTime() - tStart.getTime();
                                sb.append(" (").append(durMs / 1000).append(" сек)");
                            }
                            sb.append("\n");
                        }
                        traceRs.close();
                    }
                }
            }

            // Статистика дисперсии
            sb.append("\n📊 СТАТИСТИКА ДИСПЕРСИИ:\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

            String varSql = "SELECT " +
                    "    SUBSTRING(s.file_name FROM '^(.*?)\\.') as base_name, " +
                    "    COUNT(*) as sec_count, " +
                    "    ROUND(MIN(s.variance)::numeric, 8) as min_var, " +
                    "    ROUND(MAX(s.variance)::numeric, 8) as max_var, " +
                    "    ROUND(AVG(s.variance)::numeric, 8) as avg_var " +
                    "FROM signal_statistics s " +
                    "GROUP BY base_name " +
                    "ORDER BY base_name";

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(varSql)) {

                while (rs.next()) {
                    sb.append("   📄 ").append(rs.getString("base_name")).append("\n");
                    sb.append("      Секунд: ").append(rs.getInt("sec_count")).append("\n");
                    sb.append("      Дисперсия: мин=").append(rs.getString("min_var"))
                            .append(", макс=").append(rs.getString("max_var"))
                            .append(", сред=").append(rs.getString("avg_var")).append("\n\n");
                }
            }

        } catch (SQLException e) {
            sb.append("❌ Ошибка: ").append(e.getMessage()).append("\n");
        }

        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("💡 Выберите файл и трассу для графиков\n");
        sb.append("💡 Данные обновляются автоматически\n");
        sb.append("💡 Новые файлы из папки test/ сохраняются в БД\n");

        final String text = sb.toString();
        SwingUtilities.invokeLater(() -> {
            infoArea.setText(text);
            infoArea.setCaretPosition(0);
        });
    }

    private void updateStatus(String message) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(message));
    }

    private JPanel createTopPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        // Файл
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Файл:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        fileNameSelect = new JComboBox<>();
        fileNameSelect.setPreferredSize(new Dimension(200, 25));
        fileNameSelect.addActionListener(e -> onFileSelected());
        panel.add(fileNameSelect, gbc);

        // Трасса
        gbc.gridx = 0; gbc.gridy = 1;
        gbc.gridwidth = 1;
        panel.add(new JLabel("Трасса:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        traceSelect = new JComboBox<>();
        traceSelect.setPreferredSize(new Dimension(100, 25));
        traceSelect.addActionListener(e -> onTraceSelected());
        panel.add(traceSelect, gbc);

        // Дата С
        gbc.gridx = 0; gbc.gridy = 2;
        panel.add(new JLabel("Начало:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        startTimeField = new JTextField("2025-01-01 00:00:00");
        panel.add(startTimeField, gbc);

        // Дата ПО
        gbc.gridx = 0; gbc.gridy = 3;
        panel.add(new JLabel("Конец:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        endTimeField = new JTextField("2025-12-31 23:59:59");
        panel.add(endTimeField, gbc);

        // Кнопки
        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 3;
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        JButton signalBtn = new JButton("📈 Сигнал");
        signalBtn.addActionListener(e -> loadAndShowSignal());
        buttonPanel.add(signalBtn);

        JButton varianceBtn = new JButton("📊 Дисперсия");
        varianceBtn.addActionListener(e -> loadAndShowVariance());
        buttonPanel.add(varianceBtn);

        JButton bothBtn = new JButton("📋 Сигнал + Дисперсия");
        bothBtn.addActionListener(e -> loadAndShowBoth());
        buttonPanel.add(bothBtn);

        JButton clearBtn = new JButton("🗑️ Очистить БД");
        clearBtn.setBackground(new Color(255, 200, 200));
        clearBtn.addActionListener(e -> clearDatabase());
        buttonPanel.add(clearBtn);

        panel.add(buttonPanel, gbc);
        return panel;
    }

    private JPanel createCenterPanel() {
        JTabbedPane tabs = new JTabbedPane();

        chartPanel = new JPanel(new BorderLayout());
        chartPanel.add(new JLabel("Выберите файл, трассу и нажмите кнопку", SwingConstants.CENTER));
        tabs.addTab("📈 Графики", chartPanel);

        tableModel = new DefaultTableModel(
                new String[]{"Файл", "Трасса", "Начало", "Отсчётов", "Координаты"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        dataTable = new JTable(tableModel);
        tabs.addTab("📊 Данные", new JScrollPane(dataTable));

        infoArea = new JTextArea();
        infoArea.setEditable(false);
        infoArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        tabs.addTab("ℹ️ Информация", new JScrollPane(infoArea));

        mapPanel = new MapPanel();
        tabs.addTab("🗺️ Карта", mapPanel);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(tabs, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createStatusBar() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
        statusLabel = new JLabel("✅ Готов");
        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        panel.add(statusLabel, BorderLayout.WEST);
        return panel;
    }

    // ===== МЕТОДЫ ОБРАБОТКИ СОБЫТИЙ =====

    private void onFileSelected() {
        String selectedFile = getSelectedFile();
        if (selectedFile == null) return;

        try {
            List<Integer> traces = dbService.getTraceNumbersForFile(selectedFile);
            traceNumbers = traces;

            traceSelect.removeAllItems();
            if (traces.isEmpty()) {
                traceSelect.addItem("Нет данных");
            } else {
                for (Integer trace : traces) {
                    traceSelect.addItem(String.valueOf(trace));
                }
            }
        } catch (SQLException e) {
            System.err.println("Ошибка: " + e.getMessage());
        }
    }

    private void onTraceSelected() {
        String selectedFile = getSelectedFile();
        String selectedTrace = (String) traceSelect.getSelectedItem();
        if (selectedFile == null || selectedTrace == null) return;

        try {
            int traceNumber = Integer.parseInt(selectedTrace);

            String sql = "SELECT latitude, longitude FROM signal_data " +
                    "WHERE file_name LIKE ? AND trace_number = ? LIMIT 1";

            try (Connection conn = DriverManager.getConnection(
                    "jdbc:postgresql://localhost:5432/noisedb", "postgres", "32676");
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setString(1, selectedFile + ".%");
                pstmt.setInt(2, traceNumber);
                ResultSet rs = pstmt.executeQuery();

                if (rs.next() && mapPanel != null) {
                    double lat = rs.getDouble("latitude");
                    double lon = rs.getDouble("longitude");
                    if (lat != 0 || lon != 0) {
                        mapPanel.showLocation(lat, lon, selectedFile + " тр." + traceNumber);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Ошибка: " + e.getMessage());
        }
    }

    private String getSelectedFile() {
        String selected = (String) fileNameSelect.getSelectedItem();
        return (selected != null && !selected.equals("Нет данных")) ? selected : null;
    }

    private int getSelectedTrace() {
        String selected = (String) traceSelect.getSelectedItem();
        if (selected == null || selected.equals("Нет данных")) return 0;
        try { return Integer.parseInt(selected); }
        catch (NumberFormatException e) { return 0; }
    }

    private void loadAndShowSignal() {
        String fileName = getSelectedFile();
        int traceNumber = getSelectedTrace();
        if (fileName == null || traceNumber == 0) {
            JOptionPane.showMessageDialog(this, "Выберите файл и трассу");
            return;
        }

        String startTime = startTimeField.getText().trim();
        String endTime = endTimeField.getText().trim();

        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                chartPanel.removeAll();
                List<Double> data = dbService.getSignalData(fileName, traceNumber, startTime, endTime);

                if (data.isEmpty()) {
                    chartPanel.add(new JLabel("Нет данных", SwingConstants.CENTER));
                } else {
                    chartPanel.add(chartBuilder.createSimpleSignalChart(
                            data, 1000, fileName + " тр." + traceNumber));
                }
                return null;
            }

            @Override
            protected void done() {
                chartPanel.revalidate();
                chartPanel.repaint();
                setCursor(Cursor.getDefaultCursor());
            }
        }.execute();
    }

    private void loadAndShowVariance() {
        String fileName = getSelectedFile();
        int traceNumber = getSelectedTrace();
        if (fileName == null || traceNumber == 0) {
            JOptionPane.showMessageDialog(this, "Выберите файл и трассу");
            return;
        }

        String startTime = startTimeField.getText().trim();
        String endTime = endTimeField.getText().trim();

        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                chartPanel.removeAll();
                List<Double> data = dbService.getVarianceData(fileName, traceNumber, startTime, endTime);

                if (data.isEmpty()) {
                    chartPanel.add(new JLabel("Нет данных", SwingConstants.CENTER));
                } else {
                    chartPanel.add(chartBuilder.createSimpleVarianceChart(
                            data, fileName + " тр." + traceNumber));
                }
                return null;
            }

            @Override
            protected void done() {
                chartPanel.revalidate();
                chartPanel.repaint();
                setCursor(Cursor.getDefaultCursor());
            }
        }.execute();
    }

    private void loadAndShowBoth() {
        String fileName = getSelectedFile();
        int traceNumber = getSelectedTrace();
        if (fileName == null || traceNumber == 0) return;

        String startTime = startTimeField.getText().trim();
        String endTime = endTimeField.getText().trim();

        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                chartPanel.removeAll();
                List<Double> signal = dbService.getSignalData(fileName, traceNumber, startTime, endTime);
                List<Double> variance = dbService.getVarianceData(fileName, traceNumber, startTime, endTime);

                JPanel combined = new JPanel(new GridLayout(2, 1));
                if (!signal.isEmpty()) {
                    combined.add(chartBuilder.createSimpleSignalChart(signal, 1000, "Сигнал"));
                }
                if (!variance.isEmpty()) {
                    combined.add(chartBuilder.createSimpleVarianceChart(variance, "Дисперсия"));
                }

                if (signal.isEmpty() && variance.isEmpty()) {
                    chartPanel.add(new JLabel("Нет данных", SwingConstants.CENTER));
                } else {
                    chartPanel.add(combined);
                }
                return null;
            }

            @Override
            protected void done() {
                chartPanel.revalidate();
                chartPanel.repaint();
                setCursor(Cursor.getDefaultCursor());
            }
        }.execute();
    }

    private void clearDatabase() {
        int result = JOptionPane.showConfirmDialog(
                this, "⚠️ Удалить ВСЕ данные из БД?", "Подтверждение",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

        if (result == JOptionPane.YES_OPTION) {
            dbService.clearAllData();
            lastRecordCount = 0;
            refreshAllDataAsync();
            chartPanel.removeAll();
            chartPanel.revalidate();
            chartPanel.repaint();
        }
    }
}