package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoice;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoiceLine;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxInformation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * КОДОВЕТЕ НА ПОДАДЕНИТЕ ДОКУМЕНТИ ПО СТАТИЯ — ОТТУК ДНЕВНИКЪТ ВЗИМА КОДА, КОЙТО ЗАВИСИ ОТ
 * СДЕЛКАТА.
 *
 * <p><b>⚠️ ЕДИН КОД ЗА ЕДИН ДОКУМЕНТ</b> (решение от 15.10.2026). Седемте данъка без код в {@code
 * BgSafTaxMapping} (протоколите по чл. 117, корекциите по чл. 79/79а, вносът по чл. 167а,
 * известията) нямат статичен код — той зависи от ставката, страната или основната доставка.
 * Секцията за фактури и протоколи вече го смята за същия документ от клетката на дневника ({@link
 * BgSaftDiaryCells}, {@link BgSaftInvoiceTaxRouter}). Дневникът го взима ОТТУК, тоест кодът в
 * GeneralLedgerEntries и в Sales/PurchaseInvoices е един и същ — както при BG_ZRP.
 *
 * <p><b>⚠️ КОЙ ДОКУМЕНТ — ПО СМЕТКАТА НА РЕДА, СТРОГО.</b> Протоколът е в двете секции (продажби и
 * покупки) с различни кодове, затова:
 *
 * <ol>
 *   <li>документ на статията, чиято сметка S.I.4 е сметката на реда (ДДС за продажби → продажбите,
 *       ДДС за покупки → покупките);
 *   <li>иначе — документ, в чиито редове (S.I.30) е сметката на реда (основата);
 *   <li>иначе — единственият код на всички документи на статията.
 * </ol>
 *
 * На всяка стъпка кодът трябва да е ЕДИН; два различни — нееднозначно, следващата стъпка. Без
 * документ или без еднозначен код дневникът дава ГРЕШКА — кодът не се измисля.
 *
 * <p><b>⚠️ РЕД БЕЗ ДАНЪК — САМО ПРОТОКОЛ И САМО S.I.4</b> (решение от 21.10.2026, T4 на пробния
 * файл). Протоколът няма {@code taxLineSet} на 4531/4532 — ставката е на статията — затова редовете
 * му стигаха до дневника като 000/000000, а секцията носеше 100226/100331/100211. {@link
 * #protocolCodeFor} дава на такъв ред кода на протокола (вид 09) на статията, чиято сметка S.I.4 е
 * ТОЧНО сметката на реда. Нищо друго: фактурата (S.I.4 е 411/401), основата и разходът (60909)
 * остават 000/000000, а протоколът, чийто код чака НАП, тук не стига — той е ГРЕШКА в секцията.
 * Нееднозначно е 000/000000 без ГРЕШКА: неприложимостта е законна в GLE за всичко освен 600 и 700
 * (Прил. № 3 т. IV.8.2, V_1.0.1, изм. З-ЦУ-30-359/27.02.2026; „Въпроси и отговори“ v2, 19.03.2026,
 * с. 53 и 55). Съвпадението със секцията е НАШЕ решение (от 15.10.2026), не изискване на НАП.
 */
public final class BgSaftDocumentCodes {

  /** Без документи — дневникът пада на ГРЕШКА за всеки данък без код. */
  public static final BgSaftDocumentCodes EMPTY = new BgSaftDocumentCodes();

  /** Вид документ „протокол“ (Nom_Invoice_Types) — само той дава код на ред без данък. */
  static final String PROTOCOL_TYPE = "09";

  private final Map<Long, List<Document>> byMove = new HashMap<>();

  /** Един подаден документ: видът, сметката S.I.4, сметките на редовете и кодовете му. */
  private static final class Document {
    private final String type;
    private final String head;
    private final Set<String> lineAccounts = new TreeSet<>();
    private final Set<String> codes = new TreeSet<>();

    private Document(String type, String head) {
      this.type = type;
      this.head = head;
    }
  }

  /** Записва подаден документ на статията {@code moveId}. Нулевите кодове (000000) не се броят. */
  void add(Long moveId, SaftInvoice invoice) {
    if (moveId == null || invoice == null || this == EMPTY) {
      return;
    }
    Document document = new Document(invoice.getInvoiceType(), invoice.getAccountId());
    for (SaftInvoiceLine line : invoice.getLines()) {
      document.lineAccounts.add(line.getAccountId());
      for (SaftTaxInformation information : line.getTaxInformation()) {
        String code = information.getTaxCode();
        if (code != null && !BgSaftTaxTableAssembler.NOT_APPLICABLE_CODE.equals(code)) {
          document.codes.add(code);
        }
      }
    }
    if (!document.codes.isEmpty()) {
      byMove.computeIfAbsent(moveId, k -> new ArrayList<>()).add(document);
    }
  }

  /** Добавя документите на друг набор — протоколите към фактурите. */
  void addAll(BgSaftDocumentCodes other) {
    if (this == EMPTY) {
      return;
    }
    other.byMove.forEach(
        (moveId, documents) ->
            byMove.computeIfAbsent(moveId, k -> new ArrayList<>()).addAll(documents));
  }

  /**
   * TaxCode за ред на статията {@code moveId} по сметка {@code accountNra} (AccountID по НАП);
   * {@code null}, когато статията няма документ или кодът не е еднозначен.
   */
  public String codeFor(Long moveId, String accountNra) {
    List<Document> documents = byMove.get(moveId);
    if (documents == null || documents.isEmpty()) {
      return null;
    }
    List<Predicate<Document>> steps =
        List.of(
            d -> accountNra != null && accountNra.equals(d.head),
            d -> accountNra != null && d.lineAccounts.contains(accountNra),
            d -> true);
    for (Predicate<Document> step : steps) {
      Set<String> codes = new TreeSet<>();
      documents.stream().filter(step).forEach(d -> codes.addAll(d.codes));
      if (codes.size() == 1) {
        return codes.iterator().next();
      }
    }
    return null;
  }

  /**
   * TaxCode за ред БЕЗ данък на статията {@code moveId} по сметка {@code accountNra}: кодът на
   * протокола (вид 09), чиято сметка S.I.4 е {@code accountNra}; {@code null} без такъв протокол
   * или с повече от един код.
   */
  public String protocolCodeFor(Long moveId, String accountNra) {
    List<Document> documents = byMove.get(moveId);
    if (documents == null || accountNra == null) {
      return null;
    }
    Set<String> codes = new TreeSet<>();
    documents.stream()
        .filter(d -> PROTOCOL_TYPE.equals(d.type) && accountNra.equals(d.head))
        .forEach(d -> codes.addAll(d.codes));
    return codes.size() == 1 ? codes.iterator().next() : null;
  }
}
