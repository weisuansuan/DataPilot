package com.datapilot.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

@Component
public class SqlPolicy {
    private static final int MAX_SQL_LENGTH = 16_384;
    private static final Set<String> ALLOWED_TABLES = Set.of(
            "customer", "product_category", "product", "sales_order", "order_item", "payment", "refund",
            "v_paid_order", "v_refund_by_order", "v_refund_by_item", "v_category_paid_sales", "v_monthly_paid_sales");
    private static final Map<String, Set<String>> COLUMNS = Map.ofEntries(
            Map.entry("customer", Set.of("customer_id", "display_name", "region_code", "region_name", "created_at", "synthetic_data")),
            Map.entry("product_category", Set.of("category_id", "category_code", "category_name", "synthetic_data")),
            Map.entry("product", Set.of("product_id", "category_id", "sku", "product_name", "unit_price", "active", "synthetic_data")),
            Map.entry("sales_order", Set.of("order_id", "customer_id", "order_number", "order_date", "ship_region_code", "ship_region_name", "status", "total_amount", "synthetic_data")),
            Map.entry("order_item", Set.of("order_item_id", "order_id", "product_id", "quantity", "unit_price", "line_total", "synthetic_data")),
            Map.entry("payment", Set.of("payment_id", "order_id", "payment_method", "status", "amount", "paid_at", "synthetic_data")),
            Map.entry("refund", Set.of("refund_id", "order_id", "payment_id", "order_item_id", "status", "quantity", "amount", "refunded_at", "synthetic_data")),
            Map.entry("v_paid_order", Set.of("order_id", "customer_id", "order_date", "ship_region_code", "ship_region_name", "payment_id", "payment_status", "paid_at", "paid_amount")),
            Map.entry("v_refund_by_order", Set.of("order_id", "refund_amount", "last_refunded_at")),
            Map.entry("v_refund_by_item", Set.of("order_item_id", "refunded_quantity", "refund_amount", "last_refunded_at")),
            Map.entry("v_category_paid_sales", Set.of("category_id", "category_code", "category_name", "paid_order_count", "sales_amount")),
            Map.entry("v_monthly_paid_sales", Set.of("paid_month", "paid_order_count", "paid_amount")));
    private static final Set<String> FUNCTIONS = Set.of(
            "count", "sum", "avg", "min", "max", "coalesce", "ifnull", "nullif", "round", "abs",
            "year", "month", "quarter", "date", "date_format", "yearweek", "concat", "lower", "upper",
            "substring", "length", "cast", "greatest", "least", "timestampdiff", "datediff");

