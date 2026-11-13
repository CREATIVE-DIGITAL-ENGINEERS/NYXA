-- NapArtifact.company — еднократно попълване на заварените файлове за НАП/НОИ.
--
-- ЗАЩО. Каталогът на nyxa-security чете NapArtifact по self.company.
-- Колоната е нова и ddl=update я добавя ПРАЗНА; заварените файлове, останали без дружество, се
-- виждат само от admin. Този файл им дава дружеството, откъдето то е записано еднозначно.
--
-- КОГА. Когато и да е — редът при деплоя няма значение. Същото попълване прави и
-- стартовата стъпка BgNapArtifactCompanyBackfill при всеки старт; този файл е ръчният ѝ еквивалент.
-- Сам добавя колоната (ADD COLUMN IF NOT EXISTS, bigint — типът на id), а ddl=update дописва FK и
-- индекса, тоест може да тръгне и преди първия старт с новия код.
--
-- ОТКЪДЕ (всички източници се събират; приоритетът е само за броячите):
--   1. отчет      — saft_report → napsubmissions_saft_report.company
--   2. подаване   — napsubmissions_nap_submission_artifact.artifact и
--                   napsubmissions_nap_submission_file.{payload_artifact, signature_artifact,
--                   signer_certificate_artifact} → napsubmissions_nap_submission.company
--   3. документ   — генериран, още неподаден файл: износ и удостоверение по Прил. 9 (bg_noi_artifact;
--                   удостоверението през болничния → договора → pay_company), справка по чл. 73
--                   ал. 1 и ал. 6 (generated_artifact) → company
--   Липсваща таблица/колона-източник се ПРОПУСКА с бележка (стара схема), не спира файла.
--
-- ПРАВИЛА.
--   • Пипа САМО редове с company IS NULL — второ пускане не променя нищо (идемпотентно).
--   • Само ЕДНОЗНАЧНО: ако източниците дават РАЗЛИЧНИ дружества за един файл, той остава празен и
--     се брои като „двусмислен“. Нищо не се гадае.
--   • Неоткритите остават празни (видими само за admin); схемата на SAF-T (artifact_type_select =
--     'schema') е нарочно без дружество — една за всички — и се брои отделно.
--   • version се УВЕЛИЧАВА, не се нулира: стара сесия с
--     отворения запис получава отказ за остаряла версия, вместо да запише обратно празно дружество.
--
-- ПУСКАНЕ:
--   PGPASSWORD=… psql -h <host> -U <user> -d <db> -v ON_ERROR_STOP=1 \
--       -f 2026-10-17-nap-artifact-company.sql
--   После: 2026-10-17-nap-artifact-company-verify.sql (само чете).

DO $$
DECLARE
  parts text[] := ARRAY[]::text[];
  skipped text[] := ARRAY[]::text[];
  col text;
  n_before bigint;
  n_report bigint;
  n_submission bigint;
  n_document bigint;
  n_ambiguous bigint;
  n_updated bigint;
  n_after bigint;
  n_schema bigint;
