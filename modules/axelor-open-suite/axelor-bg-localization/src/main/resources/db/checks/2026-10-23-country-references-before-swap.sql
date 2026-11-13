-- Препратките към двете „Българии“ преди миграцията, която ги слива. ЧЕТЕ САМО.
--
-- Двата реда се намират по признак, не по id:
--   L — alpha2 LA с шаблона RNT-BG (бившата България, презаписана от демо данните);
--   B — alpha2 BG (дублиращата България от вноса).
-- Показва: всички FK към base_country и колко реда сочат L и B във всяка таблица (по дружество, където
-- таблицата има колона company); уникалните ограничения на base_country и на таблиците, които я
-- сочат; join таблиците, в които прехвърлянето ще махне повторен ред; адресите по собственик;
-- текста „LAO“ в адресите и в документите; локализациите и вноса на демо данни.
-- Т. 8б–8д: бившата bg_BG (fr_CA с importId 3 след демото) — препратките към нея и към сегашната
-- bg_BG по таблица и дружество, преди и след вноса, и id-тата за ръчно решение.
-- Само id-та, кодове и бройки — без имена на партньори и дружества.
--   psql … -v ON_ERROR_STOP=1 -f 2026-10-23-country-references-before-swap.sql
BEGIN READ ONLY;
\pset border 2
\pset format aligned

-- 1) Двата реда (и всичко с alpha2 BG/LA или с RNT-BG).
SELECT c.id, c.import_id, c.name, c.alpha2code, c.alpha3code, r.import_id AS rnt,
       CASE WHEN upper(c.alpha2code) = 'LA' AND r.import_id = 'RNT-BG' THEN 'L (бивша България)'
            WHEN upper(c.alpha2code) = 'BG' THEN 'B (BG)' ELSE '' END AS роля,
       c.created_on::date AS създадена, c.updated_on::date AS променена
  FROM base_country c
  LEFT JOIN base_registration_number_template r ON r.id = c.registration_number_template
 WHERE upper(c.alpha2code) IN ('BG', 'LA') OR r.import_id = 'RNT-BG'
 ORDER BY c.id;

-- 2) Уникалните ограничения и индекси на base_country (import_id е уникален → обмяната ползва временна стойност).
SELECT i.relname AS индекс, x.indisprimary AS pk, pg_get_indexdef(x.indexrelid) AS определение
  FROM pg_index x
  JOIN pg_class i ON i.oid = x.indexrelid
 WHERE x.indrelid = 'base_country'::regclass AND x.indisunique
 ORDER BY 1;

-- 3) Всички FK към base_country и броят редове към L и към B, по дружество, където има company.
WITH l AS (
  SELECT c.id FROM base_country c
    JOIN base_registration_number_template r ON r.id = c.registration_number_template
   WHERE upper(c.alpha2code) = 'LA' AND r.import_id = 'RNT-BG'),
b AS (
  SELECT c.id FROM base_country c WHERE upper(c.alpha2code) = 'BG'),
ids AS (
  SELECT (SELECT array_agg(id) FROM l) AS l_ids, (SELECT array_agg(id) FROM b) AS b_ids),
fk AS (
  SELECT con.conname,
         con.conrelid::regclass::text AS tbl,
         a.attname::text AS col,
         cardinality(con.conkey) AS cols,
         EXISTS (SELECT 1 FROM pg_attribute x
                  WHERE x.attrelid = con.conrelid AND x.attname = 'company' AND NOT x.attisdropped) AS has_company
    FROM pg_constraint con
    JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = con.conkey[1]
   WHERE con.contype = 'f' AND con.confrelid = 'base_country'::regclass),
