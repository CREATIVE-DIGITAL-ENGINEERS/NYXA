-- BG_COA: имената на 704 и 706 по номенклатурата на НАП — върху СЪЩЕСТВУВАЩА база.
-- Идемпотентно, транзакционно, fail-loud.
--
-- ЗАЩО. В `l10n/l10n_bgr/BG_COA/account_account.csv` сметки 704 и 706 се казваха еднакво —
-- „Приходи от продажба на материали“. По номенклатурата на НАП (NRA_Nom_Accounts от „Структура на
-- файловете на одитен файл SAF-T“, версия 1.0.2 — `nomenclatures/nra/nra_nom_accounts.csv`):
--     704 = „Приходи от наеми“
--     706 = „Приходи от продажба на суровини/материали“
-- Мапингът към SAF-T вече беше верен (`bg_saf_account_mapping.csv` → 704/706 IDENTITY), тоест
-- износът и досега назоваваше 704 „наеми“. Сменя се само името, което вижда потребителят.
--
-- ЗАЩО SQL, А НЕ ИМПОРТ: бутонът „Зареди сметкоплан“ отказва при заварени сметки (ACCOUNT_CHART_3),
-- а повторният внос пренаписва ЦЕЛИЯ ред по кода — твърде широко за смяна на две имена.
--
-- Пуска се РЪЧНО; редът спрямо старта на приложението е без значение (няма нова колона):
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-07-bg-coa-704-existing-base.sql
--
-- ⚠️ САМО DEV. По продукция — само след бекъп и след като придружаващата проверка е прочетена от
-- ЧОВЕК. Второто пускане дава само `skip` редове.
--
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- КАКВО СЕ ПИПА И КАКВО НЕ
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
--   `name`, `label`, `version`, `updated_on` на 704 и 706 във фирмите с BG сметкоплан. Нищо друго.
--
--   · `label` е съхранено изчислимо поле (Account.xml: code + "_" + company.code + " - " + name).
--     Приложението го преизчислява само при запис през ORM; гол UPDATE на `name` би оставил
--     падащите списъци да показват старото име.
--   · `version + 1`: отворена форма със старата версия получава OptimisticLock вместо тихо да
--     върне старото име при запис.
--   · Целевото име с остарял `label` (името е сменено с гол SQL) → довършва се само label-ът.
--   · Салда, статии, `account_move_line`, справки, мапинг — НЕ. `account_account.id` не се мени.
--
-- ⚠️ ИМЕ, РАЗЛИЧНО И ОТ СТАРОТО, И ОТ ЦЕЛЕВОТО, НЕ СЕ ПРЕЗАПИСВА. Сметкопланът е на предприятието
-- (ЗСч); ръчно сменено име е избор на счетоводителя. Пропуска се с ⚠ и проверката го показва като
-- `РЪЧНО` — решението е човешко.
--
-- ⚠️ ОСЧЕТОВОДЕНОТО ПО 704 СЕ ОДИТИРА, НЕ СЕ СПИРА. Докато 704 се казваше „материали“, по нея може
-- да са минали продажби на материали. Те и досега отиваха в SAF-T като „Приходи от наеми“ (мапингът
-- е IDENTITY), тоест преименуването не мени нито един износ — само прави разминаването видимо.
-- Брой и салдо се печатат; рекласът към 706, ако е нужен, е РЕДОВНА статия, не UPDATE.

BEGIN;

DROP TABLE IF EXISTS pg_temp.bg_704_name;
CREATE TEMP TABLE bg_704_name (code text NOT NULL, old_name text NOT NULL, new_name text NOT NULL);
INSERT INTO bg_704_name VALUES
  ('704', 'Приходи от продажба на материали', 'Приходи от наеми'),
  ('706', 'Приходи от продажба на материали', 'Приходи от продажба на суровини/материали');

-- ═══ 1 · САМАТА МИГРАЦИЯ ═══════════════════════════════════════════════════════════════════════

DO $mig$
DECLARE
  c            record;
  r            record;
  v_acc_id     bigint;
  v_name       text;
  v_label      text;
  v_want_label text;
  v_posted     bigint;
  v_balance    numeric;
  v_companies  integer := 0;
  n_renamed    integer := 0;
  n_relabeled  integer := 0;
  n_custom     integer := 0;
  n_missing    integer := 0;
  n_skipped    integer := 0;
