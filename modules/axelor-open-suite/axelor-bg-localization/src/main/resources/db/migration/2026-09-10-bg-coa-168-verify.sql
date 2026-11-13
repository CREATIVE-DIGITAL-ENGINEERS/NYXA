-- BG_COA / 168 — ПРОВЕРКА НА СЪСТОЯНИЕТО. Read-only, нищо не пише.
--
-- Пуска се ДВА ПЪТИ: преди 2026-09-10-bg-coa-168-existing-base.sql, за да се види какво предстои и
-- какво ще спре; и след него, за да се види, че е конвергирало.
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-09-10-bg-coa-168-verify.sql
--
-- Целевото състояние е дословно от `l10n/l10n_bgr/BG_COA/account_account.csv` (develop). Двата
-- файла държат ЕДИН И СЪЩИ списък — при промяна на плана се пипат ЗАЕДНО.
--
-- ⚠️ Обвито в транзакция с ROLLBACK. Не защото пише, а за да е невъзможно да пише.

BEGIN;

\pset pager off
\pset null '·'

\echo ''
\echo '═══════════════════════════════════════════════════════════════════════════════════════════'
\echo ' 1 · ОБХВАТ — фирми с BG плана (опори: кодове 4551 И 4552, срещат се само в BG_COA)'
\echo '═══════════════════════════════════════════════════════════════════════════════════════════'

SELECT co.id                                            AS "фирма id",
       coalesce(co.code, '·')                           AS "код",
       co.name                                          AS "име",
       (SELECT count(*) FROM account_account a
         WHERE a.company = co.id)                       AS "сметки",
       (SELECT count(*) FROM account_account a
         WHERE a.company = co.id AND a.parent_account IS NOT NULL)
                                                        AS "с родител",
       CASE WHEN (SELECT count(*) FROM account_account a
                   WHERE a.company = co.id AND a.parent_account IS NOT NULL) > 0
            THEN 'бутонът ОТКАЗВА (ACCOUNT_CHART_3)'
            ELSE 'бутонът би минал'
       END                                              AS "зареждане на плана"
  FROM base_company co
 WHERE EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4551')
   AND EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4552')
 ORDER BY co.id;

\echo ''
\echo '═══════════════════════════════════════════════════════════════════════════════════════════'
\echo ' 2 · ДЕЛТАТА ПО 168 — ред по ред, срещу целта от account_account.csv'
\echo '     PASS = вече е в целта · TODO = миграцията ще го направи · FAIL = иска ЧОВЕК'
\echo '═══════════════════════════════════════════════════════════════════════════════════════════'

