-- Привеждане на BG_COA към сметкоплана на НАП, кръг 1 — върху СЪЩЕСТВУВАЩА база.
-- Идемпотентно, транзакционно, fail-loud.
--
-- ЗАЩО. Никса построи своя сметкоплан преди НАП да обяви обединения си чарт за SAF-T. Девет кода
-- се разминават с него — шест от тях защото целият клас ДМА стои с ЕДНА позиция по-нагоре. Докато
-- разминаването стои, всяко изнасяне към НАП иска превод, а преводът е точно мястото, където
-- салдо сменя сметка без никой да види.
--
-- ⚠️ ЗАДЪЛЖИТЕЛЕН НАЦИОНАЛЕН СМЕТКОПЛАН НЯМА ОТ 2002 г. Сметкопланът е на предприятието (ЗСч).
-- Този файл разгръща ИЗБОРА, записан в `l10n/l10n_bgr/BG_COA/account_account.csv` след счетоводния
-- преглед, а не наложено число.
--
-- ЗАЩО SQL, А НЕ ИМПОРТ. Трите причини от кръг 168 и кръг 442, проверени наново в кода:
--
--   1. Бутонът „Зареди сметкоплан“ (AccountingConfigTemplateService#installAccountChart) брои
--      сметките с `self.company.id = ?1 AND self.parentAccount != null` и хвърля ACCOUNT_CHART_3
--      при брой > 0. Заварена BG база носи 186 такива → бутонът ОТКАЗВА.
--   2. Импортът може да ДОБАВЯ, не да преномерира. И `chart-config-account.xml`, и
--      `chart-config.xml` търсят `self.code = :code AND self.company.id = :_companyId`. Ключът е
--      КОДЪТ: нов код → НОВ ред, същият код → обновен ред. Преномерирането е неизразимо, а
--      премахването — още повече (импортът не трие).
--   3. `ddl=update` е за схема, не за данни.
--
-- Пуска се РЪЧНО, между `git pull` и старта на приложението с новия код:
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-09-30-bg-coa-nra-n1-existing-base.sql
--
-- Преди и след — придружаващата read-only проверка:
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-09-30-bg-coa-nra-n1-verify.sql
--
-- ⚠️ САМО DEV. По продукция — само след бекъп, в прозорец за поддръжка и след като проверката е
-- прочетена от ЧОВЕК. Второто пускане дава само `skip` редове.
--
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- ДЕЛТАТА — ЕДИНАЙСЕТ ОПЕРАЦИИ ПО ЧАРТА И ТРИЙСЕТ И ЧЕТИРИ ПО СПРАВКИТЕ, НА ФИРМА
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
--   ДМА шифт −1 (име, тип и родител НЕПРОМЕНЕНИ, мени се само номерът):
--     203 → 202  Сгради и конструкции          206 → 205  Машини и оборудване
--     204 → 203  Компютърна техника            207 → 206  Транспортни средства
--     205 → 204  Съоръжения                    208 → 207  Офис обзавеждане
--
--   153 → 154  Облигационни заеми → „Дългови инструменти“ (тип и родител непроменени)
--   409 → 40901  Ваучери за храна  (НАП 409 е РОДИТЕЛ на групата; ваучерите са лист под нея)
--   416 → 41902  Съмнителни и спорни вземания  (мапва към НАП 419)
--   C050 → тип `view`  (репортен възел; 481 и 751 остават под нея)
--   124 → ПЕНСИОНИРАНА като `Z124`, архивирана и неактивна
--
-- ⚠️ ЗАЩО ПРЕИМЕНУВАНЕТО НА 124 Е В ПАРКИНГ-КОД, А НЕ ПРОСТО АРХИВИРАНЕ. Продуктът отказва да я
-- изтрие: `AccountAccountRepository#remove()` хвърля ACCOUNT_REGULATORY_REMOVE при
-- `isRegulatoryAccount`, а всички 186 реда в BG_COA го носят. Архивирането обаче НЕ освобождава
-- кода: `com.axelor.db.Query` не филтрира `archived` (нула попадения на думата в целия клас), тъй
-- че вносът на плана — който търси `self.code = :code AND self.company.id = :_companyId` — ще
-- намери архивирания ред и ще го ОБНОВИ. Кръг 2 дава код 124 на НАП „Резултат при несъстоятелност
-- и ликвидация“ (собствен капитал!). Оставена под своя номер, пенсионираната разходна сметка щеше
-- да бъде възкресена под чуждо име, с целия си стар оборот и с `archived = true` — мълчаливо.
-- Затова кодът се мести на `Z124`: сортира се в края на плана, не може да се сблъска с НАП код
-- (те са само цифрови) и прави пенсионирането видимо.
--
-- ⚠️ ЗАЩО ОСЧЕТОВОДЕНИТЕ РЕДОВЕ СПИРАТ САМО ПРИ 124 И C050. Кръг 168 спира при ВСЕКИ rename, и е
-- прав: там сметка сменя ЗНАЧЕНИЕТО си (4554 отива от „ДЗПО – ППФ“ на „Безработица“), тъй че
-- всяка историческа справка би се чела накриво. Тук осем от деветте преименувания менят
-- САМО номера — име, тип и родител са байт по байт същите, а статиите сочат сметката по `id`, не
-- по код. Тоест нито едно историческо число не се мести и гейт не е оправдан; излиза NOTICE.
-- Двете изключения менят СМИСЪЛА:
--   · 124 — от разход става нищо (данъкът минава Дт 123 / Кт 4521); осчетоводен разход по нея иска
--     РЕДОВЕН реклас към 123, не автоматичен;
--   · C050 — става `view`; осчетоводен ред по репортен възел е дефект, който иска решение.
--
-- ⚠️ КАКВО НЕ СЕ ПИПА, И ЗАЩО. `account_account.id` не се мени при нито едно преименуване. Оттам:
--   · `account_move_line.account` е чужд ключ по `id` → НУЛА осчетоводявания се местят;
--   · `account_account_config.doubtful_customer_account` и `account_account_management
--     .purchase_account` са many-to-one → следват реда сами. Промените по едноименните колони в
--     CSV-тата важат САМО за пресен внос;
--   · `account_accounting_report_config_line_account_set` сочи `account_id` → не се пипа. (Кръг 442
--     я пише на ръка, защото ДОБАВЯ редове; тук няма нито един нов ред.)
--
-- ⚠️ СПРАВКИТЕ СЕ ПИПАТ, ЗАЩОТО ТЕХНИЯТ КЛЮЧ Е ТЕКСТ. `account_code` е `text`, а `rule` е груувски
-- израз над КОДОВЕТЕ на редовете. Резолюцията е равенство (`self.code = :code` при вноса,
-- `self.code LIKE ?` БЕЗ залепен `%` при смятането), тъй че изостанал код не гърми — дава тиха
-- нула. А в кръг 2 същият код ще започне да сочи СЪВСЕМ ДРУГА сметка.
--
-- ⚠️ ЗАМЯНАТА В ПРАВИЛАТА Е ПО ТОКЕН, НЕ ПО ПОДНИЗ. `P_3_3_409` е префикс на заварения
-- `P_3_3_4091`. Подниз замяна прави от 4091 → `P_3_3_409011` и отнася „Задържани плащания към
-- доставчици“ от баланса. Затова правилото се ЦЕПИ на „ + “, всеки елемент се сравнява за
-- РАВЕНСТВО и се слепва обратно.
--
-- ⚠️ ДАНЪЧНИЯТ БЛОК. `E_TAX_124` и `MO_TAX_124` се препокабеляват към разчетната 452 с
-- `result_select = 6` (Credit) — кредитният оборот по разчета Е начисленият за периода данък.
-- Стойност 6 е `RESULT_CREDIT` в extra-code-а на `AccountingReportConfigLine.xml` и се изчислява в
-- `AccountingReportValueMoveLineServiceImpl#getMoveLineAmount` (`value = moveLine.getCredit()`).
-- `R_EXP_124` пък се ТРИЕ от двата баланса: там 124 се броеше ВТОРИ ПЪТ — веднъж през `R_EXP`
-- (сборът на разходите, който храни `R_PL`) и веднъж през 123 в собствения капитал.
--
-- ⚠️ ЗАВАРЕН ДЕФЕКТ, КОЙТО ТОЗИ ФАЙЛ НЕ ПОПРАВЯ. BG_COA не закача НИТО ЕДНА колона към custom
-- справките: шестте реда `amt`/`bal` („Стойност“, `result_select = 4`) носят ПРАЗЕН
-- `reportTypeColumnId` и празен `reportType_importId`, тъй че не влизат нито в колонния, нито в
-- линийния списък. FRA_PCG и GBR_COA слагат там своя report type. Оттам
-- `AccountingReportValueServiceImpl#checkAccountingReportType` хвърля REPORT_TYPE_NO_COLUMN за
-- всяка BG справка. Тоест редовете тук са ВЕРНИ, но няма да дадат число, докато колоните не се
-- закачат — отделен PR, отделен преглед.

BEGIN;

-- ═══ 0 · ЦЕЛЕВОТО СЪСТОЯНИЕ, ДОСЛОВНО ОТ CSV-ТАТА ═════════════════════════════════════════════

DROP TABLE IF EXISTS pg_temp.bg_n1_rename;
CREATE TEMP TABLE bg_n1_rename (
  ord         integer NOT NULL,   -- ⚠️ ВЪЗХОДЯЩО: 203→202 ПРЕДИ 204→203, инак unique(code,company)
  old_code    text    NOT NULL,
  new_code    text    NOT NULL,
  old_name    text    NOT NULL,
  new_name    text    NOT NULL,
  expect_tech text    NOT NULL,   -- сверка на типа ПРЕДИ пипане
  gate_posted boolean NOT NULL    -- осчетоводени редове спират ли?
);
INSERT INTO bg_n1_rename VALUES
  (1, '203', '202',   'Сгради и конструкции',         'Сгради и конструкции',         'immobilisation', false),
  (2, '204', '203',   'Компютърна техника',           'Компютърна техника',           'immobilisation', false),
  (3, '205', '204',   'Съоръжения',                   'Съоръжения',                   'immobilisation', false),
  (4, '206', '205',   'Машини и оборудване',          'Машини и оборудване',          'immobilisation', false),
  (5, '207', '206',   'Транспортни средства',         'Транспортни средства',         'immobilisation', false),
  (6, '208', '207',   'Офис обзавеждане',             'Офис обзавеждане',             'immobilisation', false),
  (7, '153', '154',   'Облигационни заеми',           'Дългови инструменти',          'debt',           false),
  (8, '409', '40901', 'Ваучери за храна',             'Ваучери за храна',             'payable',        false),
  (9, '416', '41902', 'Съмнителни и спорни вземания', 'Съмнителни и спорни вземания', 'receivable',     false);

DROP TABLE IF EXISTS pg_temp.bg_n1_retype;
CREATE TEMP TABLE bg_n1_retype (
  code        text NOT NULL,
  from_tech   text NOT NULL,
  to_tech     text NOT NULL,
  keep_kids   text[] NOT NULL
);
INSERT INTO bg_n1_retype VALUES ('C050', 'debt', 'view', ARRAY['481', '751']);

DROP TABLE IF EXISTS pg_temp.bg_n1_park;
CREATE TEMP TABLE bg_n1_park (
  code        text NOT NULL,
  park_code   text NOT NULL,
  expect_name text NOT NULL,
  expect_tech text NOT NULL,
  reclass_to  text NOT NULL
);
INSERT INTO bg_n1_park VALUES ('124', 'Z124', 'Текущ корпоративен данък', 'charge', '123');

-- Редовете на справките. Ключът е `import_id` = `<КОД_ФИРМА>-<номер>`
-- (`<bind column="importId" to="importId" eval="_companyCode + '-' + importId"/>`).
DROP TABLE IF EXISTS pg_temp.bg_n1_line;
CREATE TEMP TABLE bg_n1_line (
  imp        text    NOT NULL,
  old_code   text    NOT NULL,
  new_code   text    NOT NULL,
  old_acc    text    NOT NULL,
  new_acc    text    NOT NULL,
  new_result integer          -- NULL = не се пипа
);
INSERT INTO bg_n1_line VALUES
  ('2096','A_1_2_203','A_1_2_202','203','202',NULL),   ('2356','MA_1_2_203','MA_1_2_202','203','202',NULL),
  ('2097','A_1_2_204','A_1_2_203','204','203',NULL),   ('2357','MA_1_2_204','MA_1_2_203','204','203',NULL),
  ('2098','A_1_2_205','A_1_2_204','205','204',NULL),   ('2358','MA_1_2_205','MA_1_2_204','205','204',NULL),
  ('2099','A_1_2_206','A_1_2_205','206','205',NULL),   ('2359','MA_1_2_206','MA_1_2_205','206','205',NULL),
  ('2100','A_1_2_207','A_1_2_206','207','206',NULL),   ('2360','MA_1_2_207','MA_1_2_206','207','206',NULL),
  ('2101','A_1_2_208','A_1_2_207','208','207',NULL),   ('2361','MA_1_2_208','MA_1_2_207','208','207',NULL),
  ('2115','A_2_2_416','A_2_2_41902','416','41902',NULL),('2375','MA_2_2_416','MA_2_2_41902','416','41902',NULL),
  ('2218','P_3_1_153','P_3_1_154','153','154',NULL),   ('2472','MP_2_1_153','MP_2_1_154','153','154',NULL),
  ('2573','P_3_3_409','P_3_3_40901','409','40901',NULL),('2575','MP_3_2_409','MP_3_2_40901','409','40901',NULL),
  -- Данъкът: кредитният оборот на разчетната 452 е начисленият за периода корпоративен данък.
  ('2085','E_TAX_124','E_TAX_452','124','452',6),      ('2345','MO_TAX_124','MO_TAX_452','124','452',6);

DROP TABLE IF EXISTS pg_temp.bg_n1_line_drop;
CREATE TEMP TABLE bg_n1_line_drop (imp text NOT NULL, expect_code text NOT NULL);
INSERT INTO bg_n1_line_drop VALUES ('2188','R_EXP_124'), ('2446','R_EXP_124');

-- ═══ 1 · САМАТА МИГРАЦИЯ ═══════════════════════════════════════════════════════════════════════
-- Пречките се СЪБИРАТ в `blockers` и се изсипват НАВЕДНЪЖ, за да не се налага пускане след
-- пускане, докато излезе следващата. Всеки RAISE EXCEPTION отменя ЦЯЛАТА транзакция.

DO $mig$
DECLARE
  c            record;
  r            record;
  x            record;
  l            record;
  d            record;
  v_acc_id     bigint;
  v_cnt        bigint;
  v_posted     bigint;
  v_other      bigint;
  v_balance    numeric;
  v_name       text;
  v_tech       text;
  v_rule       text;
  v_new_rule   text;
  v_arch       boolean;
  v_before     boolean;
  v_after      boolean;
  v_status     integer;
  v_drop       text[];
  v_companies  integer := 0;
  n_renamed    integer := 0;
  n_retyped    integer := 0;
  n_parked     integer := 0;
  n_lines      integer := 0;
  n_dropped    integer := 0;
  n_rules      integer := 0;
  n_skipped    integer := 0;
  blockers     text    := '';
BEGIN
  RAISE NOTICE 'BG_COA/N1 ═══ начало ═══ база=% потребител=%', current_database(), current_user;

  IF to_regclass('public.account_account') IS NULL THEN
    RAISE EXCEPTION
      'BG_COA/N1: таблица account_account липсва — не е Axelor база. Нищо не е пипано.';
  END IF;

  -- ⚠️ НУЛА INSERT-а в този файл, тъй че проверка на секвенцията НЕ трябва: и деветте
  -- преименувания, пенсионирането и промяната на типа работят върху заварени редове.

  SELECT array_agg(expect_code) INTO v_drop FROM bg_n1_line_drop;

  -- ── 1.1 · ОБХВАТ: ФИРМИТЕ С BG ПЛАНА ────────────────────────────────────────────────────────
  -- Опорите са 4551 и 4552 — двата кода ги има в BG_COA и в НИТО ЕДИН друг план в репото
  -- (сверено срещу FRA_PCG и GBR_COA). Този кръг не ги пипа, тъй че отпечатъкът остава валиден
  -- и ПРЕДИ, и СЛЕД миграцията — важно за идемпотентността.
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
    RAISE NOTICE 'BG_COA/N1 ── фирма [%] % (id=%)', c.ccode, c.cname, c.cid;

    -- ⚠️ ДОПЪЛНЕНИ С НУЛИ КОДОВЕ. ImportAccount → AccountService#fillAccountCodeOnImport допълва
    -- кода отдясно с нули, когато `hasAccountCodeFixedNbrChar` е вдигнат. В BG не се задейства, но
    -- вдигнат ръчно, сверката по ТОЧЕН код отдолу би мълчала. Затова се проверява.
    SELECT count(*) INTO v_cnt
      FROM account_account acc
     WHERE acc.company = c.cid
       AND acc.code ~ '^(20[3-8]|124|153|409|416)0+$';
    IF v_cnt > 0 THEN
      blockers := blockers || format(
        E'\n  · [%s] намерени %s сметки с код ДОПЪЛНЕН С НУЛИ (hasAccountCodeFixedNbrChar).' ||
        E'\n      Този файл сверява по ТОЧЕН код и на такава база би мълчал.', c.ccode, v_cnt);
      CONTINUE;
    END IF;

    -- ── 1.2 · ФАЗАТА НА ФИРМАТА — „ПРЕДИ“ ИЛИ „СЛЕД“ ──────────────────────────────────────────
    --
    -- ⚠️ БЕЗ ТОВА ВТОРОТО ПУСКАНЕ РАЗВАЛЯ ПЛАНА. Каскадата на ДМА прави всеки НОВ код СТАР код на
    -- съседа: след първото пускане `203` съществува — това е бившата 204 „Компютърна техника“. Ред
    -- по ред проверката „намирам ли стария код“ казва ДА и продължава да мери името ѝ срещу
    -- „Сгради и конструкции“. Оттам второто пускане или пада с десет лъжливи пречки, или — ако
    -- името случайно съвпадне — мести целия клас ОЩЕ ЕДНА позиция надолу.
    --
    -- Затова фазата се чете ВЕДНЪЖ на фирма и от двойка, която НЕ участва в каскадата: 409/40901.
    -- Техните кодове не са ничий чужд източник или цел, тъй че присъствието им е еднозначно.
    PERFORM 1 FROM account_account WHERE company = c.cid AND code = '409';
    v_before := FOUND;
    PERFORM 1 FROM account_account WHERE company = c.cid AND code = '40901';
    v_after := FOUND;

    IF v_before = v_after THEN
      blockers := blockers || format(
        E'\n  · [%s] базата не е нито „преди“ (409 налична, 40901 не), нито „след“ (обратното).' ||
        E'\n      409 налична: %s · 40901 налична: %s. Полу-мигрирано или ръчно пипано —' ||
        E'\n      иска поглед на човек, преди каскадата на ДМА да тръгне втори път.',
        c.ccode, v_before, v_after);
      CONTINUE;
    END IF;

    IF v_after THEN
      -- Вече мигрирана: само се сверява, че деветте нови кода носят целевите си имена.
      -- ⚠️ Псевдонимът НЕ може да е `r`: така се казва record-променливата на цикъла отдолу и
      -- PL/pgSQL я предпочита пред таблицата („record r is not assigned yet“).
      SELECT count(*) INTO v_cnt
        FROM bg_n1_rename rn
       WHERE NOT EXISTS (SELECT 1 FROM account_account acc
                          WHERE acc.company = c.cid AND acc.code = rn.new_code
                            AND acc.name = rn.new_name);
      IF v_cnt > 0 THEN
        blockers := blockers || format(
          E'\n  · [%s] базата е „след“, но %s от деветте нови кода липсват или носят чуждо име.',
          c.ccode, v_cnt);
      ELSE
        RAISE NOTICE 'BG_COA/N1 skip    rename  [%] — деветте кода вече са преномерирани', c.ccode;
        n_skipped := n_skipped + 9;
      END IF;
    END IF;

    -- ── 1.3 · ПРЕНОМЕРИРАНЕ, ВЪЗХОДЯЩО ────────────────────────────────────────────────────────
    FOR r IN SELECT * FROM bg_n1_rename ORDER BY ord LOOP
      CONTINUE WHEN v_after;

      SELECT acc.id, acc.name, t.technical_type_select
        INTO v_acc_id, v_name, v_tech
        FROM account_account acc
        LEFT JOIN account_account_type t ON t.id = acc.account_type
       WHERE acc.company = c.cid AND acc.code = r.old_code;

      IF NOT FOUND THEN
        blockers := blockers || format(
          E'\n  · [%s] RENAME %s → %s: базата е „преди“, а старият код липсва.',
          c.ccode, r.old_code, r.new_code);
        CONTINUE;
      END IF;

      IF v_name <> r.old_name THEN
        blockers := blockers || format(
          E'\n  · [%s] RENAME %s: носи име %L, а се очакваше %L. Ръчно редактирана —' ||
          E'\n      преномерирането ѝ би пренесло чуждо съдържание под НАП номер.',
          c.ccode, r.old_code, v_name, r.old_name);
        CONTINUE;
      END IF;
      IF v_tech IS DISTINCT FROM r.expect_tech THEN
        blockers := blockers || format(
          E'\n  · [%s] RENAME %s: тип %L вместо %L — не се приема на доверие.',
          c.ccode, r.old_code, v_tech, r.expect_tech);
        CONTINUE;
      END IF;

      PERFORM 1 FROM account_account
       WHERE company = c.cid AND code = r.new_code;
      IF FOUND THEN
        blockers := blockers || format(
          E'\n  · [%s] RENAME %s → %s: целевият код Е ЗАЕТ. Каскадата се движи ВЪЗХОДЯЩО и всеки' ||
          E'\n      номер се освобождава от предходната стъпка — зает значи, че редът е нарушен' ||
          E'\n      или че някой е въвел сметката на ръка.', c.ccode, r.old_code, r.new_code);
        CONTINUE;
      END IF;

      -- ОДИТ, не гейт: номерът се мени, значението — не. Статиите сочат сметката по `id`.
      SELECT count(*), coalesce(sum(ml.debit), 0) - coalesce(sum(ml.credit), 0)
        INTO v_posted, v_balance
        FROM account_move_line ml
        JOIN account_move m ON m.id = ml.move
       WHERE ml.account = v_acc_id AND m.status_select IN (2, 3);

      IF r.gate_posted AND v_posted > 0 THEN
        blockers := blockers || format(
          E'\n  · [%s] RENAME %s НОСИ ИСТОРИЯ: %s осчетоводени реда, салдо (Dt−Kt) = %s.',
          c.ccode, r.old_code, v_posted, to_char(v_balance, 'FM999999999990.00'));
        CONTINUE;
      END IF;

      UPDATE account_account
         SET code = r.new_code, name = r.new_name, updated_on = now()
       WHERE id = v_acc_id;
      n_renamed := n_renamed + 1;
      RAISE NOTICE 'BG_COA/N1 RENAMED         [%] % → % („%“) · % осчетоводени реда следват по id',
        c.ccode, r.old_code, r.new_code, r.new_name, v_posted;
    END LOOP;

    -- ── 1.4 · C050 → view ─────────────────────────────────────────────────────────────────────
    FOR x IN SELECT * FROM bg_n1_retype LOOP
      SELECT acc.id, t.technical_type_select INTO v_acc_id, v_tech
        FROM account_account acc
        LEFT JOIN account_account_type t ON t.id = acc.account_type
       WHERE acc.company = c.cid AND acc.code = x.code;

      IF NOT FOUND THEN
        blockers := blockers || format(
          E'\n  · [%s] RETYPE %s: сметката липсва изобщо.', c.ccode, x.code);
        CONTINUE;
      END IF;
      IF v_tech = x.to_tech THEN
        RAISE NOTICE 'BG_COA/N1 skip    retype  [%] % — вече е %L', c.ccode, x.code, x.to_tech;
        n_skipped := n_skipped + 1;
        CONTINUE;
      END IF;
      IF v_tech IS DISTINCT FROM x.from_tech THEN
        blockers := blockers || format(
          E'\n  · [%s] RETYPE %s: тип %L, а се очакваше %L или вече %L.',
          c.ccode, x.code, v_tech, x.from_tech, x.to_tech);
        CONTINUE;
      END IF;

      -- ⚠️ ГЕЙТ. Репортен възел с осчетоводени редове е дефект: view сметка не се избира в
      -- интерфейса, тъй че салдото ѝ спира да се движи и тихо застива в баланса.
      SELECT count(*), coalesce(sum(ml.debit), 0) - coalesce(sum(ml.credit), 0)
        INTO v_posted, v_balance
        FROM account_move_line ml
        JOIN account_move m ON m.id = ml.move
       WHERE ml.account = v_acc_id AND m.status_select IN (2, 3);
      IF v_posted > 0 THEN
        blockers := blockers || format(
          E'\n  · [%s] RETYPE %s → view, но сметката НОСИ %s осчетоводени реда, салдо %s.' ||
          E'\n      Репортен възел не бива да има собствено салдо: първо реклас към дете (%s)' ||
          E'\n      с РЕДОВНА статия, чак после този файл.',
          c.ccode, x.code, v_posted, to_char(v_balance, 'FM999999999990.00'),
          array_to_string(x.keep_kids, ' или '));
        CONTINUE;
      END IF;

      -- Децата трябва да са налице — типът става view именно за да ги ДЪРЖИ като група.
      SELECT count(*) INTO v_cnt
        FROM account_account kid
        JOIN account_account par ON par.id = kid.parent_account
       WHERE kid.company = c.cid AND par.code = x.code AND kid.code = ANY(x.keep_kids);
      IF v_cnt <> array_length(x.keep_kids, 1) THEN
        blockers := blockers || format(
          E'\n  · [%s] RETYPE %s: под нея висят %s от очакваните %s деца (%s).' ||
          E'\n      Възел без децата си е празна позиция в баланса.',
          c.ccode, x.code, v_cnt, array_length(x.keep_kids, 1),
          array_to_string(x.keep_kids, ', '));
        CONTINUE;
      END IF;

      UPDATE account_account
         SET account_type = (SELECT id FROM account_account_type
                              WHERE technical_type_select = x.to_tech LIMIT 1),
             updated_on = now()
       WHERE id = v_acc_id;
      n_retyped := n_retyped + 1;
      RAISE NOTICE 'BG_COA/N1 RETYPED         [%] %: % → % (децата % остават)',
        c.ccode, x.code, x.from_tech, x.to_tech, array_to_string(x.keep_kids, ' и ');
    END LOOP;

    -- ── 1.5 · 124 → Z124, архивирана и неактивна ──────────────────────────────────────────────
    FOR x IN SELECT * FROM bg_n1_park LOOP
      SELECT acc.id, acc.name, acc.archived, acc.status_select, t.technical_type_select
        INTO v_acc_id, v_name, v_arch, v_status, v_tech
        FROM account_account acc
        LEFT JOIN account_account_type t ON t.id = acc.account_type
       WHERE acc.company = c.cid AND acc.code = x.code;

      IF NOT FOUND THEN
        SELECT acc.archived, acc.status_select INTO v_arch, v_status
          FROM account_account acc
         WHERE acc.company = c.cid AND acc.code = x.park_code;
        IF FOUND AND coalesce(v_arch, false) AND v_status = 0 THEN
          RAISE NOTICE 'BG_COA/N1 skip    park    [%] % — вече е паркирана като %',
            c.ccode, x.code, x.park_code;
          n_skipped := n_skipped + 1;
        ELSE
          blockers := blockers || format(
            E'\n  · [%s] PARK %s: няма я нито под своя код, нито паркирана като %s.',
            c.ccode, x.code, x.park_code);
        END IF;
        CONTINUE;
      END IF;

      IF v_name <> x.expect_name OR v_tech IS DISTINCT FROM x.expect_tech THEN
        blockers := blockers || format(
          E'\n  · [%s] PARK %s: име %L / тип %L, а се очакваше %L / %L.',
          c.ccode, x.code, v_name, v_tech, x.expect_name, x.expect_tech);
        CONTINUE;
      END IF;

      PERFORM 1 FROM account_account WHERE company = c.cid AND code = x.park_code;
      IF FOUND THEN
        blockers := blockers || format(
          E'\n  · [%s] PARK %s → %s: паркинг-кодът Е ЗАЕТ.', c.ccode, x.code, x.park_code);
        CONTINUE;
      END IF;

      -- ⚠️ ГЕЙТ. Корпоративният данък вече се начислява Дт 123 / Кт 4521 и разходна сметка няма.
      -- Осчетоводен разход по 124 значи, че историята иска РЕДОВЕН реклас към 123 — решение на
      -- счетоводителя, не на този файл.
      SELECT count(*), coalesce(sum(ml.debit), 0) - coalesce(sum(ml.credit), 0)
        INTO v_posted, v_balance
        FROM account_move_line ml
        JOIN account_move m ON m.id = ml.move
       WHERE ml.account = v_acc_id AND m.status_select IN (2, 3);
      IF v_posted > 0 THEN
        blockers := blockers || format(
          E'\n  · [%s] PARK %s: сметката НОСИ %s осчетоводени реда, салдо (Dt−Kt) = %s.' ||
          E'\n      Данъкът минава Дт %s / Кт 4521 и разходна сметка няма. Историята иска РЕДОВЕН' ||
          E'\n      реклас към %s със счетоводна статия — този файл НЕ го прави автоматично.',
          c.ccode, x.code, v_posted, to_char(v_balance, 'FM999999999990.00'),
          x.reclass_to, x.reclass_to);
        CONTINUE;
      END IF;

      SELECT count(*) INTO v_other
        FROM account_move_line ml
        LEFT JOIN account_move m ON m.id = ml.move
       WHERE ml.account = v_acc_id
         AND (m.id IS NULL OR m.status_select NOT IN (2, 3));
      IF v_other > 0 THEN
        RAISE NOTICE 'BG_COA/N1 ⚠ [%] % носи % НЕосчетоводени реда (нов/сторниран/симулиран) —'
                     ' минават, но ще сочат паркирана сметка', c.ccode, x.code, v_other;
      END IF;

      UPDATE account_account
         SET code = x.park_code, archived = true, status_select = 0, updated_on = now()
       WHERE id = v_acc_id;
      n_parked := n_parked + 1;
      RAISE NOTICE 'BG_COA/N1 PARKED          [%] % → % (архивирана, неактивна) — кодът % е'
                   ' СВОБОДЕН за НАП', c.ccode, x.code, x.park_code, x.code;
    END LOOP;

    -- ── 1.6 · СПРАВКИТЕ: правилата, ПО ТОКЕН И ПРЕДИ ПРЕИМЕНУВАНЕТО ──────────────────────────
    -- ⚠️ РЕДЪТ ТУК Е ЗАДЪЛЖИТЕЛЕН И Е ЕДИНСТВЕНОТО, КОЕТО ПАЗИ ИДЕМПОТЕНТНОСТТА. Каскадата на ДМА
    -- прави всеки нов код СТАР код на съседа: `A_1_2_203` е и целта на 2097, и източникът на 2096.
    -- Пренапише ли се правилото СЛЕД преименуването, второто пускане вижда вече новия `A_1_2_203`,
    -- разпознава го като стар и го мести пак на `A_1_2_202` — тихо разваляне при иначе „успешно“
    -- повторно пускане. Затова замяната се връзва към реда, който ОЩЕ носи стария си код: щом той
    -- е преименуван, съответствието просто не се намира и правилото остава като е.
    FOR l IN
      SELECT id, code, rule
        FROM account_accounting_report_config_line
       WHERE import_id LIKE c.ccode || '-%'
         AND rule IS NOT NULL AND rule <> ''
    LOOP
      -- Правило с изваждане (`EBT - TAXEXP`, `R_INC - R_EXP`) не носи нито един пипнат токен.
      CONTINUE WHEN l.rule LIKE '%-%';

      SELECT string_agg(
               coalesce(
                 (SELECT m.new_code
                    FROM bg_n1_line m
                    JOIN account_accounting_report_config_line q
                      ON q.import_id = c.ccode || '-' || m.imp AND q.code = m.old_code
                   WHERE m.old_code = t.tok
                   LIMIT 1),
                 t.tok),
               ' + ' ORDER BY t.ord)
        INTO v_new_rule
        FROM unnest(string_to_array(l.rule, ' + ')) WITH ORDINALITY AS t(tok, ord)
       WHERE NOT (t.tok = ANY(v_drop));

      IF v_new_rule IS DISTINCT FROM l.rule THEN
        UPDATE account_accounting_report_config_line
           SET rule = v_new_rule, updated_on = now()
         WHERE id = l.id;
        n_rules := n_rules + 1;
        RAISE NOTICE 'BG_COA/N1 RULE            [%] %: %', c.ccode, l.code, v_new_rule;
      END IF;
    END LOOP;

    -- ── 1.7 · СПРАВКИТЕ: редове ───────────────────────────────────────────────────────────────
    FOR l IN SELECT * FROM bg_n1_line ORDER BY imp LOOP
      UPDATE account_accounting_report_config_line
         SET code = l.new_code,
             account_code = l.new_acc,
             result_select = coalesce(l.new_result, result_select),
             updated_on = now()
       WHERE import_id = c.ccode || '-' || l.imp
         AND code = l.old_code;
      IF FOUND THEN
        n_lines := n_lines + 1;
      ELSE
        PERFORM 1 FROM account_accounting_report_config_line
         WHERE import_id = c.ccode || '-' || l.imp AND code = l.new_code;
        IF FOUND THEN
          n_skipped := n_skipped + 1;
        ELSE
          blockers := blockers || format(
            E'\n  · [%s] LINE %s (import_id %s): не е нито %L, нито %L.',
            c.ccode, l.imp, c.ccode || '-' || l.imp, l.old_code, l.new_code);
        END IF;
      END IF;
    END LOOP;

    -- ── 1.8 · СПРАВКИТЕ: изтриване на двата реда по 124 ───────────────────────────────────────
    FOR d IN SELECT * FROM bg_n1_line_drop LOOP
      DELETE FROM account_accounting_report_config_line
       WHERE import_id = c.ccode || '-' || d.imp AND code = d.expect_code;
      IF FOUND THEN
        n_dropped := n_dropped + 1;
        RAISE NOTICE 'BG_COA/N1 DROPPED line    [%] % (%) — 124 се броеше ВТОРИ ПЪТ в R_EXP',
          c.ccode, d.expect_code, d.imp;
      ELSE
        n_skipped := n_skipped + 1;
      END IF;
    END LOOP;

  END LOOP;

  IF v_companies = 0 THEN
    RAISE NOTICE 'BG_COA/N1: нула фирми с BG сметкоплан (опори 4551 и 4552). Нищо за правене.';
  END IF;

  IF blockers <> '' THEN
    RAISE EXCEPTION USING MESSAGE = format(
      E'BG_COA/N1: ПРЕЧКИ — НИЩО НЕ Е ЗАПИСАНО (цялата транзакция се отменя):%s' ||
      E'\n\nВсяка иска решение ПО СМЕТКА. Поправи ги и пусни файла пак.', blockers);
  END IF;

  RAISE NOTICE 'BG_COA/N1 ═══ край ═══ фирми=% | преномерирани=% | претипизирани=% | паркирани=%',
    v_companies, n_renamed, n_retyped, n_parked;
  RAISE NOTICE 'BG_COA/N1                  редове в справки=% | изтрити=% | правила=% | skip=%',
    n_lines, n_dropped, n_rules, n_skipped;
END
$mig$;

-- ═══ 2 · СВЕРКА ЗА КОНВЕРГЕНЦИЯ — ВСЕ ОЩЕ В ТРАНЗАКЦИЯТА ══════════════════════════════════════
-- Ако състоянието не е точно целевото, тук пада всичко. Проверката гледа РЕЗУЛТАТА, а не пътя.

DO $conv$
DECLARE
  c        record;
  v_bad    text := '';
  v_cnt    bigint;
BEGIN
  FOR c IN
    SELECT co.id AS cid, coalesce(co.code, '?') AS ccode
      FROM base_company co
     WHERE EXISTS (SELECT 1 FROM account_account acc
                    WHERE acc.company = co.id AND acc.code = '4551')
     ORDER BY co.id
  LOOP
    -- 2.1 · всеки нов код съществува с целевото си име
    SELECT count(*) INTO v_cnt
      FROM bg_n1_rename rn
     WHERE NOT EXISTS (SELECT 1 FROM account_account acc
                        WHERE acc.company = c.cid AND acc.code = rn.new_code
                          AND acc.name = rn.new_name);
    IF v_cnt > 0 THEN
      v_bad := v_bad || format(E'\n  · [%s] %s нови кода липсват или носят чуждо име', c.ccode, v_cnt);
    END IF;

    -- 2.2 · нито един освободен код не е останал зает
    SELECT count(*) INTO v_cnt
      FROM account_account acc
     WHERE acc.company = c.cid
       AND acc.code IN ('124', '153', '208', '409', '416');
    IF v_cnt > 0 THEN
      v_bad := v_bad || format(
        E'\n  · [%s] %s освободени кода са още заети — кръг 2 ще пише върху тях', c.ccode, v_cnt);
    END IF;

    -- 2.3 · C050 е view и държи децата си
    PERFORM 1 FROM account_account acc
      JOIN account_account_type t ON t.id = acc.account_type
     WHERE acc.company = c.cid AND acc.code = 'C050' AND t.technical_type_select = 'view';
    IF NOT FOUND THEN
      v_bad := v_bad || format(E'\n  · [%s] C050 не е view', c.ccode);
    END IF;

    -- 2.4 · нито едно правило не сочи ред, кръстен на освободен код
    --
    -- ⚠️ ПРОВЕРКАТА Е ТЯСНА НАРОЧНО, И ДВЕТЕ ПРИЧИНИ СА ИЗМЕРЕНИ. Общо „всеки токен да се резолвва“
    -- дава ЛЪЖЛИВО ЧЕРВЕНО върху заварена BG база, защото:
    --   · правилата на ДДС справките носят АРИТМЕТИКА, а не само сборове — `(P11A * 5)`,
    --     `P13 * 5`, `S20 / 0.09`; цепенето по „ + “ оставя цели изрази за токени;
    --   · шест реда в справка 42 (`S17`, `S18`, `S21`–`S24`) имат `ruleTypeSelect = 1`, а
    --     `chart-config.xml` връзва `code` само при `typeSelect != 3 && ruleTypeSelect != 1` —
    --     тоест техният `code` е NULL на ВСЯКА внесена база, а съседните правила ги сочат по име.
    -- И двете са заварени дефекти в данните на справките, независими от този кръг. Затова тук се
    -- мери само онова, за което ТОЗИ файл отговаря: нито един събираем да не е кръстен на код,
    -- който вече е освободен за НАП.
    SELECT count(*) INTO v_cnt
      FROM account_accounting_report_config_line l
     CROSS JOIN LATERAL unnest(string_to_array(l.rule, ' + ')) AS tok
     CROSS JOIN LATERAL unnest(ARRAY['124','153','208','409','416']) AS freed
     WHERE l.import_id LIKE c.ccode || '-%'
       AND l.rule IS NOT NULL AND l.rule <> ''
       AND tok LIKE '%\_' || freed;
    IF v_cnt > 0 THEN
      v_bad := v_bad || format(
        E'\n  · [%s] %s събираеми още сочат ред по освободен код — кръг 2 ще ги пренасочи мълчаливо',
        c.ccode, v_cnt);
    END IF;

    -- 2.5 · всеки преименуван ред носи новия си код и новата си сметка
    SELECT count(*) INTO v_cnt
      FROM bg_n1_line m
     WHERE NOT EXISTS (SELECT 1 FROM account_accounting_report_config_line q
                        WHERE q.import_id = c.ccode || '-' || m.imp
                          AND q.code = m.new_code AND q.account_code = m.new_acc);
    IF v_cnt > 0 THEN
      v_bad := v_bad || format(
        E'\n  · [%s] %s реда в справките не стигнаха до новия си код/сметка', c.ccode, v_cnt);
    END IF;

    -- 2.6 · двата реда по 124 в балансите ги няма
    SELECT count(*) INTO v_cnt
      FROM account_accounting_report_config_line l
      JOIN bg_n1_line_drop d ON l.import_id = c.ccode || '-' || d.imp
     WHERE l.code = d.expect_code;
    IF v_cnt > 0 THEN
      v_bad := v_bad || format(
        E'\n  · [%s] %s реда по 124 са още в балансите — разходът се брои ВТОРИ ПЪТ', c.ccode, v_cnt);
    END IF;

    -- 2.7 · нито един ред не сочи сметка, която я няма
    SELECT count(*) INTO v_cnt
      FROM account_accounting_report_config_line l
     WHERE l.import_id LIKE c.ccode || '-%'
       AND l.account_code IS NOT NULL AND l.account_code <> ''
       AND NOT EXISTS (SELECT 1 FROM account_account acc
                        WHERE acc.company = c.cid AND acc.code = l.account_code);
    IF v_cnt > 0 THEN
      v_bad := v_bad || format(
        E'\n  · [%s] %s реда сочат изчезнала сметка — дават тиха нула', c.ccode, v_cnt);
    END IF;
  END LOOP;

  IF v_bad <> '' THEN
    RAISE EXCEPTION USING MESSAGE = format(
      E'BG_COA/N1: НЕ КОНВЕРГИРА към целевото състояние — НИЩО НЕ Е ЗАПИСАНО:%s', v_bad);
  END IF;
  RAISE NOTICE 'BG_COA/N1 ✔ конвергенцията е сверена';
END
$conv$;

COMMIT;
