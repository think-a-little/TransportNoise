package org.example.transport_noise.service;

import org.example.transport_noise.model.DetectionEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Анализатор трехкомпонентных данных (X, Y, Z): амплитуда, азимут, поляризация,
 * сравнение методов обнаружения (STA/LTA по каналам, энергетический STA/LTA, слияние min/max).
 */
public class ThreeComponentAnalyzer {

    /**
     * Параметры детекции (порог, гистерезис, минимальная длительность).
     */
    public static final class DetectionParams {
        public final double staLtaThreshold;
        public final double offRatio;
        public final int hangSamples;
        public final int minDurationSamples;
        public final int cooldownSamples;

        public DetectionParams(double staLtaThreshold, double offRatio, int hangSamples,
                               int minDurationSamples, int cooldownSamples) {
            this.staLtaThreshold = staLtaThreshold;
            this.offRatio = offRatio;
            this.hangSamples = hangSamples;
            this.minDurationSamples = minDurationSamples;
            this.cooldownSamples = cooldownSamples;
        }

    }

    /**
     * Результат трехкомпонентного анализа и сравнения детекторов.
     */
    public static class ThreeComponentResult {
        private final List<Double> resultAmplitude;
        private final List<Double> azimuth;
        private final List<Double> incidenceAngle;
        private final List<Double> polarization;
        /** Индексы начала событий (результирующий STA/LTA) — для совместимости со старым кодом. */
        private final List<Integer> detectionPoints;
        private final List<DetectionEvent> staLtaResultant;
        private final List<DetectionEvent> staLtaX;
        private final List<DetectionEvent> staLtaY;
        private final List<DetectionEvent> staLtaZ;
        private final List<DetectionEvent> energyResultant;
        private final List<DetectionEvent> staLtaMinFusion;
        private final List<DetectionEvent> staLtaMaxFusion;

        public ThreeComponentResult(List<Double> resultAmplitude,
                                    List<Double> azimuth,
                                    List<Double> incidenceAngle,
                                    List<Double> polarization,
                                    List<Integer> detectionPoints,
                                    List<DetectionEvent> staLtaResultant,
                                    List<DetectionEvent> staLtaX,
                                    List<DetectionEvent> staLtaY,
                                    List<DetectionEvent> staLtaZ,
                                    List<DetectionEvent> energyResultant,
                                    List<DetectionEvent> staLtaMinFusion,
                                    List<DetectionEvent> staLtaMaxFusion) {
            this.resultAmplitude = resultAmplitude;
            this.azimuth = azimuth;
            this.incidenceAngle = incidenceAngle;
            this.polarization = polarization;
            this.detectionPoints = detectionPoints;
            this.staLtaResultant = staLtaResultant;
            this.staLtaX = staLtaX;
            this.staLtaY = staLtaY;
            this.staLtaZ = staLtaZ;
            this.energyResultant = energyResultant;
            this.staLtaMinFusion = staLtaMinFusion;
            this.staLtaMaxFusion = staLtaMaxFusion;
        }

        public List<Double> getResultAmplitude() {
            return resultAmplitude;
        }

        public List<Double> getAzimuth() {
            return azimuth;
        }

        public List<Double> getIncidenceAngle() {
            return incidenceAngle;
        }

        public List<Double> getPolarization() {
            return polarization;
        }

        public List<Integer> getDetectionPoints() {
            return detectionPoints;
        }

        public List<DetectionEvent> getStaLtaResultantEvents() {
            return staLtaResultant;
        }

        public List<DetectionEvent> getStaLtaXEvents() {
            return staLtaX;
        }

        public List<DetectionEvent> getStaLtaYEvents() {
            return staLtaY;
        }

        public List<DetectionEvent> getStaLtaZEvents() {
            return staLtaZ;
        }

        public List<DetectionEvent> getEnergyResultantEvents() {
            return energyResultant;
        }

        public List<DetectionEvent> getStaLtaMinFusionEvents() {
            return staLtaMinFusion;
        }

        public List<DetectionEvent> getStaLtaMaxFusionEvents() {
            return staLtaMaxFusion;
        }

        /**
         * Отметка для БД: только момент регистрации события (начало), одна точка на запись.
         */
        public boolean isKeyEventSample(int sampleIndex) {
            for (DetectionEvent e : staLtaResultant) {
                if (sampleIndex == e.getStartSample()) {
                    return true;
                }
            }
            return false;
        }
    }

