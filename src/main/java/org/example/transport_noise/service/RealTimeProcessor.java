package org.example.transport_noise.service;

import org.example.transport_noise.model.FileAnalysisResult;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RealTimeProcessor {
    private final PCFileReader fileReader;
    private final DatabaseService dbService;
    private final ExecutorService processingExecutor;

    public RealTimeProcessor() {
        this.fileReader = new PCFileReader();
        this.dbService = DatabaseService.getInstance();
        this.processingExecutor = Executors.newFixedThreadPool(2);
    }

    public void processNewFile(Path filePath) {
        processingExecutor.submit(() -> {
            File file = filePath.toFile();
            String fileName = file.getName();

            try {
                System.out.println("\n" + "=".repeat(50));
                System.out.println("📁 " + fileName);

                if (!file.isFile() || file.length() == 0) {
                    System.out.println("⏭️  Пропущен");
                    return;
                }

                // Парсим файл
                FileAnalysisResult result = fileReader.parseFile(file);

                if (result != null && !result.getRawSamples().isEmpty()) {
                    System.out.printf("   Отсчётов: %,d, Секунд: %d, Частота: %d Гц\n",
                            result.getTotalSamples(),
                            result.getTotalSeconds(),
                            result.getSampleRate());

                    // Сохраняем в БД
                    dbService.saveAnalysisResult(result);

                    // УДАЛЯЕМ файл после успешного сохранения
                    deleteFile(file);

                } else {
                    System.err.println("❌ Нет данных");
                }

            } catch (Exception e) {
                System.err.println("❌ Ошибка: " + e.getMessage());
            }
        });
    }

    /**
     * Удаление файла после сохранения в БД
     */
    private void deleteFile(File file) {
        try {
            Path path = file.toPath();
            boolean deleted = Files.deleteIfExists(path);
            if (deleted) {
                System.out.println("   🗑️  Файл удален: " + file.getName());
            }
        } catch (Exception e) {
            System.err.println("   ⚠️  Не удалось удалить файл: " + e.getMessage());
        }
    }

    public void shutdown() {
        processingExecutor.shutdown();
        dbService.shutdown();
    }
}