-- BG_COA, кръг 2 на имената: 728 и 791 по НАП + label-ите на деветте преномерирани от кръг 1 —
-- върху СЪЩЕСТВУВАЩА база. Идемпотентно, транзакционно, fail-loud. Шаблонът е #280 (704/706).
--
-- ЗАЩО — ДВЕ НЕЗАВИСИМИ ПОПРАВКИ В ЕДНА ТРАНЗАКЦИЯ:
--
--   А. ИМЕНАТА. 728 и 791 носеха името на съседа си (729 и 799). По NRA_Nom_Accounts (книгата
--      1.0.2, `nomenclatures/nra/nra_nom_accounts.csv`):
--          728 = „Приходи от реинтегрирани провизии“           (беше „Други финансови приходи“)
--          791 = „Приходи от отписване на кредитни експозиции“ (беше „Извънредни приходи“)
--      Мапингът към SAF-T вече е 728 → 728 и 791 → 791 IDENTITY: износът и досега ги назоваваше
--      по НАП. Сменя се само името, което вижда потребителят.
--
--      ⚠️ 691 НАРОЧНО НЕ Е ТУК. Тя е разходната сметка за брак и липси на ДМА (15-те категории,
--      BgRemovalAccountRules). Преименувана сама, бракът би стоял по „…кредитни експозиции“; тя
--      се преименува заедно с пренасочването към 60905 „Разходи за липси и брак на активи“.
--
--   Б. LABEL-ИТЕ ОТ КРЪГ 1. `2026-09-30-bg-coa-nra-n1` преномерира девет сметки с UPDATE на
--      `code`/`name`, без `label`. `label` е СЪХРАНЕНО изчислимо поле (Account.xml: code + "_" +
--      company.code + " - " + name); getter-ът го преизчислява, но колоната остава, а списъците и
--      търсенето четат КОЛОНАТА (Query.Selector строи JPQL `SELECT self.label`). Тоест сметка 202
--      се вижда като „203_ФИРМА - Сгради и конструкции“.
--
--      Поправя се САМО ако label-ът е ТОЧНО отпечатъкът на кръг 1 — `<стар код>_ФИРМА - <старо
--      име>`. Всяко друго разминаване не е от кръг 1 и не се пипа; verify-скриптът го изброява.
--
-- Пуска се РЪЧНО; редът спрямо старта на приложението е без значение (няма нова колона):
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-07-bg-coa-names-2-existing-base.sql
--
-- ⚠️ САМО DEV. По продукция — само след бекъп и след като придружаващата проверка е прочетена от
-- ЧОВЕК. Второто пускане дава само `skip` редове.
--
-- КАКВО СЕ ПИПА: `name`, `label`, `version` (+1, срещу тихо връщане от отворена форма) и
-- `updated_on` на 728/791 и на деветте. Салда, статии, справки, мапинг — НЕ. `id` не се мени.
--
-- ⚠️ ИМЕ, РАЗЛИЧНО И ОТ СТАРОТО, И ОТ ЦЕЛЕВОТО, НЕ СЕ ПРЕЗАПИСВА (`РЪЧНО`) — сметкопланът е на
-- предприятието (ЗСч). ⚠️ ОСЧЕТОВОДЕНОТО ПО 728/791 СЕ ОДИТИРА, НЕ СЕ СПИРА: в SAF-T то и досега
-- излизаше с НАП имената; рекласът към 729/799, ако е нужен, е РЕДОВНА статия, не UPDATE.

BEGIN;

DROP TABLE IF EXISTS pg_temp.bg_names2_name;
CREATE TEMP TABLE bg_names2_name (code text NOT NULL, old_name text NOT NULL, new_name text NOT NULL);
INSERT INTO bg_names2_name VALUES
  ('728', 'Други финансови приходи', 'Приходи от реинтегрирани провизии'),
  ('791', 'Извънредни приходи',      'Приходи от отписване на кредитни експозиции');

-- Деветте от кръг 1, дословно от `bg_n1_rename`: днешният код ← кодът и името ПРЕДИ кръг 1.
DROP TABLE IF EXISTS pg_temp.bg_names2_n1;
CREATE TEMP TABLE bg_names2_n1 (code text NOT NULL, old_code text NOT NULL, old_name text NOT NULL);
INSERT INTO bg_names2_n1 VALUES
  ('202',   '203', 'Сгради и конструкции'),
  ('203',   '204', 'Компютърна техника'),
  ('204',   '205', 'Съоръжения'),
  ('205',   '206', 'Машини и оборудване'),
  ('206',   '207', 'Транспортни средства'),
  ('207',   '208', 'Офис обзавеждане'),
  ('154',   '153', 'Облигационни заеми'),
  ('40901', '409', 'Ваучери за храна'),
  ('41902', '416', 'Съмнителни и спорни вземания');

-- ═══ 1 · САМАТА МИГРАЦИЯ ═══════════════════════════════════════════════════════════════════════

