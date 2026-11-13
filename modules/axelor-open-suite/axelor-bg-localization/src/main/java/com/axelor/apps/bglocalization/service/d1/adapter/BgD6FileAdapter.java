package com.axelor.apps.bglocalization.service.d1.adapter;

import com.axelor.apps.bglocalization.db.BgD6Declaration;
import com.axelor.apps.bglocalization.service.d1.BgD6Blocks;
import com.axelor.apps.bglocalization.service.d1.BgD6CompensationLedger;
import com.axelor.apps.bglocalization.service.d1.BgD6Emitter;
import com.axelor.apps.bglocalization.service.d1.BgD6Point;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ЕДНА ДЕКЛАРАЦИЯ ОБР. 6 → ЕДИН РЕД от файла на Д6 — 59 полета.
 *
 * <p><b>ПОДРЕДБАТА (сверена с подадени в НАП файлове на Microinvest, 09.2026 и 01.2025):</b>
 *
 * <ul>
 *   <li><b>1–6 · заглавие</b> — код на задълженото лице, наименование, член на осигурителна каса,
 *       e-mail, телефон, GSM. Текстовите са в кавички, празните — {@code ""};
 *   <li><b>7–58 · ЧЕТИРИ БЛОКА по 13 полета</b> — месец, година, дата, флаг за промяна, вид плащане
 *       и осемте суми (ДОО с ТЗПБ, УчПФ, УПФ, ППФ, ЗО, ГВРС, данък, авансов данък). В блоковете
 *       НИЩО не е в кавички, включително датата;
 *   <li><b>59 · източник</b> — същият ЕИК в кавички като поле 53 на Д1.
 * </ul>
 *
 * <p><b>⚠️ БЛОКЪТ Е ВИД ПЛАЩАНЕ, НЕ ПОДРЕДБА НА СУМИ.</b> Блок 1 носи ВНОСКИТЕ с вид 5 или 6 (ръчен
 * избор — виж {@link BgD6Emitter#paymentKindRefusal}). Блок 2 носи ДАНЪКА с вид 8 и се пише сам,
 * когато има данък; без данък е празен. Блокове 3 и 4 са изцяло празни, включително месецът и
 * датата. Единственото изключение е вид 7 (гъби и плодове): по указание т. 8 „в“ вноските и данъкът
 * по чл. 41а са ЕДИН вид плащане, тоест данъкът остава в блок 1.
 *
 * <p><b>⚠️ ПРАЗНАТА СУМА Е НИЩО МЕЖДУ ЗАПЕТАИТЕ</b> — не „0“ и не „0.00“, за разлика от Д1. Така ги
 * носят приетите файлове.
 *
 * <p><b>⚠️ ПРИ ВИД 6 МЕСЕЦЪТ НА БЛОК 2 Е МЕСЕЦЪТ НА ИЗПЛАЩАНЕ</b> (т. 18), а не периодът: данъкът е
 * удържан, когато е изплатено. Образецът за 01.2025, изплатен на 28.02.2025, носи 01 в блок 1 и 02
 * в блок 2. При вид 5 двата блока са с месеца на периода.
 *
 * <p><b>⚠️ ДАТАТА Е {@code дд/мм/гггг}, НЕ ISO.</b> {@code LocalDate.toString()} дава „2026-05-31“
 * — низ, който изглежда като дата и НЕ е тази дата.
 */
@Singleton
public class BgD6FileAdapter {

  /** Полетата на реда: 6 заглавни + 4 блока × 13 + източник. */
  public static final int FIELD_COUNT = 59;

  /** Броят блокове на реда — винаги четири, и празните излизат. */
  public static final int BLOCKS = 4;

  /** Полетата на един блок. */
  public static final int BLOCK_WIDTH = 13;

  /** Форматът на т. 18 — дословно „(дд, мм, гггг)“ от указанието. */
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  private final BgD1FileAdapter shared = new BgD1FileAdapter();

  /**
   * Редът на една декларация — 59 полета.
   *
   * @param sourceFlag поле 59 — същият ЕИК като поле 53 на Д1
   * @throws IllegalStateException когато видът плащане не е избран — виж {@link
   *     BgD6Emitter#paymentKindRefusal}. ⚠️ Отказ, а не „0“ във файла: празният вид се четеше като
   *     дефект едва от валидатора, след като файлът вече е готов.
   */
  public String toLine(BgD6Declaration d, String sourceFlag) {
    return toLine(d, sourceFlag, BgD6CompensationLedger.none());
  }

  /**
   * Редът с обезщетенията по вид 27 на месеца — те дават свои блокове ({@link BgD6Blocks}).
   *
   * @param ledger частите и данъкът им за месеца ({@code BgD6GenerationService#ledgerFor})
   */
  public String toLine(BgD6Declaration d, String sourceFlag, BgD6CompensationLedger ledger) {
    if (d == null) {
      return "";
    }
    String refusal = BgD6Emitter.paymentKindRefusal(d, ledger);
    if (refusal != null) {
      throw new IllegalStateException(refusal);
    }
    List<String> f = new ArrayList<>(FIELD_COUNT);

    f.add(shared.chr(d.getT1ObligedPersonCode())); //   1 · код на задълженото лице
    f.add(shared.chr(d.getT2Name())); //                2 · наименование
    f.add(shared.num(d.getT3InsuranceFundMember())); // 3 · член на осигурителна каса
    f.add(shared.chr(d.getT4Email())); //               4 · e-mail
    f.add(shared.chr(d.getT5Phone())); //               5 · телефон
    f.add(shared.chr(d.getT6Gsm())); //                 6 · GSM

    String flag = isCorrection(d) ? "1" : "0";
    List<List<String>> blocks = new ArrayList<>(BLOCKS);

    // 7–58 · блоковете — заплатите, обезщетенията по вид 27, данъкът (BgD6Blocks)
    for (BgD6Blocks.Block b : BgD6Blocks.of(d, ledger)) {
      blocks.add(
          block(
              b.getMonth(),
              b.getDate(),
              flag,
              b.getKind(),
              b.sum(BgD6Point.T10_DOO),
              b.sum(BgD6Point.T11_TEACHER_PF),
              b.sum(BgD6Point.T12_UPF),
              b.sum(BgD6Point.T13_PPF),
              b.sum(BgD6Point.T14_HEALTH),
              b.sum(BgD6Point.T15_GVRS),
              b.getTax(),
              b.getAdvanceTax()));
    }

    // 33–58 · останалите блокове — изцяло празни
    while (blocks.size() < BLOCKS) {
      blocks.add(Collections.nCopies(BLOCK_WIDTH, ""));
    }
    for (List<String> block : blocks) {
      f.addAll(block);
    }

    f.add(shared.chr(sourceFlag)); //                   59 · източник

    return String.join(String.valueOf(BgD1FileAdapter.SEPARATOR), f);
  }

  /**
   * Един блок — 13 полета, нищо в кавички.
   *
   * <p>⚠️ Месецът е с ФИКСИРАНА ширина 2 („09“), за разлика от поле 1 на Д1 („9“).
   */
  private static List<String> block(
      LocalDate month,
      LocalDate date,
      String flag,
      String kind,
      BigDecimal doo,
      BigDecimal teacherPf,
      BigDecimal upf,
      BigDecimal ppf,
      BigDecimal health,
      BigDecimal gvrs,
      BigDecimal tax,
      BigDecimal advanceTax) {
    List<String> b = new ArrayList<>(BLOCK_WIDTH);
    b.add(month == null ? "" : String.format("%02d", month.getMonthValue())); // месец
    b.add(month == null ? "" : String.valueOf(month.getYear())); //              година
    b.add(date == null ? "" : DATE.format(date)); //                              дата, БЕЗ кавички
    b.add(flag); //                                                               флаг за промяна
    b.add(kind == null ? "" : kind); //                                           вид плащане
    b.add(sum(doo)); //                                                           ДОО (ВКЛ. ТЗПБ)
    b.add(sum(teacherPf)); //                                                     УчПФ
    b.add(sum(upf)); //                                                           ДЗПО-УПФ
    b.add(sum(ppf)); //                                                           ДЗПО-ППФ
    b.add(sum(health)); //                                                        здравно
    b.add(sum(gvrs)); //                                                          ГВРС
    b.add(sum(tax)); //                                                           данък
    b.add(sum(advanceTax)); //                                                    авансов данък
    return b;
  }

  /** „К“ на ниво декларация. Виж домейна: корекция САМО при НАМАЛЯВАНЕ (чл. 9 ал. 7). */
  boolean isCorrection(BgD6Declaration d) {
    String code = d.getT71CorrectionCode();
    return code != null && !code.trim().isEmpty();
  }

  /** Сума в блок: празната и нулевата излизат като НИЩО между запетаите. */
  static String sum(BigDecimal value) {
    if (!isPositive(value)) {
      return "";
    }
    return value.abs().setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private static boolean isPositive(BigDecimal value) {
    return value != null && value.signum() != 0;
  }
}
