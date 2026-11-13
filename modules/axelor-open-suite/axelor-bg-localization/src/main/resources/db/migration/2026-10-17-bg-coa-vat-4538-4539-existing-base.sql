-- BG_COA: ДДС 4538/4539 — поправен СМИСЪЛ на двете сметки върху СЪЩЕСТВУВАЩА база. Идемпотентно,
-- транзакционно, fail-loud. Шаблонът е 2026-10-07-bg-coa-names-2.
--
-- ЗАЩО. BG_COA носеше двете сметки със смисъл, обратен на НАП. Счетоводителят потвърди крайния смисъл:
--     4538 = „Данък върху добавената стойност за възстановяване“   (дебитна, активна)
--     4539 = „Данък върху добавената стойност за внасяне“          (кредитна, пасивна)
-- Кодовете, id-тата и статиите НЕ се местят. Сменят се САМО свойствата, които носят смисъла —
-- `name`, `label` (съхранено изчислимо), `common_position` и `bg_ap_select` — на сметките ЗАЕДНО,
-- защото commonPosition и А/П осите бяха съгласувани с ИМЕТО (внасяне = Credit/пасивна).
--
--   код    беше                                  cp  А/П      става                                    cp  А/П
--   4538   ДДС за внасяне в бюджета              1   2        ...за възстановяване                     2   1
--   4539   ДДС за възстановяване                 2   1        ...за внасяне                            1   2
--
-- SAF-T МАПИНГЪТ: `bglocalization_bg_saf_account_mapping` се внася от seed-а при всеки boot
-- (DataInitSync, upsert по importId) и сам става IDENTITY (4538→4538, 4539→4539). Тук е само
-- РЕЗЕРВЕН път за база, където синхронизацията е изключена: сменя точно старото разменено
-- (4538→4539, 4539→4538) към IDENTITY. Нищо друго в мапинга не се пипа.
--
-- ⚠️ ИМПОРТЪТ ОТ MICROINVEST НЕ СЕ ПИПА: 453/8 → 4538 и 453/9 → 4539 са верни — те са кодове по
-- сметкоплана, а не по имена.
--
-- ⚠️ ОСЧЕТОВОДЕНОТО ПО 4538/4539 СЕ ОДИТИРА, НЕ СЕ СПИРА. Статиите сочат сметката по `id`; тук се
-- печата броят осчетоводени редове и салдото. Ако досега са осчетоводявани по ИМЕТО (обратното на
-- смисъла), рекласът е РЕДОВНА счетоводна статия, не UPDATE.
--
-- Пуска се РЪЧНО (няма нова колона, редът спрямо старта е без значение):
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-17-bg-coa-vat-4538-4539-existing-base.sql
--
-- ⚠️ САМО DEV. По продукция — след бекъп и след като verify-скриптът е прочетен от ЧОВЕК.
-- Второто пускане дава само `skip` редове.
--
-- ПРАЗНО А/П се приема за част от старото състояние (сметкопланове без А/П ос — NYXABG, TESTEKS), но
-- САМО когато името и commonPosition са точно старите.
-- Сметка с различни от очакваните name/cp/А-П (ръчно променена) НЕ се презаписва (`РЪЧНО`) —
-- сметкопланът е на предприятието (ЗСч).

BEGIN;

DROP TABLE IF EXISTS pg_temp.bg_vat45_acc;
CREATE TEMP TABLE bg_vat45_acc (
  code text NOT NULL,
  old_name text NOT NULL, old_cp int NOT NULL, old_ap text NOT NULL,
  new_name text NOT NULL, new_cp int NOT NULL, new_ap text NOT NULL);
INSERT INTO bg_vat45_acc VALUES
  ('4538', 'ДДС за внасяне в бюджета', 1, '2',
           'Данък върху добавената стойност за възстановяване', 2, '1'),
  ('4539', 'ДДС за възстановяване', 2, '1',
           'Данък върху добавената стойност за внасяне', 1, '2');

