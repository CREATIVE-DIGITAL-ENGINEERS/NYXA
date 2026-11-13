package com.axelor.apps.bglocalization.service.vat.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * VIES — РЕД ЗА ПРЕДХОДЕН ДАНЪЧЕН ПЕРИОД.
 *
 * <p>ППЗДДС чл. 117 ал. 2 т. 11: „данъчен период, през който данъкът за доставките по т. 8 - 10 е
 * станал изискуем, в случай че този период е различен от данъчния период по т. 3“; ал. 5 т. 4 —
 * VIES се подава и когато лицето „е отразило пропуснати данни за предходен данъчен период в
 * съответствие с чл. 126, ал. 3, т. 1 от закона“ (консолидиран текст, последно изм. ДВ бр. 54/2024,
 * сверено 27.10.2026). Изискуемостта — ЗДДС чл. 51 ал. 3–4 (ВОД) и чл. 25 ал. 6 (услуги), lex.bg,
 * последно изм. ДВ бр. 69/2026.
 *
 * <p>VIR: таг 3 + № 5 + ИН 15 + три суми по 12 + период 7 (`мм/гггг`, празен = периодът на
 * декларацията).
 */
public class BgVatViesPastPeriodTest {

  static final String JUNE = "06/2026";

  private final BgVatViesAdapter adapter = new BgVatViesAdapter(new BgVatSalesAdapter());

  public static BgVatDocumentDto doc(
      String vin, BgVatSalesColumn column, String amount, LocalDate date, LocalDate event) {
    BgVatDocumentDto d = new BgVatDocumentDto();
    d.setNapDocumentType("01");
    d.setDocumentNumber("1");
    d.setOperationType(3);
    d.setCounterpartyVatNumber(vin);
    d.setCounterpartyDiaryId(vin);
    d.setCounterpartyName("Клиент");
    d.setDocumentDate(date);
    d.setTaxEventDate(event);
    d.addSalesAmount(column, new BigDecimal(amount));
    return d;
  }

  private List<String> lines(BgVatDocumentDto... docs) {
    return adapter.buildLines(
        Arrays.asList(docs),
        new BgVatViesAdapter.RegisteredPerson("BG100000033", "Х", "Х"),
        new BgVatViesAdapter.Declarant("1", "Х", "Х", "1000", "Х", "Х"),
        JUNE);
  }

  /** „№|ИН|стоки|тристранни|услуги|период“ на всеки VIR ред. */
  public static List<String> virs(List<String> lines) {
    return lines.stream()
        .filter(l -> l.startsWith("VIR"))
        .map(
            l ->
                l.substring(3, 8).trim()
                    + "|"
                    + l.substring(8, 23).trim()
                    + "|"
                    + l.substring(23, 35).trim()
                    + "|"
                    + l.substring(35, 47).trim()
                    + "|"
                    + l.substring(47, 59).trim()
                    + "|"
                    + l.substring(59, 66).trim())
        .collect(Collectors.toList());
  }

  private static LocalDate d(int month, int day) {
    return LocalDate.of(2026, month, day);
  }

  @Test
  @DisplayName("ВОД с фактура в периода и без дата на събитието — периодът е празен (= т. 3)")
  void текущият_период_е_празен() {
    assertEquals(
        List.of("1|DE123456789|100.00|0.00|0.00|"),
        virs(lines(doc("DE123456789", BgVatSalesColumn.K20_BASE_ICS, "100", d(6, 10), null))));
  }

  @Test
  @DisplayName("ВОД: събитие 20.04, фактура 03.06 — изискуем на 15.05 (чл. 51 ал. 3) → „05/2026“")
  void вод_изискуем_на_15_и() {
    assertEquals(
        List.of("1|DE123456789|100.00|0.00|0.00|05/2026"),
        virs(lines(doc("DE123456789", BgVatSalesColumn.K20_BASE_ICS, "100", d(6, 3), d(4, 20)))));
  }

  @Test
  @DisplayName(
      "ВОД: събитие 20.05, фактура 10.06 (преди 15.06) — изискуем на 10.06 (ал. 4) → празен")
  void вод_фактура_преди_15_и() {
    assertEquals(
        List.of("1|DE123456789|100.00|0.00|0.00|"),
        virs(lines(doc("DE123456789", BgVatSalesColumn.K20_BASE_ICS, "100", d(6, 10), d(5, 20)))));
  }

  @Test
  @DisplayName(
      "ВОД: събитие 20.04, фактура 30.04 (преди 15.05) — изискуем на 30.04 (ал. 4) → „04/2026“")
  void вод_фактура_в_месеца_на_събитието() {
    assertEquals(
        List.of("1|DE123456789|100.00|0.00|0.00|04/2026"),
        virs(lines(doc("DE123456789", BgVatSalesColumn.K20_BASE_ICS, "100", d(4, 30), d(4, 20)))));
  }

