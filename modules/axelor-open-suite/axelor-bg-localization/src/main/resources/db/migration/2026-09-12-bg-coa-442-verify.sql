-- Сметка 442 „Вземания по липси и начети“ — READ-ONLY проверка. Нищо не се записва.
--
-- Пуска се ДВА пъти: преди миграцията (за да се види какво предстои и какво би я спряло) и след
-- нея (за да се види, че всичко е PASS). Изходът е по ред:
--
--     PASS  · състоянието е целевото
--     TODO  · липсва, но миграцията ще го добави — нормално ПРЕДИ пускането
--     FAIL  · състояние, което миграцията ще откаже. Иска решение от човек.
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-09-12-bg-coa-442-verify.sql
--
-- ⚠️ ФАЙЛЪТ НЕ РЕШАВА НИЩО. Той чете. Единственото, което променя, е представата на човека какво
-- ще стане — затова се чете ПРЕДИ да се пусне миграцията, а не след това.

\pset pager off

DO $verify$
DECLARE
  c          record;
  rp         record;
  v_cnt      bigint;
  v_name     text;
  v_type     text;
  v_parent   text;
  v_status   integer;
  v_arch     boolean;
  v_rule     text;
  v_id       bigint;
  v_acc      bigint;
  v_line     bigint;
  v_max_id   bigint;
  v_seq_last bigint;
  n_pass     integer := 0;
  n_todo     integer := 0;
  n_fail     integer := 0;
  companies  integer := 0;
