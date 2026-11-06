CREATE DATABASE IF NOT EXISTS datapilot
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
USE datapilot;

CREATE TABLE dataset_metadata (
  dataset_code VARCHAR(64) PRIMARY KEY,
  description VARCHAR(255) NOT NULL,
  synthetic_data BOOLEAN NOT NULL,
  order_count INT UNSIGNED NOT NULL DEFAULT 0,
  seed_value BIGINT NOT NULL,
  refund_observation_cutoff DATE NOT NULL,
  generated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT chk_dataset_is_synthetic CHECK (synthetic_data = TRUE)
) ENGINE=InnoDB COMMENT='Dataset catalog; this development database contains synthetic data only';

INSERT INTO dataset_metadata (dataset_code, description, synthetic_data, order_count, seed_value, refund_observation_cutoff)
VALUES ('SYNTHETIC_ECOMMERCE_V1', 'Deterministic synthetic ecommerce demo data', TRUE, 0, 20261106, '2026-11-06');

CREATE TABLE customer (
  customer_id BIGINT UNSIGNED PRIMARY KEY,
  display_name VARCHAR(80) NOT NULL,
  email VARCHAR(160) NOT NULL UNIQUE,
  region_code VARCHAR(16) NOT NULL,
  region_name VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL,
  synthetic_data BOOLEAN NOT NULL DEFAULT TRUE,
  CONSTRAINT chk_customer_synthetic CHECK (synthetic_data = TRUE),
  INDEX idx_customer_region (region_code, customer_id)
) ENGINE=InnoDB COMMENT='Synthetic customer dimension';

CREATE TABLE product_category (
  category_id SMALLINT UNSIGNED PRIMARY KEY,
  category_code VARCHAR(32) NOT NULL UNIQUE,
  category_name VARCHAR(64) NOT NULL UNIQUE,
  synthetic_data BOOLEAN NOT NULL DEFAULT TRUE,
  CONSTRAINT chk_category_synthetic CHECK (synthetic_data = TRUE)
) ENGINE=InnoDB COMMENT='Synthetic product categories';

CREATE TABLE product (
  product_id BIGINT UNSIGNED PRIMARY KEY,
  category_id SMALLINT UNSIGNED NOT NULL,
  sku VARCHAR(32) NOT NULL UNIQUE,
  product_name VARCHAR(120) NOT NULL,
  unit_price DECIMAL(12,2) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  synthetic_data BOOLEAN NOT NULL DEFAULT TRUE,
  CONSTRAINT chk_product_price CHECK (unit_price > 0),
  CONSTRAINT chk_product_synthetic CHECK (synthetic_data = TRUE),
  CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES product_category(category_id),
  INDEX idx_product_category (category_id, product_id)
) ENGINE=InnoDB COMMENT='Synthetic product catalog';

CREATE TABLE sales_order (
  order_id BIGINT UNSIGNED PRIMARY KEY,
  customer_id BIGINT UNSIGNED NOT NULL,
  order_number VARCHAR(32) NOT NULL UNIQUE,
  order_date DATETIME NOT NULL,
  ship_region_code VARCHAR(16) NOT NULL,
  ship_region_name VARCHAR(32) NOT NULL,
  status ENUM('PENDING_PAYMENT','PAYMENT_FAILED','PAID','PARTIALLY_REFUNDED','REFUNDED') NOT NULL,
  total_amount DECIMAL(14,2) NOT NULL,
  synthetic_data BOOLEAN NOT NULL DEFAULT TRUE,
  CONSTRAINT chk_order_total CHECK (total_amount >= 0),
  CONSTRAINT chk_order_synthetic CHECK (synthetic_data = TRUE),
  CONSTRAINT fk_order_customer FOREIGN KEY (customer_id) REFERENCES customer(customer_id),
  INDEX idx_order_date_region (order_date, ship_region_code),
  INDEX idx_order_customer_date (customer_id, order_date),
  INDEX idx_order_region_date (ship_region_code, order_date)
) ENGINE=InnoDB COMMENT='Synthetic ecommerce orders; total_amount equals order-item line totals';

