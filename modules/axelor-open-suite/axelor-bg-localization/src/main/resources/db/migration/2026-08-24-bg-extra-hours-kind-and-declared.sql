-- ДВА НОВИ ИЗТОЧНИКА ЗА ЧАСОВЕТЕ: класификацията на извънредния труд и декларираните часове.
--
-- 1) hr_extra_hours_type.bg_extra_hours_kind (+ флаг) — кой от четирите вида извънреден труд по
--    чл. 262 ал. 1 КТ е даденият вид на клиента. Без нея ExtraHoursLine.qty дава само СБОРА, а
--    разпределението — което мени ставката — е неразличимо.
--
--    ⚠️ НЕ СЕ ИЗВЕЖДА ОТ increase. Изкушението е 50 % → работен ден, 75 % → почивен, 100 % →
--    празник. Процентите по чл. 262 ал. 1 КТ обаче са МИНИМУМИ и КТД може да е уговорил повече:
--    уговорени 100 % за работен ден биха се прочели като празник и биха вкарали часовете в друга
--    кофа — тихо и правдоподобно.
--
-- 2) bglocalization_bg_declared_work_hours — деклариран вход за НОЩНИЯ ТРУД (НСОРЗ чл. 8) и
--    ВРЕМЕТО НА РАЗПОЛОЖЕНИЕ (НСОРЗ чл. 10). За тях в модела НЯМА източник и не може да бъде
--    изведен: нощната смяна по график е РЕДОВЕН труд и не поражда ExtraHours ред, а TimesheetLine
--    няма нощен признак.
--
--    ⚠️ САМИЯТ РЕД Е ДЕКЛАРАЦИЯТА. Генерираният getter за decimal връща ZERO за непопълнена
--    колона, тоест „нула нощни часа" и „никой не е попълвал" са неразличими в стойността. Затова
--    разликата се носи от СЪЩЕСТВУВАНЕТО на реда: няма ред → сглобителят подава null; има ред →
--    и двете числа са декларирани и нулата е ОТГОВОР.
--
-- ⚠️ НИЩО НЕ СЕ ПОПЪЛВА И ТУК. Класификация по име („нощни", „празнични") е догадка, представена
-- като данни, точно както при hr_leave_reason. Некласифицираният вид остава NULL и сглобителят
-- ОТКАЗВА — отказът се вижда, тихата нула не.
--
-- Пуска се ВЕДНЪЖ. На чиста база не е нужен — там ddl=update се справя.

BEGIN;

ALTER TABLE hr_extra_hours_type
    ADD COLUMN IF NOT EXISTS bg_extra_hours_kind varchar(255),
    ADD COLUMN IF NOT EXISTS bg_extra_hours_kind_defaulted boolean;

CREATE TABLE IF NOT EXISTS bglocalization_bg_declared_work_hours (
    id                     bigint       NOT NULL,
    archived               boolean,
    import_id              varchar(255),
    import_origin          varchar(255),
    process_instance_id    varchar(255),
    version                integer,
    created_on             timestamp without time zone,
    updated_on             timestamp without time zone,
    created_by             bigint,
    updated_by             bigint,
    attrs                  jsonb,

    employment_contract    bigint       NOT NULL,
    period_from            date         NOT NULL,
    period_to              date         NOT NULL,
    bg_night_hours         numeric(10,2),
    bg_on_call_hours       numeric(10,2),
    note                   text,

    CONSTRAINT bglocalization_bg_declared_work_hours_pkey PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS bglocalization_bg_declared_work_hours_seq
    START WITH 1 INCREMENT BY 1;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_bg_declared_hours_contract') THEN
        ALTER TABLE bglocalization_bg_declared_work_hours
            ADD CONSTRAINT fk_bg_declared_hours_contract
            FOREIGN KEY (employment_contract) REFERENCES hr_employment_contract (id);
    END IF;
END $$;

-- ⚠️ Един договор, един период, ЕДНА декларация. Без това две декларации за един период се
-- сумират безшумно и удвояват добавката.
CREATE UNIQUE INDEX IF NOT EXISTS uq_bg_declared_hours_contract_period
    ON bglocalization_bg_declared_work_hours (employment_contract, period_from, period_to);

COMMIT;
