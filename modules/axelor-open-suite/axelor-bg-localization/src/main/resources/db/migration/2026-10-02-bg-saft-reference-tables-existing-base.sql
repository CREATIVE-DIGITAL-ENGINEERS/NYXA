-- Референтните таблици на SAF-T, кръг 3 — чист-ъпът върху СЪЩЕСТВУВАЩА база.
-- Идемпотентно, транзакционно, fail-loud.
--
-- ЗАЩО. Кръг 3 закача кодовете на НАП за сметките, данъците и плащанията. Самите таблици са
-- НАЧАЛНИ ДАННИ и идват сами — `DataInitSyncService` ги внася при всяко вдигане на приложението, а
-- ключът им е importId, тоест upsert без триене. Този файл НЕ ги пипа. Той довършва ЕДИНСТВЕНОТО,
-- което вносът не може: премахва четири заварени дубликата от сметкоплана и двата режима на
-- плащане, които номенклатурата на НАП не познава.
--
-- ⚠️ ЗАЩО ИЗОБЩО СЕ МАХА НЕЩО. Таблицата „сметка → код по НАП“ носи по един ред за ВСЯКА сметка на
-- BG_COA. Дубликат значи две сметки с един и същи код по НАП, тоест един и същи оборот, изнесен на
-- две места — или, ако се изключи едната, оборот, който изчезва от износа. Нито едното не е
-- състояние, което таблицата може да изрази, и точно затова чист-ъпът е част от ТОЗИ кръг.
--
-- ЗАЩО SQL, А НЕ ИМПОРТ — същите три довода като при кръг 2, проверени наново:
--   1. Бутонът „Зареди сметкоплан“ отказва при заварена йерархия (ACCOUNT_CHART_3).
--   2. Вносът МОЖЕ да добавя (ключът е кодът), но не може да ТРИЕ — а тук се трие.
--   3. `ddl=update` е за схема, не за данни.
--
-- Пуска се РЪЧНО, между `git pull` и старта на приложението с новия код:
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-02-bg-saft-reference-tables-existing-base.sql
--
-- ⚠️ САМО DEV. По продукция — само след бекъп, в прозорец за поддръжка и след като придружаващата
-- проверка е прочетена от ЧОВЕК. Второто пускане дава само `skip` редове.
--
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- ДЕЛТАТА — НА ФИРМА
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
--   4 СМЕТКИ ОТПАДАТ (461 → 457), оборотът им минава към заместителя:
--       105 „Премии, свързани с капитал“      → 115 „Резерви, свързани с капитал“
--       305 „Продукти“                        → 303 „Продукция“ (НАП я нарича „Продукти“)
--       631 „Загуби/разходи от обезценка“     → 608 „Разходи от последващи оценки на активи“
--       793 „Други извънредни приходи“        → 799 „Извънредни приходи“
--   12 РЕДА В СПРАВКИТЕ СЕ ТРИЯТ + 12 РОДИТЕЛСКИ ПРАВИЛА СЕ ЧИСТЯТ.
--   2 РЕДА В СПРАВКИТЕ СЕ ПРЕНАСОЧВАТ (отчетът за собствения капитал).
--   1 ВРЪЗКА В AccountConfig: несъбираеми вземания 631 → 608.
--   3 НОВИ РЕЖИМА НА ПЛАЩАНЕ (прихващане вх./изх., подотчетно лице). Нищо НЕ се маха.
--
-- ⚠️ ЗАЩО 12 РЕДА СЕ ТРИЯТ, А 2 СЕ ПРЕНАСОЧВАТ — И ТОВА НЕ Е ПРЕЦЕНКА.
-- Четиринайсет реда в справките четат четирите изчезващи сметки. За ДВАНАЙСЕТ от тях заместителят
-- ВЕЧЕ има собствен ред в СЪЩАТА справка (R_EXP_631 и R_EXP_608 са и двата в баланса по НСС).
-- Пренасочен, редът би събрал 608 два пъти. Затова се трие, а препратката му се маха и от
-- родителското правило. ОСТАНАЛИТЕ ДВА — SC_O_105 и SC_C_105 в „Отчет за собствения капитал“ — са
-- ЕДИНСТВЕНИЯТ източник на реда „Премийни резерви“ и нямат брат. Те се пренасочват.
--
-- ⚠️ СЛЕДСТВИЕ, КОЕТО ТРЯБВА ДА СЕ ЗНАЕ ПРЕДИ ПУСКАНЕ. В двата баланса редът „II. Премии от емисии“
-- (P_ST1_2 и MP_ST1_2) губи единствения си верен вход и остава само с P_1_2_104 „Капитал на
-- предприятия с нестопанска дейност“. Салдото на бившата 105 се явява в „IV. Резерви“, където стои
-- 115. Това е последица от решението 105 → 115 по ИМЕ. По СМИСЪЛ премията от емисия е НАП 114
-- „Резерви от емисия на акции“, но и тя се чете в „IV. Резерви“, тоест изборът мести представянето
-- и в двата случая. Преместването на реда „Премии от емисии“ е отделно счетоводно решение.
--
-- ⚠️ ЗАЩО ДВАТА РЕЖИМА „ДРУГО“ СЕ МАХАТ. `nomenclatures/nra/nom_paymentmethod.csv` е ЦЯЛАТА
-- номенклатура на НАП: три метода и девет двойки метод-механизъм. Остатъчно „друго“ в нея НЯМА,
-- тоест плащане с такъв режим не може да бъде описано в износа изобщо. На тяхно място влизат двата
-- режима, които НАП познава и които Никса досега нямаше: прихващане (02/97) и подотчетни лица
-- (02/99).
--
-- ⚠️ РЕЖИМИТЕ НА ПЛАЩАНЕ НЕ СЕ ПИПАТ ТУК — само се ДОБАВЯТ два, които НАП познава, а Никса нямаше:
-- прихващане (метод 02 / механизъм 97, вх. и изх.) и подотчетно лице (02/99). Изваждането на
-- остатъчните „Друго постъпление" / „Друго плащане", пътищата в OCR-а и миграцията за заварените
-- записи, които ги сочат, са ОТДЕЛНА писта и ОТДЕЛЕН PR. Тук нищо не се маха и нищо не се
-- пренасочва: `bg_saf_payment_mapping.csv` просто ЗАПИСВА, че номенклатурата няма как да ги опише
-- (isExcluded), и толкова.

