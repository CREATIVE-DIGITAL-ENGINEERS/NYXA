-- СЪПОСТАВЯНЕТО „ВИД ОТПУСК → ВИД ОТСЪСТВИЕ (БГ)“: две нови колони на hr_leave_reason.
--
-- bg_absence_type           — FK към речника bglocalization_bg_absence_type.
-- bg_absence_type_defaulted — сложено ли е от машина и чака ли потвърждение.
--
-- ЗАЩО. LeaveReason не носи признак „платен“ и няма връзка към българската номенклатура, тоест от
-- модела НЕ СЕ ЧЕТЕ дали отпускът е платен, неплатен или друго. Без тази колона paidLeaveDays,
-- unpaidLeaveDays и otherAbsenceDays нямат източник.
--
-- ⚠️ НИЩО НЕ СЕ ПОПЪЛВА ОТ ТОЗИ ФАЙЛ, И ТОВА Е ГЛАВНОТО РЕШЕНИЕ ТУК. Изкушението е
-- UPDATE ... SET bg_absence_type = <платен годишен> WHERE name ILIKE '%платен%'. Точно затова не
-- се прави: как е кръстен видът отпуск е решение на КЛИЕНТА, не следствие от норма. „Неплатен
-- отпуск“ съдържа „платен“ като подниз, а „Отпуск чл. 157“ не съдържа нито едното. Съпоставяне по
-- име би било догадка, представена като данни — и то догадка, която мени парите: платеният отпуск
-- влиза в осигурителния доход, неплатеният не.
--
-- Несъпоставеният вид остава NULL. Сглобителят ОТКАЗВА, когато срещне валидирана заявка с
-- несъпоставена причина, вместо да я преброи в „други“ — отказът се вижда, тихата нула не.
--
-- Пуска се ВЕДНЪЖ, върху всяка среда, чиято база е създадена преди този комит.

BEGIN;

ALTER TABLE hr_leave_reason
    ADD COLUMN IF NOT EXISTS bg_absence_type bigint,
    ADD COLUMN IF NOT EXISTS bg_absence_type_defaulted boolean;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_leave_reason_bg_absence_type') THEN
        ALTER TABLE hr_leave_reason
            ADD CONSTRAINT fk_leave_reason_bg_absence_type
            FOREIGN KEY (bg_absence_type) REFERENCES bglocalization_bg_absence_type (id);
    END IF;
END $$;

COMMIT;
