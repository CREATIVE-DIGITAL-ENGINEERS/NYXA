-- ЧЕТЕ САМО. Състоянието на базата срещу целта на кръг 1 от привеждането към НАП.
--
-- Пуска се ПРЕДИ миграцията (за да се види какво предстои и какво ще я спре) и СЛЕД нея (когато се
-- очакват само PASS). Не пипа нищо и не отваря транзакция за запис.
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-09-30-bg-coa-nra-n1-verify.sql
--
-- Легенда:
--   PASS — вече е както трябва
--   TODO — миграцията ще го направи
--   FAIL — миграцията ще СПРЕ на това; иска решение на човек ПРЕДИ да се пусне

\pset border 2
\pset linestyle unicode

\echo
\echo '═══ 0 · ОБХВАТ: фирмите с BG сметкоплан (опори 4551 и 4552) ═══'
SELECT co.id, coalesce(co.code, '?') AS код, co.name AS фирма,
       (SELECT count(*) FROM account_account a WHERE a.company = co.id) AS сметки
  FROM base_company co
 WHERE EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4551')
   AND EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4552')
 ORDER BY co.id;

\echo
\echo '═══ 1 · ФАЗАТА НА ВСЯКА ФИРМА ═══'
\echo '   Чете се от 409/40901 — единствената двойка, която НЕ участва в каскадата на ДМА.'
SELECT coalesce(co.code, '?') AS фирма,
       EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '409')   AS има_409,
       EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '40901') AS има_40901,
       CASE
         WHEN     EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '409')
              AND NOT EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '40901')
           THEN 'TODO · базата е „преди“ — миграцията ще тръгне'
         WHEN NOT EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '409')
              AND EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '40901')
           THEN 'PASS · базата е „след“'
         ELSE 'FAIL · нито „преди“, нито „след“ — полу-мигрирана или ръчно пипана'
       END AS статус
  FROM base_company co
 WHERE EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4551')
 ORDER BY co.id;

\echo
\echo '═══ 2 · ДЕВЕТТЕ ПРЕНОМЕРИРАНИЯ ═══'
WITH цел(ord, стар, нов, старо_име, ново_име) AS (VALUES
  (1,'203','202',  'Сгради и конструкции',        'Сгради и конструкции'),
  (2,'204','203',  'Компютърна техника',          'Компютърна техника'),
  (3,'205','204',  'Съоръжения',                  'Съоръжения'),
  (4,'206','205',  'Машини и оборудване',         'Машини и оборудване'),
  (5,'207','206',  'Транспортни средства',        'Транспортни средства'),
  (6,'208','207',  'Офис обзавеждане',            'Офис обзавеждане'),
  (7,'153','154',  'Облигационни заеми',          'Дългови инструменти'),
  (8,'409','40901','Ваучери за храна',            'Ваучери за храна'),
  (9,'416','41902','Съмнителни и спорни вземания','Съмнителни и спорни вземания'))
SELECT coalesce(co.code,'?') AS фирма, t.стар, t.нов, t.ново_име,
       CASE
         WHEN EXISTS (SELECT 1 FROM account_account a
                       WHERE a.company = co.id AND a.code = t.нов AND a.name = t.ново_име)
           THEN 'PASS'
         WHEN EXISTS (SELECT 1 FROM account_account a
                       WHERE a.company = co.id AND a.code = t.стар AND a.name = t.старо_име)
           THEN 'TODO'
         ELSE 'FAIL · нито старият с очакваното име, нито новият'
       END AS статус
  FROM base_company co CROSS JOIN цел t
 WHERE EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4551')
 ORDER BY co.id, t.ord;