\set ON_ERROR_STOP on
BEGIN;

-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- ДАННИТЕ — като таблици, не като повторен SQL
-- ═══════════════════════════════════════════════════════════════════════════════════════════════

-- Четирите дубликата и заместителите им. `expect_type` е технологичният тип, който ДВЕТЕ сметки
-- трябва да носят: различен тип значи, че това не е дубликат, а разместване, и миграцията спира.
CREATE TEMP TABLE bg_n3_dup (old_code text, new_code text, expect_type text, label text)
  ON COMMIT DROP;
INSERT INTO bg_n3_dup VALUES
  ('105', '115', 'equity',       'Премии, свързани с капитал → Резерви, свързани с капитал'),
  ('305', '303', 'currentAsset', 'Продукти → Продукция'),
  ('631', '608', 'charge',       'Загуби от обезценка → Разходи от последващи оценки'),
  ('793', '799', 'income',       'Други извънредни приходи → Извънредни приходи');

-- Дванайсетте реда, които се ТРИЯТ, и родителят, от чието правило трябва да падне кодът им.
-- `import_suffix` е importId-то от CSV-то; в базата ключът е `<код на фирма>-<суфикс>`.
CREATE TEMP TABLE bg_n3_dropline (import_suffix text, line_code text, parent_code text, acct text)
  ON COMMIT DROP;
INSERT INTO bg_n3_dropline VALUES
  ('2197', 'P_1_2_105',  'P_ST1_2',     '105'),
  ('2455', 'MP_1_2_105', 'MP_ST1_2',    '105'),
  ('2110', 'A_2_1_305',  'A_ST2_1',     '305'),
  ('2370', 'MA_2_1_305', 'MA_ST2_1',    '305'),
  ('2037', 'E_1_3_631',  'E_ST1_3',     '631'),
  ('2154', 'R_EXP_631',  'R_EXP',       '631'),
  ('2302', 'MO_631',     'MO_ST_18597', '631'),
  ('2412', 'R_EXP_631',  'R_EXP',       '631'),
  ('2026', 'I_3_1_793',  'I_ST3_1',     '793'),
  ('2149', 'R_INC_793',  'R_INC',       '793'),
  ('2293', 'MO_793',     'MO_ST_65640', '793'),
  ('2407', 'R_INC_793',  'R_INC',       '793');

