package org.example.transport_noise.gui;

import org.example.transport_noise.chart.ChartBuilder;
import org.example.transport_noise.model.FileAnalysisResult;
import org.example.transport_noise.service.PCFileReader;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

public class MainFrame extends JFrame {
    private ChartBuilder chartBuilder;
    private List<FileAnalysisResult> results;
    private PCFileReader parser;
    private JTable table;
    private DefaultTableModel tableModel;
    private JTextArea previewArea;
    private JPanel chartPanel;
    private JComboBox<String> fileSelect;

    public MainFrame() {

        chartBuilder = new ChartBuilder();
        parser = new PCFileReader();
        results = null;

        setTitle("Анализ PC файлов (32-bit float)");
        setSize(1200, 800);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        add(createTopPanel(), BorderLayout.NORTH);
        add(createCenterPanel(), BorderLayout.CENTER);
        add(createBottomPanel(), BorderLayout.SOUTH);
    }

    private JPanel createTopPanel() {
        JPanel panel = new JPanel();
        JButton selectBtn = new JButton("Выбрать папку с файлами");
        selectBtn.addActionListener(e -> selectDirectory());
        panel.add(selectBtn);
        return panel;
    }

    private JPanel createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout());

        JPanel selector = new JPanel(new FlowLayout(FlowLayout.LEFT));
        selector.add(new JLabel("Файл:"));
        fileSelect = new JComboBox<>();
        fileSelect.setPreferredSize(new Dimension(300, 25));
        fileSelect.addActionListener(e -> onFileSelect());
        selector.add(fileSelect);
        panel.add(selector, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();

        tableModel = new DefaultTableModel(new String[]{"Секунда", "Дисперсия", "Отсчётов"}, 0);
        table = new JTable(tableModel);
        tabs.addTab("Таблица", new JScrollPane(table));

        chartPanel = new JPanel(new BorderLayout());
        tabs.addTab("График", chartPanel);

        previewArea = new JTextArea();
        previewArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        tabs.addTab("Самопроверка", new JScrollPane(previewArea));

        panel.add(tabs, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel status = new JLabel("Готов");
        status.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        panel.add(status, BorderLayout.WEST);
        return panel;
    }

    private void selectDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            String path = chooser.getSelectedFile().getAbsolutePath();

            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    results = parser.parseDirectory(path);
                    return null;
                }

                @Override
                protected void done() {
                    if (results != null && !results.isEmpty()) {
                        fileSelect.removeAllItems();
                        for (FileAnalysisResult r : results) {
                            fileSelect.addItem(r.getFileName());
                        }
                        showFile(0);

                        // Открыть окно сравнения
                        JFrame compare = chartBuilder.createComparisonWindow(results);
                        compare.setLocationRelativeTo(MainFrame.this);
                        compare.setVisible(true);
                    } else {
                        JOptionPane.showMessageDialog(MainFrame.this, "Нет обработанных файлов");
                    }
                }
            }.execute();
        }
    }

    private void onFileSelect() {
        if (fileSelect.getSelectedIndex() >= 0) {
            showFile(fileSelect.getSelectedIndex());
        }
    }
// В методе showFile добавь отображение графиков сигнала:

    private void showFile(int index) {
        FileAnalysisResult result = results.get(index);

        // Обновляем таблицу дисперсий
        tableModel.setRowCount(0);
        for (int i = 0; i < result.getVariances().size(); i++) {
            int samples = result.getSampleRate();
            if (i == result.getVariances().size() - 1) {
                samples = result.getTotalSamples() - (i * result.getSampleRate());
            }
            tableModel.addRow(new Object[]{i + 1, String.format("%.6f", result.getVariances().get(i)), samples});
        }

        // Очищаем панель графиков
        chartPanel.removeAll();

        // Создаем вкладки с разными графиками
        JTabbedPane chartTabs = new JTabbedPane();

        // 1. График дисперсии
        chartTabs.addTab("Дисперсия по секундам", chartBuilder.createVarianceChart(result));

        // 2. График чистого сигнала (все отсчёты)
        chartTabs.addTab("Полный сигнал", chartBuilder.createFullSignalChart(result, result.getRawSamples()));

        // 3. График сигнала с выбором участка
        chartTabs.addTab("Сигнал (масштабируемый)", chartBuilder.createSelectableSignalChart(result, result.getRawSamples()));

        // 4. Комбинированный график
        chartTabs.addTab("Сигнал + Дисперсия", chartBuilder.createCombinedChart(result, result.getRawSamples()));

        chartPanel.add(chartTabs, BorderLayout.CENTER);
        chartPanel.revalidate();
        chartPanel.repaint();

        // Обновляем информацию для самопроверки
        StringBuilder sb = new StringBuilder();
        sb.append("Файл: ").append(result.getFileName()).append("\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append(result.getHeader().toString()).append("\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("Статистика сигнала:\n");
        sb.append("  Всего отсчётов: ").append(result.getTotalSamples()).append("\n");
        sb.append("  Частота: ").append(result.getSampleRate()).append(" Гц\n");
        sb.append("  Длительность: ").append(result.getTotalSeconds()).append(" сек\n\n");

        // Статистика сырых данных
        List<Double> raw = result.getRawSamples();
        if (!raw.isEmpty()) {
            double min = raw.stream().min(Double::compareTo).orElse(0.0);
            double max = raw.stream().max(Double::compareTo).orElse(0.0);
            double avg = raw.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            sb.append("Статистика сырого сигнала:\n");
            sb.append("  Мин: ").append(String.format("%.6f", min)).append("\n");
            sb.append("  Макс: ").append(String.format("%.6f", max)).append("\n");
            sb.append("  Сред: ").append(String.format("%.6f", avg)).append("\n\n");
        }

        sb.append("Статистика дисперсии:\n");
        sb.append("  Мин: ").append(String.format("%.6f", result.getMinVariance())).append("\n");
        sb.append("  Макс: ").append(String.format("%.6f", result.getMaxVariance())).append("\n");
        sb.append("  Сред: ").append(String.format("%.6f", result.getAvgVariance())).append("\n\n");

        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("Управление графиками:\n");
        sb.append("  • Выделите область мышью - увеличить\n");
        sb.append("  • Правый клик - сбросить масштаб\n");
        sb.append("  • Колесо мыши - масштабирование\n");

        previewArea.setText(sb.toString());
    }
}