\echo
\echo '═══ 3 · C050 → view, с двете си деца ═══'
SELECT coalesce(co.code,'?') AS фирма,
       (SELECT ty.technical_type_select FROM account_account a
          JOIN account_account_type ty ON ty.id = a.account_type
         WHERE a.company = co.id AND a.code = 'C050') AS тип,
       (SELECT count(*) FROM account_account k
          JOIN account_account p ON p.id = k.parent_account
         WHERE k.company = co.id AND p.code = 'C050' AND k.code IN ('481','751')) AS деца,
       (SELECT count(*) FROM account_move_line ml
          JOIN account_move m ON m.id = ml.move
          JOIN account_account a ON a.id = ml.account
         WHERE a.company = co.id AND a.code = 'C050' AND m.status_select IN (2,3)) AS осчетоводени,
       CASE
         WHEN (SELECT count(*) FROM account_move_line ml
                 JOIN account_move m ON m.id = ml.move
                 JOIN account_account a ON a.id = ml.account
                WHERE a.company = co.id AND a.code = 'C050' AND m.status_select IN (2,3)) > 0
           THEN 'FAIL · репортен възел с осчетоводени редове — първо реклас към 481/751'
         WHEN (SELECT ty.technical_type_select FROM account_account a
                 JOIN account_account_type ty ON ty.id = a.account_type
                WHERE a.company = co.id AND a.code = 'C050') = 'view' THEN 'PASS'
         ELSE 'TODO'
       END AS статус
  FROM base_company co
 WHERE EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4551')
 ORDER BY co.id;

\echo
\echo '═══ 4 · 124 → Z124 (архивирана, неактивна) ═══'
\echo '   ⚠️ Осчетоводен ред по 124 СПИРА миграцията: данъкът вече минава Дт 123 / Кт 4521 и'
\echo '   разходна сметка няма. Историята иска РЕДОВЕН реклас към 123 със счетоводна статия.'
SELECT coalesce(co.code,'?') AS фирма,
       (SELECT count(*) FROM account_account a WHERE a.company = co.id AND a.code = '124')  AS има_124,
       (SELECT count(*) FROM account_account a WHERE a.company = co.id AND a.code = 'Z124') AS има_Z124,
       (SELECT count(*) FROM account_move_line ml
          JOIN account_move m ON m.id = ml.move
          JOIN account_account a ON a.id = ml.account
         WHERE a.company = co.id AND a.code = '124' AND m.status_select IN (2,3)) AS осчетоводени,
       coalesce((SELECT to_char(sum(ml.debit) - sum(ml.credit), 'FM999999999990.00')
                   FROM account_move_line ml
                   JOIN account_move m ON m.id = ml.move
                   JOIN account_account a ON a.id = ml.account
                  WHERE a.company = co.id AND a.code = '124' AND m.status_select IN (2,3)), '—') AS салдо,
       CASE
         WHEN (SELECT count(*) FROM account_move_line ml
                 JOIN account_move m ON m.id = ml.move
                 JOIN account_account a ON a.id = ml.account
                WHERE a.company = co.id AND a.code = '124' AND m.status_select IN (2,3)) > 0
           THEN 'FAIL · иска ръчен реклас към 123 ПРЕДИ миграцията'
         WHEN EXISTS (SELECT 1 FROM account_account a
                       WHERE a.company = co.id AND a.code = 'Z124'
                         AND a.archived AND a.status_select = 0) THEN 'PASS'
         WHEN EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '124')
           THEN 'TODO'
         ELSE 'FAIL · няма я нито под 124, нито под Z124'
       END AS статус
  FROM base_company co
 WHERE EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4551')
 ORDER BY co.id;

