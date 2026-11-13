-- Проверка на ДДС 4538/4539 — смисъл в сметкоплана и SAF-T мапинг. ЧЕТЕ САМО.
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-17-bg-coa-vat-4538-4539-verify.sql
--
-- PASS   — целевото състояние (име, commonPosition, А/П).
-- TODO   — старото състояние (празно А/П също се приема за старо, ако име и cp са старите); пуска се 2026-10-17-bg-coa-vat-4538-4539-existing-base.sql.
-- РЪЧНО  — нито старото, нито новото (променено на ръка) — не се пипа от миграцията.

\pset border 2
\pset format aligned

DROP TABLE IF EXISTS pg_temp.bg_vat45_v;
CREATE TEMP TABLE bg_vat45_v (
  code text, old_name text, old_cp int, old_ap text, new_name text, new_cp int, new_ap text);
INSERT INTO bg_vat45_v VALUES
  ('4538', 'ДДС за внасяне в бюджета', 1, '2',
           'Данък върху добавената стойност за възстановяване', 2, '1'),
  ('4539', 'ДДС за възстановяване', 2, '1',
           'Данък върху добавената стойност за внасяне', 1, '2');

\echo ''
\echo '═══ 1 · СМЕТКИ ПО ФИРМИ ═══'
SELECT co.code AS фирма, t.code AS сметка, acc.name AS име, acc.common_position AS cp,
       acc.bg_ap_select AS ap,
       CASE WHEN acc.name = t.new_name AND acc.common_position = t.new_cp
                 AND acc.bg_ap_select IS NOT DISTINCT FROM t.new_ap THEN 'PASS'
            WHEN acc.name = t.old_name AND acc.common_position = t.old_cp
                 AND (acc.bg_ap_select IS NULL OR acc.bg_ap_select = t.old_ap) THEN 'TODO'
            ELSE 'РЪЧНО' END AS статус
  FROM bg_vat45_v t
  JOIN account_account acc ON acc.code = t.code
  JOIN base_company co ON co.id = acc.company
 ORDER BY co.code, t.code;

SELECT to_regclass('public.bglocalization_bg_saf_account_mapping') IS NOT NULL AS has_map \gset

\echo ''
\if :has_map
\echo '═══ 2 · SAF-T МАПИНГ (очаква се IDENTITY) ═══'
SELECT m.account_code AS сметка, m.nra_code AS нап, m.rule_select AS правило,
       CASE WHEN m.nra_code = m.account_code THEN 'PASS'
            WHEN (m.account_code = '4538' AND m.nra_code = '4539')
              OR (m.account_code = '4539' AND m.nra_code = '4538') THEN 'TODO (разменено)'
            ELSE 'ЧУЖДО' END AS статус
  FROM bglocalization_bg_saf_account_mapping m
 WHERE m.account_code IN ('4538', '4539')
 ORDER BY m.account_code;
\else
\echo '═══ 2 · SAF-T таблицата я няма — вдигни приложението ═══'
\endif

\echo ''
\echo '═══ край ═══'
