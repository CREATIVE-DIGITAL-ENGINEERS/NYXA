-- BG_COA след кръг 168 върху СЪЩЕСТВУВАЩА база — идемпотентно, транзакционно, шумно при съмнение.
--
-- ЗАЩО SQL, А НЕ ИМПОРТ. Трите изразими пътя до сметкоплана НЕ МОГАТ да свършат тази работа, и това
-- е измерено в кода, не предположено:
--
--   1. Бутонът „Зареди сметкоплан“ (AccountingConfigTemplateService#installAccountChart) брои
--      сметките на фирмата с `self.company.id = ?1 AND self.parentAccount != null` и хвърля
--      ACCOUNT_CHART_3, щом броят е > 0. Заварена BG база носи 185 сметки с родител → бутонът
--      ОТКАЗВА. ⚠️ Условието е „с родител“, не „каквато и да е“ — база само с коренови сметки би
--      минала, но такава база не е нашият случай.
--   2. Импортът на плана (chart-config-account.xml и chart-config.xml) търси реда с
--      `self.code = :code AND self.company.id = :_companyId`. Тоест ключът е КОДЪТ. Импортът може
--      да ДОБАВИ ред с нов код и да ОБНОВИ ред със същия код — не може да преномерира и не може да
--      махне. ⚠️ `importId` не се връзва изобщо за Account (нито в двата конфига), затова новите
--      редове отдолу също остават с `import_id = NULL` — точно както ги оставя импортът.
--   3. `ddl=update` на Hibernate е за схема, не за данни.
--
-- Оттам единственият изразим път е контролиран SQL, и това е официалната процедура „как разгръщаме
-- кръг 168 на съществуваща база“. Пуска се РЪЧНО, ПРЕДИ старта на приложението с новия код:
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-09-10-bg-coa-168-existing-base.sql
--
-- Преди и след пускането се пуска и придружаващата read-only проверка:
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-09-10-bg-coa-168-verify.sql
--
-- ⚠️ САМО DEV. По продукция — само след бекъп и в прозорец за поддръжка, и само след като
-- проверката отгоре е пусната и прочетена от човек. Файлът е идемпотентен: второто пускане не
-- прави промени, само `skip` редове.
--
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- ДЕЛТАТА, ИЗЧИСЛЕНА ОТ account_account.csv, А НЕ ПРЕПИСАНА ОТ ЗАДАНИЕ
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- Източникът на истината е `l10n/l10n_bgr/BG_COA/account_account.csv`. Делтата е сметната като
-- разлика между състоянието преди кръга и след него: 185 → 186 реда, две добавени, четири
-- преименувани, една отпаднала. СЕДЕМ операции, край.
--
--   ADD     409   „Ваучери за храна“                                    (нова, тип payable)
--   ADD     6094  „Социални разходи (СБКО)“                             (нова, тип charge)
--   RENAME  4553  ДЗПО УПФ                       → ДЗПО УПФ И ППФ
--   RENAME  4554  ДЗПО ППФ                       → фонд „Безработица“
--   RENAME  4555  фонд „Безработица“             → фонд „ТЗПБ“
--   RENAME  4556  фонд „ТЗПБ“                    → фонд „ГВРС“
--   RETIRE  4557  фонд „ГВРС“                    → архивирана + неактивна
--
-- ⚠️ РАЗХОДНИТЕ 605x НЕ СЕ ПИПАТ, И ТОВА Е ИЗМЕРЕНО, НЕ ПРОПУСНАТО. Огледален rename на 6053–6056
-- изглежда естествен, но кръгът няма нито един ред по 605x: редовете 6051 ДОО, 6052 здравно,
-- 6053 ДЗПО, 6054 Безработица, 6055 ТЗПБ, 6056 ГВРС са БАЙТ ПО БАЙТ еднакви преди и след него.
-- Сверяването сменя РАЗЧЕТИТЕ, за да станат огледални на разхода; разходът е верен. Мълчаливо
-- преименуване тук би СЧУПИЛО огледалото.
--
-- ⚠️ 6057 НЕ СЪЩЕСТВУВА. Тоест „премахни и разходния аналог на 4557“ няма адресат — асиметрията
-- „един разход, два разчета“ е САМО от страната на разчетите.
--
-- ⚠️ РОДИТЕЛЯТ НА 6094 Е H020, А НЕ 609. `parent_code` в BG_COA е РЕПОРТНА група, не числова
-- йерархия (604 и 6041 също висят едно до друго на H020). Числовият родител на 6094 наистина е 609,
-- но 609 „Други разходи“ виси на I060 „Други такси“ — тоест през него социалният разход се чете при
-- таксите вместо при разходите за персонала.
--
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- КАК СЕ ГРАДЯТ НОВИТЕ ДВА РЕДА
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- Формата се КЛОНИРА от братска сметка, а не се гадае. И братството не е приблизително: сверено
-- поле по поле, редът на 6094 в CSV-то се различава от реда на 6042 ТОЧНО по `code` и `name`
-- (22 колони, 20 идентични), а редът на 409 — от реда на 4091 по същите две. Затова:
--
--   · СКАЛАРНИТЕ колони се пишат ИЗРИЧНО от CSV-то (CSV печели над всичко в базата);
--   · ВРЪЗКИТЕ — `company`, `parent_account`, `account_type`, `import_origin` — се вземат от
--     братската сметка, защото те са РЕЗУЛТАТ от търсене по код, което тук не искам да повтарям;
--   · всяко взето наследство се СВЕРЯВА: тип 'payable'/'charge' и родител C010/H020, инак гърми.
--
-- ⚠️ `version` СЕ ЗАДАВА НА 0 САМО ПРИ INSERT И НИКОГА НЕ СЕ ПИПА ПРИ UPDATE. Това е колоната за
-- оптимистично заключване на Hibernate: `version = 0` при UPDATE оставя базата в състояние, при
-- което следващият запис от приложението вдига StaleObjectStateException.
--
-- ⚠️ `import_id` СЕ ОСТАВЯ NULL. Ограничението uk_befehkvbkd8uop3ylxomtjx8b е УНИКАЛНО и ГЛОБАЛНО
-- (не по фирма) — клониран import_id от братската сметка би паднал върху него. А и импортът не
-- връзва import_id за Account, тоест NULL е точно онова, което той самият оставя.
--
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- ЗАЩО 4557 СЕ АРХИВИРА, А НЕ СЕ ТРИЕ — И ТОВА НЕ Е ПРЕДПАЗЛИВОСТ, А КОДЪТ
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- `AccountAccountRepository#remove()` хвърля ACCOUNT_REGULATORY_REMOVE при
-- `account.getIsRegulatoryAccount()`. Всички редове в BG_COA носят `isRegulatoryAccount = true`,
-- 4557 включително. Тоест приложението САМО ОТКАЗВА да я изтрие; hard DELETE от SQL би заобиколил
-- изрична забрана на продукта и би отнесъл историята заедно със сметката. Затова изтриване НЕ СЕ
-- ПРЕДЛАГА В НИКОЙ СЛУЧАЙ — дори при доказано празна сметка.
--
-- Пенсионирането е двойно, защото двата флага пазят различни неща и НИТО ЕДИН не пази всичко:
--
--   · `status_select = 0` (Inactive) е ОФИЦИАЛНАТА деактивация на продукта —
--     AccountService#desactivate(), закачена на бутона през #toggleStatusSelect(). Изкарва сметката
--     от избирачите в конфигурацията (AccountManagement.xml и др. филтрират
--     `self.statusSelect = 1`).
--   · `archived = true` я скрива от UI търсенето: филтърът е в rpc/Criteria.java:145-150
--     („self.archived is null OR self.archived = false“), тоест на RPC ниво.
--
-- ⚠️ И ГРАНИЦАТА НА ТВЪРДЕНИЕТО, ЗА ДА НЕ СЕ ЧЕТЕ ПОВЕЧЕ, ОТКОЛКОТО ПРАВИ. `archived` НЕ се
-- филтрира в com.axelor.db.Query — тоест сървърен код (`accountRepository.all().filter(...)`) вижда
-- архивираните редове, а формата на MoveLine няма domain по statusSelect. Двата флага правят
-- сметката НЕДОСТИЖИМА ПРЕЗ ИНТЕРФЕЙСА; те не са твърд гейт срещу програмно осчетоводяване.
--   ⤷ Полезно следствие: понеже Query не филтрира archived, броячът на ACCOUNT_CHART_3 продължава
--     да вижда 4557 и бутонът „Зареди сметкоплан“ остава заключен. Архивирането не отваря врата.

BEGIN;

-- ═══ 0 · ЦЕЛЕВОТО СЪСТОЯНИЕ, ДОСЛОВНО ОТ account_account.csv ═══════════════════════════════════
-- Трите таблици отдолу СА заданието. Всичко останало в този файл е механика около тях.

DROP TABLE IF EXISTS pg_temp.bg_coa168_add;
CREATE TEMP TABLE bg_coa168_add (
  code                                            text    NOT NULL,
  name                                            text    NOT NULL,
  sibling_code                                    text    NOT NULL,  -- откъде се клонират връзките
  expect_technical_type                           text    NOT NULL,  -- сверка на наследения тип
  expect_parent_code                              text    NOT NULL,  -- сверка на наследения родител
  common_position                                 integer NOT NULL,
  status_select                                   integer NOT NULL,
  is_regulatory_account                           boolean NOT NULL,
  reconcile_ok                                    boolean NOT NULL,
  use_for_partner_balance                         boolean NOT NULL,
  vat_system_select                               integer NOT NULL,
  is_tax_authorized_on_move_line                  boolean NOT NULL,
  is_tax_required_on_move_line                    boolean NOT NULL,
  is_retrieved_on_payment_session                 boolean NOT NULL,
  manage_cut_off_period                           boolean NOT NULL,
  has_automatic_application_accounting_date       boolean NOT NULL,
  analytic_distribution_authorized                boolean NOT NULL,
  analytic_distribution_required_on_invoice_lines boolean NOT NULL,
  analytic_distribution_required_on_move_lines    boolean NOT NULL
) ON COMMIT DROP;

INSERT INTO bg_coa168_add VALUES
  -- Редът на стойностите следва реда на колоните горе:
  --   code · name · sibling_code · expect_technical_type · expect_parent_code
  --   common_position · status_select · is_regulatory · reconcile_ok
  --   use_for_partner_balance · vat_system_select
  --   tax_authorized · tax_required · retrieved_on_payment_session
  --   manage_cut_off_period · has_automatic_application_accounting_date
  --   analytic: authorized · required_on_invoice_lines · required_on_move_lines
  ('409',  'Ваучери за храна',        '4091', 'payable', 'C010',
   2, 1, true, true,  false, 0,   false, false, false, false, false,   false, false, false),
  ('6094', 'Социални разходи (СБКО)', '6042', 'charge',  'H020',
   1, 1, true, false, false, 1,   false, false, false, true,  false,   false, false, false);

DROP TABLE IF EXISTS pg_temp.bg_coa168_rename;
CREATE TEMP TABLE bg_coa168_rename (
  code     text NOT NULL,
  old_name text NOT NULL,
  new_name text NOT NULL
) ON COMMIT DROP;

INSERT INTO bg_coa168_rename VALUES
  ('4553', 'Разчети за ДЗПО – универсален пенсионен фонд',
           'Разчети за ДЗПО – универсален и професионален пенсионен фонд'),
  ('4554', 'Разчети за ДЗПО – професионален пенсионен фонд',
           'Разчети за фонд "Безработица"'),
  ('4555', 'Разчети за фонд "Безработица"',
           'Разчети за фонд "Трудова злополука и професионална болест"'),
  ('4556', 'Разчети за фонд "Трудова злополука и професионална болест"',
           'Разчети за фонд "Гарантирани вземания на работниците"');

DROP TABLE IF EXISTS pg_temp.bg_coa168_retire;
CREATE TEMP TABLE bg_coa168_retire (
  code          text NOT NULL,
  expected_name text NOT NULL,
  reclass_to    text NOT NULL   -- накъде човек прехвърля салдото, ако има такова
) ON COMMIT DROP;

INSERT INTO bg_coa168_retire VALUES
  ('4557', 'Разчети за фонд "Гарантирани вземания на работниците"', '4556');

-- ═══ 1 · САМАТА МИГРАЦИЯ ═══════════════════════════════════════════════════════════════════════
-- Всеки RAISE EXCEPTION отменя ЦЯЛАТА транзакция. Пречките се СЪБИРАТ и се изсипват наведнъж, за
-- да не се налага пускане след пускане, за да излезе следващата.

DO $mig$
DECLARE
  c            record;
  a            record;
  r            record;
  x            record;
  sib          record;
  fk           record;
  v_max_id     bigint;
  v_seq_last   bigint;
  v_new_id     bigint;
  v_acc_id     bigint;
  v_cnt        bigint;
  v_posted     bigint;
  v_other      bigint;
  v_balance    numeric;
  v_name       text;
  v_arch       boolean;
  v_status     integer;
  v_refs       text;
  v_companies  integer := 0;
  n_added      integer := 0;
  n_renamed    integer := 0;
  n_retired    integer := 0;
  n_skipped    integer := 0;
  blockers     text    := '';
BEGIN
  RAISE NOTICE 'BG_COA/168 ═══ начало ═══ база=% потребител=%', current_database(), current_user;

  -- ── 1.0 · ПРЕДПОЛЕТНИ ПРОВЕРКИ ──────────────────────────────────────────────────────────────
  IF to_regclass('public.account_account') IS NULL THEN
    RAISE EXCEPTION
      'BG_COA/168: таблица account_account липсва — не е Axelor база. Нищо не е пипано.';
  END IF;
  IF to_regclass('public.account_account_seq') IS NULL THEN
    RAISE EXCEPTION
      'BG_COA/168: секвенцията account_account_seq липсва. Новите редове няма откъде да
       вземат id. Нищо не е пипано.';
  END IF;

  -- ⚠️ Секвенция ЗАД max(id) е дефект на средата, не на този файл (обикновено следа от dump/restore
  -- без setval). Ако не се хване тук, nextval() връща заето id и INSERT-ът пада с нарушение на PK —
  -- същият ROLLBACK, но с неразбираемо съобщение. И приложението вече е било в същата опасност.
  SELECT coalesce(max(id), 0) INTO v_max_id FROM account_account;
  v_seq_last := coalesce(pg_sequence_last_value('public.account_account_seq'::regclass), 0);
  IF v_seq_last < v_max_id THEN
    RAISE EXCEPTION USING MESSAGE = format(
      E'BG_COA/168: account_account_seq е на %s, а max(account_account.id) е %s.\n' ||
      E'  Следващият nextval() би върнал ЗАЕТО id. Това е състояние на СРЕДАТА и\n' ||
      E'  иска отделно решение:\n' ||
      E'      SELECT setval(''public.account_account_seq'',\n' ||
      E'                    (SELECT max(id) FROM account_account));\n' ||
      E'  Нищо не е пипано.', v_seq_last, v_max_id);
  END IF;

  -- ── 1.1 · ОБХВАТ: ФИРМИТЕ С BG ПЛАНА ────────────────────────────────────────────────────────
  -- Опорите са 4551 и 4552 — двата кода се срещат в BG_COA и в НИТО ЕДИН друг сметкоплан в репото
  -- (проверено срещу FRA_PCG и GBR_COA: нула попадения). Затова двойката е BG отпечатък, докато
  -- C010/H020 не са — тях GBR_COA също ги носи.
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
    RAISE NOTICE 'BG_COA/168 ── фирма [%] % (id=%)', c.ccode, c.cname, c.cid;

    -- ⚠️ ДОПЪЛНЕНИ С НУЛИ КОДОВЕ. ImportAccount → AccountService#fillAccountCodeOnImport допълва
    -- кода отдясно с нули до accountCodeNbrCharSelect, когато hasAccountCodeFixedNbrChar е вдигнат.
    -- В BG_COA това не се задейства (изключението `!account.getIsRegulatoryAccount()` важи за
    -- всички 186 реда), но ако някой го е вдигнал ръчно, сверката по ТОЧЕН код отдолу би подминала
    -- сметките мълчаливо. Затова се проверява, вместо да се предполага.
    SELECT count(*) INTO v_cnt
      FROM account_account acc
     WHERE acc.company = c.cid AND acc.code ~ '^(455[1-7]|6094|409)0+$';
    IF v_cnt > 0 THEN
      RAISE EXCEPTION USING MESSAGE = format(
        E'BG_COA/168 [%s]: намерени %s сметки с код от вида „455x“ ДОПЪЛНЕН С НУЛИ ' ||
        E'(hasAccountCodeFixedNbrChar).\n  Този файл сверява по ТОЧЕН код и на такава\n' ||
        E'  база би мълчал. ' ||
        E'Иска отделно решение — нищо не е пипано.', c.ccode, v_cnt);
    END IF;

    -- ── 1.2 · ADD 409 и 6094 ──────────────────────────────────────────────────────────────────
    FOR a IN SELECT * FROM bg_coa168_add ORDER BY code LOOP
      SELECT aa.name INTO v_name
        FROM account_account aa WHERE aa.company = c.cid AND aa.code = a.code;

      IF FOUND THEN
        IF v_name = a.name THEN
          RAISE NOTICE 'BG_COA/168 skip    add     [%] % — вече съществува с целевото име',
            c.ccode, a.code;
          n_skipped := n_skipped + 1;
          CONTINUE;
        END IF;
        blockers := blockers || format(
          E'\n  · [%s] ADD %s: сметката ВЕЧЕ съществува, но с име %L вместо целевото %L.' ||
          E'\n      Ръчно въведена или заета от друго значение — човек решава.',
          c.ccode, a.code, v_name, a.name);
        CONTINUE;
      END IF;

      SELECT s.id, s.parent_account, s.account_type, s.import_origin,
             t.technical_type_select AS tech, p.code AS parent_code
        INTO sib
        FROM account_account s
        LEFT JOIN account_account_type t ON t.id = s.account_type
        LEFT JOIN account_account      p ON p.id = s.parent_account
       WHERE s.company = c.cid AND s.code = a.sibling_code;
      IF NOT FOUND THEN
        blockers := blockers || format(
          E'\n  · [%s] ADD %s: братската сметка %s липсва — новата няма откъде да вземе\n' ||
          E'      тип, родител и произход.',
          c.ccode, a.code, a.sibling_code);
        CONTINUE;
      END IF;
      IF sib.tech IS DISTINCT FROM a.expect_technical_type THEN
        blockers := blockers || format(
          E'\n  · [%s] ADD %s: братската %s е от тип %L, а се очакваше %L —\n' ||
          E'      наследеният тип не се приема на доверие.',
          c.ccode, a.code, a.sibling_code, sib.tech, a.expect_technical_type);
        CONTINUE;
      END IF;
      IF sib.parent_code IS DISTINCT FROM a.expect_parent_code THEN
        blockers := blockers || format(
          E'\n  · [%s] ADD %s: братската %s виси на %L, а се очакваше %L —\n' ||
          E'      наследеният родител не се приема на доверие.',
          c.ccode, a.code, a.sibling_code, sib.parent_code, a.expect_parent_code);
        CONTINUE;
      END IF;

      v_new_id := nextval('public.account_account_seq');
      INSERT INTO account_account (
        id, version, archived, created_on, updated_on, created_by, updated_by,
        import_id, import_origin, process_instance_id, attrs, label,
        code, name, company, parent_account, account_type,
        service_type, analytic_distribution_template,
        common_position, status_select, is_regulatory_account, reconcile_ok,
        use_for_partner_balance, vat_system_select,
        is_tax_authorized_on_move_line, is_tax_required_on_move_line,
        is_retrieved_on_payment_session, manage_cut_off_period,
        has_automatic_application_accounting_date,
        analytic_distribution_authorized,
        analytic_distribution_required_on_invoice_lines,
        analytic_distribution_required_on_move_lines
      ) VALUES (
        v_new_id, 0, false, now(), NULL, NULL, NULL,
        NULL, sib.import_origin, NULL, NULL,  -- import_id остава NULL: вж. хедъра
        -- label = Account.computeLabel(): code + "_" + company.code + " - " + name; Java пише
        -- null като „null“. Беше NULL → празно име в списъците до запис през приложението.
        a.code || '_' || coalesce((SELECT co.code FROM base_company co WHERE co.id = c.cid), 'null')
          || ' - ' || a.name,
        a.code, a.name, c.cid, sib.parent_account, sib.account_type,
        NULL, NULL,                                          -- serviceType и ADT са празни в CSV-то
        a.common_position, a.status_select, a.is_regulatory_account, a.reconcile_ok,
        a.use_for_partner_balance, a.vat_system_select,
        a.is_tax_authorized_on_move_line, a.is_tax_required_on_move_line,
        a.is_retrieved_on_payment_session, a.manage_cut_off_period,
        a.has_automatic_application_accounting_date,
        a.analytic_distribution_authorized,
        a.analytic_distribution_required_on_invoice_lines,
        a.analytic_distribution_required_on_move_lines
      );
      n_added := n_added + 1;
      RAISE NOTICE 'BG_COA/168 ADDED   add     [%] % „%“ (id=%, тип=%, родител=%, по образец на %)',
        c.ccode, a.code, a.name, v_new_id, sib.tech, sib.parent_code, a.sibling_code;
    END LOOP;

    -- ── 1.3 · RENAME 4553–4556 ────────────────────────────────────────────────────────────────
    -- Редът е без значение: пазачът сверява по (код, ТЕКУЩО ИМЕ), а на `name` няма уникалност.
    -- Тоест новото име на 4554 съвпада със старото на 4555, без това да отваря кръстосан път.
    FOR r IN SELECT * FROM bg_coa168_rename ORDER BY code LOOP
      SELECT aa.id, aa.name INTO v_acc_id, v_name
        FROM account_account aa WHERE aa.company = c.cid AND aa.code = r.code;
      IF NOT FOUND THEN
        blockers := blockers || format(
          E'\n  · [%s] RENAME %s: сметката ЛИПСВА изобщо. Базата не е нито „преди 168“,\n' ||
          E'      нито „след 168“ —' ||
          E'\n      непознато състояние, а не работа за автоматика.', c.ccode, r.code);
        CONTINUE;
      END IF;

      IF v_name = r.new_name THEN
        RAISE NOTICE 'BG_COA/168 skip    rename  [%] % — името вече е целевото', c.ccode, r.code;
        n_skipped := n_skipped + 1;
        CONTINUE;
      END IF;

      IF v_name <> r.old_name THEN
        blockers := blockers || format(
          E'\n  · [%s] RENAME %s: носи име %L, което НЕ Е нито заварното %L, нито целевото %L.' ||
          E'\n      Ръчно редактирано — човек решава.',
          c.ccode, r.code, v_name, r.old_name, r.new_name);
        CONTINUE;
      END IF;

      -- ⚠️ ИСТОРИЯ. Статус 2 (DayBook) и 3 (Accounted) са осчетоводените; 1/4/5 (нов, сторниран,
      -- симулиран) не са. Смяна на СМИСЪЛА на сметка, която носи осчетоводена история, изкривява
      -- всяка историческа справка — салдо, натрупано като „ППФ“, тръгва да се чете като
      -- „Безработица“. Затова спира за човешко решение ПО СМЕТКА, а не се решава наведнъж.
      SELECT count(*), coalesce(sum(ml.debit), 0) - coalesce(sum(ml.credit), 0)
        INTO v_posted, v_balance
        FROM account_move_line ml
        JOIN account_move m ON m.id = ml.move
       WHERE ml.account = v_acc_id AND m.status_select IN (2, 3);

      SELECT count(*) INTO v_other
        FROM account_move_line ml
        LEFT JOIN account_move m ON m.id = ml.move
       WHERE ml.account = v_acc_id
         AND (m.id IS NULL OR m.status_select NOT IN (2, 3));

      IF v_posted > 0 THEN
        blockers := blockers || format(
          E'\n  · [%s] RENAME %s НОСИ ИСТОРИЯ: %s осчетоводени реда, салдо (Dt−Kt) = %s' ||
          E'\n      %L → %L',
          c.ccode, r.code, v_posted, to_char(v_balance, 'FM999999999990.00'), v_name, r.new_name);
        CONTINUE;
      END IF;

      IF v_other > 0 THEN
        RAISE NOTICE USING MESSAGE = format(
          E'BG_COA/168 note    rename  [%s] %s носи %s реда по НЕосчетоводени статии\n' ||
          E'  (нов/сторниран/симулиран) — преименуването продължава',
          c.ccode, r.code, v_other);
      END IF;

      -- ⚠️ `version` НЕ СЕ ПИПА (вж. хедъра). `updated_on` се вдига нарочно: тя не е колоната за
      -- заключване, а е единствената следа в интерфейса, че редът е бутнат отвън — прегледът на
      -- Axelor (@Track) не вижда външен SQL.
      UPDATE account_account SET name = r.new_name, updated_on = now() WHERE id = v_acc_id;
      n_renamed := n_renamed + 1;
      RAISE NOTICE 'BG_COA/168 RENAMED rename  [%] %: „%“ → „%“',
        c.ccode, r.code, v_name, r.new_name;
    END LOOP;

    -- ── 1.4 · RETIRE 4557 ─────────────────────────────────────────────────────────────────────
    FOR x IN SELECT * FROM bg_coa168_retire ORDER BY code LOOP
      SELECT aa.id, aa.name, coalesce(aa.archived, false), coalesce(aa.status_select, 0)
        INTO v_acc_id, v_name, v_arch, v_status
        FROM account_account aa WHERE aa.company = c.cid AND aa.code = x.code;

      IF NOT FOUND THEN
        RAISE NOTICE USING MESSAGE = format(
          E'BG_COA/168 skip    retire  [%s] %s — липсва (базата вече е след 168, или\n' ||
          E'  планът е зареден наново)', c.ccode, x.code);
        n_skipped := n_skipped + 1;
        CONTINUE;
      END IF;

      IF v_arch AND v_status = 0 THEN
        RAISE NOTICE 'BG_COA/168 skip    retire  [%] % — вече е архивирана и неактивна',
          c.ccode, x.code;
        n_skipped := n_skipped + 1;
        CONTINUE;
      END IF;

      SELECT count(*), coalesce(sum(ml.debit), 0) - coalesce(sum(ml.credit), 0)
        INTO v_posted, v_balance
        FROM account_move_line ml
        JOIN account_move m ON m.id = ml.move
       WHERE ml.account = v_acc_id AND m.status_select IN (2, 3);

      IF v_balance <> 0 THEN
        blockers := blockers || format(
          E'\n  · [%s] RETIRE %s има НЕНУЛЕВО САЛДО: %s (по %s осчетоводени реда).' ||
          E'\n      Пенсионирана сметка със салдо е загубено салдо. Първо реклас към %s\n' ||
          E'      с РЕДОВНА' ||
          E'\n      счетоводна статия, чак после този файл.',
          c.ccode, x.code, to_char(v_balance, 'FM999999999990.00'), v_posted, x.reclass_to);
        CONTINUE;
      END IF;

      -- ⚠️ ВСИЧКИ ЧУЖДИ КЛЮЧОВЕ, обходени ДИНАМИЧНО от pg_constraint, а не изброени на ръка:
      -- към account_account сочат 106 колони в 40+ таблици и всеки закован списък остарява при
      -- следващия модул. Обходът не е гейт — той е ОДИТ: препратките се ИЗВЕЖДАТ, а решението вече
      -- е взето (архивира се независимо), защото продуктът и без това отказва изтриване.
      v_refs := '';
      FOR fk IN
        SELECT con.conrelid::regclass::text AS tbl, att.attname AS col
          FROM pg_constraint con
          CROSS JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)
          JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = k.attnum
         WHERE con.contype = 'f'
           AND con.confrelid = 'public.account_account'::regclass
         ORDER BY 1, 2
      LOOP
        EXECUTE format('SELECT count(*) FROM %s WHERE %I = $1', fk.tbl, fk.col)
           INTO v_cnt USING v_acc_id;
        IF v_cnt > 0 THEN
          v_refs := v_refs || format(E'\n             %s.%s → %s реда', fk.tbl, fk.col, v_cnt);
        END IF;
      END LOOP;

      IF v_refs <> '' THEN
        RAISE NOTICE 'BG_COA/168 note    retire  [%] % е сочена от:%', c.ccode, x.code, v_refs;
      ELSE
        RAISE NOTICE USING MESSAGE = format(
          E'BG_COA/168 note    retire  [%s] %s е ДОКАЗАНО ПРАЗНА (нула осчетоводени реда,\n' ||
          E'  нула препратки) — пак се АРХИВИРА, не се трие:\n' ||
          E'  AccountAccountRepository#remove() отказва регулаторна сметка', c.ccode, x.code);
      END IF;

      IF v_name <> x.expected_name THEN
        RAISE NOTICE USING MESSAGE = format(
          E'BG_COA/168 note    retire  [%s] %s носи име %L вместо заварното %L —\n' ||
          E'  пенсионира се въпреки това (кодът отпада, не името)',
          c.ccode, x.code, v_name, x.expected_name);
      END IF;

      UPDATE account_account
         SET archived = true, status_select = 0, updated_on = now()
       WHERE id = v_acc_id;                        -- ⚠️ `version` НЕ се пипа
      n_retired := n_retired + 1;
      RAISE NOTICE USING MESSAGE = format(
        E'BG_COA/168 RETIRED retire  [%s] %s „%s“ — archived=true, status_select=0\n' ||
        E'  (Inactive); %s осчетоводени реда остават непокътнати',
        c.ccode, x.code, v_name, v_posted);
    END LOOP;
  END LOOP;

  -- ── 1.5 · НУЛА ФИРМИ В ОБХВАТА ──────────────────────────────────────────────────────────────
  IF v_companies = 0 THEN
    RAISE NOTICE USING MESSAGE =
      'BG_COA/168 ═══ NO-OP ═══ нула фирми носят BG плана (опорите 4551+4552 не се '
      'намериха). Нищо за разгръщане.';
  END IF;

  -- ── 1.6 · ПРЕЧКИТЕ, НАВЕДНЪЖ ────────────────────────────────────────────────────────────────
  IF blockers <> '' THEN
    RAISE EXCEPTION USING MESSAGE = format(
      E'BG_COA/168: ЦЯЛАТА МИГРАЦИЯ Е ОТМЕНЕНА (ROLLBACK) — нищо от изброените NOTICE ' ||
      E'редове по-горе не е записано.\n' ||
      E'Пречките са изброени ДОКРАЙ, за да не се налага пускане след пускане:%s\n\n' ||
      E'Всяка от тях иска РЕШЕНИЕ ПО СМЕТКА, не по-широк пазач.', blockers);
  END IF;

  RAISE NOTICE 'BG_COA/168 ═══ равносметка ═══ фирми=% добавени=% преименувани=% '
               'пенсионирани=% пропуснати=%',
    v_companies, n_added, n_renamed, n_retired, n_skipped;
