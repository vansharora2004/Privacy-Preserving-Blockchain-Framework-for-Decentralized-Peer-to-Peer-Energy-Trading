package com.energytrading.service;

import java.math.BigDecimal;

/**
 * Conversions between the {@link BigDecimal} values used by the API and services and the
 * {@code DOUBLE} columns of the v1 schema. Validated values have at most a few decimal places
 * and a small magnitude, so they round-trip exactly through {@link BigDecimal#valueOf(double)}.
 */
public final class EnergyValues {

    private EnergyValues() {
    }

    public static BigDecimal toBigDecimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    public static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