BEGIN
  RAISE NOTICE 'BG_COA/704 ═══ начало ═══ база=% потребител=%', current_database(), current_user;

  IF to_regclass('public.account_account') IS NULL THEN
    RAISE EXCEPTION
      'BG_COA/704: таблица account_account липсва — не е Axelor база. Нищо не е пипано.';
  END IF;

  -- ── 1.1 · ОБХВАТ: ФИРМИТЕ С BG ПЛАНА ────────────────────────────────────────────────────────
  -- Опорите са 4551 и 4552, както в кръг 1 и 2: двата кода ги има в BG_COA и в нито един друг план
  -- в репото. ⚠️ Без опора файлът би пипнал и FRA_PCG — там 704 и 706 също съществуват.
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
    RAISE NOTICE 'BG_COA/704 ── фирма [%] % (id=%)', c.ccode, c.cname, c.cid;

    FOR r IN SELECT * FROM bg_704_name ORDER BY code LOOP
      -- `label` по формулата от Account.xml; Java конкатенацията пише null като „null“.
      v_want_label := r.code || '_' || coalesce(c.raw_code, 'null') || ' - ' || r.new_name;

      SELECT acc.id, acc.name, acc.label INTO v_acc_id, v_name, v_label
        FROM account_account acc
       WHERE acc.company = c.cid AND acc.code = r.code;

      IF NOT FOUND THEN
        n_missing := n_missing + 1;
        RAISE NOTICE 'BG_COA/704 ⚠ missing [%] % — сметката я няма; нищо за преименуване',
          c.ccode, r.code;
        CONTINUE;
      END IF;

      IF v_name = r.new_name THEN
        IF v_label IS NOT DISTINCT FROM v_want_label THEN
          n_skipped := n_skipped + 1;
          RAISE NOTICE 'BG_COA/704 skip    [%] % — вече е „%“', c.ccode, r.code, r.new_name;
        ELSE
          -- Името е сменено на ръка с гол SQL, без label-а: довършва се само label-ът.
          UPDATE account_account
             SET label = v_want_label, version = coalesce(version, 0) + 1, updated_on = now()
           WHERE id = v_acc_id;
          n_relabeled := n_relabeled + 1;
          RAISE NOTICE 'BG_COA/704 RELABEL [%] % — label „%“ → „%“', c.ccode, r.code, v_label,
            v_want_label;
        END IF;
        CONTINUE;
      END IF;

      IF v_name <> r.old_name THEN
        n_custom := n_custom + 1;
        RAISE NOTICE 'BG_COA/704 ⚠ РЪЧНО  [%] % носи „%“ — не се пипа, решава счетоводителят',
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
         SET name = r.new_name,
             label = v_want_label,
             version = coalesce(version, 0) + 1,
             updated_on = now()
       WHERE id = v_acc_id;
      n_renamed := n_renamed + 1;
      RAISE NOTICE 'BG_COA/704 RENAMED [%] % „%“ → „%“ · % осчетоводени реда, салдо (Dt−Kt) = %',
        c.ccode, r.code, v_name, r.new_name, v_posted, to_char(v_balance, 'FM999999999990.00');
    END LOOP;
  END LOOP;

  IF v_companies = 0 THEN
    RAISE NOTICE 'BG_COA/704: нула фирми с BG сметкоплан (опори 4551 и 4552). Нищо за правене.';
  END IF;

  RAISE NOTICE 'BG_COA/704 ═══ край ═══ фирми=% | преименувани=% | само label=% | skip=%',
    v_companies, n_renamed, n_relabeled, n_skipped;
  RAISE NOTICE 'BG_COA/704                  ръчни=% | липсващи=%', n_custom, n_missing;
END
$mig$;

-- ═══ 2 · СВЕРКА ЗА КОНВЕРГЕНЦИЯ — ВСЕ ОЩЕ В ТРАНЗАКЦИЯТА ══════════════════════════════════════
-- Проверката гледа РЕЗУЛТАТА, а не пътя: нито една BG фирма не държи старото име на 704/706, и
-- всяка сметка с целевото име носи съответния `label`.

DO $conv$
DECLARE
  v_bad  text := '';
  x      record;
BEGIN
  FOR x IN
    SELECT coalesce(co.code, '?') AS ccode, acc.code, acc.name, acc.label,
           acc.code || '_' || coalesce(co.code, 'null') || ' - ' || acc.name AS want_label,
           n.old_name, n.new_name
      FROM account_account acc
      JOIN base_company co ON co.id = acc.company
      JOIN bg_704_name n ON n.code = acc.code
     WHERE EXISTS (SELECT 1 FROM account_account a1 WHERE a1.company = co.id AND a1.code = '4551')
       AND EXISTS (SELECT 1 FROM account_account a2 WHERE a2.company = co.id AND a2.code = '4552')
     ORDER BY co.id, acc.code
  LOOP
    IF x.name = x.old_name THEN
      v_bad := v_bad || format(E'\n  · [%s] %s носи още старото име %L', x.ccode, x.code, x.name);
    ELSIF x.name = x.new_name AND x.label IS DISTINCT FROM x.want_label THEN
      v_bad := v_bad || format(E'\n  · [%s] %s: label %L вместо %L', x.ccode, x.code, x.label,
                               x.want_label);
    END IF;
  END LOOP;

  IF v_bad <> '' THEN
    RAISE EXCEPTION USING MESSAGE = format(
      E'BG_COA/704: НЕ КОНВЕРГИРА към целевото състояние — НИЩО НЕ Е ЗАПИСАНО:%s', v_bad);
  END IF;
  RAISE NOTICE 'BG_COA/704 ✔ конвергенцията е сверена';
END
$conv$;

COMMIT;
