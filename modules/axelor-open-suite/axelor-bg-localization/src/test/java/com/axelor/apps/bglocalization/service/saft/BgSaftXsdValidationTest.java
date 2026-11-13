package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.common.BgXsdValidator;
import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXsd;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ТРИТЕ ОФИЦИАЛНИ ЕТАЛОНА МИНАВАТ СРЕЩУ ЗАКОВАНАТА СХЕМА — И КАКВО ТОВА НЕ ДОКАЗВА.
 *
 * <p><b>⚠️ ЕТАЛОНИТЕ СА КРИТЕРИЙ, А НЕ ИЛЮСТРАЦИЯ.</b> Трите файла са Приложение №1 към заповед
 * З-ЦУ-30-1085, тоест НАП сам казва как изглежда приет файл. Паднат ли тук, дефектът е в НАШАТА
 * настройка на валидатора — втвърдяване, кодировка, път до схемата — а не в тях. Затова се
 * валидират ПРЕДИ да сме написали и един ред генерация: инак първият собствен файл щеше да се мери
 * срещу непроверен уред.
 *
 * <p><b>⚠️ И ЗАЩО СХЕМАТА ИЗОБЩО СЕ ПОСТРОЯВА Е ОТДЕЛЕН ВЪПРОС.</b> Схемата носи невалидна
 * декларация {@code xmlns:doc="urn:schemas-OECD:… xml:lang=en"} — URI с интервали. Xerces на този
 * билд я приема, защото префиксът {@code doc} никъде не се ползва. Тестът го ЗАКОВАВА: смени ли се
 * JDK-то или парсерът за по-строг, това пада тук, с назована причина, вместо генерацията да започне
 * да връща „SAFT_SCHEMA_CHECK_FAILED“ в продукция.
 */
class BgSaftXsdValidationTest {

  private static final String MONTHLY =
      "/schemas/saft/samples/VS_SAMPLE_AuditFile_Monthly_V_1.0.2.xml";
  private static final String ANNUAL = "/schemas/saft/samples/VS_SAMPLE_AuditFile_Annual_V_1.0.xml";

  /** Новият годишен еталон от пакета 1.0.3 (Заповед З-ЦУ-30-1797) — S.H.1 е „1.0.3“. */
  private static final String ANNUAL_103 =
      "/schemas/saft/samples/VS_SAMPLE_AuditFile_Annual_V_1.0.1.xml";

  private static final String ON_DEMAND =
      "/schemas/saft/samples/VS_SAMPLE_AuditFile_OnDemand_V_1.0.xml";

