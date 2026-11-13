-- Сметка 442 „Вземания по липси и начети“ върху СЪЩЕСТВУВАЩА база — идемпотентно, транзакционно.
--
-- ЗАЩО ИЗОБЩО. Възстановимата липса (има отговорно лице) НЕ е разход, а ВЗЕМАНЕ: стойността не
-- напуска предприятието, а сменя формата си от актив в претенция към това лице. Отписана на 691
-- „Извънредни разходи“, тя намалява финансовия резултат с нещо, което ще се събере, и изважда от
-- баланса стойност, която още стои там. По приложимите СС мястото ѝ е БАЛАНСЪТ — краткотрайни
-- активи, раздел „Вземания“.
--
-- ⚠️ НОМЕРЪТ 442 Е ИЗБОР НА СЪСТАВИТЕЛЯ, НЕ НОРМАТИВЕН. Той идва от стария национален сметкоплан,
-- а задължителен национален сметкоплан няма от 2002 г. — сметкопланът е на предприятието. Този
-- файл разгръща избора, записан в `l10n/l10n_bgr/BG_COA/account_account.csv`, а не наложено число.
--
-- ⚠️ ДДС ТРЕТИРАНЕТО НЕ СЕ ПИПА ОТТУК И НЕ СЕ МЕНИ ИЗОБЩО. Чл. 79 ал. 1 ЗДДС дължи данък при
-- кражба, установяване на загуба или липси; освобождаване има само по чл. 80 ал. 2 т. 1 б. „г“ и то
-- виси под условие ЛИЦЕТО ДА ДОКАЖЕ, че събитието не е по негова вина. Наличието на отговорно лице
-- не е такова доказване — то по-скоро сочи обратното. Тоест корекцията се дължи и при двете липси,
-- а тази миграция мени САМО дебитната сметка на счетоводната статия.
--
-- ЗАЩО SQL, А НЕ ИМПОРТ. Същите три причини като при кръг 168, проверени наново в кода:
--
--   1. Бутонът „Зареди сметкоплан“ (AccountingConfigTemplateService#installAccountChart) брои
--      сметките с `self.company.id = ?1 AND self.parentAccount != null` и хвърля ACCOUNT_CHART_3,
--      щом броят е > 0. Заварена BG база носи 186 такива → бутонът ОТКАЗВА.
--   2. Импортът на плана може да ДОБАВИ ред с нов код (ключът е `code + company`) — тоест сметката
--      сама по себе си е изразима през импорта. Но той се стига САМО през бутона от т. 1.
--   3. `ddl=update` е за схема, не за данни.
--
-- Пуска се РЪЧНО, между `git pull` и старта на приложението с новия код:
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-09-12-bg-coa-442-existing-base.sql
--
-- Преди и след — придружаващата read-only проверка:
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-09-12-bg-coa-442-verify.sql
--
-- ⚠️ САМО DEV. По продукция — само след бекъп, в прозорец за поддръжка и след като проверката е
-- прочетена от човек. Файлът е идемпотентен: второто пускане дава само `skip` редове.
--
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- ДЕЛТАТА — ТРИ ОПЕРАЦИИ НА ФИРМА, ВСИЧКИТЕ ДОБАВЯНЕ
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
--   1 · ADD сметка 442 „Вземания по липси и начети“, клонирана от 498 „Други дебитори“
--   2 · ADD ред A_2_2_442  в справка 28 „Счетоводен баланс (НСС)“          → подсбор A_ST2_2
--   3 · ADD ред MA_2_2_442 в справка 46 „Отчет за финансовото състояние“   → подсбор MA_ST2_2
--
-- Плюс UPDATE на правилото на двата подсбора, за да съберат новия ред.
--
-- ⚠️ НУЛА ПРЕИМЕНУВАНИЯ И НУЛА ПРЕНОМЕРИРАНИЯ. Точно това препоръчва README-то на кръг 168:
-- „добавяне пред преномериране“. Затова тук няма нито един от пазачите на кръг 168 — няма сметка с
-- история, чието име се мени, и няма секвенция, която се мести. Новите редове за справките носят
-- секвенция В КРАЯ на диапазона (184 и 180), а не на мястото си в блока: детайлните редове са
-- `hidden = true` и служат само за да влязат в правилото на подсбора, а правилата се решават с
-- итерация до неподвижна точка (AccountingReportValueServiceImpl: `while (nullCount != 0)`), тъй
-- че редът на секвенциите не гейтва изчислението.
--
-- ⚠️ `import_id` НА СМЕТКАТА ОСТАВА NULL. Нито chart-config.xml, нито chart-config-account.xml
-- връзват importId за Account — импортът също оставя NULL и намира реда по код. Редовете на
-- справките обаче ИМАТ import_id, при това с префикс по фирма: `<КОД_ФИРМА>-<номер>`
-- (`<bind column="importId" to="importId" eval="_companyCode + '-' + importId"/>`). Числата 2568 и
-- 2569 са свободни в account_accountingReportConfigLine.csv (макс. зает: 2567).
--
-- ⚠️ ВРЪЗКАТА `accountSet` СЕ ПОПЪЛВА ИЗРИЧНО. `accountCode` е само текст; той се превръща в
-- account_set от скрипта ImportAccountingReportConfigLine#setAccounts ПРИ ВНОС. Тази миграция не
-- минава през вноса, тъй че сама вписва реда в
-- account_accounting_report_config_line_account_set — инак редът би съществувал и би сумирал НУЛА.
-- ⚠️ И го вписва със сметката НА СВОЯТА ФИРМА. Базовият скрипт търси `self.code = :code` БЕЗ
-- фирма и на многофирмена база хваща произволната — известен дефект, който тук не се повтаря.

BEGIN;

-- ═══ 0 · ЦЕЛЕВОТО СЪСТОЯНИЕ, ДОСЛОВНО ОТ CSV-ТАТА ═════════════════════════════════════════════

DROP TABLE IF EXISTS pg_temp.bg_coa442_add;
CREATE TEMP TABLE bg_coa442_add (
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

-- ⚠️ Стойностите са редът на 498 от account_account.csv с друг код и име — точно както го твърди
-- BgCoa442ReceivableTest#редът_е_огледало_на_братската_сметка. Типът `receivable` и родителят B020
-- се СВЕРЯВАТ срещу братската сметка преди вмъкването, вместо да се приемат на доверие.
-- ⚠️ common_position = 1 е CREDIT (Account.xml: NONE=0, CREDIT=1, DEBIT=2), не debit. Полето
-- управлява само фокуса на полето при ръчна статия — страната, на която обикновено се пише, а при
-- вземане това е ПОГАСЯВАНЕТО. Всичките девет вземания в B020 носят същата стойност.
INSERT INTO bg_coa442_add VALUES
  ('442', 'Вземания по липси и начети', '498', 'receivable', 'B020',
   1, 1, true, true, false, 0,   false, false, false, false, false,   false, false, false);

DROP TABLE IF EXISTS pg_temp.bg_coa442_report;
CREATE TEMP TABLE bg_coa442_report (
  report_import_id text NOT NULL,  -- importId на справката в CSV-то, БЕЗ префикса по фирма
  report_name      text NOT NULL,  -- за съобщенията
  sibling_code     text NOT NULL,  -- детайлният ред, от който се клонира (реда на 422)
  subtotal_code    text NOT NULL,  -- видимият подсбор, чието правило се допълва
  new_import_id    text NOT NULL,  -- свободен номер от CSV-то, БЕЗ префикса по фирма
  new_code         text NOT NULL,
  new_sequence     integer NOT NULL
) ON COMMIT DROP;

INSERT INTO bg_coa442_report VALUES
  ('28', 'Счетоводен баланс (НСС)',                  'A_2_2_422',  'A_ST2_2',  '2568', 'A_2_2_442',  184),
  ('46', 'Отчет за финансовото състояние / Баланс (МСС)', 'MA_2_2_422', 'MA_ST2_2', '2569', 'MA_2_2_442', 180);

-- ═══ 1 · САМАТА МИГРАЦИЯ ═══════════════════════════════════════════════════════════════════════
-- Всеки RAISE EXCEPTION отменя ЦЯЛАТА транзакция. Пречките се СЪБИРАТ и се изсипват наведнъж.

DO $mig$
DECLARE
  c            record;
  a            record;
  rp           record;
  sib          record;
  dsib         record;
  v_max_id     bigint;
  v_seq_last   bigint;
  v_new_id     bigint;
  v_acc_id     bigint;
  v_line_id    bigint;
  v_rt_id      bigint;
  v_cnt        bigint;
  v_name       text;
  v_rule       text;
  v_status     integer;
  v_arch       boolean;
  v_companies  integer := 0;
  n_added      integer := 0;
  n_lines      integer := 0;
  n_rules      integer := 0;
  n_links      integer := 0;
  n_skipped    integer := 0;
  blockers     text := '';
BEGIN
  RAISE NOTICE 'BG_COA/442 ═══ начало ═══ база=% потребител=%', current_database(), current_user;

  -- ── 1.0 · СЕКВЕНЦИИТЕ СА ПРЕД max(id) ───────────────────────────────────────────────────────
  -- Заварена база, пълнена с изричен id (импорт, дъмп), може да е оставила секвенцията назад —
  -- тогава nextval() връща ЗАЕТО id и INSERT-ът пада с нарушение на PK. Това е състояние на
  -- СРЕДАТА, не дефект на файла, затова спира с готовата команда за поправка.
  SELECT coalesce(max(id), 0) INTO v_max_id FROM account_account;
  v_seq_last := coalesce(pg_sequence_last_value('public.account_account_seq'::regclass), 0);
  IF v_seq_last < v_max_id THEN
    RAISE EXCEPTION USING MESSAGE = format(
      E'BG_COA/442: секвенцията account_account_seq е на %s, а max(id) е %s.\n' ||
      E'  Следващият nextval() би върнал ЗАЕТО id. Поправка:\n' ||
      E'      SELECT setval(''public.account_account_seq'',\n' ||
      E'                    (SELECT max(id) FROM account_account));\n' ||
      E'  Нищо не е пипано.', v_seq_last, v_max_id);
  END IF;

  SELECT coalesce(max(id), 0) INTO v_max_id FROM account_accounting_report_config_line;
  v_seq_last := coalesce(
    pg_sequence_last_value('public.account_accounting_report_config_line_seq'::regclass), 0);
  IF v_seq_last < v_max_id THEN
    RAISE EXCEPTION USING MESSAGE = format(
      E'BG_COA/442: секвенцията account_accounting_report_config_line_seq е на %s, ' ||
      E'а max(id) е %s.\n  Поправка:\n' ||
      E'      SELECT setval(''public.account_accounting_report_config_line_seq'',\n' ||
      E'                    (SELECT max(id) FROM account_accounting_report_config_line));\n' ||
      E'  Нищо не е пипано.', v_seq_last, v_max_id);
  END IF;

  -- ── 1.1 · ОБХВАТ: ФИРМИТЕ С BG ПЛАНА ────────────────────────────────────────────────────────
  -- Опорите са 4551 и 4552 — двата кода се срещат в BG_COA и в НИТО ЕДИН друг сметкоплан в репото
  -- (FRA_PCG и GBR_COA: нула попадения). Същият отпечатък като при кръг 168.
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
    RAISE NOTICE 'BG_COA/442 ── фирма [%] % (id=%)', c.ccode, c.cname, c.cid;

    -- ⚠️ ДОПЪЛНЕНИ С НУЛИ КОДОВЕ. AccountService#fillAccountCodeOnImport допълва кода отдясно с
    -- нули, когато hasAccountCodeFixedNbrChar е вдигнат. В BG_COA това не се задейства, но ако
    -- някой го е вдигнал ръчно, сверката по ТОЧЕН код отдолу би подминала сметките мълчаливо.
    SELECT count(*) INTO v_cnt
      FROM account_account acc
     WHERE acc.company = c.cid AND acc.code ~ '^(442|498)0+$';
    IF v_cnt > 0 THEN
      RAISE EXCEPTION USING MESSAGE = format(
        E'BG_COA/442 [%s]: намерени %s сметки с код „442“/„498“ ДОПЪЛНЕН С НУЛИ\n' ||
        E'  (hasAccountCodeFixedNbrChar). Този файл сверява по ТОЧЕН код и на такава база би\n' ||
        E'  мълчал. Иска отделно решение — нищо не е пипано.', c.ccode, v_cnt);
    END IF;

    -- ── 1.2 · ADD сметка 442 ──────────────────────────────────────────────────────────────────
    FOR a IN SELECT * FROM bg_coa442_add ORDER BY code LOOP
      SELECT aa.id, aa.name, aa.status_select, aa.archived
        INTO v_acc_id, v_name, v_status, v_arch
        FROM account_account aa WHERE aa.company = c.cid AND aa.code = a.code;

      IF FOUND THEN
        IF v_name = a.name THEN
          RAISE NOTICE 'BG_COA/442 skip    add     [%] % — вече съществува с целевото име',
            c.ccode, a.code;
          IF v_status <> 1 OR v_arch THEN
            RAISE NOTICE USING MESSAGE = format(
              E'BG_COA/442 [%s] %s: сметката е ДЕАКТИВИРАНА (status=%s, archived=%s).\n' ||
              E'  Отписването ще отстъпи обратно към 691 — видимо в статията, не тихо.\n' ||
              E'  Активирането е решение на съставителя и не се прави оттук.',
              c.ccode, a.code, v_status, v_arch);
          END IF;
          n_skipped := n_skipped + 1;
          CONTINUE;
        END IF;
        blockers := blockers || format(
          E'\n  · [%s] ADD %s: сметката ВЕЧЕ съществува, но с име %L вместо целевото %L.' ||
          E'\n      Заета от друго значение — човек решава.',
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
          E'      тип, родител и произход.', c.ccode, a.code, a.sibling_code);
        CONTINUE;
      END IF;
      IF sib.tech IS DISTINCT FROM a.expect_technical_type THEN
        blockers := blockers || format(
          E'\n  · [%s] ADD %s: братската %s е от тип %L, а се очакваше %L — вземането не бива\n' ||
          E'      да наследи разходен или друг тип.',
          c.ccode, a.code, a.sibling_code, sib.tech, a.expect_technical_type);
        CONTINUE;
      END IF;
      IF sib.parent_code IS DISTINCT FROM a.expect_parent_code THEN
        blockers := blockers || format(
          E'\n  · [%s] ADD %s: братската %s виси на %L, а се очакваше %L — извън групата на\n' ||
          E'      вземанията сметката не влиза в раздел „Вземания“ на баланса.',
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
      RAISE NOTICE 'BG_COA/442 ADDED   add     [%] % „%“ (id=%, тип=%, родител=%, по образец на %)',
        c.ccode, a.code, a.name, v_new_id, sib.tech, sib.parent_code, a.sibling_code;
    END LOOP;

    -- ── 1.3 · ADD редовете в ДВАТА баланса ────────────────────────────────────────────────────
    SELECT aa.id INTO v_acc_id
      FROM account_account aa WHERE aa.company = c.cid AND aa.code = '442';
    IF NOT FOUND THEN
      -- Сметката не е стигнала дотук (пречка отгоре) — редовете в справките нямат какво да сочат.
      RAISE NOTICE 'BG_COA/442 skip    report  [%] — няма сметка 442, справките се пропускат',
        c.ccode;
      CONTINUE;
    END IF;

    FOR rp IN SELECT * FROM bg_coa442_report ORDER BY report_import_id LOOP
      -- ⚠️ Справките се намират по import_id С ПРЕФИКС ПО ФИРМА — точно както ги е внесъл
      -- chart-config.xml (`_companyCode + '-' + importId`).
      SELECT rt.id INTO v_rt_id
        FROM account_accounting_report_type rt
       WHERE rt.import_id = c.ccode || '-' || rp.report_import_id;
      IF NOT FOUND THEN
        RAISE NOTICE 'BG_COA/442 skip    report  [%] справка % („%“) липсва на тази фирма',
          c.ccode, rp.report_import_id, rp.report_name;
        n_skipped := n_skipped + 1;
        CONTINUE;
      END IF;

      -- 1.3.1 · самият детайлен ред
      SELECT l.id INTO v_line_id
        FROM account_accounting_report_config_line l
       WHERE l.report_type = v_rt_id AND l.code = rp.new_code;

      IF FOUND THEN
        RAISE NOTICE 'BG_COA/442 skip    line    [%] % — вече съществува', c.ccode, rp.new_code;
        n_skipped := n_skipped + 1;
      ELSE
        SELECT l.type_select, l.rule_type_select, l.style_select, l.detail_by_select,
               l.result_select, l.negate_value, l.hidden, l.not_computed_if_intersect,
               l.balance_before_period, l.import_origin
          INTO dsib
          FROM account_accounting_report_config_line l
         WHERE l.report_type = v_rt_id AND l.code = rp.sibling_code;
        IF NOT FOUND THEN
          blockers := blockers || format(
            E'\n  · [%s] справка %s: братският ред %s липсва — новият няма откъде да вземе\n' ||
            E'      тип, правило и видимост.', c.ccode, rp.report_import_id, rp.sibling_code);
          CONTINUE;
        END IF;

        SELECT count(*) INTO v_cnt
          FROM account_accounting_report_config_line l
         WHERE l.report_type = v_rt_id AND l.sequence = rp.new_sequence;
        IF v_cnt > 0 THEN
          blockers := blockers || format(
            E'\n  · [%s] справка %s: секвенция %s е заета. Дублирана секвенция прави\n' ||
            E'      подредбата зависима от реда на извличане.',
            c.ccode, rp.report_import_id, rp.new_sequence);
          CONTINUE;
        END IF;

        v_new_id := nextval('public.account_accounting_report_config_line_seq');
        INSERT INTO account_accounting_report_config_line (
          id, version, archived, created_on, updated_on, created_by, updated_by,
          import_id, import_origin, process_instance_id, attrs,
          report_type, report_type_column, sequence, code, label,
          type_select, rule_type_select, style_select, detail_by_select, result_select,
          negate_value, rule, account_code, analytic_account_code,
          hidden, not_computed_if_intersect, balance_before_period
        ) VALUES (
          v_new_id, 0, false, now(), NULL, NULL, NULL,
          c.ccode || '-' || rp.new_import_id, dsib.import_origin, NULL, NULL,
          v_rt_id, NULL, rp.new_sequence, rp.new_code, NULL,
          dsib.type_select, dsib.rule_type_select, dsib.style_select, dsib.detail_by_select,
          dsib.result_select, dsib.negate_value, NULL, '442', NULL,
          dsib.hidden, dsib.not_computed_if_intersect, dsib.balance_before_period
        );
        v_line_id := v_new_id;
        n_lines := n_lines + 1;
        RAISE NOTICE 'BG_COA/442 ADDED   line    [%] справка % · % (id=%, seq=%, по образец на %)',
          c.ccode, rp.report_import_id, rp.new_code, v_new_id, rp.new_sequence, rp.sibling_code;
      END IF;

      -- 1.3.2 · връзката към сметката — accountCode е само текст, сумира се през accountSet
      SELECT count(*) INTO v_cnt
        FROM account_accounting_report_config_line_account_set s
       WHERE s.account_accounting_report_config_line = v_line_id AND s.account_set = v_acc_id;
      IF v_cnt = 0 THEN
        INSERT INTO account_accounting_report_config_line_account_set
          (account_accounting_report_config_line, account_set)
        VALUES (v_line_id, v_acc_id);
        n_links := n_links + 1;
        RAISE NOTICE 'BG_COA/442 LINKED  line    [%] % → сметка 442 (id=%)',
          c.ccode, rp.new_code, v_acc_id;
      ELSE
        RAISE NOTICE 'BG_COA/442 skip    link    [%] % — връзката вече е налице', c.ccode,
          rp.new_code;
        n_skipped := n_skipped + 1;
      END IF;

      -- 1.3.3 · правилото на видимия подсбор
      SELECT l.id, l.rule INTO v_line_id, v_rule
        FROM account_accounting_report_config_line l
       WHERE l.report_type = v_rt_id AND l.code = rp.subtotal_code;
      IF NOT FOUND THEN
        blockers := blockers || format(
          E'\n  · [%s] справка %s: подсборът %s липсва — детайлният ред е hidden и без правило\n' ||
          E'      не се явява никъде.', c.ccode, rp.report_import_id, rp.subtotal_code);
        CONTINUE;
      END IF;
      IF v_rule IS NULL OR btrim(v_rule) = '' THEN
        blockers := blockers || format(
          E'\n  · [%s] справка %s: подсборът %s е с ПРАЗНО правило — не е този, който събира\n' ||
          E'      вземанията.', c.ccode, rp.report_import_id, rp.subtotal_code);
        CONTINUE;
      END IF;
      -- Сверка по ЦЯЛА ДУМА: „A_2_2_442“ не бива да се търси като подниз, защото
      -- „MA_2_2_442“ го съдържа, а и бъдещ „A_2_2_4421“ би минал за налично.
      IF v_rule ~ ('(^|[^A-Za-z0-9_])' || rp.new_code || '([^A-Za-z0-9_]|$)') THEN
        RAISE NOTICE 'BG_COA/442 skip    rule    [%] % — вече събира %',
          c.ccode, rp.subtotal_code, rp.new_code;
        n_skipped := n_skipped + 1;
      ELSE
        UPDATE account_accounting_report_config_line
           SET rule = v_rule || ' + ' || rp.new_code, updated_on = now()
         WHERE id = v_line_id;
        n_rules := n_rules + 1;
        RAISE NOTICE 'BG_COA/442 RULE    [%] %: „%“ → „ … + %“',
          c.ccode, rp.subtotal_code, v_rule, rp.new_code;
      END IF;
    END LOOP;
  END LOOP;

  -- ── 1.4 · ИЗСИПВАНЕ НА ПРЕЧКИТЕ ─────────────────────────────────────────────────────────────
  IF blockers <> '' THEN
    RAISE EXCEPTION USING MESSAGE =
      E'BG_COA/442: миграцията е ОТМЕНЕНА. Всяка пречка иска решение по сметка:' || blockers ||
      E'\n\n  Нищо не е записано.';
  END IF;

  IF v_companies = 0 THEN
    RAISE NOTICE 'BG_COA/442 ── нула фирми с BG сметкоплан (няма 4551 и 4552). Няма какво да се'
      ' прави.';
  END IF;

  RAISE NOTICE 'BG_COA/442 ═══ край ═══ фирми=% · сметки=% · редове=% · връзки=% · правила=% · '
    'пропуснати=%', v_companies, n_added, n_lines, n_links, n_rules, n_skipped;
END
$mig$;

COMMIT;