\echo
\echo '═══ 5 · ОСВОБОДЕНИТЕ ЗА НАП КОДОВЕ — трябва да са ПРАЗНИ след миграцията ═══'
\echo '   Кръг 2 пълни всеки от тях с ДРУГА сметка. Зает код значи, че кръг 2 ще пише върху'
\echo '   заварен ред: 124 → „Резултат при несъстоятелност“, 153 → „Кредитни карти“,'
\echo '   208 → „Подобрения на наети помещения“, 409 → „Други задължения към доставчици“,'
\echo '   416 → „Нефактурирани аванси от клиенти“.'
SELECT coalesce(co.code,'?') AS фирма, k.код,
       coalesce((SELECT a.name FROM account_account a
                  WHERE a.company = co.id AND a.code = k.код), '— свободен') AS заето_от,
       CASE WHEN EXISTS (SELECT 1 FROM account_account a
                          WHERE a.company = co.id AND a.code = k.код)
            THEN 'TODO' ELSE 'PASS' END AS статус
  FROM base_company co CROSS JOIN (VALUES ('124'),('153'),('208'),('409'),('416')) AS k(код)
 WHERE EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4551')
 ORDER BY co.id, k.код;

\echo
\echo '═══ 6 · СПРАВКИТЕ: двайсетте преименувани реда ═══'
WITH ред(imp, стар, нов, нова_сметка) AS (VALUES
  ('2096','A_1_2_203','A_1_2_202','202'),   ('2356','MA_1_2_203','MA_1_2_202','202'),
  ('2097','A_1_2_204','A_1_2_203','203'),   ('2357','MA_1_2_204','MA_1_2_203','203'),
  ('2098','A_1_2_205','A_1_2_204','204'),   ('2358','MA_1_2_205','MA_1_2_204','204'),
  ('2099','A_1_2_206','A_1_2_205','205'),   ('2359','MA_1_2_206','MA_1_2_205','205'),
  ('2100','A_1_2_207','A_1_2_206','206'),   ('2360','MA_1_2_207','MA_1_2_206','206'),
  ('2101','A_1_2_208','A_1_2_207','207'),   ('2361','MA_1_2_208','MA_1_2_207','207'),
  ('2115','A_2_2_416','A_2_2_41902','41902'),('2375','MA_2_2_416','MA_2_2_41902','41902'),
  ('2218','P_3_1_153','P_3_1_154','154'),   ('2472','MP_2_1_153','MP_2_1_154','154'),
  ('2573','P_3_3_409','P_3_3_40901','40901'),('2575','MP_3_2_409','MP_3_2_40901','40901'),
  ('2085','E_TAX_124','E_TAX_452','452'),   ('2345','MO_TAX_124','MO_TAX_452','452'))
SELECT coalesce(co.code,'?') AS фирма, count(*) FILTER (WHERE l.code = r.нов
                                                          AND l.account_code = r.нова_сметка) AS готови,
       count(*) FILTER (WHERE l.code = r.стар) AS предстоят,
       count(*) FILTER (WHERE l.id IS NULL)    AS липсват,
       CASE WHEN count(*) FILTER (WHERE l.id IS NULL) > 0 THEN 'FAIL · липсващи редове'
            WHEN count(*) FILTER (WHERE l.code = r.стар) > 0 THEN 'TODO'
            WHEN count(*) FILTER (WHERE l.code = r.нов
                                    AND l.account_code = r.нова_сметка) = 20 THEN 'PASS'
            ELSE 'FAIL · нито старо, нито ново' END AS статус
  FROM base_company co
 CROSS JOIN ред r
  LEFT JOIN account_accounting_report_config_line l
         ON l.import_id = coalesce(co.code,'?') || '-' || r.imp
 WHERE EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4551')
 GROUP BY co.id, co.code
 ORDER BY co.id;

\echo
\echo '═══ 7 · СПРАВКИТЕ: двата реда по 124 в балансите трябва да ГИ НЯМА ═══'
\echo '   R_EXP_124 броеше данъка ВТОРИ ПЪТ: веднъж в R_EXP (сборът на разходите, който храни'
\echo '   R_PL), веднъж през 123 в собствения капитал.'
SELECT coalesce(co.code,'?') AS фирма, d.imp,
       CASE WHEN EXISTS (SELECT 1 FROM account_accounting_report_config_line l
                          WHERE l.import_id = coalesce(co.code,'?') || '-' || d.imp)
            THEN 'TODO' ELSE 'PASS' END AS статус
  FROM base_company co CROSS JOIN (VALUES ('2188'),('2446')) AS d(imp)
 WHERE EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4551')
 ORDER BY co.id, d.imp;