    public List<Double> calculateResultAmplitude(List<Double> componentX,
                                                 List<Double> componentY,
                                                 List<Double> componentZ) {
        List<Double> result = new ArrayList<>();
        int size = Math.min(Math.min(componentX.size(), componentY.size()), componentZ.size());

        for (int i = 0; i < size; i++) {
            double amplitude = Math.sqrt(
                    Math.pow(componentX.get(i), 2) +
                            Math.pow(componentY.get(i), 2) +
                            Math.pow(componentZ.get(i), 2)
            );
            result.add(amplitude);
        }

        return result;
    }

    public List<Double> calculateAzimuth(List<Double> componentX,
                                         List<Double> componentY) {
        List<Double> azimuths = new ArrayList<>();
        int size = Math.min(componentX.size(), componentY.size());

        for (int i = 0; i < size; i++) {
            double azimuth = Math.toDegrees(Math.atan2(componentY.get(i), componentX.get(i)));
            if (azimuth < 0) {
                azimuth += 360;
            }
            azimuths.add(azimuth);
        }

        return azimuths;
    }

    public List<Double> calculateIncidenceAngle(List<Double> componentX,
                                                List<Double> componentY,
                                                List<Double> componentZ) {
        List<Double> angles = new ArrayList<>();
        int size = Math.min(Math.min(componentX.size(), componentY.size()), componentZ.size());

        for (int i = 0; i < size; i++) {
            double horizontal = Math.sqrt(
                    Math.pow(componentX.get(i), 2) + Math.pow(componentY.get(i), 2)
            );
            double angle = Math.toDegrees(Math.atan2(Math.abs(componentZ.get(i)), horizontal));
            angles.add(angle);
        }

        return angles;
    }

    public List<Double> calculatePolarization(List<Double> componentX,
                                              List<Double> componentY,
                                              List<Double> componentZ,
                                              int windowSize) {
        List<Double> polarizations = new ArrayList<>();
        int size = Math.min(Math.min(componentX.size(), componentY.size()), componentZ.size());

        for (int i = 0; i < size; i++) {
            int start = Math.max(0, i - windowSize / 2);
            int end = Math.min(size, i + windowSize / 2);

            double covXX = 0, covYY = 0, covZZ = 0;
            double covXY = 0, covXZ = 0, covYZ = 0;
            int count = 0;

            for (int j = start; j < end; j++) {
                covXX += componentX.get(j) * componentX.get(j);
                covYY += componentY.get(j) * componentY.get(j);
                covZZ += componentZ.get(j) * componentZ.get(j);
                covXY += componentX.get(j) * componentY.get(j);
                covXZ += componentX.get(j) * componentZ.get(j);
                covYZ += componentY.get(j) * componentZ.get(j);
                count++;
            }

            if (count > 0) {
                covXX /= count;
                covYY /= count;
                covZZ /= count;
                covXY /= count;
                covXZ /= count;
                covYZ /= count;

                double trace = covXX + covYY + covZZ;
                double maxDiag = Math.max(Math.max(covXX, covYY), covZZ);

                double polarization = trace > 0 ? maxDiag / trace : 0;
                polarization = (polarization - 0.33) / 0.67;
                polarization = Math.max(0, Math.min(1, polarization));

                polarizations.add(polarization);
            } else {
                polarizations.add(0.0);
            }
        }

        return polarizations;
    }

    /**
     * Устаревший API: только индексы срабатывания (начала событий после слияния).
     * Предпочтительнее {@link ThreeComponentResult#getStaLtaResultantEvents()}.
     */
    /**
     * Детекция с заданными окнами STA/LTA (для экспериментов); возвращает не более одного события (доминирующего).
     */
    public List<Integer> detectEventsSTA_LTA(List<Double> signal,
                                             int staWindow,
                                             int ltaWindow,
                                             double threshold) {
        int hang = Math.max(8, staWindow / 3);
        int minDur = Math.max(staWindow, staWindow * 2 / 3);
        DetectionParams p = new DetectionParams(threshold, 0.72, hang, minDur, ltaWindow);
        double[] ratio = StaLtaEventDetector.absStaLtaRatio(signal, staWindow, ltaWindow);
        List<DetectionEvent> ev = StaLtaEventDetector.pickEventsFromRatio(
                ratio, ltaWindow, p.staLtaThreshold, p.offRatio,
                p.hangSamples, p.minDurationSamples, p.cooldownSamples, "STA/LTA");
        ev = StaLtaEventDetector.keepDominantEvent(ev, ratio, signal);
        return ev.stream().map(DetectionEvent::getStartSample).toList();
    }

