-- ═══ ЕТЗ: историята на измененията става РЕДОВЕ, а не едно презаписвано поле ═══
--
-- Създава двете нови таблици и ПОСЯВА в bg_contract_amendment заварения единичен анекс, който
-- договорът пази в amendment_date. Нула промени по съществуващи колони — amendment_date,
-- amendment_type_select и employment_contract_version остават непокътнати и продължават да се
-- пишат от upstream модула.
--
-- ⚠️ ЗАЩО ИЗОБЩО ИМА МИГРАЦИЯ, ЩОМ ddl=update ПРАВИ ТАБЛИЦИ САМО.
-- `ddl=update` наистина ще създаде и двете таблици при първия старт с новия код. Това, което то НЕ
-- знае да прави, е да ПОПЪЛНИ заварените редове — а без пренос всеки договор с анекс отива в новия
-- свят с празна история и изглежда като договор без изменения. Затова файлът е тук, а разделът
-- CREATE TABLE е писан идемпотентно (IF NOT EXISTS), за да е безразличен към това дали ddl=update
-- е минал преди него.
--
-- ⚠️ ПРЕНОСЪТ СПАСЯВА ПОСЛЕДНИЯ АНЕКС, НЕ ИСТОРИЯТА.
-- Предходните са НЕВЪЗСТАНОВИМИ по конструкция: amendment_date е едно поле и всеки нов анекс е
-- изтривал предишния. Това не е пропуск на миграцията, а щетата, заради която таблицата се създава.
--
-- ⚠️ И ПОСЯТИЯТ РЕД Е НЕПЪЛЕН — НАРОЧНО.
-- Поле 12 (основанието за изменението) няма откъде да дойде: amendment_type_select е цяло число от
-- чужда селекция и НЕ носи код по номенклатурата на Наредбата. Затова редовете се посяват с
-- служебния маркер '000', който НЕ Е валиден код по т. 12 — тоест подаването им ще откаже с
-- назована причина, докато човек не избере основанието. Обратното — да се сложи 019 или 021 „по
-- вероятност“ — би вписало в регистъра основание, което никой не е избирал.
--
-- Пуска се РЪЧНО, ПРЕДИ старта на приложението с новия код:
--   PGPASSWORD=*** psql -h localhost -p 5432 -U axelor -d nyxa -v ON_ERROR_STOP=1 \
--       -f 2026-09-12-bg-contract-amendment.sql
--
-- Повторното пускане е безвредно: и трите раздела са идемпотентни.

BEGIN;

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. ИЗМЕНЕНИЕТО (тип на документа 2)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS bg_contract_amendment (
    id                     BIGINT       NOT NULL,
    version                INTEGER,
    created_on             TIMESTAMP,
    updated_on             TIMESTAMP,
    created_by             BIGINT,
    updated_by             BIGINT,
    employment_contract    BIGINT       NOT NULL,
    change_date            DATE         NOT NULL,
    entry_date             DATE,
    change_no              VARCHAR(200),
    bg_change_ground       VARCHAR(255) NOT NULL,
    new_salary             NUMERIC(20, 2),
    new_nkpd               BIGINT,
    new_workplace_ekatte   VARCHAR(5),
    new_hours_code         INTEGER,
    new_working_hours      INTEGER,
    new_term_date          DATE,
    new_vacation_days      NUMERIC(5, 2),
    nap_submission         BIGINT,
    CONSTRAINT bg_contract_amendment_pkey PRIMARY KEY (id)
);

