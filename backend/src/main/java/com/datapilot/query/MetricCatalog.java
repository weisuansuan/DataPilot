package com.datapilot.query;

import java.util.List;

final class MetricCatalog {
    private MetricCatalog() { }

    static List<QueryController.MetricDefinition> all() {
        return List.of(
                metric("order_count", "订单数", "统计全部合成订单，每个 sales_order 计一笔。", "COUNT(DISTINCT sales_order.order_id)", "无；如需支付订单请使用支付状态过滤", List.of("status", "ship_region_code", "order_date"), List.of("sales_order.order_id", "sales_order.status", "sales_order.ship_region_code", "sales_order.order_date")),
                metric("gross_paid_amount", "成交金额", "按支付成功时间统计支付本金，不扣除退款。", "SUM(payment.amount)", "payment.status IN ('SUCCEEDED','PARTIALLY_REFUNDED','REFUNDED')；按 paid_at 归期", List.of("paid_at", "ship_region_code", "payment_method"), List.of("payment.amount", "payment.status", "payment.paid_at", "sales_order.ship_region_code")),
                metric("paid_amount", "实付金额", "与第二阶段仪表盘口径一致：已成功支付本金，退款单独列示。", "SUM(payment.amount)", "payment.status IN ('SUCCEEDED','PARTIALLY_REFUNDED','REFUNDED') AND payment.paid_at IS NOT NULL", List.of("paid_at", "ship_region_code", "customer_id"), List.of("payment.amount", "payment.status", "payment.paid_at", "sales_order.ship_region_code")),
                metric("refund_amount", "退款金额", "统计已完成退款金额；按 refunded_at 归期。", "SUM(refund.amount)", "refund.status = 'COMPLETED'", List.of("refunded_at", "order_id", "order_item_id"), List.of("refund.amount", "refund.status", "refund.refunded_at", "refund.order_id")),
                metric("net_paid_amount", "净支付金额", "期间实付本金减期间已完成退款；收入与退款须采用一致的报告期间。", "SUM(payment.amount) - SUM(refund.amount)", "实付按 paid_at，退款按 refunded_at 分别过滤；先分别聚合再相减以避免 JOIN 扇出", List.of("month", "ship_region_code"), List.of("payment.amount", "payment.paid_at", "refund.amount", "refund.refunded_at")),
                metric("refund_rate", "退款率", "商品件数退款率：已完成退款件数 / 已支付订单中的售出件数。", "SUM(refund.quantity) / SUM(order_item.quantity)", "仅支付成功订单；先按 order_item 聚合 refund，再关联订单明细；退款观察截止 2026-11-06", List.of("category_code", "ship_region_code"), List.of("order_item.quantity", "refund.quantity", "product_category.category_code", "refund.status")),
                metric("regional_sales", "地区销售分布", "按收货地区统计实付金额和已支付订单数。", "SUM(payment.amount), COUNT(DISTINCT payment.order_id)", "已支付状态，金额按 payment.paid_at 归期", List.of("ship_region_code", "ship_region_name", "paid_at"), List.of("sales_order.ship_region_code", "sales_order.ship_region_name", "payment.amount", "payment.paid_at", "payment.order_id")),
                metric("category_sales", "商品品类销售分布", "按品类统计已支付订单明细金额；payment 每个订单唯一，不与 refund 明细直接联接。", "SUM(order_item.line_total)", "仅支付成功订单", List.of("category_code", "category_name"), List.of("product_category.category_code", "product_category.category_name", "order_item.line_total", "payment.status")),
                metric("monthly_sales_trend", "月度销售趋势", "按支付成功时间按月统计实付本金。", "SUM(payment.amount)", "已支付状态；按 payment.paid_at 月份归属", List.of("paid_at month"), List.of("payment.amount", "payment.status", "payment.paid_at")));
    }

    private static QueryController.MetricDefinition metric(String id, String name, String description,
            String formula, String filters, List<String> dimensions, List<String> fields) {
        return new QueryController.MetricDefinition(id, name, description, formula, filters, dimensions, fields);
    }
}
