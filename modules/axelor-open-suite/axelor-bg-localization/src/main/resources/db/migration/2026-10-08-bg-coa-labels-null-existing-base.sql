-- BG_COA: празните label-и на сметките + 603 по НАП — върху СЪЩЕСТВУВАЩА база. Идемпотентно,
-- транзакционно, fail-loud. Шаблонът е #281.
--
-- ЗАЩО — ДВЕ ПОПРАВКИ В ЕДНА ТРАНЗАКЦИЯ:
--
--   А. LABEL = NULL. INSERT-ите на кръгове 168, 442 и 2 (N2) записваха `label = NULL` (поправени са в
--      същия PR — за бази, които още не са ги пускали). `label` е СЪХРАНЕНО изчислимо поле:
--      Account.xml → `@VirtualColumn @Access(PROPERTY)`, Hibernate записва `computeLabel()` при
--      flush. Списъците и търсенето четат КОЛОНАТА (Query.Selector → `SELECT self.label`), тоест
--      такава сметка стои с празно име, докато не бъде записана през приложението.
--      Попълва се ТОЧНО по computeLabel():
--          company != null → code + "_" + company.getCode() + " - " + name   (null → „null“)
--          company == null → code + " - " + name
--      САМО където label IS NULL. Непразен label — дори остарял — не се пипа; verify-ът го брои.
--
--   Б. 603. В BG_COA беше „Разходи за амортизация“ (като възела I070); по NRA_Nom_Accounts (1.0.2)
--      е „Разходи за амортизации“. Механизмът е на #281: само точно старото име; друго → РЪЧНО.
--
-- ОБХВАТ: дружествата с BG план (опори 4551 и 4552). Френско дружество не се пипа.
-- КАКВО СЕ ПИПА: `label`, `name` (само 603), `version + 1`, `updated_on`. Салда, статии, кодове — НЕ.
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-08-bg-coa-labels-null-existing-base.sql

BEGIN;

DO $mig$
DECLARE
  c            record;
  v_acc_id     bigint;
  v_name       text;
  v_label      text;
  v_want       text;
  v_cnt        integer;
  v_companies  integer := 0;
  n_603        integer := 0;
  n_603_skip   integer := 0;
  n_603_custom integer := 0;
  n_filled     integer := 0;
  c_old        constant text := 'Разходи за амортизация';
  c_new        constant text := 'Разходи за амортизации';
