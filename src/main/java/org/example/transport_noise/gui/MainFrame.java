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

    // Таймер для автообновления
    private Timer refreshTimer;

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

        // Загружаем данные
        refreshAllData();

        // Запускаем автообновление каждые 5 секунд
        startAutoRefresh();
    }

    /**
     * Автообновление GUI
     */
    private void startAutoRefresh() {
        refreshTimer = new Timer(5000, e -> {
            SwingUtilities.invokeLater(() -> {
                refreshAllData();
            });
        });
        refreshTimer.start();
    }

    private JPanel createTopPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        // Выбор файла
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Файл (название):"), gbc);

        gbc.gridx = 1; gbc.gridwidth = 2;
        fileNameSelect = new JComboBox<>();
        fileNameSelect.setPreferredSize(new Dimension(200, 25));
        fileNameSelect.addActionListener(e -> onFileSelected());
        panel.add(fileNameSelect, gbc);

        // Выбор трассы
        gbc.gridx = 0; gbc.gridy = 1;
        gbc.gridwidth = 1;
        panel.add(new JLabel("Номер трассы:"), gbc);

        gbc.gridx = 1; gbc.gridwidth = 2;
        traceSelect = new JComboBox<>();
        traceSelect.setPreferredSize(new Dimension(100, 25));
        traceSelect.addActionListener(e -> onTraceSelected());
        panel.add(traceSelect, gbc);

        // Начало интервала
        gbc.gridx = 0; gbc.gridy = 2;
        gbc.gridwidth = 1;
        panel.add(new JLabel("Начало (ГГГГ-ММ-ДД ЧЧ:ММ:СС):"), gbc);

        gbc.gridx = 1; gbc.gridwidth = 2;
        startTimeField = new JTextField("2025-01-01 00:00:00");
        panel.add(startTimeField, gbc);

        // Конец интервала
        gbc.gridx = 0; gbc.gridy = 3;
        gbc.gridwidth = 1;
        panel.add(new JLabel("Конец (ГГГГ-ММ-ДД ЧЧ:ММ:СС):"), gbc);

        gbc.gridx = 1; gbc.gridwidth = 2;
        endTimeField = new JTextField("2025-12-31 23:59:59");
        panel.add(endTimeField, gbc);

        // Кнопки
        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 3;
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        JButton showSignalBtn = new JButton("📈 Сигнал");
        showSignalBtn.addActionListener(e -> loadAndShowSignal());
        buttonPanel.add(showSignalBtn);

        JButton showVarianceBtn = new JButton("📊 Дисперсия");
        showVarianceBtn.addActionListener(e -> loadAndShowVariance());
        buttonPanel.add(showVarianceBtn);

        JButton showBothBtn = new JButton("📋 Сигнал + Дисперсия");
        showBothBtn.addActionListener(e -> loadAndShowBoth());
        buttonPanel.add(showBothBtn);

        JButton clearDbBtn = new JButton("🗑️ Очистить БД");
        clearDbBtn.setBackground(new Color(255, 200, 200));
        clearDbBtn.addActionListener(e -> clearDatabase());
        buttonPanel.add(clearDbBtn);

        panel.add(buttonPanel, gbc);

        return panel;
    }

    private JPanel createCenterPanel() {
        JTabbedPane tabs = new JTabbedPane();

        chartPanel = new JPanel(new BorderLayout());
        chartPanel.add(new JLabel("Выберите файл, трассу и нажмите кнопку для графика",
                SwingConstants.CENTER), BorderLayout.CENTER);
        tabs.addTab("📈 Графики", chartPanel);

        tableModel = new DefaultTableModel(
                new String[]{"Файл", "Трасса", "Начало", "Отсчётов", "Длит.(сек)", "Координаты"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        dataTable = new JTable(tableModel);
        dataTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
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
        JLabel status = new JLabel("✅ Система готова | Автообновление каждые 5 сек");
        status.setFont(new Font("SansSerif", Font.PLAIN, 11));
        panel.add(status, BorderLayout.WEST);
        return panel;
    }

    /**
     * Обновить все данные (вызывается таймером и вручную)
     */
    private void refreshAllData() {
        refreshFileList();
        refreshDataTable();
        updateInfoPanel();
    }

    /**
     * Обновить список файлов
     */
    private void refreshFileList() {
        try {
            fileNames = dbService.getFileNames();

            String selectedFile = (String) fileNameSelect.getSelectedItem();

            fileNameSelect.removeAllItems();

            if (fileNames.isEmpty()) {
                fileNameSelect.addItem("Нет данных");
            } else {
                for (String name : fileNames) {
                    fileNameSelect.addItem(name);
                }

                if (selectedFile != null) {
                    fileNameSelect.setSelectedItem(selectedFile);
                }
            }
        } catch (SQLException e) {
            System.err.println("Ошибка загрузки файлов: " + e.getMessage());
        }
    }

    /**
     * При выборе файла - обновить список трасс
     */
    private void onFileSelected() {
        String selectedFile = (String) fileNameSelect.getSelectedItem();
        if (selectedFile == null || selectedFile.equals("Нет данных")) {
            return;
        }

        try {
            traceNumbers = dbService.getTraceNumbersForFile(selectedFile);

            traceSelect.removeAllItems();

            if (traceNumbers.isEmpty()) {
                traceSelect.addItem("Нет данных");
            } else {
                for (Integer trace : traceNumbers) {
                    traceSelect.addItem(String.valueOf(trace));
                }
            }
        } catch (SQLException e) {
            System.err.println("Ошибка загрузки трасс: " + e.getMessage());
        }
    }

    /**
     * Обновить таблицу данных
     */
    private void refreshDataTable() {
        tableModel.setRowCount(0);

        String sql = "SELECT " +
                "    SUBSTRING(d.file_name FROM '^(.*?)\\.') as base_name, " +
                "    d.trace_number, " +
                "    MIN(d.record_time) as start_time, " +
                "    COUNT(*) as samples, " +
                "    ROUND(EXTRACT(EPOCH FROM (MAX(d.record_time) - MIN(d.record_time)))::numeric, 1) as duration, " +
                "    d.latitude, d.longitude " +
                "FROM signal_data d " +
                "GROUP BY base_name, d.trace_number, d.latitude, d.longitude " +
                "ORDER BY base_name, d.trace_number";

        try (Connection conn = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/noisedb", "postgres", "32676");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                double lat = rs.getDouble("latitude");
                double lon = rs.getDouble("longitude");
                String coords = (lat != 0 || lon != 0) ?
                        String.format("%.4f, %.4f", lat, lon) : "—";

                tableModel.addRow(new Object[]{
                        rs.getString("base_name"),
                        rs.getInt("trace_number"),
                        rs.getTimestamp("start_time").toString(),
                        String.format("%,d", rs.getInt("samples")),
                        rs.getDouble("duration"),
                        coords
                });
            }

        } catch (SQLException e) {
            System.err.println("Ошибка таблицы: " + e.getMessage());
        }
    }

    /**
     * Обновить информационную панель
     */
    private void updateInfoPanel() {
        StringBuilder sb = new StringBuilder();
        sb.append("╔════════════════════════════════════════╗\n");
        sb.append("║     ИНФОРМАЦИЯ О ДАННЫХ               ║\n");
        sb.append("╚════════════════════════════════════════╝\n\n");

        try {
            String sql = "SELECT " +
                    "   COUNT(DISTINCT SUBSTRING(file_name FROM '^(.*?)\\.')) as files, " +
                    "   COUNT(DISTINCT trace_number) as traces, " +
                    "   COUNT(*) as total_samples, " +
                    "   MIN(record_time) as first_record, " +
                    "   MAX(record_time) as last_record " +
                    "FROM signal_data";

            try (Connection conn = DriverManager.getConnection(
                    "jdbc:postgresql://localhost:5432/noisedb", "postgres", "32676");
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {

                if (rs.next()) {
                    sb.append("📊 СТАТИСТИКА:\n");
                    sb.append("━━━━━━━━━━━━━━━━━━━━\n");
                    sb.append("   Файлов: ").append(rs.getInt("files")).append("\n");
                    sb.append("   Трасс: ").append(rs.getInt("traces")).append("\n");
                    sb.append("   Отсчётов: ").append(String.format("%,d", rs.getInt("total_samples"))).append("\n");

                    Timestamp first = rs.getTimestamp("first_record");
                    Timestamp last = rs.getTimestamp("last_record");

                    if (first != null && last != null) {
                        sb.append("   Период:\n");
                        sb.append("     с: ").append(first).append("\n");
                        sb.append("     по: ").append(last).append("\n");
                    }
                }
            }

            sb.append("\n📋 ФАЙЛЫ И ТРАССЫ:\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━\n");

            for (String fileName : fileNames) {
                sb.append("   📄 ").append(fileName).append("\n");

                List<Integer> traces = dbService.getTraceNumbersForFile(fileName);
                for (Integer trace : traces) {
                    String traceSql = "SELECT COUNT(*) as cnt " +
                            "FROM signal_data " +
                            "WHERE file_name LIKE ? AND trace_number = ?";

                    try (Connection conn = DriverManager.getConnection(
                            "jdbc:postgresql://localhost:5432/noisedb", "postgres", "32676");
                         PreparedStatement pstmt = conn.prepareStatement(traceSql)) {

                        pstmt.setString(1, fileName + ".%");
                        pstmt.setInt(2, trace);
                        ResultSet rs = pstmt.executeQuery();

                        if (rs.next()) {
                            sb.append("      Трасса ").append(trace)
                                    .append(": ").append(String.format("%,d", rs.getInt("cnt")))
                                    .append(" отсчётов\n");
                        }
                    }
                }
            }

        } catch (SQLException e) {
            sb.append("❌ Ошибка: ").append(e.getMessage()).append("\n");
        }

        sb.append("\n━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("💡 Данные обновляются автоматически\n");
        sb.append("💡 Выберите файл и трассу для графика\n");

        infoArea.setText(sb.toString());
        infoArea.setCaretPosition(0);
    }

    private void onTraceSelected() {
        String selectedFile = (String) fileNameSelect.getSelectedItem();
        String selectedTrace = (String) traceSelect.getSelectedItem();

        if (selectedFile == null || selectedTrace == null) return;
        if (selectedFile.equals("Нет данных") || selectedTrace.equals("Нет данных")) return;

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

                if (rs.next()) {
                    double lat = rs.getDouble("latitude");
                    double lon = rs.getDouble("longitude");

                    if (lat != 0 || lon != 0 && mapPanel != null) {
                        mapPanel.showLocation(lat, lon, selectedFile + " трасса " + traceNumber);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Ошибка карты: " + e.getMessage());
        }
    }

    private String getSelectedFile() {
        String selected = (String) fileNameSelect.getSelectedItem();
        return (selected != null && !selected.equals("Нет данных")) ? selected : null;
    }

    private int getSelectedTrace() {
        String selected = (String) traceSelect.getSelectedItem();
        if (selected == null || selected.equals("Нет данных")) return 0;
        try {
            return Integer.parseInt(selected);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void loadAndShowSignal() {
        String fileName = getSelectedFile();
        int traceNumber = getSelectedTrace();

        if (fileName == null || traceNumber == 0) {
            JOptionPane.showMessageDialog(this, "Выберите файл и трассу");
            return;
        }

        // ... остальной код без изменений, но используем новые методы с fileName
        String startTime = startTimeField.getText().trim();
        String endTime = endTimeField.getText().trim();

        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                chartPanel.removeAll();

                List<Double> signalData = dbService.getSignalData(
                        fileName, traceNumber, startTime, endTime);

                if (signalData.isEmpty()) {
                    chartPanel.add(new JLabel("Нет данных", SwingConstants.CENTER));
                } else {
                    JPanel chart = chartBuilder.createSimpleSignalChart(
                            signalData, 1000,
                            fileName + " - Трасса " + traceNumber);
                    chartPanel.add(chart);
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

                List<Double> varianceData = dbService.getVarianceData(
                        fileName, traceNumber, startTime, endTime);

                if (varianceData.isEmpty()) {
                    chartPanel.add(new JLabel("Нет данных", SwingConstants.CENTER));
                } else {
                    JPanel chart = chartBuilder.createSimpleVarianceChart(
                            varianceData,
                            "Дисперсия - " + fileName + " трасса " + traceNumber);
                    chartPanel.add(chart);
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

                List<Double> signalData = dbService.getSignalData(
                        fileName, traceNumber, startTime, endTime);
                List<Double> varianceData = dbService.getVarianceData(
                        fileName, traceNumber, startTime, endTime);

                JPanel combinedPanel = new JPanel(new GridLayout(2, 1));

                if (!signalData.isEmpty()) {
                    combinedPanel.add(chartBuilder.createSimpleSignalChart(
                            signalData, 1000, "Сигнал - " + fileName + " трасса " + traceNumber));
                }

                if (!varianceData.isEmpty()) {
                    combinedPanel.add(chartBuilder.createSimpleVarianceChart(
                            varianceData, "Дисперсия - " + fileName + " трасса " + traceNumber));
                }

                if (signalData.isEmpty() && varianceData.isEmpty()) {
                    chartPanel.add(new JLabel("Нет данных", SwingConstants.CENTER));
                } else {
                    chartPanel.add(combinedPanel);
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
                this,
                "⚠️ Удалить ВСЕ данные из БД?",
                "Подтверждение",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );

        if (result == JOptionPane.YES_OPTION) {
            dbService.clearAllData();
            refreshAllData();
            chartPanel.removeAll();
            chartPanel.revalidate();
            chartPanel.repaint();
        }
    }

    private boolean isValidDateTime(String dateTime) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            LocalDateTime.parse(dateTime, formatter);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}