-- Двата реда без брат — пренасочват се, кодът им също се сменя, защото носи сметката в името си.
CREATE TEMP TABLE bg_n3_remapline
  (import_suffix text, old_line_code text, new_line_code text, old_acct text, new_acct text,
   parent_code text)
  ON COMMIT DROP;
INSERT INTO bg_n3_remapline VALUES
  ('2534', 'SC_O_105', 'SC_O_115', '105', '115', 'SC_OPEN_55018'),
  ('2553', 'SC_C_105', 'SC_C_115', '105', '115', 'SC_CLOSE_55018');

-- Трите нови режима. Колоните са точно онези, които `account_paymentMode.csv` носи за режим без
-- банкова поръчка — INC_CSH/OUT_CSH са образецът.
CREATE TEMP TABLE bg_n3_pm_new
  (import_suffix text, code text, name text, in_out int, type_sel int) ON COMMIT DROP;
INSERT INTO bg_n3_pm_new VALUES
  ('14', 'INC_OFFSET', 'Постъпление от прихващане',   1, 10),
  ('15', 'OUT_OFFSET', 'Плащане чрез прихващане',     2, 10),
  ('16', 'OUT_ADV',    'Плащане през подотчетно лице', 2, 10);

-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- МИГРАЦИЯТА
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
DO $mig$
DECLARE
  c            record;
  x            record;
  f            record;
  v_old_id     bigint;
  v_new_id     bigint;
  v_line_id    bigint;
  v_cnt        bigint;
  v_moved      bigint;
  v_tech       text;
  v_rule       text;
  v_new_rule   text;
  v_rt_id      bigint;
  v_max_id     bigint;
  v_seq_last   bigint;
  v_blocked    boolean;
  v_companies  integer := 0;
  n_dropped    integer := 0;
  n_moved      integer := 0;
  n_delline    integer := 0;
  n_remapline  integer := 0;
  n_rules      integer := 0;
  n_config     integer := 0;
  n_pm_new     integer := 0;
  n_skipped    integer := 0;
  blockers     text    := '';
