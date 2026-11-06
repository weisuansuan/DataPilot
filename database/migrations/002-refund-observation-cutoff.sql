-- Apply only to an existing v1 local datapilot schema whose metadata table lacks this column.
ALTER TABLE datapilot.dataset_metadata
  ADD COLUMN refund_observation_cutoff DATE NOT NULL DEFAULT '2026-11-06' AFTER seed_value;

UPDATE datapilot.dataset_metadata
SET refund_observation_cutoff = '2026-11-06'
WHERE dataset_code = 'SYNTHETIC_ECOMMERCE_V1';