CREATE TABLE order_item (
  order_item_id BIGINT UNSIGNED PRIMARY KEY,
  order_id BIGINT UNSIGNED NOT NULL,
  product_id BIGINT UNSIGNED NOT NULL,
  quantity SMALLINT UNSIGNED NOT NULL,
  unit_price DECIMAL(12,2) NOT NULL,
  line_total DECIMAL(14,2) NOT NULL,
  synthetic_data BOOLEAN NOT NULL DEFAULT TRUE,
  CONSTRAINT chk_item_quantity CHECK (quantity > 0),
  CONSTRAINT chk_item_amount CHECK (unit_price > 0 AND line_total = unit_price * quantity),
  CONSTRAINT chk_item_synthetic CHECK (synthetic_data = TRUE),
  CONSTRAINT fk_item_order FOREIGN KEY (order_id) REFERENCES sales_order(order_id),
  CONSTRAINT fk_item_product FOREIGN KEY (product_id) REFERENCES product(product_id),
  UNIQUE KEY uq_order_item_parent (order_id, order_item_id),
  INDEX idx_item_product_order (product_id, order_id)
) ENGINE=InnoDB COMMENT='Synthetic order lines with price snapshots';

CREATE TABLE payment (
  payment_id BIGINT UNSIGNED PRIMARY KEY,
  order_id BIGINT UNSIGNED NOT NULL UNIQUE,
  payment_reference VARCHAR(40) NOT NULL UNIQUE,
  payment_method ENUM('CARD','WALLET','BANK_TRANSFER') NOT NULL,
  status ENUM('PENDING','FAILED','SUCCEEDED','PARTIALLY_REFUNDED','REFUNDED') NOT NULL,
  amount DECIMAL(14,2) NOT NULL,
  paid_at DATETIME NULL,
  synthetic_data BOOLEAN NOT NULL DEFAULT TRUE,
  CONSTRAINT chk_payment_amount CHECK (amount >= 0),
  CONSTRAINT chk_payment_success_time CHECK ((status IN ('SUCCEEDED','PARTIALLY_REFUNDED','REFUNDED') AND paid_at IS NOT NULL) OR (status IN ('PENDING','FAILED') AND paid_at IS NULL)),
  CONSTRAINT chk_payment_synthetic CHECK (synthetic_data = TRUE),
  CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES sales_order(order_id),
  UNIQUE KEY uq_payment_order_id (order_id, payment_id),
  INDEX idx_payment_status_paid_at (status, paid_at)
) ENGINE=InnoDB COMMENT='One synthetic payment attempt per order';

CREATE TABLE refund (
  refund_id BIGINT UNSIGNED PRIMARY KEY,
  order_id BIGINT UNSIGNED NOT NULL,
  payment_id BIGINT UNSIGNED NOT NULL,
  order_item_id BIGINT UNSIGNED NOT NULL,
  refund_reference VARCHAR(40) NOT NULL UNIQUE,
  status ENUM('COMPLETED') NOT NULL DEFAULT 'COMPLETED',
  quantity SMALLINT UNSIGNED NOT NULL,
  amount DECIMAL(14,2) NOT NULL,
  refunded_at DATETIME NOT NULL,
  synthetic_data BOOLEAN NOT NULL DEFAULT TRUE,
  CONSTRAINT chk_refund_quantity CHECK (quantity > 0),
  CONSTRAINT chk_refund_amount CHECK (amount > 0),
  CONSTRAINT chk_refund_synthetic CHECK (synthetic_data = TRUE),
  CONSTRAINT fk_refund_payment_order FOREIGN KEY (order_id, payment_id) REFERENCES payment(order_id, payment_id),
  CONSTRAINT fk_refund_order_item FOREIGN KEY (order_id, order_item_id) REFERENCES order_item(order_id, order_item_id),
  INDEX idx_refund_item_status (order_item_id, status),
  INDEX idx_refund_date (refunded_at)
) ENGINE=InnoDB COMMENT='Completed synthetic refunds linked to their paid order line';
