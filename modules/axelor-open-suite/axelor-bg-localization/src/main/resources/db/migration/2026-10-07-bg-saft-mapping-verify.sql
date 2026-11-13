-- Проверка на 10-те изрични реда в SAF-T мапинга. ЧЕТЕ САМО (пише единствено временна таблица на
-- сесията).
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-07-bg-saft-mapping-verify.sql
--
-- ЧЕТЕНЕ НА ИЗХОДА:
--   PASS    — целевият НАП код (и full_name по него).
--   TODO    — старият код. Първият boot с новия код го сменя сам (DataInitSync); резервът е
--             2026-10-07-bg-saft-mapping-existing-base.sql.
--   ЧУЖДО   — нито старият, нито новият — някой го е сменил на ръка. ⚠️ DataInitSync ще го
--             ПРЕЗАПИШЕ със seed-а при следващия boot.
--   ЛИПСВА  — няма ред; DataInitSync го внася.
--   FAIL    — целевият код, но full_name е стар.

\pset border 2
\pset format aligned

DROP TABLE IF EXISTS pg_temp.bg_saftmap_v;
CREATE TEMP TABLE bg_saftmap_v (code text, old_nra text, new_nra text);
INSERT INTO bg_saftmap_v VALUES
  ('6044', '604', '60901'), ('1222', '122', '121'),
  ('4551', '455', '461'), ('4553', '455', '461'), ('4554', '455', '461'),
  ('4555', '455', '461'), ('4556', '455', '461'), ('4552', '455', '463'),
  ('4621', '462', '498'), ('4622', '462', '499');

\echo ''
\echo '═══ 0 · ТАБЛИЦАТА (идва от boot-а, НЕ от миграцията) ═══'
SELECT CASE WHEN to_regclass('public.bglocalization_bg_saf_account_mapping') IS NULL
            THEN 'TODO (няма таблица — вдигни приложението)' ELSE 'PASS' END AS статус;
SELECT to_regclass('public.bglocalization_bg_saf_account_mapping') IS NOT NULL AS has_map \gset

\echo ''
\if :has_map
\echo '═══ 1 · 10-ТЕ ИЗРИЧНИ РЕДА ═══'
SELECT t.code AS сметка, t.old_nra AS беше, t.new_nra AS цел,
       coalesce(m.nra_code, '—') AS сега, coalesce(m.rule_select, '—') AS правило,
       CASE WHEN m.id IS NULL THEN 'ЛИПСВА'
            WHEN m.nra_code = t.new_nra AND m.full_name = t.code || ' → ' || t.new_nra
              THEN 'PASS'
            WHEN m.nra_code = t.new_nra THEN 'FAIL (full_name: ' || coalesce(m.full_name, 'NULL') || ')'
            WHEN m.nra_code = t.old_nra THEN 'TODO'
            ELSE 'ЧУЖДО' END AS статус
  FROM bg_saftmap_v t
  LEFT JOIN bglocalization_bg_saf_account_mapping m ON m.account_code = t.code
 ORDER BY t.code;
\endif

\echo ''
\echo '═══ край ═══'
