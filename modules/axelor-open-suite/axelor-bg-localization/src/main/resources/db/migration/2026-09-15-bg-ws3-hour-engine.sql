-- ЧАСОВИЯТ ЕНДЖИН (WS-3): ДВЕ КОЛОНИ НА ДОГОВОРА И ТРИНАЙСЕТ НА ФИША.
--
-- ═══ НА ДОГОВОРА ═══
-- bg_academic_degree          — научната степен по НСОРЗ чл. 11, СВЪРЗАНА с изпълняваната работа.
--                               Източникът на перо 120, което дотук нямаше поле зад себе си.
-- bg_is_unregulated_work_day  — ненормиран работен ден по КТ чл. 139а. Мени ПЛАЩАНЕТО: чл. 263
--                               ал. 1 не заплаща извънредния труд в работни дни.
--
-- ═══ НА ФИША ═══
-- Осем часови числа, четири признака „измервано ли е изобщо“ и броят дни с отчет.
--
-- ⚠️ ЗАЩО ЧЕТИРИ ПРИЗНАКА, А НЕ ОСЕМ. Генерираният getter за decimal връща ZERO за NULL, тоест
-- „нула нощни часа“ и „никой не е попълвал“ са неразличими в стойността — а прочетено като нула,
-- недекларираното маха добавката по НСОРЗ чл. 8 мълчаливо. Входът по § 13.1 обаче няма осем
-- независими състояния: има ТРИ източника, всеки от които става „не знам“ НАВЕДНЪЖ (декларацията
-- за нощен труд и разположение, четирите вида извънреден труд, отчетът за време), плюс
-- празничните часове, които носят СВОЯ втора причина за „не знам“ — непокрита от производствения
-- календар година.
--
-- ⚠️ bg_timesheet_days НЕ Е bg_worked_days. Второто идва от календара и отсъствията; първото
-- казва колко РАЗЛИЧНИ ДНИ покрива отчетът за време. Разликата между двете е носителят на
-- часовете на платения отпуск — третата съставка на т. 16.7 от Декларация обр. 1. Без нея т. 16.7
-- не може да чете замразеното.
--
-- ⚠️ НИЩО НЕ СЕ ПОПЪЛВА, по същия довод като 2026-08-24-bg-insurance-service-months.sql.
-- Изкушението е часовете да се изведат за заварените фишове от отчетите за време — и точно затова
-- не се прави: отчетът днес е друг от онзи, по който фишът е плащан, а замразяване със СЕГАШНОТО
-- му състояние би пренаписало историята точно там, където замразяването съществува, за да не я
-- пренаписва. Заварените редове получават NULL за числата и NULL за флаговете; генерираният getter
-- за Boolean връща FALSE за NULL, тоест те се четат като „не е измервано“ — верният прочит.
--
-- Пуска се ВЕДНЪЖ, върху всяка среда, чиято база е създадена преди този комит.

BEGIN;

ALTER TABLE hr_employment_contract
    ADD COLUMN IF NOT EXISTS bg_academic_degree varchar(255),
    ADD COLUMN IF NOT EXISTS bg_is_unregulated_work_day boolean;

ALTER TABLE bg_pay_slip
    ADD COLUMN IF NOT EXISTS bg_worked_hours numeric(10, 2),
    ADD COLUMN IF NOT EXISTS bg_worked_hours_known boolean,
    ADD COLUMN IF NOT EXISTS bg_timesheet_days integer,
    ADD COLUMN IF NOT EXISTS bg_night_hours numeric(10, 2),
    ADD COLUMN IF NOT EXISTS bg_on_call_hours numeric(10, 2),
    ADD COLUMN IF NOT EXISTS bg_declared_hours_known boolean,
    ADD COLUMN IF NOT EXISTS bg_holiday_work_hours numeric(10, 2),
    ADD COLUMN IF NOT EXISTS bg_holiday_hours_known boolean,
    ADD COLUMN IF NOT EXISTS bg_overtime_workday_hours numeric(10, 2),
    ADD COLUMN IF NOT EXISTS bg_overtime_rest_day_hours numeric(10, 2),
    ADD COLUMN IF NOT EXISTS bg_overtime_holiday_hours numeric(10, 2),
    ADD COLUMN IF NOT EXISTS bg_overtime_sirv_hours numeric(10, 2),
    ADD COLUMN IF NOT EXISTS bg_overtime_hours_known boolean;

COMMIT;