BEGIN
  -- Колоната на новия код; FK и индекса ги прави ddl=update при старта (имената им са хешове).
  ALTER TABLE napsubmissions_nap_artifact ADD COLUMN IF NOT EXISTS company bigint;

  SELECT count(*) INTO n_before FROM napsubmissions_nap_artifact WHERE company IS NULL;

  -- 1. отчет
  parts := array_append(parts, 'SELECT a.id AS artifact, r.company AS company, 1 AS prio'
    || ' FROM napsubmissions_nap_artifact a'
    || ' JOIN napsubmissions_saft_report r ON r.id = a.saft_report'
    || ' WHERE r.company IS NOT NULL');

  -- 2. подаване: връзката и трите колони на файла на подаването
  parts := array_append(parts, 'SELECT l.artifact, s.company, 2'
    || ' FROM napsubmissions_nap_submission_artifact l'
    || ' JOIN napsubmissions_nap_submission s ON s.id = l.submission'
    || ' WHERE s.company IS NOT NULL');
  FOREACH col IN ARRAY ARRAY['payload_artifact', 'signature_artifact', 'signer_certificate_artifact']
  LOOP
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'napsubmissions_nap_submission_file' AND column_name = col) THEN
      parts := array_append(parts, format(
        'SELECT f.%I, s.company, 2 FROM napsubmissions_nap_submission_file f'
        || ' JOIN napsubmissions_nap_submission s ON s.id = f.submission'
        || ' WHERE f.%I IS NOT NULL AND s.company IS NOT NULL', col, col));
    ELSE
      skipped := array_append(skipped, ('napsubmissions_nap_submission_file.' || col));
    END IF;
  END LOOP;

  -- 3. документ
  IF EXISTS (
      SELECT 1 FROM information_schema.columns
       WHERE table_schema = current_schema()
         AND table_name = 'bglocalization_bg_app9_export' AND column_name = 'bg_noi_artifact') THEN
    parts := array_append(parts, 'SELECT e.bg_noi_artifact, e.company, 3'
      || ' FROM bglocalization_bg_app9_export e'
      || ' WHERE e.bg_noi_artifact IS NOT NULL AND e.company IS NOT NULL');
  ELSE
    skipped := array_append(skipped, 'bglocalization_bg_app9_export.bg_noi_artifact');
  END IF;
  IF EXISTS (
      SELECT 1 FROM information_schema.columns
       WHERE table_schema = current_schema()
         AND table_name = 'bglocalization_bg_app9_declaration' AND column_name = 'bg_noi_artifact') THEN
    parts := array_append(parts, 'SELECT d.bg_noi_artifact, c.pay_company, 3'
      || ' FROM bglocalization_bg_app9_declaration d'
      || ' JOIN bglocalization_bg_sick_leave sl ON sl.id = d.sick_leave'
      || ' JOIN hr_employment_contract c ON c.id = sl.employment_contract'
      || ' WHERE d.bg_noi_artifact IS NOT NULL AND c.pay_company IS NOT NULL');
  ELSE
    skipped := array_append(skipped, 'bglocalization_bg_app9_declaration.bg_noi_artifact');
  END IF;
  FOREACH col IN ARRAY ARRAY['bglocalization_bg_art73_para1_report', 'bglocalization_bg_art73_para6_report']
  LOOP
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = col AND column_name = 'generated_artifact') THEN
      parts := array_append(parts, format(
        'SELECT t.generated_artifact, t.company, 3 FROM %I t'
        || ' WHERE t.generated_artifact IS NOT NULL AND t.company IS NOT NULL', col));
    ELSE
      skipped := array_append(skipped, (col || '.generated_artifact'));
    END IF;
  END LOOP;

  IF to_regclass('pg_temp.nap_artifact_company_pick') IS NOT NULL THEN
    DROP TABLE pg_temp.nap_artifact_company_pick;
  END IF;
  EXECUTE 'CREATE TEMP TABLE nap_artifact_company_pick AS'
    || ' SELECT c.artifact, count(DISTINCT c.company) AS companies,'
    || '        min(c.company) AS company, min(c.prio) AS prio'
    || ' FROM (' || array_to_string(parts, ' UNION ALL ') || ') c'
    || ' JOIN napsubmissions_nap_artifact a ON a.id = c.artifact'
    || ' WHERE a.company IS NULL'
    || ' GROUP BY c.artifact';

  SELECT count(*) FILTER (WHERE companies = 1 AND prio = 1),
         count(*) FILTER (WHERE companies = 1 AND prio = 2),
         count(*) FILTER (WHERE companies = 1 AND prio = 3),
         count(*) FILTER (WHERE companies > 1)
    INTO n_report, n_submission, n_document, n_ambiguous
    FROM nap_artifact_company_pick;

  UPDATE napsubmissions_nap_artifact a
     SET company = p.company,
         version = coalesce(a.version, 0) + 1
    FROM nap_artifact_company_pick p
   WHERE p.artifact = a.id AND p.companies = 1 AND a.company IS NULL;
  GET DIAGNOSTICS n_updated = ROW_COUNT;

  IF n_updated <> n_report + n_submission + n_document THEN
    RAISE EXCEPTION USING MESSAGE = format(
      'Броячите не се връзват: записани %s, а отчет+подаване+документ = %s — нищо не е записано.',
      n_updated, n_report + n_submission + n_document);
  END IF;

  SELECT count(*) INTO n_after FROM napsubmissions_nap_artifact WHERE company IS NULL;
  SELECT count(*) INTO n_schema FROM napsubmissions_nap_artifact
   WHERE company IS NULL AND artifact_type_select = 'schema';
  DROP TABLE nap_artifact_company_pick;

  RAISE NOTICE USING MESSAGE = format(
    'NapArtifact.company: празни преди=%s; записани=%s (от отчет=%s, от подаване=%s, от документ=%s);'
    || ' двусмислени=%s; празни след=%s (от тях схема на SAF-T=%s, без източник=%s);'
    || ' пропуснати източници: %s',
    n_before, n_updated, n_report, n_submission, n_document, n_ambiguous, n_after, n_schema,
    n_after - n_schema - n_ambiguous,
    CASE WHEN cardinality(skipped) = 0 THEN 'няма' ELSE array_to_string(skipped, ', ') END);
END
$$;