WITH scope AS (
  SELECT co.id AS cid, coalesce(co.code, '?') AS ccode
    FROM base_company co
   WHERE EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4551')
     AND EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4552')
),
target(op, code, old_name, new_name) AS (
  VALUES
    ('ADD',    '409',  NULL, 'Ваучери за храна'),
    ('ADD',    '6094', NULL, 'Социални разходи (СБКО)'),
    ('RENAME', '4553', 'Разчети за ДЗПО – универсален пенсионен фонд',
                       'Разчети за ДЗПО – универсален и професионален пенсионен фонд'),
    ('RENAME', '4554', 'Разчети за ДЗПО – професионален пенсионен фонд',
                       'Разчети за фонд "Безработица"'),
    ('RENAME', '4555', 'Разчети за фонд "Безработица"',
                       'Разчети за фонд "Трудова злополука и професионална болест"'),
    ('RENAME', '4556', 'Разчети за фонд "Трудова злополука и професионална болест"',
                       'Разчети за фонд "Гарантирани вземания на работниците"'),
    ('RETIRE', '4557', 'Разчети за фонд "Гарантирани вземания на работниците"', NULL)
),
state AS (
  SELECT s.ccode, t.op, t.code, t.old_name, t.new_name,
         aa.id AS acc_id, aa.name AS cur_name,
         coalesce(aa.archived, false) AS arch,
         coalesce(aa.status_select, 0) AS st,
         (aa.id IS NOT NULL) AS present,
         coalesce((SELECT count(*) FROM account_move_line ml
                     JOIN account_move m ON m.id = ml.move
                    WHERE ml.account = aa.id AND m.status_select IN (2, 3)), 0) AS posted,
         coalesce((SELECT sum(ml.debit) - sum(ml.credit) FROM account_move_line ml
                     JOIN account_move m ON m.id = ml.move
                    WHERE ml.account = aa.id AND m.status_select IN (2, 3)), 0) AS balance
    FROM scope s
    CROSS JOIN target t
    LEFT JOIN account_account aa ON aa.company = s.cid AND aa.code = t.code
)
SELECT ccode        AS "фирма",
       op           AS "операция",
       code         AS "код",
       CASE WHEN present THEN cur_name ELSE '· няма такъв ред ·' END AS "име в базата",
       posted       AS "осч. редове",
       to_char(balance, 'FM999999990.00') AS "салдо Dt−Kt",
       CASE
         -- ADD
         WHEN op = 'ADD' AND present AND cur_name = new_name AND NOT arch AND st = 1
              THEN 'PASS'
         WHEN op = 'ADD' AND present AND cur_name = new_name
              THEN 'FAIL · съществува, но е неактивна/архивирана'
         WHEN op = 'ADD' AND present
              THEN 'FAIL · кодът е зает от ДРУГО име'
         WHEN op = 'ADD'
              THEN 'TODO · ще се добави'
         -- RENAME
         WHEN op = 'RENAME' AND NOT present
              THEN 'FAIL · сметката липсва изобщо'
         WHEN op = 'RENAME' AND cur_name = new_name
              THEN 'PASS'
         WHEN op = 'RENAME' AND cur_name = old_name AND posted > 0
              THEN 'FAIL · НОСИ ИСТОРИЯ — спира за човек'
         WHEN op = 'RENAME' AND cur_name = old_name
              THEN 'TODO · ще се преименува'
         WHEN op = 'RENAME'
              THEN 'FAIL · име, което не е нито заварното, нито целевото'
         -- RETIRE
         WHEN op = 'RETIRE' AND NOT present
              THEN 'PASS · няма я'
         WHEN op = 'RETIRE' AND arch AND st = 0
              THEN 'PASS · архивирана и неактивна'
         WHEN op = 'RETIRE' AND balance <> 0
              THEN 'FAIL · НЕНУЛЕВО САЛДО — първо реклас към 4556'
         WHEN op = 'RETIRE'
              THEN 'TODO · ще се архивира'
       END          AS "присъда",
       CASE WHEN present THEN format('archived=%s status=%s', arch, st) ELSE '·' END AS "флагове"
  FROM state
 ORDER BY ccode, CASE op WHEN 'ADD' THEN 1 WHEN 'RENAME' THEN 2 ELSE 3 END, code;

\echo ''
\echo '═══════════════════════════════════════════════════════════════════════════════════════════'
\echo ' 3 · НЕПИПАНИТЕ 605x — контролната група. 168 НЕ ги мени; тук се доказва, че са цели.'
\echo '═══════════════════════════════════════════════════════════════════════════════════════════'

WITH scope AS (
  SELECT co.id AS cid, coalesce(co.code, '?') AS ccode
    FROM base_company co
   WHERE EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4551')
     AND EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4552')
),
expect(code, name) AS (
  VALUES
    ('6051', 'Разходи за ДОО – за сметка на работодателя'),
    ('6052', 'Разходи за здравно осигуряване – за сметка на работодателя'),
    ('6053', 'Разходи за ДЗПО – за сметка на работодателя'),
    ('6054', 'Разходи за фонд "Безработица" – за сметка на работодателя'),
    ('6055', 'Разходи за фонд "Трудова злополука"'),
    ('6056', 'Разходи за фонд "Гарантирани вземания"')
)
SELECT s.ccode AS "фирма", e.code AS "код",
       coalesce(aa.name, '· няма такъв ред ·') AS "име в базата",
       CASE WHEN aa.id IS NULL      THEN 'FAIL · липсва'
            WHEN aa.name = e.name   THEN 'PASS'
            ELSE                         'FAIL · разминаване с CSV — огледалото 605x/455x е счупено'
       END AS "присъда"
  FROM scope s CROSS JOIN expect e
  LEFT JOIN account_account aa ON aa.company = s.cid AND aa.code = e.code
 ORDER BY s.ccode, e.code;