BEGIN
  RAISE NOTICE '─────────────────────────────────────────────────────────────────────────────';
  RAISE NOTICE 'BG_COA/442 проверка · база=% · потребител=%', current_database(), current_user;
  RAISE NOTICE '─────────────────────────────────────────────────────────────────────────────';

  -- ═══ § 1 · СЕКВЕНЦИИТЕ ═══════════════════════════════════════════════════════════════════════
  SELECT coalesce(max(id), 0) INTO v_max_id FROM account_account;
  v_seq_last := coalesce(pg_sequence_last_value('public.account_account_seq'::regclass), 0);
  IF v_seq_last < v_max_id THEN
    n_fail := n_fail + 1;
    RAISE NOTICE 'FAIL  § 1 account_account_seq е на %, а max(id) е % — nextval() би върнал заето'
      ' id. Поправка: SELECT setval(''public.account_account_seq'', (SELECT max(id) FROM'
      ' account_account));', v_seq_last, v_max_id;
  ELSE
    n_pass := n_pass + 1;
    RAISE NOTICE 'PASS  § 1 account_account_seq е пред max(id) (% >= %)', v_seq_last, v_max_id;
  END IF;

  SELECT coalesce(max(id), 0) INTO v_max_id FROM account_accounting_report_config_line;
  v_seq_last := coalesce(
    pg_sequence_last_value('public.account_accounting_report_config_line_seq'::regclass), 0);
  IF v_seq_last < v_max_id THEN
    n_fail := n_fail + 1;
    RAISE NOTICE 'FAIL  § 1 account_accounting_report_config_line_seq е на %, а max(id) е %',
      v_seq_last, v_max_id;
  ELSE
    n_pass := n_pass + 1;
    RAISE NOTICE 'PASS  § 1 account_accounting_report_config_line_seq е пред max(id) (% >= %)',
      v_seq_last, v_max_id;
  END IF;

  -- ═══ § 2 · ПО ФИРМА ══════════════════════════════════════════════════════════════════════════
  FOR c IN
    SELECT co.id AS cid, coalesce(co.code, '?') AS ccode, co.name AS cname
      FROM base_company co
     WHERE EXISTS (SELECT 1 FROM account_account acc
                    WHERE acc.company = co.id AND acc.code = '4551')
       AND EXISTS (SELECT 1 FROM account_account acc
                    WHERE acc.company = co.id AND acc.code = '4552')
     ORDER BY co.id
  LOOP
    companies := companies + 1;
    RAISE NOTICE '';
    RAISE NOTICE '── фирма [%] % (id=%)', c.ccode, c.cname, c.cid;

    -- § 2.1 · братската сметка 498
    SELECT t.technical_type_select, p.code
      INTO v_type, v_parent
      FROM account_account s
      LEFT JOIN account_account_type t ON t.id = s.account_type
      LEFT JOIN account_account      p ON p.id = s.parent_account
     WHERE s.company = c.cid AND s.code = '498';
    IF NOT FOUND THEN
      n_fail := n_fail + 1;
      RAISE NOTICE 'FAIL  § 2.1 [%] братската сметка 498 „Други дебитори“ ЛИПСВА — 442 няма'
        ' откъде да вземе тип, родител и произход', c.ccode;
    ELSIF v_type IS DISTINCT FROM 'receivable' OR v_parent IS DISTINCT FROM 'B020' THEN
      n_fail := n_fail + 1;
      RAISE NOTICE 'FAIL  § 2.1 [%] братската 498 е тип % / родител %, а се очаква'
        ' receivable / B020', c.ccode, v_type, v_parent;
    ELSE
      n_pass := n_pass + 1;
      RAISE NOTICE 'PASS  § 2.1 [%] братската 498 е receivable под B020', c.ccode;
    END IF;

    -- § 2.2 · самата сметка 442
    SELECT aa.id, aa.name, aa.status_select, aa.archived, t.technical_type_select, p.code
      INTO v_acc, v_name, v_status, v_arch, v_type, v_parent
      FROM account_account aa
      LEFT JOIN account_account_type t ON t.id = aa.account_type
      LEFT JOIN account_account      p ON p.id = aa.parent_account
     WHERE aa.company = c.cid AND aa.code = '442';
    IF NOT FOUND THEN
      n_todo := n_todo + 1;
      RAISE NOTICE 'TODO  § 2.2 [%] сметка 442 липсва — миграцията ще я добави по образец на 498',
        c.ccode;
      v_acc := NULL;
    ELSIF v_name <> 'Вземания по липси и начети' THEN
      n_fail := n_fail + 1;
      RAISE NOTICE 'FAIL  § 2.2 [%] код 442 е ЗАЕТ от „%“ — заето от друго значение, човек решава',
        c.ccode, v_name;
    ELSE
      n_pass := n_pass + 1;
      RAISE NOTICE 'PASS  § 2.2 [%] сметка 442 „%“ (id=%, тип=%, родител=%)',
        c.ccode, v_name, v_acc, v_type, v_parent;
      IF v_type IS DISTINCT FROM 'receivable' THEN
        n_fail := n_fail + 1;
        RAISE NOTICE 'FAIL  § 2.2 [%] 442 е от тип %, а възстановимата липса е ВЗЕМАНЕ —'
          ' разходен тип би я върнал в ОПР', c.ccode, v_type;
      END IF;
      IF v_status <> 1 OR v_arch THEN
        RAISE NOTICE 'NOTE  § 2.2 [%] 442 е ДЕАКТИВИРАНА (status=%, archived=%) — отписването ще'
          ' отстъпи към 691, видимо в статията', c.ccode, v_status, v_arch;
      END IF;
    END IF;

    -- § 2.3 · двата баланса
    FOR rp IN
      SELECT * FROM (VALUES
        ('28', 'Счетоводен баланс (НСС)',  'A_2_2_442',  'A_ST2_2',  184),
        ('46', 'Баланс (МСС)',             'MA_2_2_442', 'MA_ST2_2', 180)
      ) AS t(rid, rname, line_code, subtotal, seq)
    LOOP
      SELECT rt.id INTO v_id
        FROM account_accounting_report_type rt
       WHERE rt.import_id = c.ccode || '-' || rp.rid;
      IF NOT FOUND THEN
        RAISE NOTICE 'NOTE  § 2.3 [%] справка % („%“) я няма на тази фирма — пропуска се',
          c.ccode, rp.rid, rp.rname;
        CONTINUE;
      END IF;

      SELECT l.id INTO v_line
        FROM account_accounting_report_config_line l
       WHERE l.report_type = v_id AND l.code = rp.line_code;
      IF NOT FOUND THEN
        n_todo := n_todo + 1;
        RAISE NOTICE 'TODO  § 2.3 [%] справка %: редът % липсва', c.ccode, rp.rid, rp.line_code;
        SELECT count(*) INTO v_cnt
          FROM account_accounting_report_config_line l
         WHERE l.report_type = v_id AND l.sequence = rp.seq;
        IF v_cnt > 0 THEN
          n_fail := n_fail + 1;
          RAISE NOTICE 'FAIL  § 2.3 [%] справка %: секвенция % е ЗАЕТА — миграцията ще откаже',
            c.ccode, rp.rid, rp.seq;
        END IF;
      ELSE
        n_pass := n_pass + 1;
        RAISE NOTICE 'PASS  § 2.3 [%] справка %: ред % (id=%)',
          c.ccode, rp.rid, rp.line_code, v_line;

        -- ⚠️ ВРЪЗКАТА, не само текстът. accountCode е само низ; сумирането минава през
        -- accountSet. Ред без връзка съществува и сумира НУЛА — мълчаливо.
        SELECT count(*) INTO v_cnt
          FROM account_accounting_report_config_line_account_set s
          JOIN account_account a ON a.id = s.account_set
         WHERE s.account_accounting_report_config_line = v_line
           AND a.code = '442' AND a.company = c.cid;
        IF v_cnt = 0 THEN
          n_todo := n_todo + 1;
          RAISE NOTICE 'TODO  § 2.3 [%] справка %: редът % НЕ е вързан към сметка 442 на ТАЗИ'
            ' фирма — такъв ред сумира нула', c.ccode, rp.rid, rp.line_code;
        ELSE
          n_pass := n_pass + 1;
          RAISE NOTICE 'PASS  § 2.3 [%] справка %: % → сметка 442 на СВОЯТА фирма',
            c.ccode, rp.rid, rp.line_code;
        END IF;

        SELECT count(*) INTO v_cnt
          FROM account_accounting_report_config_line_account_set s
          JOIN account_account a ON a.id = s.account_set
         WHERE s.account_accounting_report_config_line = v_line AND a.company <> c.cid;
        IF v_cnt > 0 THEN
          n_fail := n_fail + 1;
          RAISE NOTICE 'FAIL  § 2.3 [%] справка %: редът % сочи % сметки на ЧУЖДА фирма',
            c.ccode, rp.rid, rp.line_code, v_cnt;
        END IF;
      END IF;

      SELECT l.rule INTO v_rule
        FROM account_accounting_report_config_line l
       WHERE l.report_type = v_id AND l.code = rp.subtotal;
      IF NOT FOUND THEN
        n_fail := n_fail + 1;
        RAISE NOTICE 'FAIL  § 2.3 [%] справка %: подсборът % ЛИПСВА', c.ccode, rp.rid, rp.subtotal;
      ELSIF v_rule ~ ('(^|[^A-Za-z0-9_])' || rp.line_code || '([^A-Za-z0-9_]|$)') THEN
        n_pass := n_pass + 1;
        RAISE NOTICE 'PASS  § 2.3 [%] справка %: подсборът % събира %',
          c.ccode, rp.rid, rp.subtotal, rp.line_code;
      ELSE
        n_todo := n_todo + 1;
        RAISE NOTICE 'TODO  § 2.3 [%] справка %: подсборът % не събира % (правило: %)',
          c.ccode, rp.rid, rp.subtotal, rp.line_code, v_rule;
      END IF;
    END LOOP;

    -- § 2.4 · ОПР не бива да сочи 442
    SELECT count(*) INTO v_cnt
      FROM account_accounting_report_config_line l
      JOIN account_accounting_report_type rt ON rt.id = l.report_type
     WHERE rt.company = c.cid
       AND split_part(rt.import_id, '-', 2) IN ('27', '45')
       AND (l.account_code = '442'
            OR l.rule ~ '(^|[^A-Za-z0-9_])[A-Za-z_]*_442([^A-Za-z0-9_]|$)');
    IF v_cnt > 0 THEN
      n_fail := n_fail + 1;
      RAISE NOTICE 'FAIL  § 2.4 [%] ОПР (справки 27/45) носи % реда за 442. Възстановимата липса е'
        ' ВЗЕМАНЕ, не разход — в ОПР тя би намалила резултата с нещо, което ще се събере',
        c.ccode, v_cnt;
    ELSE
      n_pass := n_pass + 1;
      RAISE NOTICE 'PASS  § 2.4 [%] ОПР не показва 442 — вземането не е разход', c.ccode;
    END IF;
  END LOOP;

  IF companies = 0 THEN
    RAISE NOTICE '';
    RAISE NOTICE 'NOTE  нула фирми с BG сметкоплан (няма едновременно 4551 и 4552). Миграцията ще'
      ' бъде no-op с изход 0.';
  END IF;

  RAISE NOTICE '';
  RAISE NOTICE '─────────────────────────────────────────────────────────────────────────────';
  RAISE NOTICE 'BG_COA/442 · фирми=% · PASS=% · TODO=% · FAIL=%',
    companies, n_pass, n_todo, n_fail;
  IF n_fail > 0 THEN
    RAISE NOTICE 'Има FAIL редове. Миграцията ще ОТМЕНИ всичко — оправете ги преди да я пуснете.';
  ELSIF n_todo > 0 THEN
    RAISE NOTICE 'Само TODO — точно това ще добави миграцията.';
  ELSE
    RAISE NOTICE 'Нищо за правене: състоянието вече е целевото.';
  END IF;
  RAISE NOTICE '─────────────────────────────────────────────────────────────────────────────';
END
$verify$;
