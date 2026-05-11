package org.example.transport_noise.gui;

import org.example.transport_noise.chart.ChartBuilder;
import org.example.transport_noise.service.DatabaseService;
import org.example.transport_noise.service.ThreeComponentAnalyzer;

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

    // Цветовая схема для темной темы
    private static final Color DARK_BG = new Color(43, 43, 43);
    private static final Color DARKER_BG = new Color(30, 30, 30);
    private static final Color ACCENT_COLOR = new Color(75, 110, 175);
    private static final Color DANGER_COLOR = new Color(200, 80, 80);

    private double staThreshold = 1.2;  // Текущий порог (по умолчанию 1.2)
    private JTextField thresholdField;   // Поле ввода


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
                    sb.append("СТАТИСТИКА БАЗЫ ДАННЫХ:\n");
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
            sb.append("\nДАННЫЕ ПО ФАЙЛАМ:\n");
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
            sb.append("\nСТАТИСТИКА ДИСПЕРСИИ:\n");
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
        panel.setBackground(DARKER_BG);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        // Выбор файла
        gbc.gridx = 0; gbc.gridy = 0;
        JLabel fileLabel = new JLabel("Файл:");
        fileLabel.setForeground(Color.WHITE);
        panel.add(fileLabel, gbc);

        gbc.gridx = 1; gbc.gridwidth = 2;
        fileNameSelect = new JComboBox<>();
        fileNameSelect.setPreferredSize(new Dimension(200, 30));
        fileNameSelect.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        fileNameSelect.addActionListener(e -> onFileSelected());
        panel.add(fileNameSelect, gbc);

        // Выбор трассы
        gbc.gridx = 0; gbc.gridy = 1;
        gbc.gridwidth = 1;
        JLabel traceLabel = new JLabel("Трасса:");
        traceLabel.setForeground(Color.WHITE);
        panel.add(traceLabel, gbc);

        gbc.gridx = 1; gbc.gridwidth = 2;
        traceSelect = new JComboBox<>();
        traceSelect.setPreferredSize(new Dimension(100, 30));
        traceSelect.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        traceSelect.addActionListener(e -> onTraceSelected());
        panel.add(traceSelect, gbc);

        // Начало интервала
        gbc.gridx = 0; gbc.gridy = 2;
        gbc.gridwidth = 1;
        JLabel startLabel = new JLabel("Начало:");
        startLabel.setForeground(Color.WHITE);
        panel.add(startLabel, gbc);

        gbc.gridx = 1; gbc.gridwidth = 2;
        startTimeField = new JTextField("2025-01-01 00:00:00");
        startTimeField.setPreferredSize(new Dimension(200, 30));
        startTimeField.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        startTimeField.setBackground(DARK_BG);
        startTimeField.setForeground(Color.WHITE);
        startTimeField.setCaretColor(Color.WHITE);
        startTimeField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)
        ));
        panel.add(startTimeField, gbc);

        // Конец интервала
        gbc.gridx = 0; gbc.gridy = 3;
        gbc.gridwidth = 1;
        JLabel endLabel = new JLabel("Конец:");
        endLabel.setForeground(Color.WHITE);
        panel.add(endLabel, gbc);

        gbc.gridx = 1; gbc.gridwidth = 2;
        endTimeField = new JTextField("2025-12-31 23:59:59");
        endTimeField.setPreferredSize(new Dimension(200, 30));
        endTimeField.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        endTimeField.setBackground(DARK_BG);
        endTimeField.setForeground(Color.WHITE);
        endTimeField.setCaretColor(Color.WHITE);
        endTimeField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)
        ));
        panel.add(endTimeField, gbc);

        // Кнопки действий
        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 3;
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5));
        buttonPanel.setBackground(DARKER_BG);

        // Поле ввода threshold
        JLabel threshLabel = new JLabel("Порог:");
        threshLabel.setForeground(Color.WHITE);
        buttonPanel.add(threshLabel);

        JTextField thresholdField = new JTextField("1.2", 5);
        thresholdField.setPreferredSize(new Dimension(60, 30));
        thresholdField.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        thresholdField.setBackground(DARK_BG);
        thresholdField.setForeground(Color.WHITE);
        thresholdField.setCaretColor(Color.WHITE);
        thresholdField.setToolTipText("Порог STA/LTA (1.0-3.0)");
        buttonPanel.add(thresholdField);

