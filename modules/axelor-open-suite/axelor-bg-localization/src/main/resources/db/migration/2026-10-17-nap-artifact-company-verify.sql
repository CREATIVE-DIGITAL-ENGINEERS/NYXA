-- NapArtifact.company — проверка след 2026-10-17-nap-artifact-company.sql. САМО ЧЕТЕ.
--
-- Три числа и списък:
--   празни          — файлове без дружество (видими само за admin)
--   от тях схема    — схемата на SAF-T, нарочно без дружество (очаквано)
--   разминаващи се  — файл със saft_report, чието дружество е РАЗЛИЧНО от company на файла
--                     (трябва да е 0: каталогът преди и след смяната трябва да вижда същото)
-- И по видове: колко празни остават, за преглед на ръка.

SELECT 'празни' AS check_name, count(*) AS n
  FROM napsubmissions_nap_artifact WHERE company IS NULL
UNION ALL
SELECT 'от тях схема на SAF-T', count(*)
  FROM napsubmissions_nap_artifact WHERE company IS NULL AND artifact_type_select = 'schema'
UNION ALL
SELECT 'разминаващи се с отчета (трябва 0)', count(*)
  FROM napsubmissions_nap_artifact a
  JOIN napsubmissions_saft_report r ON r.id = a.saft_report
 WHERE r.company IS NOT NULL AND a.company IS DISTINCT FROM r.company;

SELECT coalesce(artifact_type_select, '(празно)') AS artifact_type,
       coalesce(institution_select, '(празно)') AS institution,
       count(*) AS still_without_company
  FROM napsubmissions_nap_artifact
 WHERE company IS NULL
 GROUP BY 1, 2
 ORDER BY 1, 2;