DO $mig$
DECLARE
  c            record;
  r            record;
  v_acc_id     bigint;
  v_name       text;
  v_label      text;
  v_want_label text;
  v_n1_label   text;
  v_posted     bigint;
  v_balance    numeric;
  v_companies  integer := 0;
  n_renamed    integer := 0;
  n_relabeled  integer := 0;
  n_n1_fixed   integer := 0;
  n_custom     integer := 0;
  n_foreign    integer := 0;
  n_missing    integer := 0;
  n_skipped    integer := 0;
BEGIN
  RAISE NOTICE 'BG_COA/NAMES2 ═══ начало ═══ база=% потребител=%', current_database(), current_user;

  IF to_regclass('public.account_account') IS NULL THEN
    RAISE EXCEPTION
      'BG_COA/NAMES2: таблица account_account липсва — не е Axelor база. Нищо не е пипано.';
  END IF;

  -- ── 1.1 · ОБХВАТ: ФИРМИТЕ С BG ПЛАНА (опори 4551 и 4552, както в #243/#280) ────────────────
  -- ⚠️ Без опорите файлът би стигнал и FRA_PCG — там 728, 791, 203… също съществуват.
  FOR c IN
    SELECT co.id AS cid, coalesce(co.code, '?') AS ccode, co.code AS raw_code, co.name AS cname
      FROM base_company co
     WHERE EXISTS (SELECT 1 FROM account_account acc
                    WHERE acc.company = co.id AND acc.code = '4551')
       AND EXISTS (SELECT 1 FROM account_account acc
                    WHERE acc.company = co.id AND acc.code = '4552')
     ORDER BY co.id
  LOOP
    v_companies := v_companies + 1;
    RAISE NOTICE 'BG_COA/NAMES2 ── фирма [%] % (id=%)', c.ccode, c.cname, c.cid;

    -- ── 1.2 · А: ИМЕНАТА НА 728 И 791 ─────────────────────────────────────────────────────────
    FOR r IN SELECT * FROM bg_names2_name ORDER BY code LOOP
      -- `label` по формулата от Account.xml; Java конкатенацията пише null като „null“.
      v_want_label := r.code || '_' || coalesce(c.raw_code, 'null') || ' - ' || r.new_name;

      SELECT acc.id, acc.name, acc.label INTO v_acc_id, v_name, v_label
        FROM account_account acc
       WHERE acc.company = c.cid AND acc.code = r.code;

      IF NOT FOUND THEN
        n_missing := n_missing + 1;
        RAISE NOTICE 'BG_COA/NAMES2 ⚠ missing [%] % — сметката я няма', c.ccode, r.code;
        CONTINUE;
      END IF;

      IF v_name = r.new_name THEN
        IF v_label IS NOT DISTINCT FROM v_want_label THEN
          n_skipped := n_skipped + 1;
          RAISE NOTICE 'BG_COA/NAMES2 skip    [%] % — вече е „%“', c.ccode, r.code, r.new_name;
        ELSE
          -- Името е сменено с гол SQL, без label-а: довършва се само label-ът.
          UPDATE account_account
             SET label = v_want_label, version = coalesce(version, 0) + 1, updated_on = now()
           WHERE id = v_acc_id;
          n_relabeled := n_relabeled + 1;
          RAISE NOTICE 'BG_COA/NAMES2 RELABEL [%] % — label „%“ → „%“', c.ccode, r.code,
            v_label, v_want_label;
        END IF;
        CONTINUE;
      END IF;

      IF v_name <> r.old_name THEN
        n_custom := n_custom + 1;
        RAISE NOTICE 'BG_COA/NAMES2 ⚠ РЪЧНО  [%] % носи „%“ — не се пипа, решава счетоводителят',
          c.ccode, r.code, v_name;
        CONTINUE;
      END IF;

      -- ОДИТ, не гейт: името се мени, статиите сочат сметката по `id` и не се местят.
      SELECT count(*), coalesce(sum(ml.debit), 0) - coalesce(sum(ml.credit), 0)
        INTO v_posted, v_balance
        FROM account_move_line ml
        JOIN account_move m ON m.id = ml.move
       WHERE ml.account = v_acc_id AND m.status_select IN (2, 3);

      UPDATE account_account
         SET name = r.new_name, label = v_want_label,
             version = coalesce(version, 0) + 1, updated_on = now()
       WHERE id = v_acc_id;
      n_renamed := n_renamed + 1;
      RAISE NOTICE 'BG_COA/NAMES2 RENAMED [%] % „%“ → „%“ · % осчетоводени реда, салдо (Dt−Kt) = %',
        c.ccode, r.code, v_name, r.new_name, v_posted, to_char(v_balance, 'FM999999999990.00');
    END LOOP;

    -- ── 1.3 · Б: LABEL-ИТЕ НА ДЕВЕТТЕ ОТ КРЪГ 1 ──────────────────────────────────────────────
    FOR r IN SELECT * FROM bg_names2_n1 ORDER BY code LOOP
      SELECT acc.id, acc.name, acc.label INTO v_acc_id, v_name, v_label
        FROM account_account acc
       WHERE acc.company = c.cid AND acc.code = r.code;

      IF NOT FOUND THEN
        -- База отпреди кръг 1 (тогава 40901, 41902 и 154 ги няма) — нищо за поправяне.
        n_missing := n_missing + 1;
        RAISE NOTICE 'BG_COA/NAMES2 ⚠ missing [%] % — сметката я няма', c.ccode, r.code;
        CONTINUE;
      END IF;

      v_want_label := r.code || '_' || coalesce(c.raw_code, 'null') || ' - ' || v_name;
      v_n1_label := r.old_code || '_' || coalesce(c.raw_code, 'null') || ' - ' || r.old_name;

      IF v_label IS NOT DISTINCT FROM v_want_label THEN
        n_skipped := n_skipped + 1;
        RAISE NOTICE 'BG_COA/NAMES2 skip    [%] % — label-ът е верен', c.ccode, r.code;
      ELSIF v_label IS NOT DISTINCT FROM v_n1_label THEN
        UPDATE account_account
           SET label = v_want_label, version = coalesce(version, 0) + 1, updated_on = now()
         WHERE id = v_acc_id;
        n_n1_fixed := n_n1_fixed + 1;
        RAISE NOTICE 'BG_COA/NAMES2 N1-LABEL [%] % — „%“ → „%“', c.ccode, r.code, v_label,
          v_want_label;
      ELSE
        -- Не е отпечатъкът на кръг 1: друг източник, друго решение. Изброява го verify-ът.
        n_foreign := n_foreign + 1;
        RAISE NOTICE 'BG_COA/NAMES2 ⚠ ЧУЖДО [%] % — label „%“ не е от кръг 1; не се пипа',
          c.ccode, r.code, coalesce(v_label, 'NULL');
      END IF;
    END LOOP;
  END LOOP;

  IF v_companies = 0 THEN
    RAISE NOTICE 'BG_COA/NAMES2: нула фирми с BG сметкоплан (опори 4551 и 4552). Нищо за правене.';
  END IF;

  RAISE NOTICE 'BG_COA/NAMES2 ═══ край ═══ фирми=% | преименувани=% | само label=% | N1 label=%',
    v_companies, n_renamed, n_relabeled, n_n1_fixed;
  RAISE NOTICE 'BG_COA/NAMES2                  skip=% | ръчни=% | чужди label=% | липсващи=%',
    n_skipped, n_custom, n_foreign, n_missing;
END
$mig$;

-- ═══ 2 · СВЕРКА ЗА КОНВЕРГЕНЦИЯ — ВСЕ ОЩЕ В ТРАНЗАКЦИЯТА ══════════════════════════════════════
-- Гледа РЕЗУЛТАТА: нито 728/791 със старото име, нито целево име с разминат label, нито един от
-- деветте с отпечатъка на кръг 1.

DO $conv$
DECLARE
  v_bad  text := '';
  x      record;
BEGIN
  FOR x IN
    SELECT coalesce(co.code, '?') AS ccode, acc.code, acc.name, acc.label,
           acc.code || '_' || coalesce(co.code, 'null') || ' - ' || acc.name AS want_label,
           n.old_name, n.new_name, n1.old_code, n1.old_name AS n1_old_name,
           n1.old_code || '_' || coalesce(co.code, 'null') || ' - ' || n1.old_name AS n1_label
      FROM account_account acc
      JOIN base_company co ON co.id = acc.company
      LEFT JOIN bg_names2_name n ON n.code = acc.code
      LEFT JOIN bg_names2_n1 n1 ON n1.code = acc.code
     WHERE (n.code IS NOT NULL OR n1.code IS NOT NULL)
       AND EXISTS (SELECT 1 FROM account_account a1 WHERE a1.company = co.id AND a1.code = '4551')
       AND EXISTS (SELECT 1 FROM account_account a2 WHERE a2.company = co.id AND a2.code = '4552')
     ORDER BY co.id, acc.code
  LOOP
    IF x.old_name IS NOT NULL AND x.name = x.old_name THEN
      v_bad := v_bad || format(E'\n  · [%s] %s носи още старото име %L', x.ccode, x.code, x.name);
    ELSIF x.new_name IS NOT NULL AND x.name = x.new_name
          AND x.label IS DISTINCT FROM x.want_label THEN
      v_bad := v_bad || format(E'\n  · [%s] %s: label %L вместо %L', x.ccode, x.code, x.label,
                               x.want_label);
    ELSIF x.old_code IS NOT NULL AND x.label IS NOT DISTINCT FROM x.n1_label THEN
      v_bad := v_bad || format(E'\n  · [%s] %s носи още label-а от кръг 1 %L', x.ccode, x.code,
                               x.label);
    END IF;
  END LOOP;

  IF v_bad <> '' THEN
    RAISE EXCEPTION USING MESSAGE = format(
      E'BG_COA/NAMES2: НЕ КОНВЕРГИРА към целевото състояние — НИЩО НЕ Е ЗАПИСАНО:%s', v_bad);
  END IF;
  RAISE NOTICE 'BG_COA/NAMES2 ✔ конвергенцията е сверена';
END
$conv$;

COMMIT;