// Кнопка применения порога
        JButton applyThresholdBtn = new JButton("✓");
        applyThresholdBtn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        applyThresholdBtn.setBackground(new Color(0, 150, 100));
        applyThresholdBtn.setForeground(Color.WHITE);
        applyThresholdBtn.setFocusPainted(false);
        applyThresholdBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        applyThresholdBtn.setToolTipText("Применить порог");
        applyThresholdBtn.addActionListener(e -> {
            try {
                double newThreshold = Double.parseDouble(thresholdField.getText().trim());
                if (newThreshold > 0 && newThreshold <= 10.0) {
                    updateThreshold(newThreshold);
                    thresholdField.setBackground(DARK_BG);
                } else {
                    thresholdField.setBackground(new Color(150, 50, 50));
                    JOptionPane.showMessageDialog(this, "Порог должен быть от 0.1 до 10.0");
                }
            } catch (NumberFormatException ex) {
                thresholdField.setBackground(new Color(150, 50, 50));
                JOptionPane.showMessageDialog(this, "Введите число (например, 1.2)");
            }
        });
        buttonPanel.add(applyThresholdBtn);

// Разделитель
        buttonPanel.add(new JLabel("  "));

        // Кнопка Сигнал
        JButton signalBtn = createStyledButton("📈 Сигнал", ACCENT_COLOR);
        signalBtn.addActionListener(e -> loadAndShowSignal());
        buttonPanel.add(signalBtn);

        // Кнопка Дисперсия
        JButton varianceBtn = createStyledButton("📊 Дисперсия", new Color(70, 150, 70));
        varianceBtn.addActionListener(e -> loadAndShowVariance());
        buttonPanel.add(varianceBtn);

        // Кнопка Оба графика
        JButton bothBtn = createStyledButton("📋 Сигнал + Дисперсия", new Color(200, 150, 50));
        bothBtn.addActionListener(e -> loadAndShowBoth());
        buttonPanel.add(bothBtn);

        // Кнопка 3D Анализ (НОВАЯ)
        JButton analysisBtn = createStyledButton("🔬 3D Анализ", new Color(120, 60, 180));
        analysisBtn.addActionListener(e -> showThreeComponentResults());
        buttonPanel.add(analysisBtn);

        // Кнопка Очистить БД
        JButton clearBtn = createStyledButton("🗑️ Очистить БД", DANGER_COLOR);
        clearBtn.addActionListener(e -> clearDatabase());
        buttonPanel.add(clearBtn);

        // В методе createTopPanel() добавьте кнопку:
        JButton reportBtn = createStyledButton("📋 Отчет", new Color(0, 150, 136));
        reportBtn.addActionListener(e -> showAnalysisReport());
        buttonPanel.add(reportBtn);

        panel.add(buttonPanel, gbc);

        return panel;
    }

    /**
     * Обновить порог STA/LTA
     */
    private void updateThreshold(double newThreshold) {
        this.staThreshold = newThreshold;
        System.out.println("⚙️  Порог STA/LTA изменен: " + newThreshold);

        // Если есть загруженные данные трехкомпонентного анализа — перестраиваем график
        String fileName = getSelectedFile();
        if (fileName != null) {
            // Перезагружаем с новым порогом
            reloadWithNewThreshold(fileName, newThreshold);
        }

        JOptionPane.showMessageDialog(this,
                "Порог установлен: " + newThreshold,
                "Настройки",
                JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Перезагрузить график с новым порогом
     */
    private void reloadWithNewThreshold(String fileName, double threshold) {
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                chartPanel.removeAll();

                // Загружаем данные
                List<Double> times = new ArrayList<>();
                List<Double> azimuths = new ArrayList<>();
                List<Double> amplitudes = new ArrayList<>();
                List<Integer> events = new ArrayList<>();

                try (Connection conn = DriverManager.getConnection(
                        "jdbc:postgresql://localhost:5432/noisedb", "postgres", "32676");
                     PreparedStatement pstmt = conn.prepareStatement(
                             "SELECT azimuth, result_amplitude, is_event, time_seconds " +
                                     "FROM three_component_analysis " +
                                     "WHERE station_name LIKE ? " +
                                     "ORDER BY time_seconds")) {

                    pstmt.setString(1, "%" + fileName + "%");
                    ResultSet rs = pstmt.executeQuery();

                    int idx = 0;
                    while (rs.next()) {
                        times.add(rs.getDouble("time_seconds"));
                        azimuths.add(rs.getDouble("azimuth"));
                        amplitudes.add(rs.getDouble("result_amplitude"));
                        if (rs.getBoolean("is_event")) {
                            events.add(idx);
                        }
                        idx++;
                    }
                }

                // Пересчитываем события с новым порогом
                if (!amplitudes.isEmpty()) {
                    // Применяем STA/LTA с новым порогом
                    List<Integer> newEvents = recalculateEvents(amplitudes, threshold);

                    JPanel analysisPanel = new JPanel(new GridLayout(2, 1));
                    analysisPanel.add(chartBuilder.createAzimuthChartWithTime(times, azimuths, fileName));
                    analysisPanel.add(chartBuilder.createSTATLTChartWithTime(times, amplitudes, newEvents, fileName));
                    chartPanel.add(analysisPanel);

                    System.out.println("   С порогом " + threshold + " найдено событий: " + newEvents.size());
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

    /**
     * Пересчет событий с новым порогом
     */
    private List<Integer> recalculateEvents(List<Double> signal, double threshold) {
        ThreeComponentAnalyzer analyzer = new ThreeComponentAnalyzer();
        return analyzer.detectEventsSTA_LTA(signal, 100, 2000, threshold);
    }

    /**
     * Показать полный отчет трехкомпонентного анализа с интерпретацией
     */
    private void showAnalysisReport() {
        String fileName = getSelectedFile();
        if (fileName == null) {
            JOptionPane.showMessageDialog(this, "Выберите станцию (файл)");
            return;
        }

        StringBuilder report = new StringBuilder();

        // Заголовок
        report.append("╔══════════════════════════════════════════════════════════╗\n");
        report.append("║        ПОЛНЫЙ ОТЧЕТ ТРЕХКОМПОНЕНТНОГО АНАЛИЗА           ║\n");
        report.append("╚══════════════════════════════════════════════════════════╝\n\n");

        try (Connection conn = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/noisedb", "postgres", "32676")) {

            // ===== 1. ОСНОВНАЯ ИНФОРМАЦИЯ =====
            String sql1 = "SELECT station_name, COUNT(*) as points, " +
                    "MIN(timestamp) as start_time, MAX(timestamp) as end_time, " +
                    "AVG(latitude) as lat, AVG(longitude) as lon, " +
                    "ROUND((MAX(time_seconds) - MIN(time_seconds))::numeric, 1) as duration_sec " +
                    "FROM three_component_analysis " +
                    "WHERE station_name LIKE ? " +
                    "GROUP BY station_name";

            try (PreparedStatement pstmt = conn.prepareStatement(sql1)) {
                pstmt.setString(1, "%" + fileName + "%");
                ResultSet rs = pstmt.executeQuery();

                if (rs.next()) {
                    report.append("┌──────────────────────────────────────────────────────────┐\n");
                    report.append("│  📡 ОСНОВНАЯ ИНФОРМАЦИЯ                                  │\n");
                    report.append("├──────────────────────────────────────────────────────────┤\n");
                    report.append(String.format("│  Станция: %-47s │\n", rs.getString("station_name")));
                    report.append(String.format("│  Координаты: %-43s │\n",
                            String.format("%.6f, %.6f", rs.getDouble("lat"), rs.getDouble("lon"))));
                    report.append(String.format("│  Период записи: %-41s │\n",
                            rs.getTimestamp("start_time") + " — " + rs.getTimestamp("end_time")));
                    report.append(String.format("│  Длительность: %-43s │\n",
                            rs.getDouble("duration_sec") + " сек"));
                    report.append(String.format("│  Точек анализа: %-42s │\n",
                            String.format("%,d", rs.getInt("points"))));
                    report.append(String.format("│  Порог STA/LTA: %-43s │\n", staThreshold));
                    report.append("└──────────────────────────────────────────────────────────┘\n\n");
                }
            }

            // ===== 2. АМПЛИТУДНЫЙ АНАЛИЗ =====
            String sql2 = "SELECT " +
                    "  ROUND(MIN(result_amplitude)::numeric, 6) as min_amp, " +
                    "  ROUND(MAX(result_amplitude)::numeric, 6) as max_amp, " +
                    "  ROUND(AVG(result_amplitude)::numeric, 6) as avg_amp, " +
                    "  ROUND(STDDEV(result_amplitude)::numeric, 6) as std_amp " +
                    "FROM three_component_analysis " +
                    "WHERE station_name LIKE ?";

            try (PreparedStatement pstmt = conn.prepareStatement(sql2)) {
                pstmt.setString(1, "%" + fileName + "%");
                ResultSet rs = pstmt.executeQuery();

                if (rs.next()) {
                    double maxAmp = rs.getDouble("max_amp");
                    double avgAmp = rs.getDouble("avg_amp");

                    report.append("┌──────────────────────────────────────────────────────────┐\n");
                    report.append("│  📈 АМПЛИТУДНЫЙ АНАЛИЗ                                   │\n");
                    report.append("├──────────────────────────────────────────────────────────┤\n");
                    report.append(String.format("│  Минимум: %-47s │\n", rs.getString("min_amp")));
                    report.append(String.format("│  Максимум: %-46s │\n", rs.getString("max_amp")));
                    report.append(String.format("│  Среднее: %-47s │\n", rs.getString("avg_amp")));
                    report.append(String.format("│  Отклонение: %-45s │\n", rs.getString("std_amp")));
                    report.append(String.format("│  Динамический диапазон: %-35s │\n",
                            String.format("%.1f дБ", 20 * Math.log10(maxAmp / Math.max(avgAmp, 0.000001)))));
                    report.append("└──────────────────────────────────────────────────────────┘\n\n");
                }
            }

            // ===== 3. АЗИМУТАЛЬНЫЙ АНАЛИЗ =====
            String sql3 = "SELECT " +
                    "  ROUND(AVG(azimuth)::numeric, 1) as avg_az, " +
                    "  ROUND(STDDEV(azimuth)::numeric, 1) as std_az, " +
                    "  ROUND(MIN(azimuth)::numeric, 1) as min_az, " +
                    "  ROUND(MAX(azimuth)::numeric, 1) as max_az " +
                    "FROM three_component_analysis " +
                    "WHERE station_name LIKE ?";

            try (PreparedStatement pstmt = conn.prepareStatement(sql3)) {
                pstmt.setString(1, "%" + fileName + "%");
                ResultSet rs = pstmt.executeQuery();

                if (rs.next()) {
                    double avgAz = rs.getDouble("avg_az");
                    double stdAz = rs.getDouble("std_az");

                    report.append("┌──────────────────────────────────────────────────────────┐\n");
                    report.append("│  🧭 АЗИМУТАЛЬНЫЙ АНАЛИЗ                                  │\n");
                    report.append("├──────────────────────────────────────────────────────────┤\n");
                    report.append(String.format("│  Средний азимут: %-40s │\n",
                            avgAz + "° (" + getDirectionName(avgAz) + ")"));
                    report.append(String.format("│  Отклонение: %-45s │\n", "±" + stdAz + "°"));
                    report.append(String.format("│  Диапазон: %-47s │\n",
                            rs.getString("min_az") + "° — " + rs.getString("max_az") + "°"));

                    // Интерпретация надежности азимута
                    String reliability;
                    if (stdAz < 15) {
                        reliability = "✅ ВЫСОКАЯ — направление определено точно";
                    } else if (stdAz < 45) {
                        reliability = "⚠️  СРЕДНЯЯ — есть преимущественное направление";
                    } else {
                        reliability = "❌ НИЗКАЯ — азимут случаен (нет направленного источника)";
                    }
                    report.append(String.format("│  Надежность: %-45s │\n", reliability));
                    report.append("└──────────────────────────────────────────────────────────┘\n\n");
                }
            }

            // ===== 4. ПОЛЯРИЗАЦИОННЫЙ АНАЛИЗ =====
            String sql4 = "SELECT " +
                    "  ROUND(AVG(polarization)::numeric, 3) as avg_pol, " +
                    "  ROUND(STDDEV(polarization)::numeric, 3) as std_pol, " +
                    "  ROUND(MIN(polarization)::numeric, 3) as min_pol, " +
                    "  ROUND(MAX(polarization)::numeric, 3) as max_pol " +
                    "FROM three_component_analysis " +
                    "WHERE station_name LIKE ?";

            try (PreparedStatement pstmt = conn.prepareStatement(sql4)) {
                pstmt.setString(1, "%" + fileName + "%");
                ResultSet rs = pstmt.executeQuery();

                if (rs.next()) {
                    double avgPol = rs.getDouble("avg_pol");

                    report.append("┌──────────────────────────────────────────────────────────┐\n");
                    report.append("│  🔄 ПОЛЯРИЗАЦИОННЫЙ АНАЛИЗ                                │\n");
                    report.append("├──────────────────────────────────────────────────────────┤\n");
                    report.append(String.format("│  Средняя поляризация: %-37s │\n", rs.getString("avg_pol")));
                    report.append(String.format("│  Отклонение: %-45s │\n", "±" + rs.getString("std_pol")));
                    report.append(String.format("│  Диапазон: %-47s │\n",
                            rs.getString("min_pol") + " — " + rs.getString("max_pol")));

                    // Интерпретация поляризации
                    String polType;
                    String polSource;
                    if (avgPol > 0.7) {
                        polType = "ЛИНЕЙНАЯ (частицы вдоль линии)";
                        polSource = "🚂 Поезд, взрыв, компрессор";
                    } else if (avgPol > 0.4) {
                        polType = "ЭЛЛИПТИЧЕСКАЯ (частицы по эллипсу)";
                        polSource = "🚗 Автомобиль, поверхностные волны";
                    } else {
                        polType = "КРУГОВАЯ (хаотичное движение)";
                        polSource = "🌬️ Ветер, фоновый шум";
                    }
                    report.append(String.format("│  Тип поляризации: %-41s │\n", polType));
                    report.append(String.format("│  Вероятный источник: %-37s │\n", polSource));
                    report.append("└──────────────────────────────────────────────────────────┘\n\n");
                }
            }

            // ===== 5. АНАЛИЗ УГЛА НАКЛОНА =====
            String sql5 = "SELECT " +
                    "  ROUND(AVG(incidence_angle)::numeric, 1) as avg_inc, " +
                    "  ROUND(STDDEV(incidence_angle)::numeric, 1) as std_inc " +
                    "FROM three_component_analysis " +
                    "WHERE station_name LIKE ?";

            try (PreparedStatement pstmt = conn.prepareStatement(sql5)) {
                pstmt.setString(1, "%" + fileName + "%");
                ResultSet rs = pstmt.executeQuery();

                if (rs.next()) {
                    double avgInc = rs.getDouble("avg_inc");

                    report.append("┌──────────────────────────────────────────────────────────┐\n");
                    report.append("│  📐 АНАЛИЗ УГЛА НАКЛОНА                                  │\n");
                    report.append("├──────────────────────────────────────────────────────────┤\n");
                    report.append(String.format("│  Средний угол: %-42s │\n", avgInc + "°"));
                    report.append(String.format("│  Отклонение: %-45s │\n", "±" + rs.getString("std_inc") + "°"));

                    String waveType;
                    String distance;
                    if (avgInc > 60) {
                        waveType = "Крутые волны (вертикальные)";
                        distance = "БЛИЗКИЙ (< 100 м)";
                    } else if (avgInc > 30) {
                        waveType = "Смешанные волны";
                        distance = "СРЕДНИЙ (100-500 м)";
                    } else {
                        waveType = "Пологие волны (горизонтальные)";
                        distance = "ДАЛЬНИЙ (> 500 м)";
                    }
                    report.append(String.format("│  Тип волн: %-46s │\n", waveType));
                    report.append(String.format("│  Оценка расстояния: %-37s │\n", distance));
                    report.append("└──────────────────────────────────────────────────────────┘\n\n");
                }
            }

            // ===== 6. ОБНАРУЖЕННЫЕ СОБЫТИЯ =====
            String sql6 = "SELECT " +
                    "  COUNT(*) as total_points, " +
                    "  COUNT(CASE WHEN is_event THEN 1 END) as events_count, " +
                    "  ROUND(100.0 * COUNT(CASE WHEN is_event THEN 1 END) / COUNT(*)::numeric, 2) as event_percent " +
                    "FROM three_component_analysis " +
                    "WHERE station_name LIKE ?";

            try (PreparedStatement pstmt = conn.prepareStatement(sql6)) {
                pstmt.setString(1, "%" + fileName + "%");
                ResultSet rs = pstmt.executeQuery();

                if (rs.next()) {
                    int events = rs.getInt("events_count");
                    double percent = rs.getDouble("event_percent");

                    report.append("┌──────────────────────────────────────────────────────────┐\n");
                    report.append("│  🎯 ОБНАРУЖЕННЫЕ СОБЫТИЯ (STA/LTA)                       │\n");
                    report.append("├──────────────────────────────────────────────────────────┤\n");
                    report.append(String.format("│  Всего событий: %-43s │\n", String.format("%,d", events)));
                    report.append(String.format("│  Процент записи: %-42s │\n", percent + "%"));

                    String activityLevel;
                    if (events == 0) {
                        activityLevel = "🔇 ТИШИНА — транспорт не обнаружен";
                    } else if (percent < 1.0) {
                        activityLevel = "🔈 НИЗКАЯ — возможен удаленный транспорт";
                    } else if (percent < 5.0) {
                        activityLevel = "🔉 СРЕДНЯЯ — периодический транспорт";
                    } else {
                        activityLevel = "🔊 ВЫСОКАЯ — интенсивное движение";
                    }
                    report.append(String.format("│  Уровень активности: %-39s │\n", activityLevel));
                    report.append("└──────────────────────────────────────────────────────────┘\n\n");
                }
            }

            // ===== 7. ТОП-5 МОЩНЕЙШИХ СОБЫТИЙ =====
            String sql7 = "SELECT timestamp, result_amplitude, azimuth, polarization, incidence_angle, time_seconds " +
                    "FROM three_component_analysis " +
                    "WHERE station_name LIKE ? AND is_event = TRUE " +
                    "ORDER BY result_amplitude DESC " +
                    "LIMIT 5";

            try (PreparedStatement pstmt = conn.prepareStatement(sql7)) {
                pstmt.setString(1, "%" + fileName + "%");
                ResultSet rs = pstmt.executeQuery();

                report.append("┌──────────────────────────────────────────────────────────┐\n");
                report.append("│  🏆 ТОП-5 МОЩНЕЙШИХ СОБЫТИЙ                              │\n");
                report.append("├──────────────────────────────────────────────────────────┤\n");

                int rank = 1;
                boolean hasEvents = false;
                while (rs.next()) {
                    hasEvents = true;
                    double az = rs.getDouble("azimuth");
                    double pol = rs.getDouble("polarization");
                    double time = rs.getDouble("time_seconds");

                    report.append(String.format("│  %d. t=%.1fс | Амп: %.4f | Аз: %.0f°(%s) | Пол: %.2f │\n",
                            rank++, time,
                            rs.getDouble("result_amplitude"),
                            az, getDirectionName(az),
                            pol));
                }

                if (!hasEvents) {
                    report.append("│  (события не обнаружены)                                  │\n");
                }
                report.append("└──────────────────────────────────────────────────────────┘\n\n");
            }

            // ===== 8. ИТОГОВОЕ ЗАКЛЮЧЕНИЕ =====
            report.append("┌──────────────────────────────────────────────────────────┐\n");
            report.append("│  📋 ИТОГОВОЕ ЗАКЛЮЧЕНИЕ                                  │\n");
            report.append("├──────────────────────────────────────────────────────────┤\n");

            // Собираем все данные для заключения
            String conclusion = generateConclusion(conn, fileName);
            report.append(conclusion);

            report.append("└──────────────────────────────────────────────────────────┘\n");

        } catch (SQLException e) {
            report.append("❌ Ошибка при формировании отчета: ").append(e.getMessage()).append("\n");
        }

        // Выводим отчет в информационную панель
        infoArea.setText(report.toString());
        infoArea.setCaretPosition(0);
    }

    /**
     * Генерация итогового заключения на основе всех данных
     */
    private String generateConclusion(Connection conn, String fileName) throws SQLException {
        StringBuilder conclusion = new StringBuilder();

        // Собираем ключевые метрики
        String sql = "SELECT " +
                "  COUNT(CASE WHEN is_event THEN 1 END) as events, " +
                "  ROUND(AVG(polarization)::numeric, 3) as avg_pol, " +
                "  ROUND(STDDEV(azimuth)::numeric, 1) as std_az " +
                "FROM three_component_analysis " +
                "WHERE station_name LIKE ?";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, "%" + fileName + "%");
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                int events = rs.getInt("events");
                double avgPol = rs.getDouble("avg_pol");
                double stdAz = rs.getDouble("std_az");

                if (events == 0) {
                    conclusion.append("│  Транспорт НЕ ОБНАРУЖЕН.                                  │\n");
                    conclusion.append("│  Запись содержит только фоновый шум.                      │\n");
                } else if (stdAz > 45 || avgPol < 0.3) {
                    conclusion.append("│  Обнаружена активность, НО:                               │\n");
                    conclusion.append("│  • Азимут нестабилен (разброс > 45°)                      │\n");
                    conclusion.append("│  • Поляризация низкая (< 0.3)                             │\n");
                    conclusion.append("│  ВЕРОЯТНО: ветер или распределенный шум.                  │\n");
                } else {
                    conclusion.append("│  ТРАНСПОРТ ОБНАРУЖЕН!                                     │\n");
                    conclusion.append("│  • Азимут стабилен (разброс < 45°)                        │\n");
                    conclusion.append("│  • Поляризация указывает на механический источник         │\n");
                    conclusion.append(String.format("│  Количество проездов: %d                                   │\n", events));
                }
            }
        }

        return conclusion.toString();
    }

    /**
     * Название направления по азимуту
     */
    private String getDirectionName(double azimuth) {
        if (azimuth >= 337.5 || azimuth < 22.5) return "Север ↑";
        if (azimuth >= 22.5 && azimuth < 67.5) return "Северо-восток ↗";
        if (azimuth >= 67.5 && azimuth < 112.5) return "Восток →";
        if (azimuth >= 112.5 && azimuth < 157.5) return "Юго-восток ↘";
        if (azimuth >= 157.5 && azimuth < 202.5) return "Юг ↓";
        if (azimuth >= 202.5 && azimuth < 247.5) return "Юго-запад ↙";
        if (azimuth >= 247.5 && azimuth < 292.5) return "Запад ←";
        return "Северо-запад ↖";
    }



    /**
     * Создание стилизованной кнопки
     */
    private JButton createStyledButton(String text, Color bgColor) {
        JButton button = new JButton(text);
        button.setFont(new Font("Segoe UI", Font.BOLD, 12));
        button.setBackground(bgColor);
        button.setForeground(Color.WHITE);
        button.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
        button.setFocusPainted(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.setBorderPainted(false);
        button.setOpaque(true);

        // Эффект при наведении
        button.addMouseListener(new java.awt.event.MouseAdapter() {
            Color originalColor = bgColor;

            public void mouseEntered(java.awt.event.MouseEvent evt) {
                button.setBackground(originalColor.brighter());
            }
            public void mouseExited(java.awt.event.MouseEvent evt) {
                button.setBackground(originalColor);
            }
        });

        return button;
    }

    /**
     * Создание стилизованного выпадающего списка
     */
    private JComboBox<String> createStyledCombo() {
        JComboBox<String> combo = new JComboBox<>();
        combo.setPreferredSize(new Dimension(200, 32));
        combo.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        combo.setBackground(DARK_BG);
        combo.setForeground(Color.WHITE);
        combo.addActionListener(e -> {
            // Обработчик выбора (если нужно)
        });
        return combo;
    }

    /**
     * Создание стилизованного текстового поля
     */
    private JTextField createStyledTextField(String text, int width) {
        JTextField field = new JTextField(text);
        field.setPreferredSize(new Dimension(width, 32));
        field.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        field.setBackground(DARK_BG);
        field.setForeground(Color.WHITE);
        field.setCaretColor(Color.WHITE);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)
        ));
        return field;
    }

    /**
     * Показать результаты трехкомпонентного анализа
     */
    private void showThreeComponentResults() {
        String fileName = getSelectedFile();
        if (fileName == null) {
            JOptionPane.showMessageDialog(this, "Выберите станцию (файл)");
            return;
        }

        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                chartPanel.removeAll();

                // ТРИ НОВЫХ СПИСКА ДЛЯ ВРЕМЕНИ
                List<Double> times = new ArrayList<>();      // ← ДОБАВИТЬ
                List<Double> azimuths = new ArrayList<>();
                List<Double> amplitudes = new ArrayList<>();
                List<Integer> events = new ArrayList<>();

                try (Connection conn = DriverManager.getConnection(
                        "jdbc:postgresql://localhost:5432/noisedb", "postgres", "32676");
                     PreparedStatement pstmt = conn.prepareStatement(
                             "SELECT azimuth, result_amplitude, is_event, time_seconds " +  // ← ДОБАВИТЬ time_seconds
                                     "FROM three_component_analysis " +
                                     "WHERE station_name LIKE ? " +
                                     "ORDER BY time_seconds")) {  // ← ИЗМЕНИТЬ на time_seconds

                    pstmt.setString(1, "%" + fileName + "%");
                    ResultSet rs = pstmt.executeQuery();

                    int idx = 0;
                    while (rs.next()) {
                        times.add(rs.getDouble("time_seconds"));  // ← ДОБАВИТЬ чтение времени
                        azimuths.add(rs.getDouble("azimuth"));
                        amplitudes.add(rs.getDouble("result_amplitude"));
                        if (rs.getBoolean("is_event")) {
                            events.add(idx);
                        }
                        idx++;
                    }
                } catch (SQLException e) {
                    System.err.println("Ошибка: " + e.getMessage());
                }

                if (!azimuths.isEmpty() && chartBuilder != null) {
                    JPanel analysisPanel = new JPanel(new GridLayout(2, 1));
                    // ← ИЗМЕНИТЬ вызовы методов
                    analysisPanel.add(chartBuilder.createAzimuthChartWithTime(times, azimuths, fileName));
                    analysisPanel.add(chartBuilder.createSTATLTChartWithTime(times, amplitudes, events, fileName));
                    chartPanel.add(analysisPanel);
                } else {
                    JLabel label = new JLabel("Нет данных анализа для " + fileName, SwingConstants.CENTER);
                    label.setForeground(Color.BLACK);
                    chartPanel.add(label);
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

    private JPanel createCenterPanel() {
        JTabbedPane tabs = new JTabbedPane();

        chartPanel = new JPanel(new BorderLayout());
        chartPanel.add(new JLabel("Выберите файл, трассу и нажмите кнопку", SwingConstants.CENTER));
        tabs.addTab("Графики", chartPanel);

        tableModel = new DefaultTableModel(
                new String[]{"Файл", "Трасса", "Начало", "Отсчётов", "Координаты"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        dataTable = new JTable(tableModel);
        tabs.addTab("Данные", new JScrollPane(dataTable));

        infoArea = new JTextArea();
        infoArea.setEditable(false);
        infoArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        tabs.addTab("Информация", new JScrollPane(infoArea));

        mapPanel = new MapPanel();
        tabs.addTab("Карта", mapPanel);

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