DO $mig$
DECLARE
  c            record;
  r            record;
  v_id         bigint;
  v_name       text;
  v_label      text;
  v_cp         int;
  v_ap         text;
  v_want_label text;
  v_posted     bigint;
  v_balance    numeric;
  v_companies  integer := 0;
  n_changed    integer := 0;
  n_relabeled  integer := 0;
  n_skipped    integer := 0;
  n_custom     integer := 0;
  n_missing    integer := 0;
  n_saft       integer := 0;
BEGIN
  RAISE NOTICE 'BG_COA/VAT45 ═══ начало ═══ база=% потребител=%', current_database(), current_user;

  IF to_regclass('public.account_account') IS NULL THEN
    RAISE EXCEPTION 'BG_COA/VAT45: таблица account_account липсва — не е Axelor база. Нищо не е пипано.';
  END IF;

  -- Обхват: фирмите с BG плана (опори 4551 и 4552, както в names-2).
  FOR c IN
    SELECT co.id AS cid, coalesce(co.code, '?') AS ccode, co.code AS raw_code, co.name AS cname
      FROM base_company co
     WHERE EXISTS (SELECT 1 FROM account_account acc WHERE acc.company = co.id AND acc.code = '4551')
       AND EXISTS (SELECT 1 FROM account_account acc WHERE acc.company = co.id AND acc.code = '4552')
     ORDER BY co.id
  LOOP
    v_companies := v_companies + 1;
    RAISE NOTICE 'BG_COA/VAT45 ── фирма [%] % (id=%)', c.ccode, c.cname, c.cid;

    FOR r IN SELECT * FROM bg_vat45_acc ORDER BY code LOOP
      v_want_label := r.code || '_' || coalesce(c.raw_code, 'null') || ' - ' || r.new_name;

      SELECT acc.id, acc.name, acc.label, acc.common_position, acc.bg_ap_select
        INTO v_id, v_name, v_label, v_cp, v_ap
        FROM account_account acc
       WHERE acc.company = c.cid AND acc.code = r.code;

      IF NOT FOUND THEN
        n_missing := n_missing + 1;
        RAISE NOTICE 'BG_COA/VAT45 ⚠ missing [%] % — сметката я няма', c.ccode, r.code;
        CONTINUE;
      END IF;

      IF v_name = r.new_name AND v_cp = r.new_cp AND v_ap IS NOT DISTINCT FROM r.new_ap THEN
        IF v_label IS NOT DISTINCT FROM v_want_label THEN
          n_skipped := n_skipped + 1;
          RAISE NOTICE 'BG_COA/VAT45 skip    [%] % — вече е „%“', c.ccode, r.code, r.new_name;
        ELSE
          UPDATE account_account
             SET label = v_want_label, version = coalesce(version, 0) + 1, updated_on = now()
           WHERE id = v_id;
          n_relabeled := n_relabeled + 1;
          RAISE NOTICE 'BG_COA/VAT45 RELABEL [%] % — label „%“ → „%“', c.ccode, r.code,
            v_label, v_want_label;
        END IF;
        CONTINUE;
      END IF;

      IF NOT (v_name = r.old_name AND v_cp = r.old_cp AND (v_ap IS NULL OR v_ap = r.old_ap)) THEN
        n_custom := n_custom + 1;
        RAISE NOTICE 'BG_COA/VAT45 ⚠ РЪЧНО  [%] % носи „%“ (cp=%, А/П=%) — не се пипа, решава счетоводителят',
          c.ccode, r.code, v_name, v_cp, coalesce(v_ap, 'NULL');
        CONTINUE;
      END IF;

      -- ОДИТ, не гейт: статиите сочат сметката по `id` и не се местят.
      SELECT count(*), coalesce(sum(ml.debit), 0) - coalesce(sum(ml.credit), 0)
        INTO v_posted, v_balance
        FROM account_move_line ml
        JOIN account_move m ON m.id = ml.move
       WHERE ml.account = v_id AND m.status_select IN (2, 3);

      UPDATE account_account
         SET name = r.new_name, label = v_want_label,
             common_position = r.new_cp, bg_ap_select = r.new_ap,
             version = coalesce(version, 0) + 1, updated_on = now()
       WHERE id = v_id;
      n_changed := n_changed + 1;
      RAISE NOTICE 'BG_COA/VAT45 CHANGED [%] % „%“ → „%“ · % осчетоводени реда, салдо (Dt−Kt) = %',
        c.ccode, r.code, v_name, r.new_name, v_posted, to_char(v_balance, 'FM999999999990.00');
    END LOOP;
  END LOOP;

  IF v_companies = 0 THEN
    RAISE NOTICE 'BG_COA/VAT45: нула фирми с BG сметкоплан (опори 4551 и 4552). Нищо за правене.';
  END IF;

  -- Резервен път за SAF-T мапинга: само точно старото разменено състояние → IDENTITY.
  IF to_regclass('public.bglocalization_bg_saf_account_mapping') IS NOT NULL THEN
    UPDATE bglocalization_bg_saf_account_mapping m
       SET nra_code = m.account_code, rule_select = 'IDENTITY',
           full_name = m.account_code || ' → ' || m.account_code,
           version = coalesce(version, 0) + 1, updated_on = now()
     WHERE (m.account_code = '4538' AND m.nra_code = '4539')
        OR (m.account_code = '4539' AND m.nra_code = '4538');
    GET DIAGNOSTICS n_saft = ROW_COUNT;
  ELSE
    RAISE NOTICE 'BG_COA/VAT45: SAF-T таблицата я няма — първият boot я създава и внася seed-а.';
  END IF;

  RAISE NOTICE 'BG_COA/VAT45 ═══ край ═══ фирми=% | сменени=% | само label=% | skip=% | ръчни=% | липсващи=% | SAF-T=%',
    v_companies, n_changed, n_relabeled, n_skipped, n_custom, n_missing, n_saft;