\echo
\echo '═══ 8 · СПРАВКИТЕ: нито едно правило да не сочи ред по освободен код ═══'
SELECT coalesce(co.code,'?') AS фирма, l.code AS ред, tok AS събираем,
       'TODO · кръг 2 ще го пренасочи мълчаливо' AS статус
  FROM base_company co
  JOIN account_accounting_report_config_line l
    ON l.import_id LIKE coalesce(co.code,'?') || '-%'
 CROSS JOIN LATERAL unnest(string_to_array(l.rule, ' + ')) AS tok
 CROSS JOIN LATERAL unnest(ARRAY['124','153','208','409','416']) AS freed
 WHERE EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4551')
   AND l.rule IS NOT NULL AND l.rule <> ''
   AND tok LIKE '%\_' || freed
 ORDER BY co.id, l.code;

\echo
\echo '═══ 9 · СПРАВКИТЕ: ред, който сочи сметка извън сметкоплана (дава тиха нула) ═══'
SELECT coalesce(co.code,'?') AS фирма, l.code AS ред, l.account_code AS сметка, 'FAIL' AS статус
  FROM base_company co
  JOIN account_accounting_report_config_line l
    ON l.import_id LIKE coalesce(co.code,'?') || '-%'
 WHERE EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4551')
   AND l.account_code IS NOT NULL AND l.account_code <> ''
   AND NOT EXISTS (SELECT 1 FROM account_account a
                    WHERE a.company = co.id AND a.code = l.account_code)
 ORDER BY co.id, l.code;

\echo
\echo '═══ 10 · КОДОВЕ, ДОПЪЛНЕНИ С НУЛИ (hasAccountCodeFixedNbrChar) — спират миграцията ═══'
SELECT coalesce(co.code,'?') AS фирма, a.code, a.name, 'FAIL · сверката е по ТОЧЕН код' AS статус
  FROM base_company co
  JOIN account_account a ON a.company = co.id
 WHERE EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4551')
   AND a.code ~ '^(20[3-8]|124|153|409|416)0+$'
 ORDER BY co.id, a.code;

\echo
\echo '═══ 11 · КОНТРОЛНА ГРУПА — НЕ трябва да се е променило нищо ═══'
\echo '   4091 „Задържани плащания“ е съседът, който подниз-замяна би отнесла заедно с 409.'
\echo '   452/123 са сметките, към които данъкът се пренасочва — те самите не се пипат.'
SELECT coalesce(co.code,'?') AS фирма, a.code, a.name,
       (SELECT ty.technical_type_select FROM account_account_type ty WHERE ty.id = a.account_type) AS тип
  FROM base_company co
  JOIN account_account a ON a.company = co.id
 WHERE EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4551')
   AND a.code IN ('4091','452','123','201','241','481','751')
 ORDER BY co.id, a.code;

\echo
\echo '═══ 12 · P_3_3_4091 срещу P_3_3_40901 — префикс-капанът ═══'
\echo '   Двата реда трябва да съществуват ПОРАЗДЕЛНО и двата да са в правилото на подсбора.'
SELECT coalesce(co.code,'?') AS фирма, l.code AS подсбор,
       (l.rule LIKE '%P\_3\_3\_4091 %' OR l.rule LIKE '%P\_3\_3\_4091')     AS има_4091,
       (l.rule LIKE '%P\_3\_3\_40901 %' OR l.rule LIKE '%P\_3\_3\_40901')   AS има_40901,
       (l.rule LIKE '%\_409011%')                                          AS повреден
  FROM base_company co
  JOIN account_accounting_report_config_line l
    ON l.import_id LIKE coalesce(co.code,'?') || '-%'
 WHERE EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4551')
   AND l.code = 'P_ST3_3'
 ORDER BY co.id;

\echo
\echo '── край на проверката ──'
