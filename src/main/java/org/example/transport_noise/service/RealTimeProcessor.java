package org.example.transport_noise.service;

import org.example.transport_noise.model.DetectionEvent;
import org.example.transport_noise.model.FileAnalysisResult;
import org.example.transport_noise.model.FileHeader;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RealTimeProcessor {
    private final PCFileReader fileReader;
    private final DatabaseService dbService;
    private final ThreeComponentAnalyzer threeComponentAnalyzer;
    private final ExecutorService processingExecutor;

    // Кэш для накопления трех компонент
    // Ключ: время начала записи (округленное до секунды) + координаты
    private final Map<String, Map<Integer, FileAnalysisResult>> componentCache;
    private static final int REQUIRED_COMPONENTS = 3;

    public RealTimeProcessor() {
        this.fileReader = new PCFileReader();
        this.dbService = DatabaseService.getInstance();
        this.threeComponentAnalyzer = new ThreeComponentAnalyzer();
        this.processingExecutor = Executors.newFixedThreadPool(2);
        this.componentCache = new ConcurrentHashMap<>();
    }

    public void processNewFile(Path filePath) {
        processingExecutor.submit(() -> {
            File file = filePath.toFile();
            String fileName = file.getName();

            try {
                System.out.println("\n" + "=".repeat(60));
                System.out.println("📁 ОБРАБОТКА ФАЙЛА: " + fileName);
                System.out.println("=".repeat(60));

                if (!file.isFile() || file.length() == 0) {
                    System.out.println("⏭️  Пропущен");
                    return;
                }

                // Парсим файл
                FileAnalysisResult result = fileReader.parseFile(file);

                if (result == null || result.getRawSamples().isEmpty()) {
                    System.err.println("❌ Нет данных");
                    return;
                }

                // Диагностика
                diagnoseFile(result);

                // Сохраняем в БД
                dbService.saveAnalysisResult(result);

                // Пытаемся собрать три компоненты
                collectComponentForAnalysis(result);

                // Удаляем файл
                deleteFile(file);

            } catch (Exception e) {
                System.err.println("❌ Ошибка: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * Диагностика файла
     */
    private void diagnoseFile(FileAnalysisResult result) {
        int traceNumber = result.getHeader().getTrNum() & 0xFF;
        Timestamp startTime = convertToTimestamp(result.getHeader());

        System.out.println("\n📋 ДИАГНОСТИКА:");
        System.out.println("   Файл: " + result.getFileName());
        System.out.println("   Трасса: " + traceNumber);
        System.out.println("   Время начала: " + startTime);
        System.out.println("   Отсчётов: " + String.format("%,d", result.getTotalSamples()));
        System.out.println("   Частота: " + result.getSampleRate() + " Гц");
        System.out.println("   Координаты: " +
                String.format("%.6f, %.6f",
                        result.getHeader().getLat(),
                        result.getHeader().getLon()));
    }

    /**
     * Сбор компонент для трехкомпонентного анализа
     */
    private void collectComponentForAnalysis(FileAnalysisResult result) {
        int traceNumber = result.getHeader().getTrNum() & 0xFF;

        // Определяем, является ли файл частью трехкомпонентной записи
        if (traceNumber < 1 || traceNumber > 3) {
            System.out.println("   ℹ️  Трасса " + traceNumber + " не подходит для 3D анализа");
            System.out.println("   💡 Трехкомпонентный анализ ожидает трассы 1, 2, 3");
            return;
        }

        // Создаем ключ для группировки: время начала (с точностью до секунды) + координаты
        Timestamp startTime = convertToTimestamp(result.getHeader());
        String timeKey = String.format("%s_%.4f_%.4f",
                startTime.toString().substring(0, 19), // Обрезаем до секунд
                result.getHeader().getLat(),
                result.getHeader().getLon());

        System.out.println("\n🔍 ПОИСК КОМПОНЕНТ:");
        System.out.println("   Трасса " + traceNumber + " → компонента " +
                (traceNumber == 1 ? "X (N-S)" : traceNumber == 2 ? "Y (E-W)" : "Z (вертикальная)"));
        System.out.println("   Ключ группировки: " + timeKey);

        // Добавляем в кэш
        componentCache.putIfAbsent(timeKey, new ConcurrentHashMap<>());
        Map<Integer, FileAnalysisResult> components = componentCache.get(timeKey);
        components.put(traceNumber, result);

        System.out.println("   📦 Накоплено компонент: " + components.size() + "/" + REQUIRED_COMPONENTS);
        System.out.println("   Имеющиеся трассы: " + components.keySet());

        // Проверяем, все ли 3 компоненты собраны
        if (components.size() >= REQUIRED_COMPONENTS) {
            System.out.println("\n   🎯 ВСЕ ТРИ КОМПОНЕНТЫ СОБРАНЫ!");
            System.out.println("   Запуск трехкомпонентного анализа...");

            performThreeComponentAnalysis(timeKey, components);

            // Очищаем кэш
            componentCache.remove(timeKey);
        } else {
            System.out.println("   ⏳ Ожидание остальных компонент...");
            System.out.println("   Не хватает трасс: " + getMissingTraces(components.keySet()));
        }
    }

    /**
     * Получить список недостающих трасс
     */
    private Set<Integer> getMissingTraces(Set<Integer> existingTraces) {
        Set<Integer> allTraces = new HashSet<>(Arrays.asList(1, 2, 3));
        allTraces.removeAll(existingTraces);
        return allTraces;
    }

    /**
     * Выполнение трехкомпонентного анализа
     */
    private void performThreeComponentAnalysis(String timeKey,
                                               Map<Integer, FileAnalysisResult> components) {
        try {
            System.out.println("\n" + "🔬".repeat(30));
            System.out.println("   ТРЕХКОМПОНЕНТНЫЙ АНАЛИЗ");
            System.out.println("   Ключ: " + timeKey);
            System.out.println("🔬".repeat(30));

            FileAnalysisResult comp1 = components.get(1); // X
            FileAnalysisResult comp2 = components.get(2); // Y
            FileAnalysisResult comp3 = components.get(3); // Z

            // Приводим к одной длине
            int minSize = Math.min(
                    Math.min(comp1.getRawSamples().size(), comp2.getRawSamples().size()),
                    comp3.getRawSamples().size()
            );

            System.out.println("\n   📊 Подготовка данных:");
            System.out.println("   Трасса 1 (X): " + String.format("%,d", comp1.getRawSamples().size()) + " отсчётов");
            System.out.println("   Трасса 2 (Y): " + String.format("%,d", comp2.getRawSamples().size()) + " отсчётов");
            System.out.println("   Трасса 3 (Z): " + String.format("%,d", comp3.getRawSamples().size()) + " отсчётов");
            System.out.println("   Анализируемая длина: " + String.format("%,d", minSize) + " отсчётов");

            List<Double> xData = comp1.getRawSamples().subList(0, minSize);
            List<Double> yData = comp2.getRawSamples().subList(0, minSize);
            List<Double> zData = comp3.getRawSamples().subList(0, minSize);

            int sampleRate = comp1.getSampleRate();
            double lat = comp1.getHeader().getLat();
            double lon = comp1.getHeader().getLon();

            // Создаем имя станции из файлов
            String stationName = extractCommonName(
                    comp1.getFileName(),
                    comp2.getFileName(),
                    comp3.getFileName()
            );

            System.out.println("   Станция: " + stationName);
            System.out.println("   Длительность: " + String.format("%.2f сек", (double)minSize/sampleRate));

            // Выполняем анализ
            ThreeComponentAnalyzer.ThreeComponentResult analysisResult =
                    threeComponentAnalyzer.fullAnalysis(xData, yData, zData, sampleRate);

            // Сохраняем результаты
            dbService.saveThreeComponentResult(stationName, analysisResult, lat, lon, sampleRate);

            // Выводим сводку
            printAnalysisSummary(stationName, analysisResult, sampleRate);

            System.out.println("\n   ✅ Трехкомпонентный анализ завершен!");

        } catch (Exception e) {
            System.err.println("   ❌ Ошибка анализа: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Извлечение общего имени из трех файлов
     */
    private String extractCommonName(String name1, String name2, String name3) {
        // Находим общий префикс
        StringBuilder common = new StringBuilder();
        int minLen = Math.min(Math.min(name1.length(), name2.length()), name3.length());

        for (int i = 0; i < minLen; i++) {
            char c1 = name1.charAt(i);
            if (c1 == name2.charAt(i) && c1 == name3.charAt(i)) {
                common.append(c1);
            } else {
                break;
            }
        }

        // Удаляем расширение и точки в конце
        String result = common.toString().replaceAll("[.]+$", "").trim();

        if (result.isEmpty()) {
            // Если нет общего префикса, используем время
            return "Station_" + System.currentTimeMillis();
        }

        return result;
    }

    /**
     * Преобразование времени из заголовка
     */
    private Timestamp convertToTimestamp(FileHeader header) {
        LocalDateTime ldt = LocalDateTime.of(
                1900 + (header.getYear() & 0xFF),
                header.getMonth() & 0xFF,
                header.getDay() & 0xFF,
                header.getHour() & 0xFF,
                header.getMinute() & 0xFF,
                header.getSecond() & 0xFF,
                header.getMicroSec() * 1000
        );
        return Timestamp.valueOf(ldt);
    }

    /**
     * Удаление файла после обработки
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

    /**
     * Вывод сводки трехкомпонентного анализа
     */
    private void printAnalysisSummary(String stationName,
                                      ThreeComponentAnalyzer.ThreeComponentResult result,
                                      int sampleRate) {
        System.out.println("\n   📊 СВОДКА АНАЛИЗА:");
        System.out.println("   " + "─".repeat(50));

        // Амплитуда
        List<Double> amplitudes = result.getResultAmplitude();
        double maxAmp = amplitudes.stream().max(Double::compareTo).orElse(0.0);
        double avgAmp = amplitudes.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        System.out.println("   📈 Результирующая амплитуда:");
        System.out.println("      Максимальная: " + String.format("%.6f", maxAmp));
        System.out.println("      Средняя: " + String.format("%.6f", avgAmp));

        // Азимут
        List<Double> azimuths = result.getAzimuth();
        double avgAzimuth = azimuths.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        String direction = getDirectionFromAzimuth(avgAzimuth);

        System.out.println("   🧭 Азимут на источник:");
        System.out.println("      Средний: " + String.format("%.1f°", avgAzimuth));
        System.out.println("      Направление: " + direction);

        // Угол наклона
        List<Double> incAngles = result.getIncidenceAngle();
        double avgIncAngle = incAngles.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        System.out.println("   📐 Угол наклона:");
        System.out.println("      Средний: " + String.format("%.1f°", avgIncAngle));

        if (avgIncAngle < 30) {
            System.out.println("      Тип: Преимущественно горизонтальные колебания");
        } else if (avgIncAngle < 60) {
            System.out.println("      Тип: Смешанные колебания");
        } else {
            System.out.println("      Тип: Преимущественно вертикальные колебания");
        }

        // Поляризация
        List<Double> polarizations = result.getPolarization();
        double avgPolarization = polarizations.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        System.out.println("   🔄 Поляризация:");
        System.out.println("      Средняя: " + String.format("%.3f", avgPolarization));

        if (avgPolarization > 0.7) {
            System.out.println("      Тип: Высокая линейная (P-волны)");
        } else if (avgPolarization > 0.4) {
            System.out.println("      Тип: Смешанная");
        } else {
            System.out.println("      Тип: Эллиптическая (S-волны или поверхностные волны)");
        }

        List<DetectionEvent> events = result.getStaLtaResultantEvents();
        System.out.println("   🎯 Обнаружено событий (STA/LTA по R): " + events.size());

        if (!events.isEmpty()) {
            System.out.println("   Первые события (начало — конец):");
            int count = 0;
            for (DetectionEvent ev : events) {
                if (count >= 5) break;
                int peak = ev.getPeakSample();
                if (peak < amplitudes.size()) {
                    double t0 = ev.getStartSample() / (double) sampleRate;
                    double t1 = ev.getEndSample() / (double) sampleRate;
                    double ampAtEvent = amplitudes.get(peak);
                    double azAtEvent = azimuths.get(peak);

                    System.out.printf("      %d. t=%.2f–%.2f с, амп=%.4f, азимут=%.0f° (%s)\n",
                            count + 1, t0, t1, ampAtEvent, azAtEvent,
                            getDirectionFromAzimuth(azAtEvent));
                    count++;
                }
            }
        }

        System.out.println("   " + "─".repeat(50));
    }

    /**
     * Преобразование азимута в направление
     */
    private String getDirectionFromAzimuth(double azimuth) {
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
     * Принудительный анализ накопленных компонент
     */
    public void forceAnalysis() {
        System.out.println("\n🔄 ПРИНУДИТЕЛЬНЫЙ АНАЛИЗ НАКОПЛЕННЫХ ДАННЫХ");

        for (Map.Entry<String, Map<Integer, FileAnalysisResult>> entry : componentCache.entrySet()) {
            String timeKey = entry.getKey();
            Map<Integer, FileAnalysisResult> components = entry.getValue();

            System.out.println("   Ключ: " + timeKey + " | Компонент: " + components.size());

            if (components.size() >= REQUIRED_COMPONENTS) {
                performThreeComponentAnalysis(timeKey, components);
            }
        }

        componentCache.clear();
    }

    /**
     * Завершение работы процессора
     */
    public void shutdown() {
        System.out.println("\n🛑 Завершение процессора...");
        forceAnalysis(); // Анализируем оставшиеся данные
        processingExecutor.shutdown();
        dbService.shutdown();
        System.out.println("✅ Процессор остановлен");
    }
}