counts AS (
  SELECT fk.tbl, fk.col, fk.cols, fk.has_company, x.company, x.country, x.n
    FROM fk, ids,
         XMLTABLE('/table/row' PASSING query_to_xml(
           format('SELECT %s AS company, %I AS country, count(*) AS n FROM %s WHERE %I = ANY(%L::bigint[]) GROUP BY 1, 2',
                  CASE WHEN fk.has_company THEN 'company' ELSE 'NULL::bigint' END,
                  fk.col, fk.tbl, fk.col,
                  coalesce(ids.l_ids, '{}'::bigint[]) || coalesce(ids.b_ids, '{}'::bigint[])),
           false, false, '')
           COLUMNS company bigint, country bigint, n bigint) x)
SELECT fk.tbl AS таблица,
       fk.col AS колона,
       CASE WHEN fk.has_company THEN coalesce(c.company::text, '(празно)') ELSE '—' END AS дружество,
       coalesce(sum(c.n) FILTER (WHERE c.country = ANY(ids.l_ids)), 0) AS към_L,
       coalesce(sum(c.n) FILTER (WHERE c.country = ANY(ids.b_ids)), 0) AS към_B,
       CASE WHEN fk.cols > 1 THEN 'съставен FK!' ELSE '' END AS бележка
  FROM fk
  CROSS JOIN ids
  LEFT JOIN counts c ON c.tbl = fk.tbl AND c.col = fk.col
 GROUP BY fk.tbl, fk.col, fk.cols, fk.has_company, c.company, ids.l_ids, ids.b_ids
 ORDER BY 1, 2, 3;

-- 4) Уникални индекси в таблиците, които сочат base_country, съдържащи колоната на FK —
--    прехвърлянето B → L може да се удари в тях (миграцията тогава спира и връща всичко назад).
SELECT con.conrelid::regclass AS таблица, a.attname AS колона, i.relname AS индекс,
       pg_get_indexdef(x.indexrelid) AS определение
  FROM pg_constraint con
  JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = con.conkey[1]
  JOIN pg_index x ON x.indrelid = con.conrelid AND x.indisunique AND a.attnum = ANY(x.indkey::int2[])
  JOIN pg_class i ON i.oid = x.indexrelid
 WHERE con.contype = 'f' AND con.confrelid = 'base_country'::regclass
 ORDER BY 1, 2;

-- 5) Join таблиците (без колона id): колко реда към B имат вече двойник към L — те се махат.
WITH l AS (
  SELECT c.id FROM base_country c
    JOIN base_registration_number_template r ON r.id = c.registration_number_template
   WHERE upper(c.alpha2code) = 'LA' AND r.import_id = 'RNT-BG'),
b AS (
  SELECT c.id FROM base_country c WHERE upper(c.alpha2code) = 'BG'),
jt AS (
  SELECT con.conrelid AS rel, con.conrelid::regclass::text AS tbl, a.attname::text AS col,
         (SELECT string_agg(format('u.%1$I IS NOT DISTINCT FROM t.%1$I', o.attname), ' AND ')
            FROM pg_attribute o
           WHERE o.attrelid = con.conrelid AND o.attnum > 0 AND NOT o.attisdropped
             AND o.attname <> a.attname) AS others
    FROM pg_constraint con
    JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = con.conkey[1]
   WHERE con.contype = 'f' AND con.confrelid = 'base_country'::regclass
     AND NOT EXISTS (SELECT 1 FROM pg_attribute x
                      WHERE x.attrelid = con.conrelid AND x.attname = 'id' AND NOT x.attisdropped))
SELECT jt.tbl AS join_таблица, jt.col AS колона, x.n AS повторени_за_махане
  FROM jt
  CROSS JOIN (SELECT (SELECT min(id) FROM l) AS l_id, (SELECT min(id) FROM b) AS b_id) ids,
       XMLTABLE('/table/row' PASSING query_to_xml(
         format('SELECT count(*) AS n FROM %s t WHERE t.%I = %s AND EXISTS (SELECT 1 FROM %s u WHERE u.%I = %s AND %s)',
                jt.tbl, jt.col, coalesce(ids.b_id, -1), jt.tbl, jt.col, coalesce(ids.l_id, -1),
                coalesce(jt.others, 'true')),
         false, false, '')
         COLUMNS n bigint) x
 ORDER BY 1;

