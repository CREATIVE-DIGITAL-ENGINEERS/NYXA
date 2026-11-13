-- СОЦИАЛНИТЕ РАЗХОДИ (2xx) — ТРИТЕ НОВИ КОЛОНИ ВЪРХУ ЗАВАРЕНИ ТАБЛИЦИ.
--
-- ⚠️ ДВЕТЕ НОВИ ТАБЛИЦИ ГИ НЯМА ТУК, И ТОВА Е ПО ПРЕЦЕДЕНТ. bglocalization_bg_social_expense_cap и
-- bglocalization_bg_social_expense_exception се създават ЦЕЛИ от ddl=update при първото вдигане на
-- приложението с този код. Нито една от четиринайсетте заварени миграции в тази папка не създава
-- таблица — виж коментара в BgGarnishmentScale.xml, където същото решение е записано за скалата по
-- чл. 446 и е ИЗМЕРЕНО след App Install.
--
-- ⚠️ ТУК СТОЯТ САМО КОЛОНИТЕ ВЪРХУ ПОПЪЛНЕНИ ТАБЛИЦИ. ddl=update добавя колона и НЕ попълва заварени
-- редове — а за трите колони отдолу подразбирането НЕ е безразлично:
--
--   · bg_pay_slip_line_type.bg_social_expense_cap (FK) — NULL е верният отговор за заварените 76
--     реда: капачка носят само редовете от 2xx, а те са НОВИ. Колоната е nullable нарочно.
--
--   · bg_pay_slip_line_type.bg_is_company_expense_taxed — ⚠️ ТУК NULL НЕ Е FALSE В БАЗАТА, но Е
--     FALSE при четене: генерираният getter за Boolean връща FALSE и за NULL. Тоест заварените редове
--     се четат като „не влиза целият размер в месечната ЗКПО основа“ — верният отговор за 1xx, 5xx,
--     6xx, 7xx, 8xx и 9xx. Изричното UPDATE отдолу все пак се прави, за да не зависи прочитът от
--     това дали някой ще напише `= false` в заявка вместо `IS NOT TRUE` — същият капан, който
--     BgInsuredType.bg_is_seafarer остави жив (виж 2026-08-22-bg-insured-type-is-seafarer.sql).
--
--   · bg_pay_slip.bg_social_expense_tax_base — заварените фишове нямат сметната месечна ЗКПО основа
--     и NULL се чете като ZERO от генерирания getter. ⚠️ И ТОВА Е ВЯРНО, А НЕ УДОБНО: до този комит
--     перо от 2xx НЯМА нито един фиш в базата, тоест основата им наистина е нула. Попълва се изрично
--     с 0.00 по същия довод като по-горе.
--
-- Пуска се РЪЧНО, ПРЕДИ старта на приложението с новия код:
--   PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d axelor \
--       -v ON_ERROR_STOP=1 -f 2026-08-26-bg-social-expenses.sql

BEGIN;

ALTER TABLE bglocalization_bg_pay_slip_line_type
  ADD COLUMN IF NOT EXISTS bg_social_expense_cap bigint;

ALTER TABLE bglocalization_bg_pay_slip_line_type
  ADD COLUMN IF NOT EXISTS bg_is_company_expense_taxed boolean;

UPDATE bglocalization_bg_pay_slip_line_type
   SET bg_is_company_expense_taxed = false
 WHERE bg_is_company_expense_taxed IS NULL;

ALTER TABLE bglocalization_bg_pay_slip
  ADD COLUMN IF NOT EXISTS bg_social_expense_tax_base numeric(20, 2);

UPDATE bglocalization_bg_pay_slip
   SET bg_social_expense_tax_base = 0.00
 WHERE bg_social_expense_tax_base IS NULL;

-- ⚠️ ВЪНШНИЯТ КЛЮЧ НЕ СЕ СЛАГА ТУК. Целевата таблица още я няма — тя се създава от ddl=update при
-- първото вдигане, и точно тогава Hibernate слага и ограничението. Сложен по-рано, той би провалил
-- целия файл с „relation does not exist“ и щеше да остави колоните недобавени.

COMMIT;
