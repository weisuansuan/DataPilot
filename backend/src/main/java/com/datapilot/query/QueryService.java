package com.datapilot.query;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class QueryService {
    private static final int MAX_ROWS = 200;
    private final JdbcTemplate jdbc;
    private final SqlPolicy policy;
    private final int timeoutSeconds;
    private final Semaphore concurrency;
    private final ObjectMapper objectMapper;
    private final BigDecimal maximumPlanCost;

    public QueryService(JdbcTemplate jdbc, SqlPolicy policy,
            @Value("${datapilot.query.timeout-seconds:5}") int timeoutSeconds,
            @Value("${datapilot.query.max-concurrency:4}") int maxConcurrency,
            @Value("${datapilot.query.max-plan-cost:10000000}") BigDecimal maximumPlanCost) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.timeoutSeconds = Math.max(1, Math.min(timeoutSeconds, 30));
        this.concurrency = new Semaphore(Math.max(1, Math.min(maxConcurrency, 8)));
        this.objectMapper = new ObjectMapper();
        this.maximumPlanCost = maximumPlanCost;
    }

    public SqlPolicy.Validation validate(String sql) { return policy.validate(sql); }

    public QueryResponse execute(String sql) {
        long started = System.nanoTime();
        SqlPolicy.Validation validation = policy.validate(sql);
        if (!validation.allowed()) return QueryResponse.failed(validation.errorCode(), validation.message(), elapsed(started));
        boolean acquired = false;
        try {
            acquired = concurrency.tryAcquire(100, TimeUnit.MILLISECONDS);
            if (!acquired) return QueryResponse.failed("QUERY_BUSY", "查询繁忙，请稍后重试", elapsed(started));
            return jdbc.execute((ConnectionCallback<QueryResponse>) connection -> execute(connection, sql, started));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return QueryResponse.failed("QUERY_CANCELLED", "查询已取消", elapsed(started));
        } catch (QueryPlanRejected exception) {
            return QueryResponse.failed("QUERY_PLAN_LIMIT", "查询计划成本过高或无法安全评估", elapsed(started));
        } catch (DataAccessException exception) {
            if (hasTimeoutCause(exception)) return QueryResponse.failed("QUERY_TIMEOUT", "查询超过执行时间限制", elapsed(started));
            if (hasSqlError(exception, 1064)) return QueryResponse.failed("QUERY_SYNTAX_ERROR", "生成的 SQL 语法无法被 MySQL 执行", elapsed(started));
            if (hasSqlError(exception, 1054, 1052, 1146)) return QueryResponse.failed("QUERY_SCHEMA_ERROR", "生成的 SQL 字段或数据对象与当前授权 schema 不匹配", elapsed(started));
            return QueryResponse.failed("QUERY_FAILED", "数据库无法完成该查询", elapsed(started));
        } catch (Exception exception) {
            return QueryResponse.failed("QUERY_FAILED", "查询执行失败", elapsed(started));
        } finally {
            if (acquired) concurrency.release();
        }
    }

    private QueryResponse execute(Connection connection, String sql, long started) throws SQLException {
        try (Statement session = connection.createStatement()) {
            session.execute("SET SESSION max_execution_time = " + (timeoutSeconds * 1000));
            try {
                reviewPlan(connection, sql);
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(timeoutSeconds);
                    statement.setMaxRows(MAX_ROWS + 1);
                    try (ResultSet resultSet = statement.executeQuery(sql)) {
                        ResultSetMetaData metadata = resultSet.getMetaData();
                        List<QueryColumn> columns = new ArrayList<>();
                        for (int index = 1; index <= metadata.getColumnCount(); index++)
                            columns.add(new QueryColumn(metadata.getColumnLabel(index), metadata.getColumnTypeName(index)));
                        List<Map<String, Object>> rows = new ArrayList<>();
                        while (resultSet.next() && rows.size() < MAX_ROWS + 1) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                                Object value = resultSet.getObject(index);
                                if (value instanceof BigDecimal decimal) value = decimal.toPlainString();
                                else if (value instanceof BigInteger integer) value = integer.toString();
                                else if (value instanceof Long integer) value = integer.toString();
                                row.put(metadata.getColumnLabel(index), value);
                            }
                            rows.add(row);
                        }
                        boolean truncated = rows.size() > MAX_ROWS;
                        if (truncated) rows.remove(rows.size() - 1);
                        return new QueryResponse(true, columns, rows, rows.size(), elapsed(started), truncated, null, null);
                    }
                }
            } finally {
                try { session.execute("SET SESSION max_execution_time = 0"); }
                catch (SQLException ignored) { connection.abort(Runnable::run); }
            }
        }
    }

    private void reviewPlan(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            try (ResultSet result = statement.executeQuery("EXPLAIN FORMAT=JSON " + sql)) {
                if (!result.next()) throw new QueryPlanRejected();
                JsonNode document = objectMapper.readTree(result.getString(1));
                JsonNode estimate = document == null ? null : document.findValue("query_cost");
                if (estimate == null) throw new QueryPlanRejected();
                BigDecimal cost;
                try {
                    cost = estimate.isNumber() ? estimate.decimalValue() : new BigDecimal(estimate.asString());
                } catch (NumberFormatException exception) {
                    throw new QueryPlanRejected();
                }
                if (cost.compareTo(maximumPlanCost) > 0) throw new QueryPlanRejected();
            } catch (tools.jackson.core.JacksonException exception) {
                throw new QueryPlanRejected();
            }
        }
    }

    private static boolean hasTimeoutCause(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLTimeoutException) return true;
            if (cause instanceof SQLException sqlException
                    && (sqlException.getErrorCode() == 3024 || "HYT00".equals(sqlException.getSQLState()))) return true;
            String message = cause.getMessage();
            if (message != null && (message.toLowerCase().contains("timeout")
                    || message.toLowerCase().contains("maximum statement execution time")
                    || message.toLowerCase().contains("statement execution time exceeded"))) return true;
        }
        return false;
    }

    private static boolean hasSqlError(Throwable throwable, int... codes) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException)
                for (int code : codes) if (sqlException.getErrorCode() == code) return true;
        }
        return false;
    }

    private static long elapsed(long started) { return Duration.ofNanos(System.nanoTime() - started).toMillis(); }

    public record QueryColumn(String name, String type) { }
    public record QueryResponse(boolean success, List<QueryColumn> columns, List<Map<String, Object>> rows,
            int rowCount, long executionTimeMs, boolean truncated, String errorCode, String message) {
        static QueryResponse failed(String code, String message, long elapsed) {
            return new QueryResponse(false, List.of(), List.of(), 0, elapsed, false, code, message);
        }
    }

    private static final class QueryPlanRejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
