package org.example.transport_noise.service;

import org.example.transport_noise.model.DetectionEvent;

import java.util.List;

/**
 * Текстовый отчёт по результатам детекции для вкладки «Информация».
 */
public final class DetectionReportFormatter {

    private DetectionReportFormatter() {
    }

    public static String buildThreeComponentDetectionReport(
            String stationLabel,
            int sampleRate,
            double staSec,        // ← ДОБАВИТЬ
            double ltaSec,        // ← ДОБАВИТЬ
            double threshold,
            List<Double> times,
            List<Double> resultAmplitude,
            List<DetectionEvent> staLtaR,
            List<DetectionEvent> staLtaX,
            List<DetectionEvent> staLtaY,
            List<DetectionEvent> staLtaZ,
            List<DetectionEvent> energyR,
            List<DetectionEvent> minFusion,
            List<DetectionEvent> maxFusion) {

        int sta = TransportDetectionTuning.staSamples(sampleRate, staSec);     // ✅ 2 аргумента
        int lta = TransportDetectionTuning.ltaSamples(sampleRate, staSec, ltaSec); // ✅ 3 аргумента
        double staSecond = sta / (double) sampleRate;
        double ltaSecond = lta / (double) sampleRate;

        StringBuilder sb = new StringBuilder();
        sb.append("══════════════════════════════════════════════════════════════\n");
        sb.append("  ОТЧЁТ: ТРЁХКОМПОНЕНТНОЕ ОБНАРУЖЕНИЕ (транспорт / шум)\n");
        sb.append("══════════════════════════════════════════════════════════════\n\n");
        sb.append("Станция / префикс: ").append(stationLabel).append("\n");
        sb.append("Частота дискретизации (оценка): ").append(sampleRate).append(" Гц\n");
        sb.append("Длина ряда: ").append(times != null ? times.size() : 0).append(" отсчётов\n");
        if (times != null && !times.isEmpty()) {
            sb.append("Длительность записи: ")
                    .append(String.format("%.2f", times.get(times.size() - 1)))
                    .append(" с\n");
        }
        sb.append("\n── Параметры детектора ──────────────────────────────────────\n");
        sb.append("Порог STA/LTA (отношение кратк./длинного среднего): ")
                .append(String.format("%.2f", threshold)).append("\n");
        sb.append("Окно STA: ").append(sta).append(" отсчётов (~")
                .append(String.format("%.3f", staSecond)).append(" с)\n");
        sb.append("Окно LTA: ").append(lta).append(" отсчётов (~")
                .append(String.format("%.2f", ltaSecond)).append(" с)\n");
        sb.append("Гистерезис отключения: ~72 % от порога; мин. длительность события завязана на STA.\n");
        sb.append("После детекции выбирается одно доминирующее событие по максимуму STA/LTA на пике.\n");
        sb.append("Полосовая фильтрация при приёме файлов: Баттерворд 4-го порядка до 100 Гц (нижняя граница ~")
                .append(String.format("%.1f", Math.max(0.5, sampleRate / 500.0))).append(" Гц).\n");

        sb.append("\n── Результаты по методам (по одному сильнейшему событию) ────\n");
        appendMethod(sb, "STA/LTA по результирующей R", staLtaR, sampleRate);
        appendMethod(sb, "STA/LTA по компоненте X (N–S)", staLtaX, sampleRate);
        appendMethod(sb, "STA/LTA по компоненте Y (E–W)", staLtaY, sampleRate);
        appendMethod(sb, "STA/LTA по компоненте Z (вертикальная)", staLtaZ, sampleRate);
        appendMethod(sb, "Энергетический STA/LTA по R (среднее квадрата)", energyR, sampleRate);
        appendMethod(sb, "Слияние min(STA/LTA) по X,Y,Z", minFusion, sampleRate);
        appendMethod(sb, "Слияние max(STA/LTA) по X,Y,Z", maxFusion, sampleRate);

        sb.append("\n── Интерпретация ───────────────────────────────────────────\n");
        if (staLtaR == null || staLtaR.isEmpty()) {
            sb.append("По основному критерию (R, STA/LTA) всплеск не выделен. Попробуйте снизить порог\n");
            sb.append("или проверьте качество записи и синхронизацию трёх трасс.\n");
        } else {
            DetectionEvent e = staLtaR.get(0);
            sb.append("Зарегистрировано одно транспортное событие (доминирующее по пику STA/LTA по R).\n");
            sb.append("Интервал по R: с ").append(fmtTime(e.getStartSample(), sampleRate))
                    .append(" с по ").append(fmtTime(e.getEndSample(), sampleRate)).append(" с.\n");
            if (resultAmplitude != null && e.getPeakSample() >= 0 && e.getPeakSample() < resultAmplitude.size()) {
                sb.append("Амплитуда R в пике: ")
                        .append(String.format("%.6f", resultAmplitude.get(e.getPeakSample()))).append("\n");
            }
            sb.append("Сравните графики отношений STA/LTA и энергетического критерия на вкладке «Графики».\n");
        }
        sb.append("\n── Визуализация в GUI ─────────────────────────────────────\n");
        sb.append("После «3D Анализ»: вкладка «STA/LTA: X, Y, Z» — отношение STA/LTA по каждой компоненте;\n");
        sb.append("«Фильтр и энергия» — сырой X vs полоса Баттерворта и кривые кратковременной/длинной энергии (x²).\n");
        sb.append("Кнопка «STA/LTA 1 трасса» — то же для выбранной трассы и интервала «Начало/Конец».\n");
        sb.append("\n══════════════════════════════════════════════════════════════\n");
        return sb.toString();
    }

    private static void appendMethod(StringBuilder sb, String title, List<DetectionEvent> ev, int fs) {
        sb.append("\n• ").append(title).append("\n");
        if (ev == null || ev.isEmpty()) {
            sb.append("  событие не выделено\n");
            return;
        }
        DetectionEvent e = ev.get(0);
        sb.append("  начало: ").append(fmtTime(e.getStartSample(), fs)).append(" с, конец: ")
                .append(fmtTime(e.getEndSample(), fs)).append(" с, пик: ")
                .append(fmtTime(e.getPeakSample(), fs)).append(" с\n");
    }

    private static String fmtTime(int sample, int fs) {
        if (fs <= 0) return String.valueOf(sample);
        return String.format("%.4f", sample / (double) fs);
    }
}