    /**
     * Одно доминирующее событие по STA/LTA на результирующей амплитуде (для графиков и отчёта в GUI).
     */
    public List<DetectionEvent> detectDominantStaLtaOnSignal(List<Double> signal,
                                                             int sampleRate,
                                                             double staSec,
                                                             double ltaSec,
                                                             double threshold) {
        int sta = TransportDetectionTuning.staSamples(sampleRate, staSec);
        int lta = TransportDetectionTuning.ltaSamples(sampleRate, staSec, ltaSec);
        DetectionParams p = TransportDetectionTuning.params(sampleRate, staSec, ltaSec, threshold);
        double[] ratio = StaLtaEventDetector.absStaLtaRatio(signal, sta, lta);
        List<DetectionEvent> ev = StaLtaEventDetector.pickEventsFromRatio(
                ratio, lta, p.staLtaThreshold, p.offRatio,
                p.hangSamples, p.minDurationSamples, p.cooldownSamples, "STA/LTA");
        return StaLtaEventDetector.keepDominantEvent(ev, ratio, signal);
    }


    public ThreeComponentResult fullAnalysis(List<Double> componentX,
                                             List<Double> componentY,
                                             List<Double> componentZ,
                                             int sampleRate,
                                             double staSec,
                                             double ltaSec,
                                             double threshold) {

        System.out.println("\n🔬 ТРЕХКОМПОНЕНТНЫЙ АНАЛИЗ");
        System.out.println("═".repeat(50));

        double lowCutHz = Math.max(0.5, sampleRate / 500.0);
        double highCutHz = 100.0;

        List<Double> xUse = new ArrayList<>(componentX);
        List<Double> yUse = new ArrayList<>(componentY);
        List<Double> zUse = new ArrayList<>(componentZ);

        System.out.println("🔧 Полосовой фильтр Баттерворта 4-го порядка: " +
                String.format("%.2f", lowCutHz) + "–" + String.format("%.0f", highCutHz) + " Гц");
        xUse = ButterworthBandpass.filter(xUse, sampleRate, lowCutHz, highCutHz);
        yUse = ButterworthBandpass.filter(yUse, sampleRate, lowCutHz, highCutHz);
        zUse = ButterworthBandpass.filter(zUse, sampleRate, lowCutHz, highCutHz);
        int m = Math.min(Math.min(xUse.size(), yUse.size()), zUse.size());
        if (m == 0) {
            xUse = new ArrayList<>(componentX);
            yUse = new ArrayList<>(componentY);
            zUse = new ArrayList<>(componentZ);
            System.err.println("   ⚠️  После фильтрации нет отсчётов — используются исходные данные");
        } else if (m < xUse.size()) {
            xUse = new ArrayList<>(xUse.subList(0, m));
            yUse = new ArrayList<>(yUse.subList(0, m));
            zUse = new ArrayList<>(zUse.subList(0, m));
        }

        List<Double> resultAmplitude = calculateResultAmplitude(xUse, yUse, zUse);
        System.out.println("✅ Результирующая амплитуда (после полосовой фильтрации)");
        System.out.println("   Макс: " + String.format("%.6f", resultAmplitude.stream().max(Double::compareTo).orElse(0.0)));

        List<Double> azimuth = calculateAzimuth(xUse, yUse);
        double avgAzimuth = azimuth.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        System.out.println("✅ Азимут вычислен");
        System.out.println("   Средний: " + String.format("%.1f°", avgAzimuth));
        System.out.println("   Направление: " + azimuthToDirection(avgAzimuth));

        List<Double> incidenceAngle = calculateIncidenceAngle(xUse, yUse, zUse);
        System.out.println("✅ Угол наклона вычислен");
        System.out.println("   Средний: " + String.format("%.1f°",
                incidenceAngle.stream().mapToDouble(Double::doubleValue).average().orElse(0)));

        int windowSize = Math.max(2, sampleRate / 10);
        List<Double> polarization = calculatePolarization(xUse, yUse, zUse, windowSize);
        double avgPolarization = polarization.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        System.out.println("✅ Поляризация вычислена");
        System.out.println("   Средняя: " + String.format("%.3f", avgPolarization));
        System.out.println("   Тип: " + interpretPolarization(avgPolarization));

        int staWindow = TransportDetectionTuning.staSamples(sampleRate, staSec);
        int ltaWindow = TransportDetectionTuning.ltaSamples(sampleRate, staSec, ltaSec);
        DetectionParams det = TransportDetectionTuning.params(sampleRate, staSec, ltaSec, threshold);

        double[] ratioR = StaLtaEventDetector.absStaLtaRatio(resultAmplitude, staWindow, ltaWindow);
        double[] ratioX = StaLtaEventDetector.absStaLtaRatio(xUse, staWindow, ltaWindow);
        double[] ratioY = StaLtaEventDetector.absStaLtaRatio(yUse, staWindow, ltaWindow);
        double[] ratioZ = StaLtaEventDetector.absStaLtaRatio(zUse, staWindow, ltaWindow);
        double[] ratioEnergy = StaLtaEventDetector.energyStaLtaRatio(resultAmplitude, staWindow, ltaWindow);
        double[] ratioMin = StaLtaEventDetector.minStaLtaRatio(xUse, yUse, zUse, staWindow, ltaWindow);
        double[] ratioMax = StaLtaEventDetector.maxStaLtaRatio(xUse, yUse, zUse, staWindow, ltaWindow);

        List<DetectionEvent> evR = StaLtaEventDetector.pickEventsFromRatio(
                ratioR, ltaWindow, det.staLtaThreshold, det.offRatio,
                det.hangSamples, det.minDurationSamples, det.cooldownSamples, "R STA/LTA");
        List<DetectionEvent> evX = StaLtaEventDetector.pickEventsFromRatio(
                ratioX, ltaWindow, det.staLtaThreshold, det.offRatio,
                det.hangSamples, det.minDurationSamples, det.cooldownSamples, "X STA/LTA");
        List<DetectionEvent> evY = StaLtaEventDetector.pickEventsFromRatio(
                ratioY, ltaWindow, det.staLtaThreshold, det.offRatio,
                det.hangSamples, det.minDurationSamples, det.cooldownSamples, "Y STA/LTA");
        List<DetectionEvent> evZ = StaLtaEventDetector.pickEventsFromRatio(
                ratioZ, ltaWindow, det.staLtaThreshold, det.offRatio,
                det.hangSamples, det.minDurationSamples, det.cooldownSamples, "Z STA/LTA");
        List<DetectionEvent> evE = StaLtaEventDetector.pickEventsFromRatio(
                ratioEnergy, ltaWindow, det.staLtaThreshold, det.offRatio,
                det.hangSamples, det.minDurationSamples, det.cooldownSamples, "Energy STA/LTA");
        List<DetectionEvent> evMin = StaLtaEventDetector.pickEventsFromRatio(
                ratioMin, ltaWindow, det.staLtaThreshold, det.offRatio,
                det.hangSamples, det.minDurationSamples, det.cooldownSamples, "min(X,Y,Z) STA/LTA");
        List<DetectionEvent> evMax = StaLtaEventDetector.pickEventsFromRatio(
                ratioMax, ltaWindow, det.staLtaThreshold, det.offRatio,
                det.hangSamples, det.minDurationSamples, det.cooldownSamples, "max(X,Y,Z) STA/LTA");

        evR = StaLtaEventDetector.keepDominantEvent(evR, ratioR, null);
        evX = StaLtaEventDetector.keepDominantEvent(evX, ratioX, null);
        evY = StaLtaEventDetector.keepDominantEvent(evY, ratioY, null);
        evZ = StaLtaEventDetector.keepDominantEvent(evZ, ratioZ, null);
        evE = StaLtaEventDetector.keepDominantEvent(evE, ratioEnergy, null);
        evMin = StaLtaEventDetector.keepDominantEvent(evMin, ratioMin, null);
        evMax = StaLtaEventDetector.keepDominantEvent(evMax, ratioMax, null);

        List<Integer> onsetList = evR.stream().map(DetectionEvent::getStartSample).toList();

        printDetectionComparison(sampleRate, evR, evX, evY, evZ, evE, evMin, evMax);

        System.out.println("═".repeat(50));

        return new ThreeComponentResult(resultAmplitude, azimuth, incidenceAngle, polarization,
                onsetList, evR, evX, evY, evZ, evE, evMin, evMax);
    }

