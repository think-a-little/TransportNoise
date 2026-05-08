package org.example.transport_noise;

import org.example.transport_noise.gui.MainFrame;
import org.example.transport_noise.service.FileWatcherService;
import org.example.transport_noise.service.RealTimeProcessor;

import javax.swing.*;
import java.io.IOException;

public class Main {
    private static RealTimeProcessor processor;
    private static FileWatcherService watcher;

    public static void main(String[] args) {
        System.out.println("🚀 СИСТЕМА МОНИТОРИНГА ШУМА");
        System.out.println("=".repeat(50));

        // Запускаем GUI
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            frame.setVisible(true);

            frame.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent e) {
                    shutdownServices();
                }
            });
        });

        // Запускаем мониторинг
        startMonitoring();
    }

    private static void startMonitoring() {
        try {
            processor = new RealTimeProcessor();

            String watchPath = "C:\\Users\\nstua\\Documents\\test\\";

            watcher = new FileWatcherService(watchPath, new FileWatcherService.FileProcessedCallback() {
                @Override
                public void onFileDetected(java.nio.file.Path filePath) {
                    System.out.println("📁 Обнаружен: " + filePath.getFileName());
                    processor.processNewFile(filePath);
                }

                @Override
                public void onError(java.nio.file.Path filePath, Exception e) {
                    System.err.println("❌ Ошибка: " + e.getMessage());
                }
            });

            watcher.startWatching();

            System.out.println("\n👀 Мониторинг активен: " + watchPath);
            System.out.println("📊 Данные сохраняются в: noisedb");
            System.out.println("=".repeat(50) + "\n");

        } catch (IOException e) {
            System.err.println("❌ Ошибка запуска: " + e.getMessage());
        }
    }

    private static void shutdownServices() {
        if (watcher != null) watcher.stopWatching();
        if (processor != null) processor.shutdown();
        System.exit(0);
    }
}