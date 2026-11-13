-- Проверка на 13-те реда на PR C в SAF-T мапинга. ЧЕТЕ САМО (пише единствено временна таблица на
-- сесията).
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-08-bg-saft-remap-verify.sql
--
-- ЧЕТЕНЕ НА ИЗХОДА:
--   PASS    — целевият НАП код (и full_name по него).
--   TODO    — старият код. Първият boot с новия код го сменя сам (DataInitSync); резервът е
--             2026-10-08-bg-saft-remap-existing-base.sql.
--   ЧУЖДО   — нито старият, нито новият — някой го е сменил на ръка. ⚠️ DataInitSync ще го
--             ПРЕЗАПИШЕ със seed-а при следващия boot.
--   ЛИПСВА  — няма ред; DataInitSync го внася.
--   FAIL    — целевият код, но full_name е стар.

\pset border 2
\pset format aligned

DROP TABLE IF EXISTS pg_temp.bg_saftremap_v;
CREATE TEMP TABLE bg_saftremap_v (code text, old_nra text, new_nra text);
INSERT INTO bg_saftremap_v VALUES
  ('408', '408', '409'),
  ('456', '456', '469'),
  ('481', '481', '483'),
  ('103', '103', '101'),
  ('455', '455', '461'),
  ('615', '615', '614'),
  ('622', '622', '629'),
  ('692', '692', '60909'),
  ('792', '792', '709'),
  ('722', '722', '721'),
  ('723', '723', '722'),
  ('725', '725', '723'),
  ('462', '462', '499');

\echo ''
\echo '═══ 0 · ТАБЛИЦАТА (идва от boot-а, НЕ от миграцията) ═══'
SELECT CASE WHEN to_regclass('public.bglocalization_bg_saf_account_mapping') IS NULL
            THEN 'TODO (няма таблица — вдигни приложението)' ELSE 'PASS' END AS статус;
SELECT to_regclass('public.bglocalization_bg_saf_account_mapping') IS NOT NULL AS has_map \gset

\echo ''
\if :has_map
\echo '═══ 1 · 13-ТЕ РЕДА НА PR C ═══'
SELECT t.code AS сметка, t.old_nra AS беше, t.new_nra AS цел,
       coalesce(m.nra_code, '—') AS сега, coalesce(m.rule_select, '—') AS правило,
       CASE WHEN m.id IS NULL THEN 'ЛИПСВА'
            WHEN m.nra_code = t.new_nra AND m.full_name = t.code || ' → ' || t.new_nra
              THEN 'PASS'
            WHEN m.nra_code = t.new_nra THEN 'FAIL (full_name: ' || coalesce(m.full_name, 'NULL') || ')'
            WHEN m.nra_code = t.old_nra THEN 'TODO'
            ELSE 'ЧУЖДО' END AS статус
  FROM bg_saftremap_v t
  LEFT JOIN bglocalization_bg_saf_account_mapping m ON m.account_code = t.code
 ORDER BY t.code;
\endif

\echo ''
\echo '═══ край ═══'
