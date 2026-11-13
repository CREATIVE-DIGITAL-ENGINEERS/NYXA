-- БОЛНИЧНИЯТ ЕПИЗОД: нова таблица bglocalization_bg_sick_leave.
--
-- ЗАЩО ТАБЛИЦА, А НЕ КОЛОНА С БРОЙ ДНИ. По чл. 40 ал. 5 КСО първите два работни дни от ВСЕКИ
-- болничен се плащат от осигурителя, а останалите от ДОО. Тоест отговорът зависи от това КЪДЕ
-- ЗАПОЧВА епизодът: пет дни болничен са два дни за сметка на работодателя като ЕДИН епизод и
-- четири като ДВА. Едно число не различава двата случая, а разликата е в парите.
--
-- ⚠️ И ПРОДЪЛЖЕНИЕТО НЕ Е ИЗВОДИМО ОТ ДАТИТЕ. Болничен, издаден като продължение, не открива нов
-- епизод и по него не се плащат втори път два дни. Два съседни листа може да са и продължение, и
-- два отделни епизода. Признакът се декларира от документа, не се гади от календара.
--
-- ⚠️ ТАЗИ ТАБЛИЦА ХРАНИ ЕНДЖИНА. BgSickLeaveService, BgSickLeaveResolver и целият им златен тест
-- четат BgPayrollInput.sickLeaveEpisodes — списък, който сглобителят без нея няма откъде да
-- прочете.
--
-- ⚠️ НИЩО НЕ СЕ ПОПЪЛВА. Заварените болнични живеят на хартия и в чужди системи; внесени тук с
-- предположение „нов епизод“, те биха накарали работодателя да плати по два дни за всеки от тях.
-- Празната таблица кара сглобителя да подаде ПРАЗЕН списък, а празният списък дава нула дни
-- болничен — което е верният отговор, докато никой не е въвел епизод.
--
-- Пуска се ВЕДНЪЖ. На чиста база не е нужен — там ddl=update създава таблицата.

BEGIN;

-- ⚠️ Одитните колони са преписани от заварена таблица на модула (bglocalization_bg_compensation,
-- сверено срещу information_schema), а не изброени по памет: AuditableModel носи и
-- archived, и process_instance_id, а created_by/updated_by са FK към auth_user, не текст.
CREATE TABLE IF NOT EXISTS bglocalization_bg_sick_leave (
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
    bg_absence_type        bigint       NOT NULL,
    from_date              date         NOT NULL,
    to_date                date         NOT NULL,
    bg_is_continuation     boolean      DEFAULT false,
    bg_document_number     varchar(255),
    status_select          varchar(255) NOT NULL DEFAULT 'DRAFT',
    note                   text,

    CONSTRAINT bglocalization_bg_sick_leave_pkey PRIMARY KEY (id)
);

-- ⚠️ Последователността се създава ОТДЕЛНО и с IF NOT EXISTS: Hibernate я иска по име
-- <таблица>_seq и пада при старт, ако таблицата съществува без нея.
CREATE SEQUENCE IF NOT EXISTS bglocalization_bg_sick_leave_seq START WITH 1 INCREMENT BY 1;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_bg_sick_leave_contract') THEN
        ALTER TABLE bglocalization_bg_sick_leave
            ADD CONSTRAINT fk_bg_sick_leave_contract
            FOREIGN KEY (employment_contract) REFERENCES hr_employment_contract (id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_bg_sick_leave_absence_type') THEN
        ALTER TABLE bglocalization_bg_sick_leave
            ADD CONSTRAINT fk_bg_sick_leave_absence_type
            FOREIGN KEY (bg_absence_type) REFERENCES bglocalization_bg_absence_type (id);
    END IF;
END $$;

-- Сглобителят чете по (договор, период) и филтрира по състояние — индексът обслужва точно това.
CREATE INDEX IF NOT EXISTS idx_bg_sick_leave_contract_dates
    ON bglocalization_bg_sick_leave (employment_contract, from_date, to_date);

COMMIT;
