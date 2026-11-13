-- Проверка на дублираната 'reconcile' поредица в BG_COA. ЧЕТЕ САМО.
--
--     PGPASSWORD=... psql -h localhost -p 5432 -U nyxa -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-18-bg-coa-duplicate-reconcile-sequence-verify.sql
--
-- PASS — фирмата има точно 1 поредица codeSelect='reconcile'.
-- TODO — фирмата има 2 (или повече), включва '-101' дубликат; пуска се
--        2026-10-18-bg-coa-duplicate-reconcile-sequence-existing-base.sql.

\pset border 2
\pset format aligned

\echo ''
\echo '═══ 1 · RECONCILE ПОРЕДИЦИ ПО ФИРМИ ═══'
SELECT co.code AS фирма, s.id, s.import_id, s.name, s.yearly_reset_ok AS yearly,
       coalesce(sv.next_num, 0) AS next_num
  FROM base_sequence s
  JOIN base_company co ON co.id = s.company
  LEFT JOIN base_sequence_version sv ON sv.sequence = s.id
 WHERE s.code_select = 'reconcile'
 ORDER BY co.code, s.import_id;

\echo ''
\echo '═══ 2 · СТАТУС ПО ФИРМА (очаква се точно 1 ред = PASS) ═══'
SELECT co.code AS фирма, count(*) AS брой_reconcile_поредици,
       CASE WHEN count(*) = 1 THEN 'PASS' ELSE 'TODO' END AS статус
  FROM base_sequence s
  JOIN base_company co ON co.id = s.company
 WHERE s.code_select = 'reconcile'
 GROUP BY co.code
 ORDER BY co.code;

\echo ''
\echo '═══ край ═══'