END
$mig$;

-- ═══ СВЕРКА ЗА КОНВЕРГЕНЦИЯ — ВСЕ ОЩЕ В ТРАНЗАКЦИЯТА ═════════════════════════════════════════════
-- Гледа РЕЗУЛТАТА: нито една сметка не остава в старото състояние, нито SAF-T ред в старото
-- разменено. (Ръчно променена сметка не е „старо състояние“ и не пада тук.)
DO $conv$
DECLARE
  v_bad text := '';
  x     record;
BEGIN
  FOR x IN
    SELECT co.code AS ccode, acc.code, acc.name
      FROM account_account acc
      JOIN bg_vat45_acc t ON t.code = acc.code
      JOIN base_company co ON co.id = acc.company
     WHERE acc.name = t.old_name AND acc.common_position = t.old_cp
       AND (acc.bg_ap_select IS NULL OR acc.bg_ap_select = t.old_ap)
       AND EXISTS (SELECT 1 FROM account_account a2 WHERE a2.company = co.id AND a2.code = '4551')
       AND EXISTS (SELECT 1 FROM account_account a3 WHERE a3.company = co.id AND a3.code = '4552')
  LOOP
    v_bad := v_bad || format(E'\n  · [%s] %s още носи „%s“', x.ccode, x.code, x.name);
  END LOOP;

  IF to_regclass('public.bglocalization_bg_saf_account_mapping') IS NOT NULL THEN
    FOR x IN
      SELECT m.account_code, m.nra_code
        FROM bglocalization_bg_saf_account_mapping m
       WHERE (m.account_code = '4538' AND m.nra_code = '4539')
          OR (m.account_code = '4539' AND m.nra_code = '4538')
    LOOP
      v_bad := v_bad || format(E'\n  · SAF-T %s още сочи разменения %s', x.account_code, x.nra_code);
    END LOOP;
  END IF;

  IF v_bad <> '' THEN
    RAISE EXCEPTION USING MESSAGE = format(
      E'BG_COA/VAT45: НЕ КОНВЕРГИРА към целевото състояние — НИЩО НЕ Е ЗАПИСАНО:%s', v_bad);
  END IF;
  RAISE NOTICE 'BG_COA/VAT45 ✔ конвергенцията е сверена';
END
$conv$;

COMMIT;
