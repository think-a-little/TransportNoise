package org.example.transport_noise.service;

import org.example.transport_noise.model.FileAnalysisResult;
import org.example.transport_noise.model.FileHeader;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class PCFileReader {
    private VarianceCalculator varianceCalculator;

    public PCFileReader() {
        this.varianceCalculator = new VarianceCalculator();
    }


    public FileAnalysisResult parseFile(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(fis)) {

            // 1. Читаем заголовок 42 байта
            byte[] headerBytes = new byte[42];
            int read = bis.read(headerBytes);
            if (read != 42) {
                throw new IOException("Не удалось прочитать заголовок (42 байта), прочитано: " + read);
            }

            FileHeader header = parseHeader(headerBytes);
            System.out.println(header);

            // 2. Получаем параметры из заголовка
            int sampleSize = header.getSampleSize();
            int samplesToRead = header.getSamplNum();
            int sampleRate = header.getSamplRate() & 0xFFFF;
            if (sampleRate == 0) sampleRate = 1000;

            System.out.println("\n  Параметры:");
            System.out.println("    Тип данных: " + header.getSampleTypeString());
            System.out.println("    Размер отсчёта: " + sampleSize + " байт");
            System.out.println("    Ожидаемое число отсчётов: " + samplesToRead);
            System.out.println("    Частота: " + sampleRate + " Гц");

            // ====== ВЫВОД SCALE В ТЕРМИНАЛ ======
            double scale = header.getScale();
            System.out.println("    ⚖️  SCALE (коэффициент пересчета): " + scale);

            // Дополнительная информация о scale
            if (scale == 0.0) {
                System.out.println("    ⚠️  ВНИМАНИЕ: Scale равен 0! Данные не масштабируются!");
                System.out.println("    ⚠️  Все значения будут равны 0. Проверьте заголовок файла.");
            } else if (scale == 1.0) {
                System.out.println("    ℹ️  Scale = 1.0 - данные в исходных единицах АЦП");
            } else if (scale < 0.0001) {
                System.out.println("    ℹ️  Scale очень маленький (" + scale + ") - возможно, данные в вольтах или паскалях");
            } else if (scale > 1000) {
                System.out.println("    ℹ️  Scale большой (" + scale + ") - возможно, данные в милли- или микроединицах");
            }

            // Выводим информацию о единицах измерения
            System.out.println("    📏 Амплитуда сигнала = значение_отсчёта × " + scale);
            System.out.println("    📏 Дисперсия = квадрат амплитуды (условные единицы²)");
            // ====== КОНЕЦ ВЫВОДА SCALE ======

            // 3. Читаем данные отсчётов
            List<Double> samples = new ArrayList<>();

            // Пропускаем заголовок (мы его уже прочитали)
            // Читаем все оставшиеся байты
            byte[] remainingBytes = bis.readAllBytes();
            System.out.println("    Осталось байт в файле: " + remainingBytes.length);

            if (remainingBytes.length > 0) {
                samples = parseSamples(remainingBytes, header.getSamplType(), header.getScale());
            }

            System.out.println("    Фактически прочитано отсчётов: " + samples.size());

            // 4. Самопроверка - первые 20 отсчётов
            System.out.println("\n=== САМОПРОВЕРКА ===");
            System.out.println("Первые 20 отсчётов (после пересчета с scale=" + header.getScale() + "):");
            for (int i = 0; i < Math.min(20, samples.size()); i++) {
                System.out.printf("  [%d]: %.6f\n", i + 1, samples.get(i));
            }

            // Дополнительная проверка: если все значения нулевые
            if (!samples.isEmpty()) {
                double sum = samples.stream().mapToDouble(Double::doubleValue).sum();
                if (sum == 0.0) {
                    System.out.println("  ⚠️  ВНИМАНИЕ: Все значения равны 0!");
                    System.out.println("  ⚠️  Возможные причины:");
                    System.out.println("  ⚠️  1. Scale = 0 (проверьте заголовок)");
                    System.out.println("  ⚠️  2. Файл содержит тишину");
                    System.out.println("  ⚠️  3. Ошибка при чтении данных");
                }
            }

            // 5. Вычисляем дисперсию по секундам
            List<Double> variances = varianceCalculator.calculatePerSecond(samples, sampleRate);
            int seconds = (int) Math.ceil((double) samples.size() / sampleRate);

            System.out.println("\n=== СТАТИСТИКА ===");
            System.out.println("  Частота дискретизации: " + sampleRate + " Гц");
            System.out.println("  Всего секунд: " + seconds);
            System.out.println("  Всего отсчётов: " + samples.size());
            System.out.println("  Scale: " + scale + " (амплитуда = отсчёт × scale)");

            if (!variances.isEmpty()) {
                System.out.println("  Дисперсия (1-я секунда): " + variances.get(0));
                System.out.println("  Дисперсия (min): " + variances.stream().min(Double::compareTo).orElse(0.0));
                System.out.println("  Дисперсия (max): " + variances.stream().max(Double::compareTo).orElse(0.0));
                System.out.println("  Дисперсия (avg): " + variances.stream().mapToDouble(Double::doubleValue).average().orElse(0.0));

                // Выводим интерпретацию дисперсии
                System.out.println("\n  📊 Интерпретация дисперсии:");
                System.out.println("  • Дисперсия измеряется в квадрате единиц амплитуды");
                System.out.println("  • Амплитуда измеряется в условных единицах × " + scale);
                System.out.println("  • Для получения СКО (среднеквадратичного отклонения): √дисперсия");
                System.out.println("  • СКО будет в тех же единицах, что и амплитуда");
            }

            return new FileAnalysisResult(file.getName(), variances, samples, samples.size(), seconds, header);
        }
    }

    private FileHeader parseHeader(byte[] header) {
        ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);

        short id = bb.getShort();                    // +00
        byte[] reserv = new byte[4];
        bb.get(reserv);                               // +02-+05
        float lat = bb.getFloat();                   // +06
        float lon = bb.getFloat();                   // +10
        double scale = bb.getDouble();               // +14
        byte year = bb.get();                        // +22
        byte month = bb.get();                       // +23
        byte day = bb.get();                         // +24
        byte hour = bb.get();                        // +25
        byte minute = bb.get();                      // +26
        byte second = bb.get();                      // +27
        int microSec = bb.getInt();                  // +28
        short samplRate = bb.getShort();             // +32
        int samplNum = bb.getInt();                  // +34
        short samplType = bb.getShort();             // +38
        byte trNum = bb.get();                       // +40
        byte reserved = bb.get();                    // +41

        return new FileHeader(id, reserv, lat, lon, scale, year, month, day,
                hour, minute, second, microSec, samplRate,
                samplNum, samplType, trNum, reserved);
    }

    private List<Double> parseSamples(byte[] data, short sampleType, double scale) {
        List<Double> samples = new ArrayList<>();
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        System.out.println("    Парсинг данных: " + data.length + " байт");

        switch (sampleType) {
            case 0x0002: // short (16-bit знаковый)
                while (bb.remaining() >= 2) {
                    short val = bb.getShort();
                    samples.add(val * scale);
                }
                break;

            case 0x0004: // int (32-bit знаковый)
                while (bb.remaining() >= 4) {
                    int val = bb.getInt();
                    samples.add(val * scale);
                }
                break;

            case 0x1004: // float (32-bit плавающая)
                while (bb.remaining() >= 4) {
                    float val = bb.getFloat();
                    samples.add(val * scale);
                }
                break;

            case 0x1008: // double (64-bit)
                while (bb.remaining() >= 8) {
                    double val = bb.getDouble();
                    samples.add(val * scale);
                }
                break;

            default:
                System.err.println("    Неизвестный тип отсчёта: 0x" +
                        Integer.toHexString(sampleType & 0xFFFF));
        }

        System.out.println("    Распарсено отсчётов: " + samples.size());
        return samples;
    }
}