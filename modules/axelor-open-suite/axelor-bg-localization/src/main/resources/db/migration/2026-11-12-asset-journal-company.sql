-- АКТИВИ · ДНЕВНИКЪТ НА КАТЕГОРИЯТА ДМА, НА АКТИВА И НА ЧЕРНОВАТА — от собственото дружество.
--
-- ЗАЩО. Вносът на сметкоплана (l10n/chart-config.xml, axelor-account) търси дневника на
-- категорията ДМА по код И дружество. Търсен само по код, той дава на всяко дружество след първото
-- с BG_COA дневника MOA на ПЪРВОТО; активът взема дневника от категорията, амортизацията — от
-- актива, и „Сметка“ отказва статията: „компанията на движението е различна от тази на дневника“.
-- Този файл поправя редове, внесени по стария начин.
--
-- КАКВО ПРАВИ. За ред, чийто дневник е на ДРУГО дружество, слага дневника със СЪЩИЯ КОД в
-- собственото му дружество (и вдига version):
--   1. account_fixed_asset_category.journal;
--   2. account_fixed_asset.journal;
--   3. account_move.journal — САМО за чернова (status_select = 1, MoveRepository.STATUS_NEW).
-- НЕ пипа: движение в дневника (2), осчетоводено (3), отказано (4), симулирано (5) — само ги брои в
-- NOTICE: те имат номер и следа в дневника, сменят се само на ръка (сторно). Ако в собственото
-- дружество няма дневник със същия код (или има повече от един) — нищо не сменя, а казва в NOTICE
-- коя таблица, кое дружество и кой код.
--
-- КОГА. В automated.txt: деплоят го пуска сам, в една транзакция, преди превключването (заварена
-- база) или след първия старт (нова база — там няма редове и файлът е празен ход). Само данни;
-- безопасен за старата версия: тя чете дневника по id и не очаква чужд.
--
-- ИДЕМПОТЕНТЕН: второто пускане не намира поправим ред — нищо не се сменя, version не мърда.
-- Транзакцията и ON_ERROR_STOP ги дава обвивката: файлът няма BEGIN/COMMIT; проверката в края връща
-- целия файл назад, ако остане поправим ред.

DO $fix$
DECLARE
  n_category int;
  n_asset int;
  n_move int;
  r record;
BEGIN
  UPDATE account_fixed_asset_category t
     SET journal = own.id, version = COALESCE(t.version, 0) + 1
    FROM account_journal fj
    JOIN (SELECT company, code, min(id) AS id FROM account_journal  -- единственият с този код
           WHERE company IS NOT NULL AND code IS NOT NULL
           GROUP BY company, code HAVING count(*) = 1) own ON own.code = fj.code
   WHERE fj.id = t.journal AND fj.company <> t.company AND own.company = t.company;
  GET DIAGNOSTICS n_category = ROW_COUNT;

  UPDATE account_fixed_asset t
     SET journal = own.id, version = COALESCE(t.version, 0) + 1
    FROM account_journal fj
    JOIN (SELECT company, code, min(id) AS id FROM account_journal  -- единственият с този код
           WHERE company IS NOT NULL AND code IS NOT NULL
           GROUP BY company, code HAVING count(*) = 1) own ON own.code = fj.code
   WHERE fj.id = t.journal AND fj.company <> t.company AND own.company = t.company;
  GET DIAGNOSTICS n_asset = ROW_COUNT;

  UPDATE account_move t
     SET journal = own.id, version = COALESCE(t.version, 0) + 1
    FROM account_journal fj
    JOIN (SELECT company, code, min(id) AS id FROM account_journal  -- единственият с този код
           WHERE company IS NOT NULL AND code IS NOT NULL
           GROUP BY company, code HAVING count(*) = 1) own ON own.code = fj.code
   WHERE fj.id = t.journal AND fj.company <> t.company AND own.company = t.company
     AND t.status_select = 1;
  GET DIAGNOSTICS n_move = ROW_COUNT;

  RAISE NOTICE 'Дневник от собственото дружество: категории ДМА %, активи %, чернови %',
    n_category, n_asset, n_move;

  -- Непоправимите: в собственото дружество няма (или има няколко) дневника със същия код.
  FOR r IN
    SELECT 'категория ДМА' AS what, t.company, fj.code, count(*) AS n
      FROM account_fixed_asset_category t JOIN account_journal fj ON fj.id = t.journal
     WHERE fj.company <> t.company GROUP BY t.company, fj.code
    UNION ALL
    SELECT 'актив', t.company, fj.code, count(*)
      FROM account_fixed_asset t JOIN account_journal fj ON fj.id = t.journal
     WHERE fj.company <> t.company GROUP BY t.company, fj.code
    UNION ALL
    SELECT 'чернова', t.company, fj.code, count(*)
      FROM account_move t JOIN account_journal fj ON fj.id = t.journal
     WHERE fj.company <> t.company AND t.status_select = 1 GROUP BY t.company, fj.code
    ORDER BY 1, 2, 3
  LOOP
    RAISE NOTICE 'НЕ Е СМЕНЕНО: % × % на дружество % с дневник % на друго дружество — в дружество % няма точно един дневник с код %',
      r.n, r.what, r.company, r.code, r.company, r.code;
  END LOOP;

  -- Движенията извън чернова с чужд дневник — не се пипат, само се броят.
  FOR r IN
    SELECT t.status_select AS status, count(*) AS n
      FROM account_move t JOIN account_journal fj ON fj.id = t.journal
     WHERE fj.company <> t.company AND t.status_select <> 1
     GROUP BY t.status_select ORDER BY 1
  LOOP
    RAISE NOTICE 'НЕ СЕ ПИПА: % движения в статус % (2 — в дневника, 3 — осчетоводено, 4 — отказано, 5 — симулирано) с дневник на друго дружество',
      r.n, r.status;
  END LOOP;
END $fix$;

-- Проверка: не остава ред, който този файл е трябвало да поправи.
DO $verify$
DECLARE
  left_over int;
BEGIN
  SELECT count(*) INTO left_over FROM (
    SELECT t.company, fj.code
      FROM account_fixed_asset_category t JOIN account_journal fj ON fj.id = t.journal
     WHERE fj.company <> t.company
    UNION ALL
    SELECT t.company, fj.code
      FROM account_fixed_asset t JOIN account_journal fj ON fj.id = t.journal
     WHERE fj.company <> t.company
    UNION ALL
    SELECT t.company, fj.code
      FROM account_move t JOIN account_journal fj ON fj.id = t.journal
     WHERE fj.company <> t.company AND t.status_select = 1
  ) x
  WHERE (SELECT count(*) FROM account_journal o WHERE o.company = x.company AND o.code = x.code) = 1;
  IF left_over > 0 THEN
    RAISE EXCEPTION 'Остават % реда с дневник на друго дружество, които имат свой дневник със същия код', left_over;
  END IF;
END $verify$;
