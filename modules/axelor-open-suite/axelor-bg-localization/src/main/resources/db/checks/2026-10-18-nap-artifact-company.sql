-- NapArtifact.company — колко файла за НАП/НОИ са с дружество и колко без. ЧЕТЕ САМО.
--
-- Каталогът на nyxa-security чете NapArtifact по self.company. Файл без
-- дружество вижда само admin. Празното го попълват стартовата стъпка (BgNapArtifactCompanyBackfill)
-- и ръчната миграция db/migration/2026-10-17-nap-artifact-company.sql; остават празни схемата на
-- SAF-T (нарочно), двусмислените и тези без източник.
--
-- Само бройки по тип — без имена на файлове, без съдържание, без ЕГН.
-- Работи и ПРЕДИ деплоя: дружеството се чете през to_jsonb(ред) ->> 'company', тоест при липсваща
-- колона всичко излиза „без дружество“, а вторият ред казва, че колоната още я няма.
--
-- Очаквано след деплоя: без_дружество = 0 за всички типове освен schema (и евентуални двусмислени
-- или без източник — тях ги брои редът в лога при старта).
--   psql … -v ON_ERROR_STOP=1 -f 2026-10-18-nap-artifact-company.sql
BEGIN READ ONLY;
\pset border 2
\pset format aligned
SELECT coalesce(a.artifact_type_select, '(празно)')                      AS тип,
       count(*) FILTER (WHERE to_jsonb(a) ->> 'company' IS NULL)      AS без_дружество,
       count(*) FILTER (WHERE to_jsonb(a) ->> 'company' IS NOT NULL)  AS с_дружество,
       count(*)                                                        AS общо
  FROM napsubmissions_nap_artifact a
 GROUP BY 1
 ORDER BY 1;
SELECT EXISTS (SELECT 1
                 FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'napsubmissions_nap_artifact'
                  AND column_name = 'company')                         AS колоната_company_я_има;
ROLLBACK;