-- ⚠️ КЛЮЧЪТ Е ОГЛЕДАЛО НА РЕГИСТЪРА, НЕ НАША ПОДРЕДБА. Приложение №1: ключови полета при тип на
-- документа 2 са 1, 3, 4, 5, 6, 10, 12 и 16. За фиксиран договор остават ПОЛЕ 12 (основание) и
-- ПОЛЕ 16 (дата). Едно споразумение, което мени И заплата (019), И работно време (020), е ДВА
-- законни записа с еднаква дата — ключ без основанието би ги отхвърлил.
-- ⚠️ Поле 17 (change_no) НЕ е в ключа: то е задължително само при вид правоотношение 02, за трудов
-- договор е NULL, а NULL-ите в Postgres са различни — тоест ограничение с него не би сработвало
-- точно в масовия случай.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_bg_amendment_contract_date_ground') THEN
        ALTER TABLE bg_contract_amendment
            ADD CONSTRAINT uk_bg_amendment_contract_date_ground
            UNIQUE (employment_contract, change_date, bg_change_ground);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_bg_amendment_contract') THEN
        ALTER TABLE bg_contract_amendment
            ADD CONSTRAINT fk_bg_amendment_contract
            FOREIGN KEY (employment_contract) REFERENCES employment_contract (id);
    END IF;
END
$$;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. ПРЕХВЪРЛЯНЕТО по чл. 123 / чл. 123а
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS bg_contract_transfer (
    id                     BIGINT       NOT NULL,
    version                INTEGER,
    created_on             TIMESTAMP,
    updated_on             TIMESTAMP,
    created_by             BIGINT,
    updated_by             BIGINT,
    employment_contract    BIGINT       NOT NULL,
    change_date            DATE         NOT NULL,
    former_eik             VARCHAR(13)  NOT NULL,
    bg_change_reason       VARCHAR(255) NOT NULL,
    new_workplace_ekatte   VARCHAR(5),
    nap_submission         BIGINT,
    CONSTRAINT bg_contract_transfer_pkey PRIMARY KEY (id)
);

-- Приложение №2: ключови полета 1, 2, 3, 4, 5, 6, 7, 11, 12 и 13 → за фиксиран договор остават
-- ПОЛЕ 13 (дата), ПОЛЕ 3 (основание на промяната) и ПОЛЕ 4 (предишен ЕИК). Един договор може да
-- бъде прехвърлен повече от веднъж — верига от преобразувания — затова датата сама не стига.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_bg_transfer_contract_date_reason') THEN
        ALTER TABLE bg_contract_transfer
            ADD CONSTRAINT uk_bg_transfer_contract_date_reason
            UNIQUE (employment_contract, change_date, bg_change_reason, former_eik);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_bg_transfer_contract') THEN
        ALTER TABLE bg_contract_transfer
            ADD CONSTRAINT fk_bg_transfer_contract
            FOREIGN KEY (employment_contract) REFERENCES employment_contract (id);
    END IF;
END
$$;

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. ПРЕНОС на заварения единичен анекс
-- ─────────────────────────────────────────────────────────────────────────────
-- ⚠️ ИДЕМПОТЕНТНОСТТА Е В `NOT EXISTS`, А НЕ В „ON CONFLICT DO NOTHING“.
-- ON CONFLICT би разчитал на уникалния ключ, тоест на (договор, дата, ОСНОВАНИЕ) — а посятите
-- редове носят едно и също служебно основание '000'. Проверката по (договор, дата) е по-строгата:
-- тя не създава втори ред дори ако човек вече е избрал истинското основание за същия анекс.
INSERT INTO bg_contract_amendment (
    id, version, created_on, employment_contract, change_date, bg_change_ground, new_salary,
    new_workplace_ekatte, new_vacation_days, new_term_date)
SELECT
    nextval('hibernate_sequence'),
    0,
    now(),
    c.id,
    c.amendment_date,
    '000',
    c.bg_base_salary,
    c.bg_workplace_ekatte,
    c.bg_annual_leave_days,
    c.end_date
FROM employment_contract c
WHERE c.amendment_date IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM bg_contract_amendment a
      WHERE a.employment_contract = c.id
        AND a.change_date = c.amendment_date);

COMMIT;

-- ── Проверка след пускане ────────────────────────────────────────────────────
--   SELECT count(*) FROM bg_contract_amendment WHERE bg_change_ground = '000';
-- Толкова договора чакат човек да избере основание по т. 12. Докато го няма, подаването им отказва
-- с назована причина — вместо да впише основание, което никой не е избирал.
