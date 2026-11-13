-- The Microinvest accounting archive, parallel fixed-asset register and settlement audit were
-- removed after the import was redirected to the standard Axelor accounting and asset models.
-- Run this migration manually after deploying that model change. It permanently removes the old
-- parallel records. DROP TABLE IF EXISTS makes re-running the migration harmless.

BEGIN;

-- Child tables must be removed before their referenced parent tables.
DROP TABLE IF EXISTS bglocalization_microinvest_asset_plan_line;
DROP TABLE IF EXISTS bglocalization_microinvest_fixed_asset;

DROP TABLE IF EXISTS bglocalization_microinvest_archive_line;
DROP TABLE IF EXISTS bglocalization_microinvest_archive_entry;

DROP TABLE IF EXISTS bglocalization_microinvest_archive_opening_balance;
DROP TABLE IF EXISTS bglocalization_microinvest_settlement_audit;
DROP TABLE IF EXISTS bglocalization_microinvest_accounting_import_run;

COMMIT;
