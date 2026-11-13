-- Проверка: брак и липса на ДМА НЕ на 691. ЧЕТЕ САМО.
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-08-bg-coa-691-assets-verify.sql
--
-- ЧЕТЕНЕ НА ИЗХОДА:
--   PASS   — категорията сочи 60905 / причината носи 60908.
--   TODO   — категорията сочи 691 / причината е без код; миграцията (или boot-ът за причините) го сменя.
--   ЧУЖДО  — друга сметка или код; не се пипа. ⚠️ За причините DataInitSync ще ПРЕЗАПИШЕ ръчна
--            стойност със seed-а при следващия boot.
--   ИНФО   — (секция 3) осчетоводено по 691: заварените отписвания остават там, статии не се местят.

\pset border 2
\pset format aligned

\echo ''
\echo '═══ 1 · КАТЕГОРИИТЕ ДМА: approExtraordDepreciationExpenseAccount (опори 4551 и 4552) ═══'
SELECT co.code AS фирма, coalesce(a.code, 'NULL') AS сметка, count(*) AS категории,
       CASE WHEN a.code = '60905' THEN 'PASS'
            WHEN a.code = '691' AND EXISTS (SELECT 1 FROM account_account s
                                             WHERE s.company = co.id AND s.code = '60905')
              THEN 'TODO'
            ELSE 'ЧУЖДО' END AS статус
  FROM account_fixed_asset_category f
  JOIN base_company co ON co.id = f.company
  LEFT JOIN account_account a ON a.id = f.appro_extraord_depreciation_expense_account
 WHERE EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4551')
   AND EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4552')
 GROUP BY co.id, co.code, a.code ORDER BY co.id, a.code;

\echo ''
\echo '═══ 2 · ПРИЧИНИТЕ: унищожен актив → 60908 (глобални, идват от seed-а при boot) ═══'
SELECT t.code AS причина, coalesce(d.bg_removal_account_code, '—') AS сметка,
       CASE WHEN d.id IS NULL THEN 'ЛИПСВА (boot я внася)'
            WHEN d.bg_removal_account_code = '60908' THEN 'PASS'
            WHEN coalesce(btrim(d.bg_removal_account_code), '') = '' THEN 'TODO'
            ELSE 'ЧУЖДО' END AS статус
  FROM (VALUES ('BG_SCRAP'), ('BG_FORCE')) t(code)
  LEFT JOIN account_asset_disposal_reason d ON d.code = t.code
 ORDER BY t.code;

\echo ''
\echo '═══ 3 · ИНФО: осчетоводено по 691 (заварени отписвания — НЕ се местят) ═══'
SELECT co.code AS фирма, count(ml.id) AS редове,
       to_char(coalesce(sum(ml.debit - ml.credit), 0), 'FM999999999990.00') AS салдо_dt_kt,
       min(extract(year FROM m.date_val))::int AS от, max(extract(year FROM m.date_val))::int AS до
  FROM base_company co
  JOIN account_account a ON a.company = co.id AND a.code = '691'
  JOIN account_move_line ml ON ml.account = a.id
  JOIN account_move m ON m.id = ml.move AND m.status_select IN (2, 3)
 WHERE EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4551')
   AND EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4552')
 GROUP BY co.id, co.code ORDER BY co.id;

\echo ''
\echo '═══ край ═══'
