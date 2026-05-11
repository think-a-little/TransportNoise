package org.example.transport_noise.service;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

public class ButterworthBandpassTest {

    @Test
    public void bandpassReducesDcAfterWarmup() {
        List<Double> in = new ArrayList<>();
        for (int i = 0; i < 8000; i++) {
            in.add(5.0);
        }
        int fs = 1000;
        List<Double> out = ButterworthBandpass.filter(in, fs, 1.0, 100.0);
        assertTrue(out.size() > 0);
        double tailMean = out.subList(4000, out.size()).stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0);
        assertTrue("ВЧ убирает постоянную составляющую", Math.abs(tailMean) < 2.0);
    }
}