    public Validation validate(String sql) {
        if (sql == null || sql.isBlank() || sql.length() > MAX_SQL_LENGTH)
            return Validation.denied("INVALID_SQL", "SQL 不能为空且长度不能超过 16384 个字符");
        if (sql.contains("/*+") || sql.contains("/*!"))
            return Validation.denied("UNSUPPORTED_SQL_FEATURE", "不允许 MySQL 优化器提示或可执行注释");
        try {
            Statements parsed = net.sf.jsqlparser.parser.CCJSqlParserUtil.parseStatements(sql,
                    parser -> parser.withDialect(net.sf.jsqlparser.parser.AbstractJSqlParser.Dialect.MYSQL));
            if (parsed == null || parsed.size() != 1)
                return Validation.denied("MULTIPLE_STATEMENTS", "仅允许一条 SQL 语句");
            Statement statement = parsed.get(0);
            if (!(statement instanceof Select select))
                return Validation.denied("READ_ONLY_SELECT_REQUIRED", "仅允许 SELECT 查询");
            if (select.getWithItemsList() != null && select.getWithItemsList().stream().anyMatch(item -> item.isRecursive()))
                return Validation.denied("UNSUPPORTED_SQL_FEATURE", "暂不允许递归 CTE");

            PolicyVisitor visitor = new PolicyVisitor();
            registerCtes(select, visitor);
            visitor.getTableList(statement);
            if (visitor.error != null) return visitor.error;
            if (visitor.tables.isEmpty()) return Validation.denied("TABLE_REQUIRED", "查询必须读取授权业务表");
            for (Table table : visitor.tables) {
                String tableName = normalize(table.getName());
                if (table.getSchemaName() != null && !table.getSchemaName().isBlank())
                    return Validation.denied("UNAUTHORIZED_TABLE", "不允许指定数据库或 schema");
                if (!ALLOWED_TABLES.contains(tableName) && !visitor.cteColumns.containsKey(tableName))
                    return Validation.denied("UNAUTHORIZED_TABLE", "查询引用了未授权的数据表");
            }

            for (Column column : visitor.columns) {
                String name = normalize(column.getColumnName());
                if (name.equals("*")) continue;
                String qualifier = column.getTable() == null ? "" : normalize(column.getTable().getName());
                Table source = visitor.tables.stream()
                        .filter(table -> normalize(table.getName()).equals(qualifier)
                                || table.getAlias() != null && normalize(table.getAlias().getName()).equals(qualifier))
                        .findFirst().orElse(null);
                if (!qualifier.isEmpty() && source == null)
                    return Validation.denied("UNAUTHORIZED_COLUMN", "字段引用了未授权的数据表或别名");
                String sourceName = source == null ? "" : normalize(source.getName());
                boolean found = visitor.cteColumns.containsKey(sourceName)
                        ? visitor.cteColumns.get(sourceName).contains(name)
                        : source == null
                        ? visitor.projectionAliases.contains(name)
                                || visitor.tables.stream().anyMatch(table -> COLUMNS.get(normalize(table.getName())).contains(name))
                        : COLUMNS.get(normalize(source.getName())).contains(name);
                if (!found) return Validation.denied("UNAUTHORIZED_COLUMN", "查询引用了未授权的字段");
            }
            return Validation.permit();
        } catch (Exception exception) {
            return Validation.denied("SQL_PARSE_ERROR", "SQL 无法解析或包含不支持的语法");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace("`", "").replace("\"", "").toLowerCase(Locale.ROOT);
    }

    private static void registerCtes(Select select, PolicyVisitor visitor) {
        if (select.getWithItemsList() == null) return;
        select.getWithItemsList().forEach(item -> {
            String name = normalize(item.getAliasName());
            Set<String> outputColumns = new java.util.HashSet<>();
            if (item.getWithItemList() != null)
                item.getWithItemList().forEach(column -> outputColumns.add(normalize(column.getAliasName())));
            Select cteSelect = item.getSelect() == null ? null : item.getSelect().getSelectBody();
            while (cteSelect instanceof net.sf.jsqlparser.statement.select.ParenthesedSelect parenthesed)
                cteSelect = parenthesed.getSelect();
            if (cteSelect instanceof PlainSelect body)
                body.getSelectItems().forEach(selected -> {
                    if (selected.getAlias() != null) outputColumns.add(normalize(selected.getAlias().getName()));
                    else if (selected.getExpression() instanceof Column column) outputColumns.add(normalize(column.getColumnName()));
                });
            if (!name.isBlank() && !outputColumns.isEmpty()) visitor.cteColumns.put(name, outputColumns);
        });
    }

    private static final class PolicyVisitor extends TablesNamesFinder<Void> {
        private final List<Table> tables = new ArrayList<>();
        private final List<Column> columns = new ArrayList<>();
        private final Set<String> projectionAliases = new HashSet<>();
        private final Map<String, Set<String>> cteColumns = new HashMap<>();
        private Validation error;

        @Override public <S> Void visit(Table table, S context) {
            tables.add(table);
            return super.visit(table, context);
        }

        @Override public <S> Void visit(Column column, S context) {
            columns.add(column);
            return super.visit(column, context);
        }

        @Override public <S> Void visit(Function function, S context) {
            if (!FUNCTIONS.contains(normalize(function.getName())))
                error = Validation.denied("UNAUTHORIZED_FUNCTION", "查询包含未授权的函数");
            return super.visit(function, context);
        }

        @Override public <S> Void visit(net.sf.jsqlparser.expression.UserVariable variable, S context) {
            error = Validation.denied("UNSUPPORTED_SQL_FEATURE", "不允许用户变量或变量赋值");
            return super.visit(variable, context);
        }

        @Override public <S> Void visit(net.sf.jsqlparser.expression.VariableAssignment assignment, S context) {
            error = Validation.denied("UNSUPPORTED_SQL_FEATURE", "不允许用户变量或变量赋值");
            return super.visit(assignment, context);
        }

        @Override public <S> Void visit(PlainSelect select, S context) {
            select.getSelectItems().forEach(item -> {
                if (item.getAlias() != null) projectionAliases.add(normalize(item.getAlias().getName()));
            });
            if (select.getIntoTables() != null && !select.getIntoTables().isEmpty()
                    || select.getMySqlSelectIntoClause() != null
                    || select.getMySqlProcedureAnalyse() != null
                    || select.getForUpdateTable() != null
                    || select.getForMode() != null
                    || select.getSkip() != null
                    || select.getWait() != null)
                error = Validation.denied("UNSUPPORTED_SQL_FEATURE", "查询包含写入、锁定或等待语法");
            return super.visit(select, context);
        }

        @Override public <S> Void visit(net.sf.jsqlparser.statement.select.SetOperationList set, S context) {
            error = Validation.denied("UNSUPPORTED_SQL_FEATURE", "暂不允许 UNION 或集合运算");
            return super.visit(set, context);
        }

        @Override public <S> Void visit(net.sf.jsqlparser.expression.AnalyticExpression analytic, S context) {
            error = Validation.denied("UNSUPPORTED_SQL_FEATURE", "暂不允许窗口函数");
            return super.visit(analytic, context);
        }

        @Override public <S> Void visit(net.sf.jsqlparser.statement.select.WithItem<?> item, S context) {
            String name = normalize(item.getAliasName());
            if (!name.isBlank()) {
                Set<String> outputColumns = new HashSet<>();
                if (item.getWithItemList() != null) {
                    item.getWithItemList().forEach(column -> outputColumns.add(normalize(column.getAliasName())));
                }
                if (item.getSelect() != null && item.getSelect().getSelectBody() instanceof PlainSelect body) {
                    body.getSelectItems().forEach(selected -> {
                        if (selected.getAlias() != null) outputColumns.add(normalize(selected.getAlias().getName()));
                        else if (selected.getExpression() instanceof Column column) outputColumns.add(normalize(column.getColumnName()));
                    });
                }
                if (!outputColumns.isEmpty()) cteColumns.put(name, outputColumns);
            }
            return super.visit(item, context);
        }
    }

    public record Validation(boolean allowed, String errorCode, String message) {
        static Validation permit() { return new Validation(true, null, null); }
        static Validation denied(String code, String message) { return new Validation(false, code, message); }
    }
}
