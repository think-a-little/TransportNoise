package org.example.transport_noise.service;

import org.example.transport_noise.model.DetectionEvent;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StaLtaEventDetectorTest {

    @Test
    public void detectsBurstWithStartEnd() {
        List<Double> sig = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            sig.add(0.02);
        }
        for (int i = 0; i < 120; i++) {
            sig.add(4.0);
        }
        for (int i = 0; i < 400; i++) {
            sig.add(0.02);
        }

        int sta = 20;
        int lta = 100;
        double[] ratio = StaLtaEventDetector.absStaLtaRatio(sig, sta, lta);
        List<DetectionEvent> ev = StaLtaEventDetector.pickEventsFromRatio(
                ratio, lta, 2.0, 0.45, 8, 10, 30, "test");

        assertFalse(ev.isEmpty());
        DetectionEvent e = ev.get(0);
        assertTrue("начало до всплеска", e.getStartSample() < 450);
        assertTrue("конец после всплеска", e.getEndSample() > 400);
        assertTrue(e.getPeakSample() >= e.getStartSample() && e.getPeakSample() <= e.getEndSample());
    }

    @Test
    public void minFusionLowerOrEqualThanMax() {
        List<Double> x = new ArrayList<>();
        List<Double> y = new ArrayList<>();
        List<Double> z = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            x.add(1.0);
            y.add(0.1);
            z.add(0.1);
        }
        int sta = 10;
        int lta = 80;
        double[] rMin = StaLtaEventDetector.minStaLtaRatio(x, y, z, sta, lta);
        double[] rMax = StaLtaEventDetector.maxStaLtaRatio(x, y, z, sta, lta);
        for (int i = lta; i < 500; i++) {
            assertTrue(rMin[i] <= rMax[i] + 1e-9);
        }
    }
}
