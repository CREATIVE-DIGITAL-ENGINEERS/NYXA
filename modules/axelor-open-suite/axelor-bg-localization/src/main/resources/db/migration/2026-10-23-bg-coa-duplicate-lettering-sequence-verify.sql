-- Проверка на дублираните поредици за буквено маркиране в BG_COA. ЧЕТЕ САМО.
--
--     PGPASSWORD=... psql -h localhost -p 5432 -U nyxa -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-23-bg-coa-duplicate-lettering-sequence-verify.sql
--
-- PASS — фирмата има точно по 1 поредица reconcileGroupDraft и reconcileGroupFinal, с РАЗЛИЧНИ азбуки.
-- TODO — има дубликат ('-102'/'-103') или двата кода делят една азбука; пуска се
--        2026-10-23-bg-coa-duplicate-lettering-sequence-existing-base.sql.

\pset border 2
\pset format aligned

\echo ''
\echo '═══ 1 · ПОРЕДИЦИ ЗА БУКВЕНО МАРКИРАНЕ ПО ФИРМИ ═══'
SELECT co.code AS фирма, s.id, s.import_id, s.code_select, s.name,
       s.sequence_letters_type_select AS регистър, coalesce(sv.next_num, 0) AS next_num,
       CASE WHEN coalesce(sv.next_num, 0) > 1 THEN 'ИЗПОЛЗВАНА' ELSE '' END AS ползвана
  FROM base_sequence s
  JOIN base_company co ON co.id = s.company
  LEFT JOIN base_sequence_version sv ON sv.sequence = s.id
 WHERE s.code_select IN ('reconcileGroupDraft', 'reconcileGroupFinal')
 ORDER BY co.code, s.code_select, s.import_id;

\echo ''
\echo '═══ 2 · СТАТУС ПО ФИРМА И КОД (очаква се брой=1 за всеки ред) ═══'
SELECT co.code AS фирма, s.code_select, count(*) AS брой,
       CASE WHEN count(*) = 1 THEN 'PASS' ELSE 'TODO' END AS статус
  FROM base_sequence s
  JOIN base_company co ON co.id = s.company
 WHERE s.code_select IN ('reconcileGroupDraft', 'reconcileGroupFinal')
 GROUP BY co.code, s.code_select
 ORDER BY co.code, s.code_select;

\echo ''
\echo '═══ 3 · ДЕЛЯТ ЛИ ЧЕРНОВАТА И ФИНАЛНАТА ЕДНА АЗБУКА (очаква се празен резултат) ═══'
SELECT co.code AS фирма, d.import_id AS чернова, f.import_id AS финална,
       d.sequence_letters_type_select AS общ_регистър
  FROM base_sequence d
  JOIN base_sequence f ON f.company = d.company AND f.code_select = 'reconcileGroupFinal'
  JOIN base_company co ON co.id = d.company
 WHERE d.code_select = 'reconcileGroupDraft'
   AND d.sequence_letters_type_select = f.sequence_letters_type_select
 ORDER BY co.code;

\echo ''
\echo '═══ 4 · ВЕЧЕ РАЗДАДЕНИ БУКВЕНИ КОДОВЕ (историята, която не се пипа) ═══'
SELECT co.code AS фирма, g.id, g.code, g.status_select AS статус,
       (SELECT count(*) FROM account_reconcile r WHERE r.reconcile_group = g.id) AS равнения
  FROM account_reconcile_group g
  JOIN base_company co ON co.id = g.company
 ORDER BY co.code, g.id;

\echo ''
\echo '═══ край ═══'
