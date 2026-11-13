-- total_rate: заличено поле от старата схема на осигурителния модел — totalRate е производно от
-- employerRate + employeeRate и не е в BgInsuranceContribution.xml, но остава в заварена база,
-- защото ddl=update никога не трие колона, чийто модел е свален. Полето не се чете от НИЩО в текущия код (проверено с grep за total_rate/totalRate
-- в целия модул — нула съвпадения извън тази миграция).
--
-- ЗАЩО ПАДА ИМПОРТЪТ. Колоната е NOT NULL от времето, когато полето беше required="true" на
-- модела. CSVImporter/App Install не я пълни (не е в bg_insurance_contribution.csv и config-ът
-- няма bind към нея), тоест всеки INSERT на нов ред пада. App Install презарежда само
-- СЪЩЕСТВУВАЩИ importId-та (UPDATE), но DataInitSyncService INSERT-ва нови редове (например
-- морските лица, importId IC-127-2026..IC-149-2026) в заварена база при обикновен старт.
--
-- Пуска се ВЕДНЪЖ, върху всяка среда, чиято база е създадена преди премахването на totalRate. На
-- чиста база не е нужна — там ddl=update никога не е създавал колоната.

BEGIN;

ALTER TABLE bglocalization_bg_insurance_contribution
    DROP COLUMN IF EXISTS total_rate;

COMMIT;
