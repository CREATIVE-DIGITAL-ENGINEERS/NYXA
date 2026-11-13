-- BG · МЕНЮТА — премахване на осиротелия корен „Nyxa“ от `meta_menu`.
--
-- ЗАЩО СЪЩЕСТВУВА. Менютата са разпределени по ОРГАНА (НАП / НОИ / HR) и собственият корен
-- на модула `nyxa-root` („Nyxa“) вече не е в `Menu-payroll.xml`. Триенето от XML обаче не трие ред от база:
-- `ViewLoader` внася и обновява, но НЕ изтрива, и същото важи за `ModuleViewSyncService` —
-- javadoc-ът му го казва дословно („the shipped XML replaces the META_VIEW/META_MENU/... rows“;
-- за ред без XML няма кой да го замени). На ЧИСТА инсталация редът изобщо не се ражда; на всяка
-- заварена база остава като празен top-level корен.
--
-- ЗАЩО СЕ ВИЖДА. `MenuChecker#isAllowed` (редове 80–83) пуска меню без роли и без групи само ако
-- то ИМА РОДИТЕЛ — а първата клауза пуска на админ всичко. `nyxa-root` няма нито роли, нито групи,
-- нито родител, тоест увисва точно в админския клон: празен корен „Nyxa“ в лявото меню на всеки
-- администратор, и по един излишен ред във всяко зареждане на менюто (`MenuUtils#fetchMetaMenu`).
--
-- ЗАЩО ТРИЕНЕ, А НЕ СКРИВАНЕ ИЛИ АРХИВИРАНЕ.
--   • `archived = true` НЕ върши работа. `MenuUtils#fetchMetaMenu` вика `JPA.em().createQuery(...)`
--     направо — archived филтърът на Axelor живее в `com.axelor.db.Query`, не в JPQL. Редът би
--     продължил да идва.
--   • `hidden = true` работи (`MenuChecker#canShow` го отрязва), но не ЧИСТИ: редът остава,
--     `fetchMetaMenu` продължава да го чете, `MenuChecker` продължава да го оценява и екранът
--     „Меню“ на администратора продължава да го показва. Оставя и трето състояние на базата,
--     каквото няма нито чиста инсталация, нито бъдеща среда.
--   • Триенето връща заварената база към състоянието на чистата — това е и целта.
--   Скриването остава като обратим първи ход, ако екипът предпочете; редът е в README-то.
--
-- КОГА. СЛЕД деплоя и след първото успешно вдигане с новия код — тогава view-sync-ът е внесъл
-- новия XML, децата вече висят на `hr-root`/`nap-root`/`noi-root`, а „Nyxa“ е останало празно.
-- Пуснато ПРЕДИ това, почистването отказва (проверки 5 и 6) — редът тогава не е сирак.
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-09-10-bg-orphan-nyxa-root-menu.sql
--
-- Незадължително, за среда, в която id-то е вече прочетено с -verify.sql:
--     ... -v expected_id=724 -f 2026-09-10-bg-orphan-nyxa-root-menu.sql
-- ⚠️ id-то Е РАЗЛИЧНО ПО СРЕДИ (локално напр. 724, другаде — друго). Затова ключът
-- навсякъде по-долу е `name`, а expected_id е само доброволна застраховка: подаде ли се и не
-- съвпадне, всичко се отменя.
--
-- ИДЕМПОТЕНТНО. Втори пробег не намира ред, казва го и излиза с успех.

\pset pager off

-- Незададено = -1 = проверката за id се пропуска.
\if :{?expected_id}
\else
  \set expected_id -1
\endif

BEGIN;

-- psql НЕ замества променливи вътре в dollar-quoted блок (проверено), затова стойността минава
-- през параметър на транзакцията.
SET LOCAL bg.nyxa_root_expected_id = :'expected_id';

DO $cleanup$
DECLARE
  c_name    CONSTANT text := 'nyxa-root';
  c_title   CONSTANT text := 'Nyxa';
  c_module  CONSTANT text := 'axelor-bg-localization';
  c_witness CONSTANT text := 'noi-root';

  v_count    integer;
  v_id       bigint;
  v_title    text;
  v_module   text;
  v_parent   bigint;
  v_expected bigint;
  v_children text;
  v_snapshot text;
  v_links    integer;
  v_n        bigint;
  r          record;