-- 6) Адресите към L и B по собственик: адрес на дружество, на партньор (по дружеството на партньора), друг.
WITH lb AS (
  SELECT c.id,
         CASE WHEN upper(c.alpha2code) = 'BG' THEN 'B' ELSE 'L' END AS роля
    FROM base_country c
    LEFT JOIN base_registration_number_template r ON r.id = c.registration_number_template
   WHERE upper(c.alpha2code) = 'BG' OR (upper(c.alpha2code) = 'LA' AND r.import_id = 'RNT-BG')),
owner AS (
  SELECT a.id, lb.роля,
         CASE WHEN EXISTS (SELECT 1 FROM base_company co WHERE co.address = a.id) THEN 'на дружество'
              WHEN EXISTS (SELECT 1 FROM base_partner_address pa WHERE pa.address = a.id) THEN 'на партньор'
              ELSE 'друг' END AS вид,
         (SELECT min((to_jsonb(p) ->> 'company')::bigint)
            FROM base_partner_address pa JOIN base_partner p ON p.id = pa.partner
           WHERE pa.address = a.id) AS дружество_на_партньора,
         a.created_on >= DATE '2026-10-15' AS след_15_10,
         a.formatted_full_name ~ '(^|\n)\s*LAOS?\s*$' OR a.full_name ~ '(^|\n|\s)LAOS?\s*$' AS текст_LAO
    FROM base_address a
    JOIN lb ON lb.id = a.country)
SELECT роля, вид, coalesce(дружество_на_партньора::text, '—') AS дружество_на_партньора,
       count(*) AS адреси,
       count(*) FILTER (WHERE след_15_10) AS създадени_след_15_10,
       count(*) FILTER (WHERE текст_LAO) AS с_LAO_в_текста
  FROM owner
 GROUP BY 1, 2, 3
 ORDER BY 1, 2, 3;

-- 7) „LAO“ като последен ред в текстовите копия на адреси в документите (миграцията НЕ ги пипа).
WITH cols AS (
  SELECT table_name::text AS tbl, column_name::text AS col
    FROM information_schema.columns
   WHERE table_schema = current_schema()
     AND column_name ~ 'address.*str' AND table_name <> 'base_address_template'
     AND data_type IN ('text', 'character varying'))
SELECT cols.tbl AS таблица, cols.col AS колона, x.n AS с_LAO
  FROM cols,
       XMLTABLE('/table/row' PASSING query_to_xml(
         format('SELECT count(*) AS n FROM %I WHERE %I ~ %L', cols.tbl, cols.col, '(^|\n)\s*LAOS?\s*$'),
         false, false, '')
         COLUMNS n bigint) x
 WHERE x.n > 0
 ORDER BY 1, 2;

-- 8) Локализациите — дали bg_BG още е с importId 3 и дали демото е добавило fr_CA (importId 3 в AOS).
SELECT l.id, l.import_id, l.code, c.id AS country_id, c.alpha2code, g.code AS език, l.updated_on::date AS променена
  FROM base_localization l
  LEFT JOIN base_country c ON c.id = l.country
  LEFT JOIN base_language g ON g.id = l.language
 ORDER BY l.id;

-- 8б) ЛОКАЛИЗАЦИИТЕ И ВНОСЪТ НА ДЕМО ДАННИТЕ. Демото на AOS внася fr_CA с importId 3, а нашият
--     data-init дава 3 на bg_BG: редът bg_BG се презаписва на fr_CA и всичко, вързано за него,
--     става „френски (Канада)“. Без фиксирани id:
--       T — моментът на вноса на пълния списък: най-ранното created_on на държава, която кореновият
--           data-init няма (alpha2 извън CA, US, FR, GB, BG и LA — LA е бившата България преди обмяната);
--       X — редът fr_CA, създаден ПРЕДИ T (бившата bg_BG);
--       Y — сегашният bg_BG.
SELECT 'T (внос на пълния списък държави)' AS какво,
       (SELECT min(c.created_on) FROM base_country c
         WHERE upper(c.alpha2code) NOT IN ('CA', 'US', 'FR', 'GB', 'BG', 'LA'))::text AS стойност
