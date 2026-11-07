package com.datapilot.ai.evaluation;

import com.datapilot.query.QueryService.QueryResponse;
import java.math.BigDecimal;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class QueryResultComparator {
    private QueryResultComparator() { }

    public static boolean equivalent(QueryResponse expected, QueryResponse actual) {
        if (expected == null || actual == null || !expected.success() || !actual.success()) return false;
        if (!expected.columns().stream().map(c -> c.name().toLowerCase()).sorted().toList()
                .equals(actual.columns().stream().map(c -> c.name().toLowerCase()).sorted().toList())) return false;
        return rowSignatures(expected.rows(), expected.columns()).equals(rowSignatures(actual.rows(), actual.columns()));
    }

    public static String digest(QueryResponse response) {
        if (response == null || !response.success()) return null;
        String canonical = response.columns().stream().map(c -> c.name().toLowerCase()).sorted().toList()
                + "\n" + String.join("\n", rowSignatures(response.rows(), response.columns()));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static List<String> rowSignatures(List<Map<String, Object>> rows, List<com.datapilot.query.QueryService.QueryColumn> columns) {
        Map<String, Boolean> numericColumns = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        columns.forEach(column -> numericColumns.put(column.name(), column.type() != null && column.type().matches("(?i).*(tinyint|smallint|mediumint|bigint|int|decimal|numeric|float|double|real).*")));
        List<String> values = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            TreeMap<String, String> normalized = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            row.forEach((key, value) -> normalized.put(key, normalize(value, numericColumns.getOrDefault(key, false))));
            StringBuilder signature = new StringBuilder();
            normalized.forEach((key, value) -> signature.append(key.length()).append(':').append(key)
                    .append(value.length()).append(':').append(value));
            values.add(signature.toString());
        }
        values.sort(String::compareTo);
        return values;
    }

    private static String normalize(Object value, boolean numericColumn) {
        if (value == null) return "N:";
        if (value instanceof BigDecimal decimal) return "D:" + decimal.stripTrailingZeros().toPlainString();
        if (value instanceof Number number) {
            try { return "D:" + new BigDecimal(number.toString()).stripTrailingZeros().toPlainString(); }
            catch (NumberFormatException ignored) { return "N:" + number; }
        }
        if (numericColumn && value instanceof String string) {
            try { return "D:" + new BigDecimal(string).stripTrailingZeros().toPlainString(); }
            catch (NumberFormatException ignored) { return "S:" + string; }
        }
        if (value instanceof TemporalAccessor || value instanceof java.util.Date) return "T:" + value;
        return "S:" + value;
    }
}
