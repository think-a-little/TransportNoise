package org.example.transport_noise.service;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;

public class FileWatcherService {
    private final Path watchPath;
    private final WatchService watchService;
    private final Thread watcherThread;
    private volatile boolean running = true;
    private FileProcessedCallback callback;
    private final Set<String> processedFiles = new HashSet<>();

    public interface FileProcessedCallback {
        void onFileDetected(Path filePath);
        void onError(Path filePath, Exception e);
    }

    public FileWatcherService(String directoryPath, FileProcessedCallback callback)
            throws IOException {
        this.watchPath = Paths.get(directoryPath);
        this.watchService = FileSystems.getDefault().newWatchService();
        this.callback = callback;

        // Создаем папку для мониторинга, если её нет
        if (!Files.exists(watchPath)) {
            Files.createDirectories(watchPath);
            System.out.println("📁 Создана папка для мониторинга: " + watchPath.toAbsolutePath());
        }

        // Регистрируем только создание файлов (не директорий)
        watchPath.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE);

        this.watcherThread = new Thread(this::watchLoop);
        this.watcherThread.setName("FileWatcher-Thread");

        System.out.println("👀 Мониторинг папки: " + watchPath.toAbsolutePath());
    }

    private void watchLoop() {
        while (running) {
            try {
                WatchKey key = watchService.take();

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    Path fileName = (Path) event.context();
                    Path fullPath = watchPath.resolve(fileName);

                    // ===== ВАЖНО: Пропускаем ВСЁ, что не является обычным файлом =====

                    // Проверяем, что путь существует
                    if (!Files.exists(fullPath)) {
                        continue;
                    }

                    // Пропускаем директории (включая папку processed)
                    if (Files.isDirectory(fullPath)) {
                        // Не выводим сообщение о директориях, чтобы не засорять лог
                        continue;
                    }

                    // Проверяем, что это обычный файл (не ссылка, не устройство)
                    if (!Files.isRegularFile(fullPath)) {
                        continue;
                    }

                    // Пропускаем скрытые файлы
                    String name = fileName.toString();
                    if (name.startsWith(".") || name.startsWith("~")) {
                        continue;
                    }

                    // Пропускаем временные файлы
                    String nameLower = name.toLowerCase();
                    if (nameLower.endsWith(".tmp") ||
                            nameLower.endsWith(".temp") ||
                            nameLower.endsWith(".lock") ||
                            nameLower.endsWith(".part")) {
                        continue;
                    }

                    // Проверяем, не обработан ли уже этот файл
                    String absolutePath = fullPath.toAbsolutePath().toString();
                    if (processedFiles.contains(absolutePath)) {
                        continue;
                    }

                    // Ждем готовности файла
                    if (!waitForFileReady(fullPath)) {
                        continue;
                    }

                    // Отмечаем как обработанный и запускаем обработку
                    processedFiles.add(absolutePath);
                    System.out.println("\n📁 Обнаружен новый файл: " + fileName);
                    callback.onFileDetected(fullPath);

                }

                boolean valid = key.reset();
                if (!valid) {
                    System.out.println("❌ Мониторинг прерван");
                    break;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("❌ Ошибка: " + e.getMessage());
            }
        }
    }

    private boolean waitForFileReady(Path path) {
        try {
            // Ждем начальную задержку
            Thread.sleep(500);

            if (!Files.exists(path) || !Files.isReadable(path) || Files.isDirectory(path)) {
                return false;
            }

            // Проверяем стабильность размера файла
            long size1 = Files.size(path);
            Thread.sleep(500);
            long size2 = Files.size(path);

            return size1 == size2 && size1 > 0;

        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    public void startWatching() {
        watcherThread.setDaemon(true);
        watcherThread.start();
        System.out.println("✅ Мониторинг запущен");
    }

    public void stopWatching() {
        running = false;
        watcherThread.interrupt();
        try {
            watchService.close();
        } catch (IOException e) {
            System.err.println("❌ Ошибка при остановке: " + e.getMessage());
        }
        System.out.println("👀 Мониторинг остановлен");
    }
}