BEGIN
  ----------------------------------------------------------------------------------------------
  -- 1 · Колко реда носят стабилното име
  ----------------------------------------------------------------------------------------------
  SELECT count(*) INTO v_count FROM meta_menu WHERE name = c_name;

  IF v_count = 0 THEN
    RAISE NOTICE 'ПРОПУСНАТО · няма ред с name = %. Базата е чиста (или чиста инсталация).', c_name;
    RETURN;
  END IF;

  IF v_count > 1 THEN
    RAISE EXCEPTION 'ОТКАЗ · % реда с name = %, а сирачето е точно един.', v_count, c_name
      USING HINT = 'Няколко реда с едно име значи преопределяне през `id` '
                   '(виж AbstractLoader#isVisited). '
                   'Пусни -verify.sql и решѝ на ръка кой ред е сирачето.';
  END IF;

  SELECT id, title, module, parent
    INTO v_id, v_title, v_module, v_parent
    FROM meta_menu
   WHERE name = c_name;

  ----------------------------------------------------------------------------------------------
  -- 2 · Доброволната проверка за id (портируемост: по подразбиране изключена)
  ----------------------------------------------------------------------------------------------
  v_expected := nullif(current_setting('bg.nyxa_root_expected_id', true), '')::bigint;
  IF v_expected IS NOT NULL AND v_expected <> -1 AND v_id <> v_expected THEN
    RAISE EXCEPTION 'ОТКАЗ · подадено expected_id=%, а „%“ е id=% в тази среда.',
                    v_expected, c_name, v_id
      USING HINT = 'id-то е специфично за средата. Прочети го с -verify.sql '
                   'или изобщо не подавай expected_id.';
  END IF;

  ----------------------------------------------------------------------------------------------
  -- 3 · Редът наистина ли е описаното сираче
  ----------------------------------------------------------------------------------------------
  IF v_title IS DISTINCT FROM c_title THEN
    RAISE EXCEPTION 'ОТКАЗ · id=% носи title=%, а сирачето е %.',
                    v_id, coalesce(v_title, 'NULL'), c_title
      USING HINT = 'Редът е пипан на ръка или името е преизползвано. Иска ЧОВЕК.';
  END IF;

  IF v_module IS DISTINCT FROM c_module THEN
    RAISE EXCEPTION 'ОТКАЗ · id=% е на модул %, а сирачето е на %.',
                    v_id, coalesce(v_module, 'NULL'), c_module
      USING HINT = 'Друг модул е внесъл този ред. Иска ЧОВЕК.';
  END IF;

  IF v_parent IS NOT NULL THEN
    RAISE EXCEPTION 'ОТКАЗ · id=% има родител (%), а сирачето е top-level корен.', v_id, v_parent
      USING HINT = 'Някой е закачил „Nyxa“ под друго меню. Иска ЧОВЕК.';
  END IF;

  ----------------------------------------------------------------------------------------------
  -- 4 · Деца — меню с деца не е сирак
  ----------------------------------------------------------------------------------------------
  SELECT string_agg(format('%s (id=%s)', c.name, c.id), ', ' ORDER BY c.id)
    INTO v_children
    FROM meta_menu c
   WHERE c.parent = v_id;

  IF v_children IS NOT NULL THEN
    RAISE EXCEPTION 'ОТКАЗ · „%“ още има деца: %.', c_name, v_children
      USING HINT = 'Това не е сирак, а жив корен: view-sync-ът с кода след преместването на менюто още не е минал. '
                   'Деплойни, вдигни приложението веднъж и пусни пак.';
  END IF;

  ----------------------------------------------------------------------------------------------
  -- 5 · Свидетелят за минал view-sync. Празен корен без деца може да значи и „някой е изтрил
  --     децата на ръка“; „noi-root“ различава двете — той се ражда САМО от новия код.
  ----------------------------------------------------------------------------------------------
  PERFORM 1 FROM meta_menu WHERE name = c_witness;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'ОТКАЗ · липсва „%“ — XML-ът от преместването на менюто още не е внесен в тази база.', c_witness
      USING HINT = 'Почистването е СЛЕД деплоя и след първото вдигане с новия код.';
  END IF;

  ----------------------------------------------------------------------------------------------
  -- 6 · Всяка чужда връзка към реда. Списъкът се чете от pg_constraint, а не се изброява от
  --     паметта — така важи и за схема, каквато този файл не е виждал (studio_studio_menu е
  --     точно такъв случай: две връзки, които не личат от domain XML-ите).
  ----------------------------------------------------------------------------------------------
  FOR r IN
    SELECT ns.nspname  AS schema_name,
           src.relname AS ref_table,
           att.attname AS ref_column,
           con.conname AS constraint_name,
           array_length(con.conkey, 1) AS cols
      FROM pg_constraint con
      JOIN pg_class src     ON src.oid = con.conrelid
      JOIN pg_namespace ns  ON ns.oid = src.relnamespace
      CROSS JOIN LATERAL unnest(con.conkey) AS k(attnum)
      JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = k.attnum
     WHERE con.contype = 'f'
       AND con.confrelid = 'meta_menu'::regclass
     ORDER BY 2, 3
  LOOP
    IF r.cols <> 1 THEN
      RAISE EXCEPTION 'ОТКАЗ · съставен външен ключ % към meta_menu — не е предвиден тук.',
                      r.constraint_name;
    END IF;

    -- Собствените връзки на реда (неговите групи и роли) и децата му са проверени по-горе.
    CONTINUE WHEN (r.ref_table, r.ref_column) IN (('meta_menu_groups', 'meta_menu_id'),
                                                  ('meta_menu_roles',  'menus'),
                                                  ('meta_menu',        'parent'));

    EXECUTE format('SELECT count(*) FROM %I.%I WHERE %I = $1',
                   r.schema_name, r.ref_table, r.ref_column)
       INTO v_n USING v_id;

    IF v_n > 0 THEN
      RAISE EXCEPTION 'ОТКАЗ · % ред(а) в %.% сочат id=% през %.',
                      v_n, r.ref_table, r.ref_column, v_id, r.constraint_name
        USING HINT = 'Някой е закачил нещо за „Nyxa“ след преместването на менюто. Иска ЧОВЕК.';
    END IF;
  END LOOP;

  ----------------------------------------------------------------------------------------------
  -- 7 · meta_help — единствената връзка по ИМЕ, не по външен ключ (`menu_name` е низ)
  ----------------------------------------------------------------------------------------------
  SELECT count(*) INTO v_count FROM meta_help WHERE menu_name = c_name;
  IF v_count > 0 THEN
    RAISE EXCEPTION 'ОТКАЗ · % ред(а) в meta_help сочат меню „%“ по име.', v_count, c_name
      USING HINT = 'Нито един модул не доставя помощ за „Nyxa“ — редът е ръчен. Иска ЧОВЕК.';
  END IF;

  ----------------------------------------------------------------------------------------------
  -- 8 · Отпечатъкът в лога Е рецептата за връщане назад. Само това е нужно, за да се възстанови
  --     редът: INSERT-ът в README-то се попълва от този ред.
  ----------------------------------------------------------------------------------------------
  SELECT format('id=%s name=%L xml_id=%L title=%L module=%L order_seq=%s priority=%s icon=%L '
                'icon_background=%L left_menu=%L mobile_menu=%L hidden=%L link=%L action=%s '
                'user_id=%s condition_to_check=%L module_to_check=%L created_on=%L version=%s',
                id, name, xml_id, title, module,
                coalesce(order_seq::text, 'NULL'), coalesce(priority::text, 'NULL'), icon,
                icon_background, left_menu, mobile_menu, hidden, link,
                coalesce(action::text, 'NULL'), coalesce(user_id::text, 'NULL'),
                condition_to_check, module_to_check, created_on,
                coalesce(version::text, 'NULL'))
    INTO v_snapshot
    FROM meta_menu
   WHERE id = v_id;

  RAISE NOTICE 'ЗАПИС ПРЕДИ ТРИЕНЕТО · %', v_snapshot;

  ----------------------------------------------------------------------------------------------
  -- 9 · Първо собствените връзки (външният ключ иначе отказва), после самият ред
  ----------------------------------------------------------------------------------------------
  DELETE FROM meta_menu_groups WHERE meta_menu_id = v_id;
  GET DIAGNOSTICS v_links = ROW_COUNT;
  IF v_links > 0 THEN
    RAISE NOTICE '  · %  реда в meta_menu_groups (собствени връзки на сирачето)', v_links;
  END IF;

  DELETE FROM meta_menu_roles WHERE menus = v_id;
  GET DIAGNOSTICS v_links = ROW_COUNT;
  IF v_links > 0 THEN
    RAISE NOTICE '  · %  реда в meta_menu_roles (собствени връзки на сирачето)', v_links;
  END IF;

  DELETE FROM meta_menu WHERE id = v_id AND name = c_name;
  GET DIAGNOSTICS v_count = ROW_COUNT;

  IF v_count <> 1 THEN
    RAISE EXCEPTION 'ОТКАЗ · изтрити % реда вместо точно 1.', v_count;
  END IF;

  RAISE NOTICE 'ГОТОВО · изтрит е един ред: % (id=%). Заварената база е като чиста инсталация.',
               c_name, v_id;
END
$cleanup$;

COMMIT;