  private static byte[] sample(String resource) throws Exception {
    try (InputStream in = BgSaftXsdValidationTest.class.getResourceAsStream(resource)) {
      assertNotNull(in, "еталонът " + resource + " не е в classpath-а на теста");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) > 0) {
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    }
  }

  /** Същото извикване, което прави и генераторът — през общия валидатор с префикс SAFT. */
  private static List<BgDeclarationFileFinding> validate(byte[] xml, BgSaftXsd.Version v) {
    return BgXsdValidator.validate(xml, v.getResource(), v.describe(), BgSaftXsd.CODE_PREFIX);
  }

  /** Срещу схемата на 1.0.2 — свидетелите долу не зависят от версията. */
  private static List<BgDeclarationFileFinding> validate(byte[] xml) {
    return validate(xml, BgSaftXsd.V1_0_2);
  }

  /** Месечният еталон не е сменян в пакета 1.0.3 — минава и двете схеми. */
  @Test
  @DisplayName("Месечният еталон на НАП минава схемите 1.0.2 и 1.0.3")
  void месечният_еталон_минава() throws Exception {
    for (BgSaftXsd.Version v : BgSaftXsd.VERSIONS) {
      assertEquals(List.of(), validate(sample(MONTHLY), v), v.describe());
    }
  }

  @Test
  @DisplayName("Годишните еталони: V_1.0 минава 1.0.2, новият V_1.0.1 минава 1.0.3")
  void годишният_еталон_минава() throws Exception {
    assertEquals(List.of(), validate(sample(ANNUAL), BgSaftXsd.V1_0_2), "годишният V_1.0");
    assertEquals(
        List.of(), validate(sample(ANNUAL_103), BgSaftXsd.V1_0_3), "годишният V_1.0.1 (1.0.3)");
  }

  @Test
  @DisplayName("Еталонът „при поискване“ на НАП минава схемите 1.0.2 и 1.0.3")
  void еталонът_при_поискване_минава() throws Exception {
    for (BgSaftXsd.Version v : BgSaftXsd.VERSIONS) {
      assertEquals(List.of(), validate(sample(ON_DEMAND), v), v.describe());
    }
  }

  @Test
  @DisplayName("Липсваща схема НЕ се брои за „чисто“")
  void липсваща_схема_не_е_чисто() throws Exception {
    List<BgDeclarationFileFinding> findings =
        BgXsdValidator.validate(
            sample(MONTHLY), "/schemas/saft/НЯМА_ТАКАВА.xsd", "несъществуваща", "SAFT");
    assertEquals(1, findings.size(), findings.toString());
    assertEquals(BgSaftXsd.CODE_PREFIX + "_SCHEMA_MISSING", findings.get(0).getCode());
    assertTrue(findings.get(0).isBlocking(), "непроведена проверка е БЛОКИРАЩА, не предупреждение");
  }

  /**
   * <b>МИНАЛА XSD ПРОВЕРКА НЕ ЗНАЧИ ПРИЕТ ФАЙЛ — И ТОВА Е ИЗМЕРЕНО, НЕ ЗАПИСАНО В КОМЕНТАР.</b>
   *
   * <p>Двата свидетеля са от различни слоеве и двата минават схемата с НУЛА находки:
   *
   * <ol>
   *   <li><b>Код на сметка извън номенклатурата.</b> {@code AccountID} е {@code SAFAccountType} —
   *       {@code xs:string} с шаблон {@code [1-9][0-9]{2,4}}. Самата схема пише в документацията
   *       си, че кодът „трябва да съответства на предоставената номенклатура от сметки от НАП“ (434
   *       реда), но регулярен израз не може да провери списък. Заменени и 146-те сметки в месечния
   *       еталон с {@code 99999}, файлът остава структурно безупречен.
   *   <li><b>Счупен дебит-кредит баланс.</b> Една сума, вдигната на {@code 999999.99}, прави
   *       статията несъбираема — и схемата мълчи, защото тя описва ТИПА на полето, не сбора на
   *       колоната.
   * </ol>
   *
   * <p>Тоест XSD е ПЪРВАТА врата. Номенклатурите, балансите, кръстосаните контроли и правилото, че
   * опционална секция без данни СЕ ПРОПУСКА (Приложение №3, раздел I.1), са на семантичния
   * валидатор, който още не е написан. Докато не е — „минава схемата“ не бива да се докладва на
   * човека като „готов за подаване“.
   */
  @Test
  @DisplayName("Схемата НЕ пази номенклатури и баланси — XSD ≠ приет файл")
  void миналата_схема_не_значи_приет_файл() throws Exception {
    String monthly = new String(sample(MONTHLY), StandardCharsets.UTF_8);

    String unknownAccounts =
        monthly.replaceAll("(<nsSAFT:AccountID>)[^<]*(</nsSAFT:AccountID>)", "$199999$2");
    assertTrue(unknownAccounts.contains("99999"), "замяната трябва да е се е случила");
    assertEquals(
        List.of(),
        validate(unknownAccounts.getBytes(StandardCharsets.UTF_8)),
        "сметка извън номенклатурата на НАП минава схемата — контролата е СЕМАНТИЧНА");

    String unbalanced =
        monthly.replaceFirst(
            "<nsSAFT:Amount>[^<]*</nsSAFT:Amount>", "<nsSAFT:Amount>999999.99</nsSAFT:Amount>");
    assertTrue(unbalanced.contains("999999.99"), "замяната трябва да се е случила");
    assertEquals(
        List.of(),
        validate(unbalanced.getBytes(StandardCharsets.UTF_8)),
        "счупен баланс минава схемата — контролата е СЕМАНТИЧНА");
  }
}