    private void printDetectionComparison(int sampleRate,
                                          List<DetectionEvent> evR,
                                          List<DetectionEvent> evX,
                                          List<DetectionEvent> evY,
                                          List<DetectionEvent> evZ,
                                          List<DetectionEvent> evE,
                                          List<DetectionEvent> evMin,
                                          List<DetectionEvent> evMax) {
        System.out.println("\n📊 СРАВНЕНИЕ МЕТОДОВ ОБНАРУЖЕНИЯ (порог STA/LTA, границы события [начало..конец], с)");
        printMethodBlock("Результирующая √(X²+Y²+Z²), STA/LTA", evR, sampleRate);
        printMethodBlock("Компонента X (N–S), STA/LTA отдельно", evX, sampleRate);
        printMethodBlock("Компонента Y (E–W), STA/LTA отдельно", evY, sampleRate);
        printMethodBlock("Компонента Z (верт.), STA/LTA отдельно", evZ, sampleRate);
        printMethodBlock("Энергетический STA/LTA по R (квадрат амплитуды)", evE, sampleRate);
        printMethodBlock("Трёхкомпонентное слияние: min(STA/LTA) по X,Y,Z", evMin, sampleRate);
        printMethodBlock("Трёхкомпонентное слияние: max(STA/LTA) по X,Y,Z", evMax, sampleRate);

        System.out.println("\n   Итого число событий: R=" + evR.size() + ", X=" + evX.size() + ", Y=" + evY.size()
                + ", Z=" + evZ.size() + ", Energy(R)=" + evE.size()
                + ", min-фьюжн=" + evMin.size() + ", max-фьюжн=" + evMax.size());
    }

