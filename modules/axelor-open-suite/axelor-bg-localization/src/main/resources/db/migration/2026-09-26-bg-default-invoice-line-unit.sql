-- МЕРНАТА ЕДИНИЦА ПО ПОДРАЗБИРАНЕ ЗА РЪЧНО ДОБАВЕН РЕД ОТ ФАКТУРА: нова колона на настройката на
-- модула (studio_app_bg_localization.bg_default_invoice_line_unit -> base_unit.id) и ЕДНОКРАТНО
-- първоначално попълване.
--
-- ⚠️ ЗАЩО ВРЪЗКА, А НЕ ТЪРСЕНЕ ПО ИМЕ. Върху base_unit няма за какво друго да се хване: домейнът
-- (axelor-base, domains/Unit.xml) носи само name (translatable, required, namecolumn),
-- label_to_printing (translatable) и unit_type_select. Нито код, нито уникален индекс. `import_id`
-- от data-init листа НЕ СЪЩЕСТВУВА като колона — проверено срещу генерирания Unit.java и срещу
-- целия axelor-open-platform. Тоест единственият траен идентификатор на реда е самият ред, и
-- оттук нататък настройката сочи към него: преименуване, превод и дубликат я оставят непокътната.
--
-- ⚠️ ИМЕТО СЕ ПОЛЗВА ТОЧНО ВЕДНЪЖ И САМО ТУК — за да се намери редът, който data-init вече е внесъл
-- (base_unit.csv: „Брой“ / „бр.“). Това е ЗАРЕЖДАНЕ НА ПОКАЗАЛЕЦА, а не начин на разрешаване:
-- след този скрипт нищо в кода не чете името. Затова и съвпадението е стегнато:
--   • иска ТОЧНО ЕДИН кандидат — при нула или при дубликат не пипа нищо и оставя полето празно,
--     вместо да избере „първия“;
--   • пише САМО върху NULL — ръчно направен избор не се прегазва при повторно пускане;
--   • не създава единици и не пипа base_unit.
--
-- Празно поле след скрипта НЕ Е тиха повреда: новият ред тогава идва без предварително попълнена
-- единица и НЕ МОЖЕ да се запише празен (полето е задължително за обикновен ред), а администраторът
-- избира единицата от Настройки → Българска локализация → Редове на фактура.
--
-- Пуска се ВЕДНЪЖ, върху всяка среда, чиято база е създадена преди този комит. Идемпотентен е.

BEGIN;

ALTER TABLE studio_app_bg_localization
    ADD COLUMN IF NOT EXISTS bg_default_invoice_line_unit bigint;

-- ⚠️ ТЪРСИ СЕ ВРЪЗКА ПО КОЛОНА, А НЕ ПО ИМЕ, И ТОВА НЕ Е ПРИДИРЧИВОСТ. Първата версия на скрипта
-- питаше само за СВОЕТО име на ограничението, а по жива база (ddl=update) Hibernate вече е създал
-- колоната И нейния външен ключ под СВОЕ генерирано име — измерено: fk_rlcdv47a55mfvuco137ytuu3t.
-- Тоест проверката по име не намираше нищо и скриптът щеше да добави ВТОРИ, дублиращ външен ключ
-- върху същата колона: не грешка, но и не идемпотентност.
--
-- ⚠️ И ЗАПИТВАНЕТО Е ОГРАНИЧЕНО ДО ТАЗИ ТАБЛИЦА (conrelid), защото имената в pg_constraint НЕ СА
-- уникални за базата — съвпадение от друга таблица би отменило създаването тук.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_attribute a
          ON a.attrelid = c.conrelid
         AND a.attnum = ANY (c.conkey)
        WHERE c.conrelid = 'studio_app_bg_localization'::regclass
          AND c.contype = 'f'
          AND c.confrelid = 'base_unit'::regclass
          AND a.attname = 'bg_default_invoice_line_unit'
    ) THEN
        ALTER TABLE studio_app_bg_localization
            ADD CONSTRAINT fk_bg_localization_default_invoice_line_unit
            FOREIGN KEY (bg_default_invoice_line_unit) REFERENCES base_unit (id);
    END IF;
END $$;

-- Еднократното зареждане на показалеца. Подзаявката връща стойност само когато кандидатът е точно
-- един; при нула или при повече от един реда UPDATE-ът не намира ред за промяна и нищо не се случва.
UPDATE studio_app_bg_localization app
SET bg_default_invoice_line_unit = (
        SELECT u.id
        FROM base_unit u
        WHERE u.name = 'Брой'
          AND COALESCE(u.archived, false) = false
    )
WHERE app.bg_default_invoice_line_unit IS NULL
  AND (SELECT count(*) FROM base_unit u
       WHERE u.name = 'Брой' AND COALESCE(u.archived, false) = false) = 1;

COMMIT;
