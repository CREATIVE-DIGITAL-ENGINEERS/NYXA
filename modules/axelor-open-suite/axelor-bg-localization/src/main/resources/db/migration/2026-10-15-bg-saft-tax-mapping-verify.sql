-- Проверка на четирите реда на G6a в BgSafTaxMapping. ЧЕТЕ САМО (пише единствено временна таблица
-- на сесията).
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-15-bg-saft-tax-mapping-verify.sql
--
-- ЧЕТЕНЕ НА ИЗХОДА:
--   PASS    — целевият код, динамичност и full_name.
--   TODO    — старият код. Първият boot с новия код го сменя сам (DataInitSync, ако
--             datainit.sync.modules съдържа axelor-bg-localization); резервът е
--             2026-10-15-bg-saft-tax-mapping-existing-base.sql.
--   ЧУЖДО   — нито старият, нито новият — сменен на ръка. ⚠️ DataInitSync ще го ПРЕЗАПИШЕ.
--   ЛИПСВА  — няма ред; DataInitSync го внася.
--   FAIL    — целевият код, но динамичност или full_name са стари.

\pset border 2
\pset format aligned

DROP TABLE IF EXISTS pg_temp.bg_safttax_v;
CREATE TEMP TABLE bg_safttax_v (tax text, old_code text, new_code text, new_dynamic boolean);
INSERT INTO bg_safttax_v VALUES
  ('BG_ZRS', NULL, '100214', false),
  ('BG_OSS_EU', NULL, '100218', false),
  ('BG_MOSS', NULL, '100218', false),
  ('BG_ZRP', '100331', '100330', false);

\echo ''
\echo '═══ 0 · ТАБЛИЦАТА (идва от boot-а, НЕ от миграцията) ═══'
SELECT CASE WHEN to_regclass('public.bglocalization_bg_saf_tax_mapping') IS NULL
            THEN 'TODO (няма таблица — вдигни приложението)' ELSE 'PASS' END AS статус;
SELECT to_regclass('public.bglocalization_bg_saf_tax_mapping') IS NOT NULL AS has_map \gset

\echo ''
\if :has_map
\echo '═══ 1 · ЧЕТИРИТЕ РЕДА НА G6a ═══'
SELECT t.tax AS данък, coalesce(t.old_code, 'празно') AS беше, t.new_code AS цел,
       coalesce(nullif(trim(m.saf_tax_code), ''), 'празно') AS сега,
       coalesce(m.is_dynamic::text, '—') AS динамичен,
       CASE WHEN m.id IS NULL THEN 'ЛИПСВА'
            WHEN nullif(trim(m.saf_tax_code), '') = t.new_code
                 AND coalesce(m.is_dynamic, false) = t.new_dynamic
                 AND m.full_name = t.tax || ' → ' || t.new_code THEN 'PASS'
            WHEN nullif(trim(m.saf_tax_code), '') = t.new_code THEN 'FAIL'
            WHEN nullif(trim(m.saf_tax_code), '') IS NOT DISTINCT FROM t.old_code THEN 'TODO'
            ELSE 'ЧУЖДО' END AS статус
  FROM bg_safttax_v t
  LEFT JOIN bglocalization_bg_saf_tax_mapping m ON m.tax_code = t.tax
 ORDER BY t.tax;

\echo ''
\echo '═══ 2 · ПРАЗНИТЕ КОДОВЕ — трябва да са точно седемте „да се потвърди“ ═══'
SELECT m.tax_code AS данък
  FROM bglocalization_bg_saf_tax_mapping m
 WHERE nullif(trim(m.saf_tax_code), '') IS NULL
 ORDER BY m.tax_code;
\endif

\echo ''
\echo '═══ край ═══'
