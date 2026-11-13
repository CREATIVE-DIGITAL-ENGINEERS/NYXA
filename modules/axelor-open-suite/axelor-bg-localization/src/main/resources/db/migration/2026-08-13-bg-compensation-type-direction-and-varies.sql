-- BgCompensationType: две нови задължителни колони — bgDirection и bgBaseVariesBy.
--
-- ЗАЩО НЕ СТИГА ddl=update. И двете са required, тоест @NotNull, тоест NOT NULL на ниво DDL. Върху
-- таблица със заварени редове PostgreSQL отказва ALTER TABLE ... ADD COLUMN ... NOT NULL. default в
-- домейна е само инициализатор на java полето, не DEFAULT на колоната, и не помага тук.
--
-- Затова редът е трите стъпки: добавяне като nullable, попълване, чак тогава NOT NULL.
--
-- СТОЙНОСТИТЕ ЗА ЗАВАРЕНИТЕ:
--   bgDirection = EMPLOYER_TO_EMPLOYEE — всичките десет заредени вида са задължение на работодателя
--   към лицето. Обратната посока се появява за пръв път с чл. 221 ал. 2 КТ, който идва като НОВ ред
--   от seed-а със собствената си стойност, тоест не се засяга от този UPDATE.
--
--   bgBaseVariesBy = NONE — базата на заварените не зависи от условие. Единственото изключение,
--   comp_kt_222_3 (2 срещу 6 БТВ според стажа), се преобозначава на SENIORITY от самия seed при
--   следващия App Install, а не оттук: стойността му е данни, не миграция.
--
-- Пуска се ВЕДНЪЖ, върху всяка среда, чиято база е създадена преди този комит, и ПРЕДИ старта на
-- приложението с новия код.

BEGIN;

ALTER TABLE bglocalization_bg_compensation_type
    ADD COLUMN bg_direction varchar(255);
ALTER TABLE bglocalization_bg_compensation_type
    ADD COLUMN bg_base_varies_by varchar(255);

UPDATE bglocalization_bg_compensation_type
    SET bg_direction = 'EMPLOYER_TO_EMPLOYEE'
    WHERE bg_direction IS NULL;
UPDATE bglocalization_bg_compensation_type
    SET bg_base_varies_by = 'NONE'
    WHERE bg_base_varies_by IS NULL;

ALTER TABLE bglocalization_bg_compensation_type
    ALTER COLUMN bg_direction SET NOT NULL;
ALTER TABLE bglocalization_bg_compensation_type
    ALTER COLUMN bg_base_varies_by SET NOT NULL;

COMMIT;