    private void printMethodBlock(String title, List<DetectionEvent> events, int sampleRate) {
        System.out.println("\n   ► " + title + " — событий: " + events.size());
        int n = Math.min(5, events.size());
        for (int i = 0; i < n; i++) {
            DetectionEvent e = events.get(i);
            double t0 = e.getStartSample() / (double) sampleRate;
            double t1 = e.getEndSample() / (double) sampleRate;
            double tp = e.getPeakSample() / (double) sampleRate;
            System.out.printf("      %d) t_start=%.3f с, t_end=%.3f с, t_peak=%.3f с, длительность=%.3f с%n",
                    i + 1, t0, t1, tp, t1 - t0);
        }
        if (events.size() > 5) {
            System.out.println("      … ещё " + (events.size() - 5));
        }
    }

    private String azimuthToDirection(double azimuth) {
        if (azimuth >= 337.5 || azimuth < 22.5) return "Север ↑";
        if (azimuth >= 22.5 && azimuth < 67.5) return "Северо-восток ↗";
        if (azimuth >= 67.5 && azimuth < 112.5) return "Восток →";
        if (azimuth >= 112.5 && azimuth < 157.5) return "Юго-восток ↘";
        if (azimuth >= 157.5 && azimuth < 202.5) return "Юг ↓";
        if (azimuth >= 202.5 && azimuth < 247.5) return "Юго-запад ↙";
        if (azimuth >= 247.5 && azimuth < 292.5) return "Запад ←";
        return "Северо-запад ↖";
    }

    private String interpretPolarization(double polarization) {
        if (polarization > 0.8) return "Высокая линейная (P-волны)";
        if (polarization > 0.6) return "Средняя линейная";
        if (polarization > 0.4) return "Смешанная";
        if (polarization > 0.2) return "Преимущественно эллиптическая";
        return "Низкая (S-волны или шум)";
    }

    // Добавьте в ThreeComponentAnalyzer.java


    /**
     * Экспериментальный детектор с произвольными параметрами STA/LTA.
     * Возвращает ВСЕ события (не только доминирующее).
     */
    public List<DetectionEvent> detectWithCustomParams(
            List<Double> signal,
            int sampleRate,
            double staSec,
            double ltaSec,
            double threshold,
            String label) {

        int staWindow = Math.max(2, (int)(staSec * sampleRate));
        int ltaWindow = Math.max(staWindow + 1, (int)(ltaSec * sampleRate));

        double offRatio = 0.5;        // Ниже для плавных сигналов
        int hangSamples = staWindow;  // Дольше держим событие
        int minDuration = staWindow / 2;
        int cooldownSamples = ltaWindow;

        double[] ratio = StaLtaEventDetector.absStaLtaRatio(signal, staWindow, ltaWindow);

        List<DetectionEvent> events = StaLtaEventDetector.pickEventsFromRatio(
                ratio, ltaWindow, threshold, offRatio,
                hangSamples, minDuration, cooldownSamples, label);

        // Не фильтруем — возвращаем все для эксперимента
        return events;
    }

}
