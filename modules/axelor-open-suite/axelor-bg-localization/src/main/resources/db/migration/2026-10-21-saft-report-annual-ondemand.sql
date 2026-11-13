-- SAF-T · ГОДИШЕН ФАЙЛ И ФАЙЛ ПРИ ПОИСКВАНЕ — колоните на SaftReport (T1).
--
-- ЗАЩО. Месечният SAF-T отчет пази месеца в period (Period на финансовата година). Годишният файл е
-- за цялата година (името ЕИК_YYYY по Прил. 3 v1.0.1 т. I.2; QA на НАП, версия 2, II.4.5), а файлът
-- при поискване — за периода, който органът по приходите е поискал (Прил. 3 v1.0.1), с номер и дата
-- на искането. (Календарна година — ЗКПО чл. 21, четен само по консолидацията на lex.bg: да се
-- потвърди по ДВ.)
-- Новият код чете пет нови колони; приложението НЕ променя схемата, тоест те идват само оттук.
--
-- КОГА И КАК. Срещу заварената база, ПРЕДИ деплоя на новата версия:
--   1. файлът в транзакция с ROLLBACK (пробен пробег) — прочетете NOTICE-ите;
--   2. същото с COMMIT;
--   3. деплой. Новата версия при старт САМО ПРОВЕРЯВА (SaftReportSchemaCheck в
--      NapSubmissionsIntegrator): липсва ли колона, стартът спира с името на този файл.
--
-- ТРАНЗАКЦИЯТА И ON_ERROR_STOP ги дава обвивката: файлът няма BEGIN/COMMIT и се пуска като `BEGIN; \i файла; COMMIT|ROLLBACK;` с psql -v ON_ERROR_STOP=1. Всяка грешка (вкл.
-- проверката в края) връща целия файл назад.
--
-- СЪВМЕСТИМО СЪС СТАРАТА ВЕРСИЯ. Колоните са нови, празни и без ограничения; старият код не ги
-- познава и не ги пише. Заварените отчети са месечни и остават непокътнати.
--
-- ИДЕМПОТЕНТЕН: второ пускане не променя нищо и не дава грешка. Не пипа редове — само схемата.
-- Типовете са тези, които Hibernate дава на домейна (integer → integer, date → date,
-- string → varchar(255)), тоест ddl=update на нова база прави същото.

ALTER TABLE napsubmissions_saft_report ADD COLUMN IF NOT EXISTS report_year integer;
ALTER TABLE napsubmissions_saft_report ADD COLUMN IF NOT EXISTS from_date date;
ALTER TABLE napsubmissions_saft_report ADD COLUMN IF NOT EXISTS to_date date;
ALTER TABLE napsubmissions_saft_report ADD COLUMN IF NOT EXISTS nap_request_number varchar(255);
ALTER TABLE napsubmissions_saft_report ADD COLUMN IF NOT EXISTS nap_request_date date;

-- Проверка на резултата — грешка (и връщане на целия файл), ако не е верен: всяка колона е там и е
-- от очаквания тип.
DO $$
DECLARE
  expected text[][] := ARRAY[
    ARRAY['report_year', 'integer'],
    ARRAY['from_date', 'date'],
    ARRAY['to_date', 'date'],
    ARRAY['nap_request_number', 'character varying(255)'],
    ARRAY['nap_request_date', 'date']];
  actual text;
  wrong text[] := ARRAY[]::text[];
  i int;
BEGIN
  FOR i IN 1 .. array_length(expected, 1) LOOP
    SELECT format_type(a.atttypid, a.atttypmod) INTO actual
      FROM pg_attribute a
     WHERE a.attrelid = 'napsubmissions_saft_report'::regclass
       AND a.attname = expected[i][1]
       AND a.attnum > 0
       AND NOT a.attisdropped;
    IF actual IS DISTINCT FROM expected[i][2] THEN
      wrong := array_append(wrong,
        format('%s=%s (очаквано %s)', expected[i][1], coalesce(actual, 'липсва'), expected[i][2]));
    END IF;
  END LOOP;
  IF array_length(wrong, 1) IS NOT NULL THEN
    RAISE EXCEPTION USING MESSAGE = format(
      'Резултатът не е верен: %s.', array_to_string(wrong, '; '));
  END IF;
  RAISE NOTICE 'проверка: report_year, from_date, to_date, nap_request_number, nap_request_date — вярно';
END $$;
