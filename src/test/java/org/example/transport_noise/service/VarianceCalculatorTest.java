//package org.example.transport_noise.service;
//
//import org.junit.Test;
//import java.util.Arrays;
//import java.util.List;
//import static org.junit.Assert.*;
//
//public class VarianceCalculatorTest {
//
//    @Test
//    public void testSimpleData() {
//        VarianceCalculator calc = new VarianceCalculator();
//        List<Double> data = Arrays.asList(1.0, 2.0, 3.0);
//        List<Double> variances = calc.calculatePerSecond(data);
//
//        assertEquals(1, variances.size());
//        assertEquals(1.0, variances.get(0), 0.0001);
//    }
//
//    @Test
//    public void testEmptyData() {
//        VarianceCalculator calc = new VarianceCalculator();
//        List<Double> data = Arrays.asList();
//        List<Double> variances = calc.calculatePerSecond(data);
//
//        assertTrue(variances.isEmpty());
//    }
//}