UNION ALL
SELECT 'X (fr_CA отпреди T) id',
       (SELECT string_agg(l.id::text, ',') FROM base_localization l
         WHERE l.code = 'fr_CA'
           AND l.created_on < (SELECT min(c.created_on) FROM base_country c
                                WHERE upper(c.alpha2code) NOT IN ('CA', 'US', 'FR', 'GB', 'BG', 'LA')))
UNION ALL
SELECT 'Y (bg_BG) id', (SELECT string_agg(l.id::text, ',') FROM base_localization l WHERE l.code = 'bg_BG');

-- 8в) Всички локализации с import_id и кой/кога ги е създал и пипал (Y — кога и от кого е създаден).
SELECT l.id, l.import_id, l.code, c.alpha2code AS държава,
       l.created_on, l.created_by AS създал_id, l.updated_on, l.updated_by AS променил_id, l.import_origin
  FROM base_localization l
  LEFT JOIN base_country c ON c.id = l.country
 ORDER BY l.id;

-- 8г) Препратките към X и Y по таблица и дружество, преди и след T. Дружеството: company, на
--     потребителя — active_company, на base_company — самото то; само id.
WITH t AS (
  SELECT min(c.created_on) AS t FROM base_country c
   WHERE upper(c.alpha2code) NOT IN ('CA', 'US', 'FR', 'GB', 'BG', 'LA')),
xy AS (
  SELECT (SELECT array_agg(l.id) FROM base_localization l, t
           WHERE l.code = 'fr_CA' AND l.created_on < t.t) AS x_ids,
         (SELECT array_agg(l.id) FROM base_localization l WHERE l.code = 'bg_BG') AS y_ids),
fk AS (
  SELECT con.conrelid::regclass::text AS tbl,
         a.attname::text AS col,
         EXISTS (SELECT 1 FROM pg_attribute x WHERE x.attrelid = con.conrelid
                   AND x.attname = 'created_on' AND NOT x.attisdropped) AS has_created,
         CASE WHEN con.conrelid = 'base_company'::regclass THEN 'id'
              WHEN EXISTS (SELECT 1 FROM pg_attribute x WHERE x.attrelid = con.conrelid
                             AND x.attname = 'company' AND NOT x.attisdropped) THEN 'company'
              WHEN EXISTS (SELECT 1 FROM pg_attribute x WHERE x.attrelid = con.conrelid
                             AND x.attname = 'active_company' AND NOT x.attisdropped) THEN 'active_company'
              ELSE NULL END AS company_col
    FROM pg_constraint con
    JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = con.conkey[1]
   WHERE con.contype = 'f' AND con.confrelid = 'base_localization'::regclass),
counts AS (
  SELECT fk.tbl, fk.col, r.company, r.loc, r.before_t, r.n
    FROM fk, xy, t,
         XMLTABLE('/table/row' PASSING query_to_xml(
           format('SELECT %s AS company, %I AS loc, %s AS before_t, count(*) AS n FROM %s'
                  || ' WHERE %I = ANY(%L::bigint[]) GROUP BY 1, 2, 3',
                  coalesce(quote_ident(fk.company_col), 'NULL::bigint'),
                  fk.col,
                  CASE WHEN fk.has_created THEN format('(created_on < %L::timestamp)', t.t) ELSE 'NULL::boolean' END,
                  fk.tbl, fk.col,
                  coalesce(xy.x_ids, '{}'::bigint[]) || coalesce(xy.y_ids, '{}'::bigint[])),
           false, false, '')
           COLUMNS company bigint, loc bigint, before_t boolean, n bigint) r)
