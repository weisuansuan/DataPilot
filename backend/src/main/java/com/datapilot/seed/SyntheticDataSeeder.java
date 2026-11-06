package com.datapilot.seed;

import static com.datapilot.seed.SyntheticSeedPlan.CATEGORY_CODES;
import static com.datapilot.seed.SyntheticSeedPlan.CATEGORY_NAMES;
import static com.datapilot.seed.SyntheticSeedPlan.CUSTOMER_COUNT;
import static com.datapilot.seed.SyntheticSeedPlan.FIRST_MONTH;
import static com.datapilot.seed.SyntheticSeedPlan.PRODUCT_COUNT;
import static com.datapilot.seed.SyntheticSeedPlan.REGION_CODES;
import static com.datapilot.seed.SyntheticSeedPlan.REGION_NAMES;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("seed")
public class SyntheticDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SyntheticDataSeeder.class);
    private static final int BATCH_SIZE = 1_000;
    private static final long SEED = SyntheticSeedPlan.RANDOM_SEED;
    private static final LocalDate REFUND_CUTOFF = LocalDate.of(2026, 11, 6);
    private static final String[] PAYMENT_METHODS = {"CARD", "WALLET", "BANK_TRANSFER"};
    private static final String[] PRODUCT_SUFFIXES = {"经典款", "轻享款", "升级款", "家庭装"};

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final int requestedOrders;

    public SyntheticDataSeeder(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${datapilot.seed.order-count:100000}") int requestedOrders) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.requestedOrders = requestedOrders;
    }

    @Override
    public void run(String... args) {
        SyntheticSeedPlan.validateOrderCount(requestedOrders);
        assertOwnedDevelopmentDatabase();
        log.info("Regenerating synthetic dataset for {} orders with fixed seed {}", requestedOrders, SEED);
        resetData();
        insertCategories();
        Map<String, List<Long>> customersByRegion = insertCustomers();
        List<Product> products = insertProducts();
        generateOrders(customersByRegion, products);
        jdbc.update("UPDATE dataset_metadata SET order_count = ?, seed_value = ?, generated_at = CURRENT_TIMESTAMP WHERE dataset_code = 'SYNTHETIC_ECOMMERCE_V1'",
                requestedOrders, SEED);
        log.info("Synthetic seed completed: {} orders, {} customers, {} products", requestedOrders, CUSTOMER_COUNT, PRODUCT_COUNT);
    }

    private void assertOwnedDevelopmentDatabase() {
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        if (!"datapilot".equals(database)) {
            throw new IllegalStateException("Refusing to seed database '" + database + "'; expected the dedicated datapilot database.");
        }
        Integer synthetic = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dataset_metadata WHERE dataset_code = 'SYNTHETIC_ECOMMERCE_V1' AND synthetic_data = TRUE",
                Integer.class);
        if (synthetic == null || synthetic != 1) {
            throw new IllegalStateException("Refusing to seed: the expected synthetic dataset marker is missing.");
        }
    }

    private void resetData() {
        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM refund");
            jdbc.update("DELETE FROM payment");
            jdbc.update("DELETE FROM order_item");
            jdbc.update("DELETE FROM sales_order");
            jdbc.update("DELETE FROM product");
            jdbc.update("DELETE FROM customer");
            jdbc.update("DELETE FROM product_category");
            jdbc.update("UPDATE dataset_metadata SET order_count = 0 WHERE dataset_code = 'SYNTHETIC_ECOMMERCE_V1'");
        });
    }

    private void insertCategories() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < CATEGORY_CODES.size(); i++) {
            rows.add(new Object[]{i + 1, CATEGORY_CODES.get(i), CATEGORY_NAMES.get(i), true});
        }
        jdbc.batchUpdate("INSERT INTO product_category (category_id, category_code, category_name, synthetic_data) VALUES (?, ?, ?, ?)", rows);
    }

    private Map<String, List<Long>> insertCustomers() {
        List<Object[]> rows = new ArrayList<>(CUSTOMER_COUNT);
        Map<String, List<Long>> byRegion = REGION_CODES.stream().collect(Collectors.toMap(code -> code, code -> new ArrayList<>()));
        LocalDateTime created = LocalDateTime.of(2025, 1, 1, 0, 0);
        for (long id = 1; id <= CUSTOMER_COUNT; id++) {
            int regionIndex = (int) ((id - 1) % REGION_CODES.size());
            String code = REGION_CODES.get(regionIndex);
            byRegion.get(code).add(id);
            rows.add(new Object[]{id, "合成客户-%05d".formatted(id), "customer%05d@example.test".formatted(id),
                    code, REGION_NAMES.get(regionIndex), Timestamp.valueOf(created.plusDays(id % 365)), true});
        }
        batch("INSERT INTO customer (customer_id, display_name, email, region_code, region_name, created_at, synthetic_data) VALUES (?, ?, ?, ?, ?, ?, ?)", rows);
        return byRegion;
    }

    private List<Product> insertProducts() {
        List<Object[]> rows = new ArrayList<>(PRODUCT_COUNT);
        List<Product> products = new ArrayList<>(PRODUCT_COUNT);
        for (int categoryIndex = 0; categoryIndex < CATEGORY_CODES.size(); categoryIndex++) {
            for (int withinCategory = 0; withinCategory < PRODUCT_COUNT / CATEGORY_CODES.size(); withinCategory++) {
                long id = products.size() + 1L;
                BigDecimal price = BigDecimal.valueOf(19 + categoryIndex * 37L + withinCategory * 11L, 0)
                        .setScale(2, RoundingMode.UNNECESSARY);
                products.add(new Product(id, categoryIndex + 1, price));
                rows.add(new Object[]{id, categoryIndex + 1, "SKU-%04d".formatted(id),
                        "合成商品-%s-%02d-%s".formatted(CATEGORY_NAMES.get(categoryIndex), withinCategory + 1, PRODUCT_SUFFIXES[withinCategory % PRODUCT_SUFFIXES.length]),
                        price, true, true});
            }
        }
        batch("INSERT INTO product (product_id, category_id, sku, product_name, unit_price, active, synthetic_data) VALUES (?, ?, ?, ?, ?, ?, ?)", rows);
        return List.copyOf(products);
    }

    private void generateOrders(Map<String, List<Long>> customersByRegion, List<Product> products) {
        Random random = new Random(SEED);
        List<Object[]> orderRows = new ArrayList<>(BATCH_SIZE);
        List<Object[]> itemRows = new ArrayList<>(BATCH_SIZE * 2);
        List<Object[]> paymentRows = new ArrayList<>(BATCH_SIZE);
        List<Object[]> refundRows = new ArrayList<>(BATCH_SIZE / 10);
        long orderId = 0;

        for (int monthIndex = 0; monthIndex < 10; monthIndex++) {
            YearMonth month = FIRST_MONTH.plusMonths(monthIndex);
            int monthCount = SyntheticSeedPlan.monthOrderCount(requestedOrders, monthIndex);
            List<String> regions = regionSchedule(monthIndex, monthCount);
            Collections.shuffle(regions, random);
            for (String region : regions) {
                orderId++;
                int regionIndex = REGION_CODES.indexOf(region);
                long customerId = randomCustomer(random, customersByRegion.get(region));
                LocalDateTime orderedAt = randomOrderTime(random, month);
                int itemCount = 1 + random.nextInt(3);
                List<GeneratedItem> items = new ArrayList<>(itemCount);
                BigDecimal orderTotal = BigDecimal.ZERO.setScale(2);
                for (int itemIndex = 1; itemIndex <= itemCount; itemIndex++) {
                    Product product = products.get(random.nextInt(products.size()));
                    int quantity = 1 + random.nextInt(3);
                    BigDecimal lineTotal = product.unitPrice().multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.UNNECESSARY);
                    long itemId = orderId * 3 + itemIndex;
                    items.add(new GeneratedItem(itemId, product, quantity, lineTotal));
                    orderTotal = orderTotal.add(lineTotal);
                }

                double paymentRoll = random.nextDouble();
                String paymentStatus = paymentRoll < 0.93 ? "SUCCEEDED" : paymentRoll < 0.96 ? "FAILED" : "PENDING";
                boolean paid = "SUCCEEDED".equals(paymentStatus);
                LocalDateTime paidAt = paid ? orderedAt.plusMinutes(2 + random.nextInt(118)) : null;
                List<GeneratedItem> refundable = new ArrayList<>();
                BigDecimal refundedAmount = BigDecimal.ZERO.setScale(2);
                if (paid) {
                    for (GeneratedItem item : items) {
                        double refundProbability = item.product().categoryId() == 1 ? 0.22 : 0.02;
                        if (random.nextDouble() < refundProbability) {
                            refundable.add(item);
                            refundedAmount = refundedAmount.add(item.lineTotal());
                        }
                    }
                }
                String orderStatus = !paid ? ("FAILED".equals(paymentStatus) ? "PAYMENT_FAILED" : "PENDING_PAYMENT")
                        : refundedAmount.signum() == 0 ? "PAID"
                        : refundedAmount.compareTo(orderTotal) == 0 ? "REFUNDED" : "PARTIALLY_REFUNDED";
                String finalPaymentStatus = refundedAmount.signum() == 0 ? paymentStatus
                        : refundedAmount.compareTo(orderTotal) == 0 ? "REFUNDED" : "PARTIALLY_REFUNDED";
                orderRows.add(new Object[]{orderId, customerId, "DP-%09d".formatted(orderId), Timestamp.valueOf(orderedAt),
                        region, REGION_NAMES.get(regionIndex), orderStatus, orderTotal, true});
                for (GeneratedItem item : items) {
                    itemRows.add(new Object[]{item.id(), orderId, item.product().id(), item.quantity(), item.product().unitPrice(), item.lineTotal(), true});
                }
                paymentRows.add(new Object[]{orderId, orderId, "PAY-%09d".formatted(orderId), PAYMENT_METHODS[random.nextInt(PAYMENT_METHODS.length)],
                        finalPaymentStatus, orderTotal, paidAt == null ? null : Timestamp.valueOf(paidAt), true});
                for (GeneratedItem item : refundable) {
                    int daysAvailable = Math.toIntExact(ChronoUnit.DAYS.between(paidAt.toLocalDate(), REFUND_CUTOFF));
                    LocalDateTime refundedAt = paidAt.plusDays(1 + random.nextInt(daysAvailable));
                    refundRows.add(new Object[]{item.id(), orderId, orderId, item.id(), "REF-%09d".formatted(item.id()), "COMPLETED",
                            item.quantity(), item.lineTotal(), Timestamp.valueOf(refundedAt), true});
                }

                if (orderRows.size() >= BATCH_SIZE) {
                    insertOrderBatch(orderRows, itemRows, paymentRows, refundRows);
                    orderRows.clear(); itemRows.clear(); paymentRows.clear(); refundRows.clear();
                    if (orderId % 10_000 == 0) {
                        log.info("Generated {} / {} synthetic orders", orderId, requestedOrders);
                    }
                }
            }
        }
        if (!orderRows.isEmpty()) {
            insertOrderBatch(orderRows, itemRows, paymentRows, refundRows);
        }
    }

    private List<String> regionSchedule(int monthIndex, int monthCount) {
        List<String> regions = new ArrayList<>(monthCount);
        List<Integer> counts = SyntheticSeedPlan.regionalOrderCounts(monthIndex, monthCount);
        for (int i = 0; i < counts.size(); i++) {
            for (int n = 0; n < counts.get(i); n++) {
                regions.add(REGION_CODES.get(i));
            }
        }
        return regions;
    }

    private void insertOrderBatch(List<Object[]> orders, List<Object[]> items, List<Object[]> payments, List<Object[]> refunds) {
        transaction.executeWithoutResult(status -> {
            batch("INSERT INTO sales_order (order_id, customer_id, order_number, order_date, ship_region_code, ship_region_name, status, total_amount, synthetic_data) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", orders);
            batch("INSERT INTO order_item (order_item_id, order_id, product_id, quantity, unit_price, line_total, synthetic_data) VALUES (?, ?, ?, ?, ?, ?, ?)", items);
            batch("INSERT INTO payment (payment_id, order_id, payment_reference, payment_method, status, amount, paid_at, synthetic_data) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", payments);
            batch("INSERT INTO refund (refund_id, order_id, payment_id, order_item_id, refund_reference, status, quantity, amount, refunded_at, synthetic_data) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", refunds);
        });
    }

    private void batch(String sql, List<Object[]> rows) {
        if (rows.isEmpty()) return;
        jdbc.batchUpdate(sql, rows, BATCH_SIZE, (PreparedStatement statement, Object[] row) -> {
            for (int i = 0; i < row.length; i++) {
                statement.setObject(i + 1, row[i]);
            }
        });
    }

    private long randomCustomer(Random random, List<Long> customers) {
        return customers.get(random.nextInt(customers.size()));
    }

    private LocalDateTime randomOrderTime(Random random, YearMonth month) {
        return month.atDay(1).atTime(8, 0)
                .plusDays(random.nextInt(month.lengthOfMonth()))
                .plusSeconds(random.nextInt(43_200));
    }

    private record Product(long id, int categoryId, BigDecimal unitPrice) { }
    private record GeneratedItem(long id, Product product, int quantity, BigDecimal lineTotal) { }
}
