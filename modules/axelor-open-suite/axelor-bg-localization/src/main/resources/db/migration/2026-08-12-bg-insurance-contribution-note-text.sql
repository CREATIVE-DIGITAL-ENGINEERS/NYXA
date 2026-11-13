-- BgInsuranceContribution.bgNote: от varchar(255) на text.
--
-- ЗАЩО НЕ СТИГА ddl=update. Hibernate добавя колони, но НЕ променя типа на съществуваща колона.
-- Полето е обявено large=true и на чиста база се създава като text, а на заварена остава
-- varchar(255) завинаги.
--
-- ЗАЩО ИЗОБЩО. Колоната носи източника на реда — откъде идват процентите и чие е потвърждението.
-- Редът за учителския фонд на вид осигурен 04 (IC-108-2026) е 324 знака. Вносителят не
-- съкращава: той ОТХВЪРЛЯ целия ред с „value too long for type character varying(255)“ и
-- продължава със следващия. Тоест файлът би казвал 108 реда, базата би имала 107, а липсващият би
-- бил единственият с bgTeacherScope = TEACHER — точно редът, заради който колоната bgTeacherScope
-- изобщо съществува. Същият таван има и BgCompensationType.note.
--
-- Пуска се ВЕДНЪЖ, върху всяка среда, чиято база е създадена преди този комит, и ПРЕДИ следващия
-- App Install.

BEGIN;

ALTER TABLE bglocalization_bg_insurance_contribution
    ALTER COLUMN bg_note TYPE text;

COMMIT;