END
$mig$;

-- ═══ 2 · СВЕРКА ЗА КОНВЕРГЕНЦИЯ — ВСЕ ОЩЕ В ТРАНЗАКЦИЯТА ═══════════════════════════════════════
-- Отделен DO блок нарочно: този не поправя нищо и не знае как. Той пита само „състоянието ли е,
-- което CSV-то описва“ и при „не“ отменя всичко, направено от блок 1.

DO $assert$
DECLARE
  c        record;
  a        record;
  r        record;
  x        record;
  v_name   text;
  v_arch   boolean;
  v_status integer;
  failures text := '';
  n_checked integer := 0;
BEGIN
  FOR c IN
    SELECT co.id AS cid, coalesce(co.code, '?') AS ccode
      FROM base_company co
     WHERE EXISTS (SELECT 1 FROM account_account acc
                    WHERE acc.company = co.id AND acc.code = '4551')
       AND EXISTS (SELECT 1 FROM account_account acc
                    WHERE acc.company = co.id AND acc.code = '4552')
     ORDER BY co.id
  LOOP
    n_checked := n_checked + 1;

    FOR a IN SELECT code, name FROM bg_coa168_add ORDER BY code LOOP
      SELECT aa.name, coalesce(aa.archived, false), coalesce(aa.status_select, 0)
        INTO v_name, v_arch, v_status
        FROM account_account aa WHERE aa.company = c.cid AND aa.code = a.code;
      IF NOT FOUND THEN
        failures := failures || format(E'\n  · [%s] %s ЛИПСВА', c.ccode, a.code);
      ELSIF v_name <> a.name THEN
        failures := failures || format(E'\n  · [%s] %s носи %L вместо %L',
                                      c.ccode, a.code, v_name, a.name);
      ELSIF v_arch OR v_status <> 1 THEN
        failures := failures || format(
          E'\n  · [%s] %s съществува, но не е активна (archived=%s, status_select=%s)',
                                       c.ccode, a.code, v_arch, v_status);
      END IF;
    END LOOP;

    FOR r IN SELECT code, new_name FROM bg_coa168_rename ORDER BY code LOOP
      SELECT aa.name INTO v_name
        FROM account_account aa WHERE aa.company = c.cid AND aa.code = r.code;
      IF NOT FOUND THEN
        failures := failures || format(E'\n  · [%s] %s ЛИПСВА', c.ccode, r.code);
      ELSIF v_name <> r.new_name THEN
        failures := failures || format(E'\n  · [%s] %s носи %L вместо %L',
                                      c.ccode, r.code, v_name, r.new_name);
      END IF;
    END LOOP;

    -- 4557: или я няма, или е инертна. Трето положение няма.
    FOR x IN SELECT code FROM bg_coa168_retire ORDER BY code LOOP
      SELECT coalesce(aa.archived, false), coalesce(aa.status_select, 0)
        INTO v_arch, v_status
        FROM account_account aa WHERE aa.company = c.cid AND aa.code = x.code;
      IF FOUND AND NOT (v_arch AND v_status = 0) THEN
        failures := failures || format(E'\n  · [%s] %s още е ЖИВА (archived=%s, status_select=%s)',
                                       c.ccode, x.code, v_arch, v_status);
      END IF;
    END LOOP;
  END LOOP;

  IF failures <> '' THEN
    RAISE EXCEPTION USING MESSAGE = format(
      E'BG_COA/168: НЕ КОНВЕРГИРА към account_account.csv — ЦЯЛАТА миграция е\n' ||
      E'отменена (ROLLBACK):%s',
      failures);
  END IF;

  RAISE NOTICE 'BG_COA/168 ═══ конвергенция ═══ % фирми съвпадат с account_account.csv '
               '(409, 6094, 4553–4556, 4557 инертна)', n_checked;
END
$assert$;

COMMIT;