BEGIN
  RAISE NOTICE 'BG_COA/LBL ═══ начало ═══ база=% потребител=%', current_database(), current_user;

  IF to_regclass('public.account_account') IS NULL THEN
    RAISE EXCEPTION 'BG_COA/LBL: таблица account_account липсва — не е Axelor база. Нищо не е пипано.';
  END IF;

  FOR c IN
    SELECT co.id AS cid, coalesce(co.code, '?') AS ccode, co.code AS raw_code, co.name AS cname
      FROM base_company co
     WHERE EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4551')
       AND EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4552')
     ORDER BY co.id
  LOOP
    v_companies := v_companies + 1;
    RAISE NOTICE 'BG_COA/LBL ── фирма [%] % (id=%)', c.ccode, c.cname, c.cid;

    -- ── Б · 603 (преди А, за да влезе с новото си име в label) ────────────────────────────────
    SELECT a.id, a.name, a.label INTO v_acc_id, v_name, v_label
      FROM account_account a WHERE a.company = c.cid AND a.code = '603';
    IF FOUND THEN
      v_want := '603_' || coalesce(c.raw_code, 'null') || ' - ' || c_new;
      IF v_name = c_new AND v_label IS NOT DISTINCT FROM v_want THEN
        n_603_skip := n_603_skip + 1;
      ELSIF v_name = c_new AND v_label IS NOT NULL THEN
        -- Името е сменено с гол SQL, без label-а: довършва се само label-ът (както в #281).
        UPDATE account_account
           SET label = v_want, version = coalesce(version, 0) + 1, updated_on = now()
         WHERE id = v_acc_id;
        n_603 := n_603 + 1;
        RAISE NOTICE 'BG_COA/LBL RELABEL [%] 603 — label „%“ → „%“', c.ccode, v_label, v_want;
      ELSIF v_name = c_new THEN
        n_603_skip := n_603_skip + 1;   -- label е NULL — попълва го стъпка А
      ELSIF v_name = c_old THEN
        UPDATE account_account
           SET name = c_new, label = v_want, version = coalesce(version, 0) + 1, updated_on = now()
         WHERE id = v_acc_id;
        n_603 := n_603 + 1;
        RAISE NOTICE 'BG_COA/LBL RENAMED [%] 603 „%“ → „%“', c.ccode, c_old, c_new;
      ELSE
        n_603_custom := n_603_custom + 1;
        RAISE NOTICE 'BG_COA/LBL ⚠ РЪЧНО  [%] 603 носи „%“ — не се пипа', c.ccode, v_name;
      END IF;
    END IF;

    -- ── А · label = NULL ───────────────────────────────────────────────────────────────────────
    UPDATE account_account a
       SET label = a.code || '_' || coalesce(c.raw_code, 'null') || ' - ' || a.name,
           version = coalesce(a.version, 0) + 1, updated_on = now()
     WHERE a.company = c.cid AND a.label IS NULL;
    GET DIAGNOSTICS v_cnt = ROW_COUNT;
    n_filled := n_filled + v_cnt;
    IF v_cnt > 0 THEN
      RAISE NOTICE 'BG_COA/LBL FILLED  [%] % сметки с празен label', c.ccode, v_cnt;
    END IF;
  END LOOP;

  IF v_companies = 0 THEN
    RAISE NOTICE 'BG_COA/LBL: нула фирми с BG сметкоплан (опори 4551 и 4552). Нищо за правене.';
  END IF;
  RAISE NOTICE 'BG_COA/LBL ═══ край ═══ фирми=% | попълнени label=% | 603 сменени=%',
    v_companies, n_filled, n_603;
  RAISE NOTICE 'BG_COA/LBL                  603 skip=% | 603 ръчни=%', n_603_skip, n_603_custom;
END
$mig$;

-- ═══ СВЕРКА ЗА КОНВЕРГЕНЦИЯ — ВСЕ ОЩЕ В ТРАНЗАКЦИЯТА ═══════════════════════════════════════════
-- Нито една BG сметка с празен label; 603 без старото име; 603 с новото име носи своя label.
DO $conv$
DECLARE
  v_bad  text := '';
  x      record;
BEGIN
  FOR x IN
    SELECT coalesce(co.code, '?') AS ccode, count(*) AS n
      FROM account_account a JOIN base_company co ON co.id = a.company
     WHERE a.label IS NULL
       AND EXISTS (SELECT 1 FROM account_account s WHERE s.company = co.id AND s.code = '4551')
       AND EXISTS (SELECT 1 FROM account_account s WHERE s.company = co.id AND s.code = '4552')
     GROUP BY co.id, co.code
  LOOP
    v_bad := v_bad || format(E'\n  · [%s] %s сметки още с празен label', x.ccode, x.n);
  END LOOP;

  FOR x IN
    SELECT coalesce(co.code, '?') AS ccode, a.name, a.label,
           '603_' || coalesce(co.code, 'null') || ' - ' || a.name AS want
      FROM account_account a JOIN base_company co ON co.id = a.company
     WHERE a.code = '603'
       AND EXISTS (SELECT 1 FROM account_account s WHERE s.company = co.id AND s.code = '4551')
       AND EXISTS (SELECT 1 FROM account_account s WHERE s.company = co.id AND s.code = '4552')
  LOOP
    IF x.name = 'Разходи за амортизация' THEN
      v_bad := v_bad || format(E'\n  · [%s] 603 носи още старото име', x.ccode);
    ELSIF x.name = 'Разходи за амортизации' AND x.label IS DISTINCT FROM x.want THEN
      v_bad := v_bad || format(E'\n  · [%s] 603: label %L вместо %L', x.ccode, x.label, x.want);
    END IF;
  END LOOP;

  IF v_bad <> '' THEN
    RAISE EXCEPTION USING MESSAGE = format(
      E'BG_COA/LBL: НЕ КОНВЕРГИРА към целевото състояние — НИЩО НЕ Е ЗАПИСАНО:%s', v_bad);
  END IF;
  RAISE NOTICE 'BG_COA/LBL ✔ конвергенцията е сверена';
END
$conv$;

COMMIT;
