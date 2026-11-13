-- Проверка: 691 с името по NRA_Nom_Accounts („Разходи от отписване на кредитни експозиции“). ЧЕТЕ САМО.
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-08-bg-coa-691-name-verify.sql
--
-- PASS — името и label-ът са целевите · TODO — старото име или остарял label · РЪЧНО — друго име.
-- Секция 2 (ИНФО): категории ДМА, които още сочат 691 — трябва да е празна след …-691-assets.

\pset border 2
\pset format aligned

\echo ''
\echo '═══ 1 · ИМЕТО НА 691 ═══'
SELECT co.code AS фирма, a.name AS име_сега, coalesce(a.label, 'NULL') AS label_сега,
       CASE WHEN a.name = 'Разходи от отписване на кредитни експозиции'
             AND a.label = '691_' || coalesce(co.code, 'null') || ' - ' || a.name THEN 'PASS'
            WHEN a.name IN ('Извънредни разходи', 'Разходи от отписване на кредитни експозиции')
              THEN 'TODO'
            ELSE 'РЪЧНО' END AS статус
  FROM base_company co
  JOIN account_account a ON a.company = co.id AND a.code = '691'
 WHERE EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4551')
   AND EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4552')
 ORDER BY co.id;

\echo ''
\echo '═══ 2 · ИНФО: категории ДМА, които още сочат 691 (пусни първо …-691-assets) ═══'
SELECT co.code AS фирма, count(*) AS категории
  FROM account_fixed_asset_category f
  JOIN base_company co ON co.id = f.company
  JOIN account_account a ON a.id = f.appro_extraord_depreciation_expense_account AND a.code = '691'
 WHERE EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4551')
   AND EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4552')
 GROUP BY co.id, co.code ORDER BY co.id;

\echo ''
\echo '═══ край ═══'