SELECT c.tbl AS таблица, c.col AS колона,
       coalesce(c.company::text, '—') AS дружество,
       sum(c.n) FILTER (WHERE c.loc = ANY(xy.x_ids) AND c.before_t)     AS X_преди_T,
       sum(c.n) FILTER (WHERE c.loc = ANY(xy.x_ids) AND NOT c.before_t) AS X_след_T,
       sum(c.n) FILTER (WHERE c.loc = ANY(xy.x_ids) AND c.before_t IS NULL) AS X_без_дата,
       sum(c.n) FILTER (WHERE c.loc = ANY(xy.y_ids) AND c.before_t)     AS Y_преди_T,
       sum(c.n) FILTER (WHERE c.loc = ANY(xy.y_ids) AND NOT c.before_t) AS Y_след_T,
       sum(c.n) FILTER (WHERE c.loc = ANY(xy.y_ids) AND c.before_t IS NULL) AS Y_без_дата
  FROM counts c CROSS JOIN xy
 GROUP BY 1, 2, 3
 ORDER BY 1, 2, 3;

-- 8д) Списъкът за ръчно решение: id-тата на записите към X, преди и след T (само таблици с колона id).
WITH t AS (
  SELECT min(c.created_on) AS t FROM base_country c
   WHERE upper(c.alpha2code) NOT IN ('CA', 'US', 'FR', 'GB', 'BG', 'LA')),
x AS (
  SELECT array_agg(l.id) AS ids FROM base_localization l, t WHERE l.code = 'fr_CA' AND l.created_on < t.t),
fk AS (
  SELECT con.conrelid::regclass::text AS tbl, a.attname::text AS col
    FROM pg_constraint con
    JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = con.conkey[1]
   WHERE con.contype = 'f' AND con.confrelid = 'base_localization'::regclass
     AND EXISTS (SELECT 1 FROM pg_attribute i WHERE i.attrelid = con.conrelid AND i.attname = 'id')
     AND EXISTS (SELECT 1 FROM pg_attribute i WHERE i.attrelid = con.conrelid AND i.attname = 'created_on'))
SELECT fk.tbl AS таблица, fk.col AS колона, r.before_t AS преди_T, r.ids AS id_та
  FROM fk, x, t,
       XMLTABLE('/table/row' PASSING query_to_xml(
         format('SELECT created_on < %L::timestamp AS before_t, string_agg(id::text, '','' ORDER BY id) AS ids'
                || ' FROM %s WHERE %I = ANY(%L::bigint[]) GROUP BY 1',
                t.t, fk.tbl, fk.col, coalesce(x.ids, '{}'::bigint[])),
         false, false, '')
         COLUMNS before_t boolean, ids text) r
 ORDER BY 1, 2, 3 DESC;

-- 9) Откъде са демо данните: приложенията с внесени демо данни (бутонът „Import demo data“).
SELECT a.code, a.active, a.demo_data_loaded, a.updated_on::date AS променено, a.updated_by AS от_потребител_id
  FROM studio_app a
 WHERE a.demo_data_loaded
 ORDER BY a.updated_on;

-- 10) Съобщения и последователи, вързани за L или B (препратки извън FK — обмяната не ги мести).
SELECT 'mail_message' AS таблица, m.related_id AS country_id, count(*) AS брой
  FROM mail_message m
 WHERE m.related_model = 'com.axelor.apps.base.db.Country'
   AND m.related_id IN (SELECT id FROM base_country WHERE upper(alpha2code) IN ('BG', 'LA'))
 GROUP BY 2
UNION ALL
SELECT 'mail_follower', f.related_id, count(*)
  FROM mail_follower f
 WHERE f.related_model = 'com.axelor.apps.base.db.Country'
   AND f.related_id IN (SELECT id FROM base_country WHERE upper(alpha2code) IN ('BG', 'LA'))
 GROUP BY 2
 ORDER BY 1, 2;

ROLLBACK;
