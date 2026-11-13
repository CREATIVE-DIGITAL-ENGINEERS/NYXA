-- bgTeacherScope: трета позволителна ос на реда с вноски (учителска длъжност под вид осигурен 04).
--
-- ЗАЩО НЕ СТИГА ddl=update. Колоната е обявена required, тоест генераторът ѝ слага @NotNull, а
-- Hibernate превежда това в NOT NULL на ниво DDL. Върху таблица със заварени редове —
-- bglocalization_bg_insurance_contribution беше с 84 — PostgreSQL отказва
-- ALTER TABLE ... ADD COLUMN ... NOT NULL, защото заварените редове нямат стойност. Hibernate
-- записва грешката и продължава, тоест приложението тръгва БЕЗ колоната и всяка заявка, която я
-- споменава, пада по-късно. Затова колоната се добавя тук, преди старта.
--
-- Редът е трите стъпки в една транзакция: добавяне като nullable, попълване с ANY, и чак тогава
-- NOT NULL. ANY е верният заместител — редът важи независимо от учителството, и точно затова
-- колоната НЕ е nullable: NULL-овете са различни в PostgreSQL, не пазят уникалния ключ, а
-- филтърът „bgTeacherScope = 'ANY' OR = :teacherScope“ би ги изхвърлил тихо.
--
-- Пуска се ВЕДНЪЖ, върху всяка среда, чиято база е създадена преди този комит. На чиста база не е
-- нужна — там ddl=update създава таблицата вече с колоната.

BEGIN;

ALTER TABLE bglocalization_bg_insurance_contribution
    ADD COLUMN bg_teacher_scope varchar(255);

UPDATE bglocalization_bg_insurance_contribution
    SET bg_teacher_scope = 'ANY';

ALTER TABLE bglocalization_bg_insurance_contribution
    ALTER COLUMN bg_teacher_scope SET NOT NULL;

COMMIT;
