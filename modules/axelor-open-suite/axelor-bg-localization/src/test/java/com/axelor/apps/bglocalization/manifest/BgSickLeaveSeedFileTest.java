package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ФАЙЛОВАТА половина за перото на болничния за сметка на осигурителя: вярно ли е ЗАПИСАНО.
 *
 * <p>Двойник на {@code BgPaySlipLineTypeManifestTest}, който пита ДОСТАВЕНО ли е и се пропуска без
 * база. Разликата между двата въпроса е разликата между „сгрешен флаг“ и „не е пускан App Install“;
 * слети в един тест, второто маскира първото — и точно това е причината този файл да съществува,
 * вместо новият ред да се провери само срещу базата.
 *
 * <p><b>⚠️ ЗАЩО ФЛАГОВЕТЕ СЕ ПРОВЕРЯВАТ ПООТДЕЛНО, А НЕ КАТО РЕД.</b> Двата флага на това перо
 * изглеждат противоречиви — осигурителен доход И необлагаем — и точно затова следващият читател ще
 * реши, че единият е грешка. Всеки от двата има отделен свидетел с отделно правно основание в
 * съобщението си, за да не се „поправи“ мълчаливо.
 */
class BgSickLeaveSeedFileTest {

  private static final String FILE = "bg_pay_slip_line_type.csv";

  /** Стабилният код на перото — същият низ, който {@code BgSickLeaveResolver} ползва. */
  private static final String CODE = "pl_bolnichen_rabotodatel";

  private static Map<String, String> row(String code) {
    List<String> h = BgSeed.header(FILE);
    for (String[] r : BgSeed.rows(FILE)) {
      if (code.equals(BgSeed.col(h, r, "code"))) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String column : h) {
          out.put(column, BgSeed.col(h, r, column));
        }
        return out;
      }
    }
    fail(
        "няма ред с код „"
            + code
            + "“ в "
            + FILE
            + " — перото на болничния за сметка на осигурителя не е заредено. Намерени кодове: "
            + codes());
    return null;
  }

  private static List<String> codes() {
    List<String> h = BgSeed.header(FILE);
    List<String> out = new ArrayList<>();
    for (String[] r : BgSeed.rows(FILE)) {
      out.add(BgSeed.col(h, r, "code"));
    }
    return out;
  }

  @Test
  @DisplayName("Перото го има, с номер 195 в група „Заплата и добавки“ и посока „начислява се“")
  void theLineTypeIsSeeded() {
    Map<String, String> r = row(CODE);
    assertEquals(
        "195", r.get("number"), "номерът е част от идентичността: по него се брои групата");
    assertEquals(
        "SALARY_ADDITIONS",
        r.get("bgGroup"),
        "⚠️ 1xx, А НЕ 3xx, И ТОВА Е АРИТМЕТИКА, НЕ СХОДСТВО. BgPaySlipResolver брои брутото и разхода"
            + " за работодателя САМО от 1xx и 9xx. Ред от 3xx („Болнични и отпуски“) би паднал извън"
            + " двете, при положение че тази сума е разход на работодателя и осигурителен доход на"
            + " лицето. Групата 3xx чака дните и сумите, които НОИ плаща.");
    assertEquals("CREDIT", r.get("bgSign"), "начислява се на лицето, не се удържа");
    assertEquals("true", r.get("active"));
    assertEquals(
        "DERIVED",
        r.get("bgCalculationBase"),
        "сумата идва от BgSickLeaveService, не от начисленията: тя стъпва на епизодите и на"
            + " осигурителния стаж, които енджинът за начисления не познава");
  }

  @Test
  @DisplayName("⚠️ ОСИГУРИТЕЛЕН ДОХОД по чл. 40 ал. 5 КСО — вноските се дължат върху тази сума")
  void theAmountIsInsurableIncome() {
    assertEquals(
        "true",
        row(CODE).get("bgIsInsuranceIncome"),
        "⚠️ Свален, флагът изважда сумата от осигурителния доход и от Д1 — тоест лицето остава"
            + " неосигурено за дните по чл. 40 ал. 5 КСО, а фишът пак изглежда пресметнат.");
  }

  @Test
  @DisplayName("⚠️ НЕОБЛАГАЕМ по ЗДДФЛ чл. 24 ал. 2 т. 14 във вр. с чл. 13 ал. 1 т. 6")
  void theAmountIsNotTaxable() {
    assertEquals(
        "false",
        row(CODE).get("bgIsTaxable"),
        "⚠️ Вдигнат, флагът облага обезщетение, което ЗДДФЛ изрично изключва от облагаемия доход от"
            + " трудово правоотношение. Двата флага НЕ се сливат: единият отговаря на КСО, другият"
            + " на ЗДДФЛ, и точно затова изглеждат противоречиви.");
  }

  @Test
  @DisplayName("⚠️ БЕЗ постоянен характер — инак болничният влиза в базата за платения отпуск")
  void theAmountIsNotOfPermanentCharacter() {
    assertEquals(
        "false",
        row(CODE).get("bgIsPermanentCharacter"),
        "⚠️ Не е допълнително трудово възнаграждение по НСОРЗ чл. 11 или чл. 12 и НЕ зависи от"
            + " отработеното време — то е нула точно в дните, за които се плаща. Вдигнат, флагът би"
            + " го вкарал в базата за платения отпуск по КТ чл. 177 ал. 2 и в БТВ за обезщетенията.");
  }

  @Test
  @DisplayName("Правното основание и провенансът стоят на реда, не само в доклад")
  void theProvenanceTravelsWithTheRow() {
    Map<String, String> r = row(CODE);
    assertEquals("чл. 40 ал. 5 КСО", r.get("bgLegalBasis"));
    String note = r.get("note");
    assertTrue(
        note.contains("чл. 24 ал. 2 т. 14") && note.contains("чл. 13 ал. 1 т. 6"),
        "бележката трябва да носи ДВЕТЕ препратки на необлагаемостта, инак следващият читател ще"
            + " прочете само единия закон: "
            + note);
    assertTrue(
        note.contains("WS-7"),
        "бележката трябва да казва изрично, че сумата и данните за НОИ са WS-7 — инак липсата им"
            + " изглежда като пропуск: "
            + note);
  }

  @Test
  @DisplayName("⚠️ Номерът и кодът са УНИКАЛНИ — новото перо не е заело чужда позиция")
  void theNumberAndCodeAreUnique() {
    List<String> h = BgSeed.header(FILE);
    Map<String, Integer> byNumber = new LinkedHashMap<>();
    Map<String, Integer> byCode = new LinkedHashMap<>();
    for (String[] r : BgSeed.rows(FILE)) {
      byNumber.merge(BgSeed.col(h, r, "number"), 1, Integer::sum);
      byCode.merge(BgSeed.col(h, r, "code"), 1, Integer::sum);
    }
    assertEquals(1, byNumber.get("195").intValue(), "номер 195 се среща повече от веднъж");
    assertEquals(1, byCode.get(CODE).intValue(), "кодът " + CODE + " се среща повече от веднъж");
  }
}
