package org.example.transport_noise.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Полосовой фильтр Баттерворта 4-го порядка: каскад ВЧ + НЧ (RBJ biquad, TDF-II).
 * Полоса по умолчанию: нижняя граница — {@code lowCutHz}, верхняя — до {@code highCutHz} (например 100 Гц).
 */
public final class ButterworthBandpass {

    private ButterworthBandpass() {
    }

    /**
     * @param sampleRate    частота дискретизации, Гц
     * @param lowCutHz      нижняя частота среза ВЧ-звена, Гц (убирает постоянную составляющую и очень низкие частоты)
     * @param highCutHz     верхняя частота среза НЧ-звена, Гц (полоса «до 100 Гц»)
     */
    public static List<Double> filter(List<Double> input, int sampleRate, double lowCutHz, double highCutHz) {
        if (input == null || input.isEmpty()) {
            return new ArrayList<>();
        }
        double nyq = sampleRate / 2.0;
        double hi = Math.min(highCutHz, nyq * 0.49);
        double lo = lowCutHz;
        if (lo < 0.1) {
            lo = 0.1;
        }
        if (lo >= hi - 0.5) {
            lo = Math.max(0.1, hi - 1.0);
        }
        if (hi <= lo + 0.5) {
            return new ArrayList<>(input);
        }

        double[] x = new double[input.size()];
        for (int i = 0; i < input.size(); i++) {
            x[i] = input.get(i);
        }

        // 4-й порядок: два biquad на ВЧ и два на НЧ
        BiquadChain hp = makeHighPass(sampleRate, lo);
        BiquadChain lp = makeLowPass(sampleRate, hi);

        x = hp.process(x);
        x = lp.process(x);

        List<Double> out = new ArrayList<>(x.length);
        for (double v : x) {
            out.add(v);
        }
        return out;
    }

    private static BiquadChain makeLowPass(int fs, double fc) {
        double q1 = 0.5411961;
        double q2 = 1.3065630;
        return new BiquadChain(
                biquadLowPass(fs, fc, q1),
                biquadLowPass(fs, fc, q2)
        );
    }

    private static BiquadChain makeHighPass(int fs, double fc) {
        double q1 = 0.5411961;
        double q2 = 1.3065630;
        return new BiquadChain(
                biquadHighPass(fs, fc, q1),
                biquadHighPass(fs, fc, q2)
        );
    }

    private static Biquad biquadLowPass(int fs, double fc, double q) {
        double w0 = 2.0 * Math.PI * fc / fs;
        double cosw0 = Math.cos(w0);
        double sinw0 = Math.sin(w0);
        double alpha = sinw0 / (2.0 * q);
        double b0 = (1.0 - cosw0) / 2.0;
        double b1 = 1.0 - cosw0;
        double b2 = (1.0 - cosw0) / 2.0;
        double a0 = 1.0 + alpha;
        double a1 = -2.0 * cosw0;
        double a2 = 1.0 - alpha;
        return new Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0);
    }

    private static Biquad biquadHighPass(int fs, double fc, double q) {
        double w0 = 2.0 * Math.PI * fc / fs;
        double cosw0 = Math.cos(w0);
        double sinw0 = Math.sin(w0);
        double alpha = sinw0 / (2.0 * q);
        double b0 = (1.0 + cosw0) / 2.0;
        double b1 = -(1.0 + cosw0);
        double b2 = (1.0 + cosw0) / 2.0;
        double a0 = 1.0 + alpha;
        double a1 = -2.0 * cosw0;
        double a2 = 1.0 - alpha;
        return new Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0);
    }

    private static final class Biquad {
        final double b0, b1, b2, a1, a2;
        double z1, z2;

        Biquad(double b0, double b1, double b2, double a1, double a2) {
            this.b0 = b0;
            this.b1 = b1;
            this.b2 = b2;
            this.a1 = a1;
            this.a2 = a2;
        }

        double process(double x) {
            double y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }

        void reset() {
            z1 = z2 = 0;
        }
    }

    private static final class BiquadChain {
        private final Biquad[] sections;

        BiquadChain(Biquad... sections) {
            this.sections = sections;
        }

        double[] process(double[] in) {
            for (Biquad b : sections) {
                b.reset();
            }
            double[] out = new double[in.length];
            for (int i = 0; i < in.length; i++) {
                double v = in[i];
                for (Biquad b : sections) {
                    v = b.process(v);
                }
                out[i] = v;
            }
            return out;
        }
    }
}
