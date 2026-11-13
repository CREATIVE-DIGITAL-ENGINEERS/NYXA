-- bgEmployerRateKind / bgEmployeeRateKind: видът на ВСЯКА СТАВКА поотделно (законова нула,
-- структурна нула, липсваща стойност или установена ставка).
--
-- ЗАЩО НЕ СТИГА ddl=update. Същото като при bgTeacherScope и по същата причина:
-- колоните са обявени required, генераторът им слага @NotNull, Hibernate го превежда в NOT NULL, а
-- PostgreSQL отказва ALTER TABLE ... ADD COLUMN ... NOT NULL върху таблица със заварени редове —
-- bglocalization_bg_insurance_contribution е със 143. Hibernate записва грешката и ПРОДЪЛЖАВА,
-- тоест приложението тръгва БЕЗ колоните, а App Install после отхвърля всеки ред. Затова колоните
-- се добавят тук, ПРЕДИ старта.
--
-- ⚠️ ПОПЪЛВАНЕТО ТУК НЕ Е ВТОРИ ИЗТОЧНИК НА ИСТИНА. Източникът е bg_insurance_contribution.csv и
-- App Install го презаписва върху всеки от 143-те реда веднага след старта. Тези UPDATE-и
-- съществуват само защото SET NOT NULL иска колоната да е пълна В МОМЕНТА на изпълнението си.
-- Правилата долу са дословно същите като в seed-а, за да не се разминат на нито един ред.
--
-- Пуска се ВЕДНЪЖ, върху всяка среда, чиято база е създадена преди този комит. На чиста база не е
-- нужна — там ddl=update създава таблицата вече с колоните.

BEGIN;

ALTER TABLE bglocalization_bg_insurance_contribution
    ADD COLUMN bg_employer_rate_kind varchar(255),
    ADD COLUMN bg_employee_rate_kind varchar(255);

-- ГВРС: законова нула И ЗА ДВАМАТА платци. ЗБДОО чл. 15 ал. 1 за 2026 г. не занулява дела на
-- единия — спира вноската за целия фонд, тоест и двете нули идват от една и съща норма.
-- Всичките единайсет реда за ГВРС са 0,0000/0,0000, затова условие по ставката тук не е нужно.
UPDATE bglocalization_bg_insurance_contribution
    SET bg_employer_rate_kind = 'LEGAL_ZERO',
        bg_employee_rate_kind = 'LEGAL_ZERO'
    WHERE fund_code = 'GVRS';

-- Всяка друга нула е структурна: вноската е изцяло за сметка на ДРУГИЯ платец и този ред не може
-- да се напълни, докато основанието важи. Посоката НЕ се извежда от фонда — при морските лица
-- (чл. 4а ал. 1 КСО) ТЗПБ и ППФ обръщат страната си — затова условието е по самата ставка.
UPDATE bglocalization_bg_insurance_contribution
    SET bg_employer_rate_kind = CASE WHEN employer_rate = 0 THEN 'STRUCTURAL_ZERO' ELSE 'VALUE' END,
        bg_employee_rate_kind = CASE WHEN employee_rate = 0 THEN 'STRUCTURAL_ZERO' ELSE 'VALUE' END
    WHERE fund_code <> 'GVRS';

ALTER TABLE bglocalization_bg_insurance_contribution
    ALTER COLUMN bg_employer_rate_kind SET NOT NULL,
    ALTER COLUMN bg_employee_rate_kind SET NOT NULL;

COMMIT;
