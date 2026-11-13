-- BgInsuranceContribution → BgContributionFund, СТЪПКИ 2 и 3 от 3: попълване и заключване.
--
-- Пуска се СЛЕД като речникът е зареден (App Install) — виж ...-fund-fk-1-kolona.sql за целия ред и
-- за причината стъпките да са в два файла.
--
-- ОТКЪДЕ ИДВА СТОЙНОСТТА. От низа fund_code на самия ред. Кодовете на речника са огледални на
-- стойностите в него нарочно, точно за да е съпоставянето еднозначно и проверимо. Деветте стойности
-- в данните към днес: PENSION (44), UPF (21), HEALTH (14), OZM (13), UNEMPLOYMENT (12), GVRS (11),
-- PPF (4), TEACHER_PF (2), TZPB (2).
--
-- Редовете от seed-а вече идват с попълнен FK — импортът го връзва по същата колона fundCode. UPDATE-ът
-- тук хваща онова, което seed-ът не носи: редове, въведени на ръка в живата база.
--
-- ПРОВЕРКАТА ГРЪМВА, ВМЕСТО ДА ЗАТИХНЕ. Ако остане ред с фонд, който го няма в речника, третата
-- стъпка отказва с NOT NULL violation. Мълчаливо частично попълване не е възможно.
--
-- НИЗЪТ fund_code ОСТАВА — той е още източникът на истина за изчислението (BgContributionResolver
-- сравнява точно него). Маха се в отделен клон, след като FK-ът се докаже, и пак с ръчен SQL, защото
-- App Install не изчиства стойности.

BEGIN;

-- 2 · попълване по низа, който редът вече носи
UPDATE bglocalization_bg_insurance_contribution c
    SET bg_contribution_fund = f.id
    FROM bglocalization_bg_contribution_fund f
    WHERE f.code = c.fund_code
      AND c.bg_contribution_fund IS NULL;

-- 3 · чак сега задължително, плюс истински външен ключ
ALTER TABLE bglocalization_bg_insurance_contribution
    ALTER COLUMN bg_contribution_fund SET NOT NULL;

ALTER TABLE bglocalization_bg_insurance_contribution
    DROP CONSTRAINT IF EXISTS fk_bg_insurance_contribution_fund;
ALTER TABLE bglocalization_bg_insurance_contribution
    ADD CONSTRAINT fk_bg_insurance_contribution_fund
    FOREIGN KEY (bg_contribution_fund)
    REFERENCES bglocalization_bg_contribution_fund (id);

COMMIT;