BEGIN
  RAISE NOTICE 'SAFT/N3 ═══ начало ═══ база=% потребител=%', current_database(), current_user;

  IF to_regclass('public.account_account') IS NULL THEN
    RAISE EXCEPTION 'SAFT/N3: таблица account_account липсва — не е Axelor база. Нищо не е пипано.';
  END IF;

  -- ⚠️ ТОЗИ ФАЙЛ ПРАВИ INSERT-и в account_payment_mode. Изостанала секвенция връща ЗАЕТО id и
  -- INSERT-ът пада по средата — класическото след ръчно вмъкване или след дъмп.
  SELECT coalesce(max(id), 0) INTO v_max_id FROM account_payment_mode;
  v_seq_last := coalesce(pg_sequence_last_value('public.account_payment_mode_seq'::regclass), 0);
  IF v_seq_last < v_max_id THEN
    RAISE EXCEPTION
      E'SAFT/N3: секвенцията account_payment_mode_seq е на %, а max(id) е %.\n'
      '  Следващият nextval() би върнал ЗАЕТО id. Поправка:\n'
      '      SELECT setval(''public.account_payment_mode_seq'',\n'
      '          (SELECT max(id) FROM account_payment_mode));\n'
      '  Нищо не е пипано.', v_seq_last, v_max_id;
  END IF;

  -- ── 0 · ТРИТЕ НОВИ РЕЖИМА НА ПЛАЩАНЕ ───────────────────────────────────────────────────────
  -- ⚠️ САМО ДОБАВЯНЕ. Нито един заварен режим не се маха и нито един запис не се пренасочва —
  -- изваждането на остатъчните „Друго" е отделна писта и отделен PR. Тук влизат двата режима,
  -- които НАП познава, а Никса нямаше: прихващане (02/97) и подотчетно лице (02/99).
  -- ⚠️ ИЗВЪН ЦИКЪЛА ПО ФИРМИ, И ТОВА НЕ Е ПОДРЕДБА. `account_payment_mode` НЯМА колона `company`
  -- — вътре в цикъла стъпката би се пуснала по веднъж на фирма и би броила едно и също по няколко
  -- пъти (а при втора фирма `PERFORM` щеше да я прескача като „вече съществува").

  FOR x IN SELECT * FROM bg_n3_pm_new ORDER BY import_suffix LOOP
    PERFORM 1 FROM account_payment_mode WHERE code = x.code;
    IF FOUND THEN
      n_skipped := n_skipped + 1;
      CONTINUE;
    END IF;
    INSERT INTO account_payment_mode
      (id, version, created_on, updated_on, import_id, name, code, in_out_select, type_select,
       can_select_in_payment_session, days_margin_on_pay_session, accounting_method_select,
       accounting_trigger_select, generate_bank_order, conso_bank_order_line_per_partner,
       order_type_select)
    VALUES
      (nextval('public.account_payment_mode_seq'), 0, now(), now(), x.import_suffix, x.name,
       x.code, x.in_out, x.type_sel, false, 0, 0, 1, false, false, 0);
    n_pm_new := n_pm_new + 1;
    RAISE NOTICE 'SAFT/N3   NEW PAYMENT MODE % (%) in_out=% type=%',
      x.code, x.name, x.in_out, x.type_sel;
  END LOOP;

  -- ── 1 · ОБХВАТ: ФИРМИТЕ С BG ПЛАНА ──────────────────────────────────────────────────────────
  -- Същите опори като при кръг 2 — 4551 и 4552 ги има в BG_COA и в НИТО ЕДИН друг план в репото.
  -- Този кръг не ги пипа, тъй че отпечатъкът важи и преди, и след миграцията.
  FOR c IN
    SELECT co.id AS cid, coalesce(co.code, '?') AS ccode, co.name AS cname
      FROM base_company co
     WHERE EXISTS (SELECT 1 FROM account_account acc
                    WHERE acc.company = co.id AND acc.code = '4551')
       AND EXISTS (SELECT 1 FROM account_account acc
                    WHERE acc.company = co.id AND acc.code = '4552')
     ORDER BY co.id
  LOOP
    v_companies := v_companies + 1;
    RAISE NOTICE 'SAFT/N3 ── фирма [%] % ──', c.ccode, c.cname;

    -- ── 1.1 · РЕДОВЕТЕ В СПРАВКИТЕ, КОИТО СЕ ТРИЯТ ────────────────────────────────────────────
    -- Първо редовете, после сметките: ред, който сочи изтрита сметка, дава ТИХА НУЛА в отчета, а
    -- наборът `..._account_set` още пази стария идентификатор и го връща въпреки всичко.
    FOR x IN SELECT * FROM bg_n3_dropline LOOP
      -- ⚠️ СПРАВКАТА СЕ ХВАЩА ПРЕДИ ИЗТРИВАНЕТО. Родителят се търси по КОД, а кодовете R_EXP_631
      -- и R_INC_793 се срещат в ДВЕ справки — единственото, което ги различава, е report_type на
      -- самия изтриван ред. Прочетен СЛЕД DELETE-а, той вече го няма и чистенето на правилото се
      -- пропуска мълчаливо: редът изчезва, а сборът на родителя продължава да го сочи.
      SELECT cl.id, cl.account_code, cl.report_type INTO v_line_id, v_tech, v_rt_id
        FROM account_accounting_report_config_line cl
       WHERE cl.import_id = c.ccode || '-' || x.import_suffix;
      IF NOT FOUND THEN
        n_skipped := n_skipped + 1;
        CONTINUE;
      END IF;
      IF v_tech IS DISTINCT FROM x.acct THEN
        blockers := blockers || format(
          E'\n  · [%s] DROPLINE %s (%s-%s): accountCode е %L, а се очакваше %L.' ||
          E'\n      Редът е пипан след внасянето — изтриването му би отнесло чужда промяна.',
          c.ccode, x.line_code, c.ccode, x.import_suffix, v_tech, x.acct);
        CONTINUE;
      END IF;
      DELETE FROM account_accounting_report_config_line_account_set
       WHERE account_accounting_report_config_line = v_line_id;
      DELETE FROM account_accounting_report_config_line WHERE id = v_line_id;
      n_delline := n_delline + 1;

      -- Препратката пада и от родителското правило. ⚠️ ТЪРСИ СЕ В СЪЩАТА СПРАВКА: кодовете
      -- R_EXP_631 и R_INC_793 се срещат в ДВЕ справки (28 и 46) и правилото на едната не бива да
      -- се чисти от реда на другата.
      SELECT cl.id, cl.rule INTO v_line_id, v_rule
        FROM account_accounting_report_config_line cl
       WHERE cl.code = x.parent_code AND cl.report_type = v_rt_id
       LIMIT 1;
      IF v_rule IS NULL OR position(x.line_code IN v_rule) = 0 THEN
        n_skipped := n_skipped + 1;
        CONTINUE;
      END IF;
      v_new_rule := regexp_replace(v_rule, '\s*\+\s*' || x.line_code || '(?![A-Za-z0-9_])', '', 'g');
      v_new_rule := regexp_replace(v_new_rule, x.line_code || '(?![A-Za-z0-9_])\s*\+\s*', '', 'g');
      IF v_new_rule = v_rule THEN
        blockers := blockers || format(
          E'\n  · [%s] RULE %s: кодът %s стои в правилото, но не като събираемо: %L',
          c.ccode, x.parent_code, x.line_code, v_rule);
        CONTINUE;
      END IF;
      UPDATE account_accounting_report_config_line
         SET rule = v_new_rule, updated_on = now() WHERE id = v_line_id;
      n_rules := n_rules + 1;
    END LOOP;

    -- ── 1.2 · ДВАТА РЕДА БЕЗ БРАТ: ПРЕНАСОЧВАНЕ ───────────────────────────────────────────────
    FOR x IN SELECT * FROM bg_n3_remapline LOOP
      SELECT cl.id, cl.account_code INTO v_line_id, v_tech
        FROM account_accounting_report_config_line cl
       WHERE cl.import_id = c.ccode || '-' || x.import_suffix;
      IF NOT FOUND THEN
        n_skipped := n_skipped + 1;
        CONTINUE;
      END IF;
      IF v_tech = x.new_acct THEN
        n_skipped := n_skipped + 1;
        CONTINUE;
      END IF;
      IF v_tech IS DISTINCT FROM x.old_acct THEN
        blockers := blockers || format(
          E'\n  · [%s] REMAPLINE %s: accountCode е %L, а се очакваше %L или вече %L.',
          c.ccode, x.old_line_code, v_tech, x.old_acct, x.new_acct);
        CONTINUE;
      END IF;
      UPDATE account_accounting_report_config_line
         SET account_code = x.new_acct, code = x.new_line_code, updated_on = now()
       WHERE id = v_line_id;
      -- ⚠️ СТАРИЯТ НАБОР СЕ ЧИСТИ ЗАДЪЛЖИТЕЛНО. Смятането прави accountSet ∪ LIKE(accountCode):
      -- остане ли там старата 105, тя се връща в отчета въпреки пренасочването, и то мълчаливо.
      DELETE FROM account_accounting_report_config_line_account_set
       WHERE account_accounting_report_config_line = v_line_id;
      INSERT INTO account_accounting_report_config_line_account_set
        (account_accounting_report_config_line, account_set)
      SELECT v_line_id, acc.id FROM account_account acc
       WHERE acc.company = c.cid AND acc.code = x.new_acct;
      n_remapline := n_remapline + 1;

      -- Правилото на родителя сочи реда по КОД, тоест и то трябва да се пренапише.
      UPDATE account_accounting_report_config_line
         SET rule = regexp_replace(rule, x.old_line_code || '(?![A-Za-z0-9_])',
                                   x.new_line_code, 'g'),
             updated_on = now()
       WHERE code = x.parent_code
         AND rule LIKE '%' || x.old_line_code || '%'
         AND report_type IN (
               SELECT p.report_type FROM account_accounting_report_config_line p
                WHERE p.id = v_line_id);
      GET DIAGNOSTICS v_cnt = ROW_COUNT;
      n_rules := n_rules + v_cnt;
    END LOOP;

    -- ── 1.3 · AccountConfig: НЕСЪБИРАЕМИТЕ ВЗЕМАНИЯ ───────────────────────────────────────────
    -- ⚠️ БЕЗ ТОВА 631 НЕ МОЖЕ ДА СЕ ИЗТРИЕ ВЪОБЩЕ — чуждият ключ я държи. И по-лошото: пропусната,
    -- настройката щеше да сочи изчезнала сметка, а търсенето при вноса (`self.code = :code`)
    -- връща NULL мълчаливо, вместо да гръмне.
    SELECT acc.id INTO v_new_id FROM account_account acc
     WHERE acc.company = c.cid AND acc.code = '608';
    IF v_new_id IS NOT NULL THEN
      UPDATE account_account_config cfg
         SET irrecoverable_account = v_new_id, updated_on = now()
        FROM account_account old
       WHERE cfg.company = c.cid AND cfg.irrecoverable_account = old.id
         AND old.company = c.cid AND old.code = '631';
      GET DIAGNOSTICS v_cnt = ROW_COUNT;
      n_config := n_config + v_cnt;
    END IF;

    -- ── 1.4 · ЧЕТИРИТЕ ДУБЛИКАТА ──────────────────────────────────────────────────────────────
    FOR x IN SELECT * FROM bg_n3_dup LOOP
      SELECT acc.id INTO v_old_id FROM account_account acc
       WHERE acc.company = c.cid AND acc.code = x.old_code;
      IF NOT FOUND THEN
        n_skipped := n_skipped + 1;
        CONTINUE;
      END IF;

      SELECT acc.id INTO v_new_id FROM account_account acc
       WHERE acc.company = c.cid AND acc.code = x.new_code;
      IF v_new_id IS NULL THEN
        blockers := blockers || format(
          E'\n  · [%s] DUP %s: заместителят %s го НЯМА в плана.' ||
          E'\n      Той идва с кръг 2 (#247) — пусни първо неговата миграция.',
          c.ccode, x.old_code, x.new_code);
        CONTINUE;
      END IF;

      -- ⚠️ ГЕЙТ ПО ТИП. „Дубликат“ значи ДВЕ сметки за едно и също нещо. Различен технологичен тип
      -- значи, че това вече не е дубликат — и че преместването на оборота би прехвърлило салдо
      -- между раздели на баланса. Тогава решението е реклас с редовна статия, не този файл.
      PERFORM 1 FROM account_account a JOIN account_account_type t ON t.id = a.account_type
       WHERE a.id = v_old_id AND t.technical_type_select = x.expect_type;
      IF NOT FOUND THEN
        blockers := blockers || format(
          E'\n  · [%s] DUP %s: типът ѝ не е %L. Това не е дубликатът, който този файл очаква.',
          c.ccode, x.old_code, x.expect_type);
        CONTINUE;
      END IF;
      PERFORM 1 FROM account_account a JOIN account_account_type t ON t.id = a.account_type
       WHERE a.id = v_new_id AND t.technical_type_select = x.expect_type;
      IF NOT FOUND THEN
        blockers := blockers || format(
          E'\n  · [%s] DUP %s → %s: заместителят не е от тип %L. Прехвърлянето би преместило' ||
          E' салдо между раздели на баланса.', c.ccode, x.old_code, x.new_code, x.expect_type);
        CONTINUE;
      END IF;

      -- ⚠️ ДЕЦАТА СА ГЕЙТ, А НЕ СЛУЧАЙ ЗА ОБРАБОТКА. Нито един от четирите кода няма деца в
      -- seed-а; има ли ги в базата, някой е добавил аналитично ниво под сметка, която този файл
      -- смята за дубликат, и изборът на нов родител е негов, не наш.
      SELECT count(*) INTO v_cnt FROM account_account WHERE parent_account = v_old_id;
      IF v_cnt > 0 THEN
        blockers := blockers || format(
          E'\n  · [%s] DUP %s: носи %s подсметки. Пренареждането им е решение на счетоводител.',
          c.ccode, x.old_code, v_cnt);
        CONTINUE;
      END IF;

      -- ПРЕМЕСТВАНЕ НА ОБОРОТА. Осчетоводените редове минават към заместителя заедно с аналитиката
      -- си; това е целта на чист-ъпа, а не страничен ефект.
      UPDATE account_move_line SET account = v_new_id, updated_on = now()
       WHERE account = v_old_id;
      GET DIAGNOSTICS v_moved = ROW_COUNT;
      IF v_moved > 0 THEN
        n_moved := n_moved + v_moved;
        RAISE NOTICE 'SAFT/N3   MOVE [%] % → % : % осчетоводени реда',
          c.ccode, x.old_code, x.new_code, v_moved;
      END IF;

      -- Наборите на справките и съвместимите сметки се ЧИСТЯТ, а не се пренасочват: редовете,
      -- които четат стария код, вече са изтрити или пренасочени по-горе и наборите им са пресни.
      DELETE FROM account_accounting_report_config_line_account_set WHERE account_set = v_old_id;
      DELETE FROM account_account_compatible_account_set
       WHERE account_account = v_old_id OR compatible_account_set = v_old_id;

      -- ⚠️ И СЕГА ВСЕКИ ОСТАНАЛ ЧУЖД КЛЮЧ — ДИНАМИЧНО. Схемата носи десетки връзки към
      -- account_account (настройки, управление на сметки, категории ДМА, данъчни сметки…). Списък
      -- на ръка остарява при следващия модул; затова тук се ПИТА каталогът и всяка още сочеща
      -- връзка СПИРА миграцията поименно, вместо изтриването да падне с „violates foreign key“.
      -- ⚠️ ФЛАГЪТ Е ЛОКАЛЕН, А НЕ `blockers <> ''`. Проверка срещу общия низ би спряла изтриването
      -- на ВСЕКИ следващ дубликат заради чужда пречка отпреди него — и броячът накрая би лъгал.
      v_blocked := false;
      FOR f IN
        SELECT cl.relname::text AS tbl, att.attname::text AS col
          FROM pg_constraint con
          JOIN pg_class  cl  ON cl.oid  = con.conrelid
          JOIN pg_class  rcl ON rcl.oid = con.confrelid
          JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = con.conkey[1]
         WHERE con.contype = 'f'
           AND rcl.relname = 'account_account'
           AND array_length(con.conkey, 1) = 1
         ORDER BY 1, 2
      LOOP
        EXECUTE format('SELECT count(*) FROM %I WHERE %I = $1', f.tbl, f.col)
          INTO v_cnt USING v_old_id;
        IF v_cnt > 0 THEN
          blockers := blockers || format(
            E'\n  · [%s] DUP %s: още %s реда в %s.%s сочат към нея.' ||
            E'\n      Пренасочи ги към %s с решение на счетоводител, после пак този файл.',
            c.ccode, x.old_code, v_cnt, f.tbl, f.col, x.new_code);
          v_blocked := true;
        END IF;
      END LOOP;
      IF v_blocked THEN
        CONTINUE;
      END IF;

      DELETE FROM account_account WHERE id = v_old_id;
      n_dropped := n_dropped + 1;
      RAISE NOTICE 'SAFT/N3   DROP [%] % (%)', c.ccode, x.old_code, x.label;
    END LOOP;

  END LOOP;

  IF v_companies = 0 THEN
    RAISE EXCEPTION
      E'SAFT/N3: нито една фирма с BG сметкоплан (опори 4551 и 4552). Нищо не е пипано.';
  END IF;

  IF blockers <> '' THEN
    RAISE EXCEPTION E'SAFT/N3 ОТКАЗ — нищо не е записано.\n%', blockers;
  END IF;

  RAISE NOTICE 'SAFT/N3 ═══ готово ═══ фирми=% · сметки −% · преместени редове % ·'
               ' изтрити редове в справки % · пренасочени % · правила % · AccountConfig % ·'
               ' нови режими % · пропуснати %',
    v_companies, n_dropped, n_moved, n_delline, n_remapline, n_rules, n_config,
    n_pm_new, n_skipped;
END
$mig$;

COMMIT;
