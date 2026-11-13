-- Проверка СЛЕД миграцията. Очакван изход: три реда, всичките с „ДА“.
SELECT 'carrier_key е NOT NULL' AS check,
       CASE WHEN is_nullable = 'NO' THEN 'ДА' ELSE 'НЕ' END AS ok
  FROM information_schema.columns
 WHERE table_name = 'bglocalization_bg_vat_annual_correction_line'
   AND column_name = 'carrier_key'
UNION ALL
SELECT 'старият ключ (annual_correction, fixed_asset) е премахнат',
       CASE WHEN count(*) = 0 THEN 'ДА' ELSE 'НЕ' END
  FROM pg_constraint con
  JOIN pg_class rel ON rel.oid = con.conrelid
 WHERE rel.relname = 'bglocalization_bg_vat_annual_correction_line'
   AND con.contype = 'u'
   AND pg_get_constraintdef(con.oid) = 'UNIQUE (annual_correction, fixed_asset)'
UNION ALL
SELECT 'новият ключ (…, carrier_key) съществува',
       CASE WHEN count(*) = 1 THEN 'ДА' ELSE 'НЕ' END
  FROM pg_constraint con
  JOIN pg_class rel ON rel.oid = con.conrelid
 WHERE rel.relname = 'bglocalization_bg_vat_annual_correction_line'
   AND con.contype = 'u'
   AND pg_get_constraintdef(con.oid)
       = 'UNIQUE (annual_correction, fixed_asset, carrier_key)';
