-- РЕТРО-КОРЕКЦИИ И ЗАЛИЧАВАНИЯ на ведомостта (WS-3): пет нови колони, две таблици.
--
-- bglocalization_bg_pay_slip_line.bg_corrects_period
--     МАРКЕРЪТ. Празно значи „перото е за собствения период на фиша“ — всеки обикновен ред. Попълнено
--     значи „това перо носи нетната разлика за ДРУГ, вече затворен период“. Оттам зависи кое число в
--     коя декларация влиза: агрегатите за Д1/Д6 гледат САМО редовете с празен маркер, а нетото и
--     ведомостта включват и маркираните — те са пари, които лицето получава в текущия месец.
--
-- bglocalization_bg_pay_slip.bg_is_correction / bg_correction_of
--     Коригиращият фиш носи ПЪЛНИТЕ верни числа за миналия месец (Наредба Н-13) и храни коригираща
--     Д1/Д6 за него, но НЕ плаща сам. Без признака той е неразличим от втори редовен фиш за същия
--     период — а такъв плаща, тоест лицето би получило пълните нови числа вместо разликата.
--
-- bglocalization_bg_pay_slip.bg_is_deletion / bg_deletion_of
--     Симетричният близнак: фиш, издаден по грешка, който трябва да ИЗЧЕЗНЕ от НАП. Оригиналът остава
--     в Nyxa за одит, а в текущия период сяда ОТРИЦАТЕЛНА нето-делта.
--
-- ⚠️ ЗАЩО ФАЙЛ, ЩОМ ddl=update БИ СЕ СПРАВИЛО. И петте колони са NULLABLE и Hibernate наистина би ги
-- добавил сам — за разлика от 2026-08-17-bg-rate-kind.sql, където required колона върху попълнена
-- таблица се проваляше тихо. Файлът съществува по ДИСЦИПЛИНА на модула:
-- чуждите среди пускат схемата на ръка, преди старта, и не разчитат на ddl=update изобщо.
--
-- ⚠️ НИЩО НЕ СЕ ПОПЪЛВА И НИЩО НЕ СЕ ЗАКЛЮЧВА, И ТОВА Е РЕШЕНИЕ. Заварените фишове получават NULL по
-- трите връзки и NULL по двата признака. Генерираният getter за Boolean връща FALSE и за NULL, тоест
-- заварен ред се чете като „не е коригиращ и не е заличаващ“ — ВЕРНИЯТ прочит: всеки фиш отпреди този
-- комит е редовен по устройство, защото потокът на корекцията още не е съществувал. Тоест тук не се
-- иска UPDATE, който да „оправи“ данни — няма какво да се оправя.
--
-- ⚠️ ДВЕТЕ САМОПРЕПРАТКИ (bg_correction_of, bg_deletion_of) СОЧАТ СЪЩАТА ТАБЛИЦА и това е нарочно:
-- коригиращият фиш сочи оригинала. Външният ключ НЕ е ON DELETE CASCADE — изтриването на оригинала
-- трябва да ПАДНЕ, а не да отнесе коригиращия със себе си: оригиналът е одитната следа и точно затова
-- се пази.
--
-- Пуска се ВЕДНЪЖ, върху всяка среда, чиято база е създадена преди този комит. На чиста база не е
-- нужен — там ddl=update създава таблиците вече с колоните. Идемпотентен е: ADD COLUMN IF NOT EXISTS
-- плюс DROP CONSTRAINT IF EXISTS преди всяко ADD CONSTRAINT, тоест второ пускане не пада.

BEGIN;

ALTER TABLE bglocalization_bg_pay_slip_line
    ADD COLUMN IF NOT EXISTS bg_corrects_period bigint;

ALTER TABLE bglocalization_bg_pay_slip
    ADD COLUMN IF NOT EXISTS bg_correction_of bigint,
    ADD COLUMN IF NOT EXISTS bg_is_correction boolean,
    ADD COLUMN IF NOT EXISTS bg_deletion_of bigint,
    ADD COLUMN IF NOT EXISTS bg_is_deletion boolean;

ALTER TABLE bglocalization_bg_pay_slip_line
    DROP CONSTRAINT IF EXISTS fk_bg_pay_slip_line_corrects_period;
ALTER TABLE bglocalization_bg_pay_slip_line
    ADD CONSTRAINT fk_bg_pay_slip_line_corrects_period
    FOREIGN KEY (bg_corrects_period)
    REFERENCES base_period (id);

ALTER TABLE bglocalization_bg_pay_slip
    DROP CONSTRAINT IF EXISTS fk_bg_pay_slip_correction_of;
ALTER TABLE bglocalization_bg_pay_slip
    ADD CONSTRAINT fk_bg_pay_slip_correction_of
    FOREIGN KEY (bg_correction_of)
    REFERENCES bglocalization_bg_pay_slip (id);

ALTER TABLE bglocalization_bg_pay_slip
    DROP CONSTRAINT IF EXISTS fk_bg_pay_slip_deletion_of;
ALTER TABLE bglocalization_bg_pay_slip
    ADD CONSTRAINT fk_bg_pay_slip_deletion_of
    FOREIGN KEY (bg_deletion_of)
    REFERENCES bglocalization_bg_pay_slip (id);

COMMIT;