\echo ''
\echo '═══════════════════════════════════════════════════════════════════════════════════════════'
\echo ' 4 · КОЙ СОЧИ 4557 — динамично, по всички чужди ключове към account_account'
\echo '     Празен резултат = нула препратки. Редовете тук НЕ спират миграцията (тя архивира,'
\echo '     не трие) — те казват какво ще остане да сочи архивирана сметка.'
\echo '═══════════════════════════════════════════════════════════════════════════════════════════'

DO $refs$
DECLARE
  fk    record;
  ids   bigint[];
  v_cnt bigint;
  found boolean := false;
BEGIN
  SELECT array_agg(id) INTO ids FROM account_account WHERE code = '4557';
  IF ids IS NULL THEN
    RAISE NOTICE '  4557 не съществува в тази база — няма какво да я сочи.';
    RETURN;
  END IF;
  FOR fk IN
    SELECT con.conrelid::regclass::text AS tbl, att.attname AS col
      FROM pg_constraint con
      CROSS JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)
      JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = k.attnum
     WHERE con.contype = 'f' AND con.confrelid = 'public.account_account'::regclass
     ORDER BY 1, 2
  LOOP
    EXECUTE format('SELECT count(*) FROM %s WHERE %I = ANY($1)', fk.tbl, fk.col)
       INTO v_cnt USING ids;
    IF v_cnt > 0 THEN
      RAISE NOTICE '  %.% → % реда', fk.tbl, fk.col, v_cnt;
      found := true;
    END IF;
  END LOOP;
  IF NOT found THEN
    RAISE NOTICE '  нула препратки по всички % чужди ключа към account_account',
      (SELECT count(*) FROM pg_constraint WHERE contype = 'f'
        AND confrelid = 'public.account_account'::regclass);
  END IF;
END
$refs$;

\echo ''
\echo '═══════════════════════════════════════════════════════════════════════════════════════════'
\echo ' 5 · ЗДРАВЕ НА СЕКВЕНЦИЯТА — новите редове вземат id оттам'
\echo '═══════════════════════════════════════════════════════════════════════════════════════════'

SELECT coalesce(pg_sequence_last_value('public.account_account_seq'::regclass), 0) AS "секвенция",
       (SELECT coalesce(max(id), 0) FROM account_account)                          AS "max(id)",
       CASE WHEN coalesce(pg_sequence_last_value('public.account_account_seq'::regclass), 0)
                 >= (SELECT coalesce(max(id), 0) FROM account_account)
            THEN 'PASS'
            ELSE 'FAIL · setval(''public.account_account_seq'','
                 ' (SELECT max(id) FROM account_account))'
       END                                                                          AS "присъда";

\echo ''
\echo '═══════════════════════════════════════════════════════════════════════════════════════════'
\echo ' 6 · ДОПЪЛНЕНИ С НУЛИ КОДОВЕ — миграцията сверява по ТОЧЕН код и на такава база би мълчала'
\echo '═══════════════════════════════════════════════════════════════════════════════════════════'

SELECT coalesce(co.code, '?') AS "фирма", aa.code AS "код", aa.name AS "име"
  FROM account_account aa
  JOIN base_company co ON co.id = aa.company
 WHERE aa.code ~ '^(455[1-7]|6094|409)0+$'
 ORDER BY 1, 2;

ROLLBACK;