  @Test
  @DisplayName("Услуга по чл. 21 ал. 2: извършена 25.05, фактура 02.06 — изискуем на 25.05")
  void услугата_е_по_събитието() {
    assertEquals(
        List.of("1|FR12345678901|0.00|0.00|250.00|05/2026"),
        virs(
            lines(
                doc(
                    "FR12345678901",
                    BgVatSalesColumn.K22_BASE_EU_SERVICES,
                    "250",
                    d(6, 2),
                    d(5, 25)))));
  }

  /**
   * ⚠️ Посредникът в тристранна операция НЕ следва чл. 51: неговата доставка „не е вътреобщностна
   * доставка“ (ЗДДС чл. 7 ал. 5 т. 6). Периодът е този на данъчното събитие (чл. 25 ал. 2) — същите
   * дати дават различен месец за ВОД и за тристранната.
   */
  @Test
  @DisplayName(
      "Тристранна: събитие 20.05, фактура 03.06 → „05/2026“ (чл. 25 ал. 2), ВОД → юни (чл. 51)")
  void тристранната_не_е_вод() {
    assertEquals(
        List.of("1|AT12345678|0.00|80.00|0.00|05/2026"),
        virs(
            lines(
                doc("AT12345678", BgVatSalesColumn.K25_BASE_TRIANGULAR, "80", d(6, 3), d(5, 20)))));
    assertEquals(
        List.of("1|AT12345678|80.00|0.00|0.00|"),
        virs(lines(doc("AT12345678", BgVatSalesColumn.K20_BASE_ICS, "80", d(6, 3), d(5, 20)))));
  }

  @Test
  @DisplayName("Тристранна без дата на събитието — периодът на документа")
  void тристранната_без_събитие() {
    assertEquals(
        List.of("1|AT12345678|0.00|80.00|0.00|04/2026"),
        virs(lines(doc("AT12345678", BgVatSalesColumn.K25_BASE_TRIANGULAR, "80", d(4, 28), null))));
  }

  /**
   * ППЗДДС чл. 117 ал. 4: във VIES „не се включват издадените данъчни документи във връзка с
   * получени авансови плащания по вътреобщностни доставки на стоки“; „Полученото авансово плащане …
   * от посредник в тристранна операция не се включва“. Авансът за услуга по чл. 21 ал. 2 СЕ включва
   * (ал. 2 т. 4 б. „б“).
   */
  @Test
  @DisplayName("Аванс: ВОД и тристранна — не във VIES (ППЗДДС чл. 117 ал. 4); услуга — да")
  void авансите() {
    BgVatDocumentDto vod = doc("DE123456789", BgVatSalesColumn.K20_BASE_ICS, "100", d(6, 5), null);
    vod.setAdvancePayment(true);
    BgVatDocumentDto triangular =
        doc("AT12345678", BgVatSalesColumn.K25_BASE_TRIANGULAR, "80", d(6, 5), null);
    triangular.setAdvancePayment(true);
    BgVatDocumentDto service =
        doc("FR12345678901", BgVatSalesColumn.K22_BASE_EU_SERVICES, "250", d(6, 5), null);
    service.setAdvancePayment(true);

    assertEquals(
        List.of("1|FR12345678901|0.00|0.00|250.00|"), virs(lines(vod, triangular, service)));
  }

  @Test
  @DisplayName("Документ с дата в предходен период и без събитие — периодът е датата му")
  void датата_на_документа() {
    assertEquals(
        List.of("1|DE123456789|100.00|0.00|0.00|04/2026"),
        virs(lines(doc("DE123456789", BgVatSalesColumn.K20_BASE_ICS, "100", d(4, 30), null))));
  }

  @Test
  @DisplayName("Смесен период: един получател, текущ и минал ред — два VIR реда, общите суми")
  void смесеният_период() {
    List<String> lines =
        lines(
            doc("DE123456789", BgVatSalesColumn.K20_BASE_ICS, "100", d(6, 10), null),
            doc("DE123456789", BgVatSalesColumn.K20_BASE_ICS, "40", d(6, 3), d(4, 20)),
            doc("DE123456789", BgVatSalesColumn.K20_BASE_ICS, "5", d(6, 12), null));

    assertEquals(
        List.of("1|DE123456789|105.00|0.00|0.00|", "2|DE123456789|40.00|0.00|0.00|05/2026"),
        virs(lines));
    // VHR: брой редове в раздел В = 2; TTR: всичко 145, ВОД 145.
    assertEquals("VHR06/2026    2", lines.get(0));
    assertEquals("TTR      145.00      145.00", lines.get(3));
  }
}
