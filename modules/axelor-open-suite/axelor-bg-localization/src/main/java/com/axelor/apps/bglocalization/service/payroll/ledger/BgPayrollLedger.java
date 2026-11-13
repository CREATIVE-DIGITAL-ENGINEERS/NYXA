package com.axelor.apps.bglocalization.service.payroll.ledger;

import com.axelor.apps.bglocalization.db.BgPaySlip;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * РАЗЧЕТНО-ПЛАТЕЖНАТА ВЕДОМОСТ — целият период, разбит по вид правоотношение. Чиста функция върху
 * списък фишове; нула заявки, нула BIRT, нула Guice.
 *
 * <p><b>⚠️ ЛИСТЪТ Е ЕДИН, А ВЕДОМОСТИТЕ СА ПЕТ, И ТОВА Е ОБРАТНОТО НА ТОВА, КОЕТО ИЗГЛЕЖДА
 * ЕСТЕСТВЕНО.</b> Естественото е да се пита базата за „фишовете на трудовите договори за март“ и да
 * се печата отговорът. Тогава обаче ВЕДОМОСТТА НЕ ЗНАЕ КОГО НЕ Е ВИДЯЛА: лице, чийто вид осигурен е
 * празен, просто не се връща, а листът излиза пълен и подписуем. Затова тук се строи ЦЕЛИЯТ период
 * веднъж, разбиването се брои ({@link #census()}), и чак после изборът реже. Отговорът на „всеки ли
 * влезе някъде“ е събиране, а не доверие.
 *
 * <p><b>⚠️ И ЗАТОВА ДОКЛАДЪТ ЗА ГРАНИЦИТЕ Е ЧАСТ ОТ ОТЧЕТА, А НЕ ЖУРНАЛ.</b> Човекът, който
 * подписва ведомостта, отговаря за нея. Празна колона и липсващ ред изглеждат еднакво при „няма
 * такова лице“ и при „лицето не беше разпознато“; разликата се вижда само ако е НАПИСАНА, и то с
 * ЧИСЛО.
 *
 * <p><b>⚠️ ОТКАЗАНИТЕ ФИШОВЕ НЕ СЕ ПОДАВАТ ТУК.</b> Кои фишове влизат е въпрос на обхода ({@code
 * BgPayrollLedgerService}), а не на този клас: той събира каквото му дадат и не мълчи за нищо.
 */
public final class BgPayrollLedger {

  private final List<BgPayrollLedgerRow> rows;
  private final Map<BgPayrollLedgerVariant, Integer> census;
  private final int selfInsuredOutsideScope;

  private BgPayrollLedger(List<BgPayrollLedgerRow> rows, int selfInsuredOutsideScope) {
    this.rows = rows;
    this.selfInsuredOutsideScope = selfInsuredOutsideScope;
    this.census = new EnumMap<>(BgPayrollLedgerVariant.class);
    for (BgPayrollLedgerVariant variant : BgPayrollLedgerVariant.values()) {
      census.put(variant, 0);
    }
    for (BgPayrollLedgerRow row : rows) {
      census.merge(row.getVariant(), 1, Integer::sum);
    }
  }

  /**
   * ⚠️ РЕДЪТ НА ХАРТИЯТА СЕ РЕШАВА ТУК, В JAVA, И НЕ Е ПРЕДПОЧИТАНИЕ.
   *
   * <p>{@code ORDER BY} в Postgres подрежда по КОЛАЦИЯТА на базата, а тя е свойство на машината:
   * измерено, под {@code en_US.UTF-8} и под {@code C} едни и същи низове излизат в различен ред.
   * Ведомост, подредена в SQL, би излизала различно на две инсталации от един и същ билд — и
   * разликата се вижда само ако двата листа се сложат един до друг. {@link Collator} за български
   * дава ЕДИН ред, който не зависи от машината.
   *
   * <p>⚠️ И ВТОРИЯТ КЛЮЧ Е НОМЕРЪТ НА ФИША, А НЕ НИЩО. Едно лице може да има два фиша за един
   * период (коригиращ до редовния); без стабилен втори ключ двата реда си сменят местата между две
   * разпечатки на едни и същи данни.
   */
  private static final Comparator<BgPayrollLedgerRow> BY_NAME =
      Comparator.comparing(
              BgPayrollLedgerRow::getEmployeeNames, Collator.getInstance(new Locale("bg", "BG")))
          .thenComparing(BgPayrollLedgerRow::getReference);

  /** Ведомостта на подадените фишове. Празен списък дава празна ведомост, не отказ. */
  public static BgPayrollLedger of(List<BgPaySlip> slips) {
    return of(slips, 0);
  }

  /**
   * Същото, но със ЗНАНИЕТО колко самоосигуряващи се са останали извън обхвата.
   *
   * <p><b>⚠️ ЕДИНСТВЕНОТО ИЗРЯЗВАНЕ, КОЕТО ВЕДОМОСТТА НЕ МОЖЕ ДА ВИДИ САМА.</b> Филтърът по
   * дружество минава през {@code EmploymentContract.payCompany}, а самоосигуряващото се лице няма
   * договор — тоест неговите фишове изобщо не стигат дотук. Без това число разрезът
   * „Самоосигуряващи се“ показва НУЛА и нулата изглежда като отговор („такива лица нямаме“), докато
   * истинският отговор е „има ги, но не на този лист“. Броячът превръща мълчанието в изречение.
   */
  public static BgPayrollLedger of(List<BgPaySlip> slips, int selfInsuredOutsideScope) {
    List<BgPayrollLedgerRow> rows = new ArrayList<>();
    if (slips != null) {
      for (BgPaySlip slip : slips) {
        if (slip != null) {
          rows.add(BgPayrollLedgerRow.of(slip));
        }
      }
    }
    rows.sort(BY_NAME);
    return new BgPayrollLedger(rows, selfInsuredOutsideScope);
  }

  /** ВСИЧКИ редове на периода, преди какъвто и да било избор. */
  public List<BgPayrollLedgerRow> getRows() {
    return Collections.unmodifiableList(rows);
  }

  /** Редовете на избраната ведомост. */
  public List<BgPayrollLedgerRow> rows(BgPayrollLedgerSelection selection) {
    return rows.stream().filter(selection::accepts).collect(Collectors.toList());
  }

  /** Редът „общо“ на избраната ведомост — сборът на нейните колони и нищо друго. */
  public BgPayrollLedgerTotals totals(BgPayrollLedgerSelection selection) {
    return BgPayrollLedgerTotals.of(rows(selection));
  }

  /** Идентификаторите на фишовете в избраната ведомост, в реда на редовете. */
  public List<Long> slipIds(BgPayrollLedgerSelection selection) {
    return rows(selection).stream()
        .map(BgPayrollLedgerRow::getSlipId)
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.toList());
  }

  /** По колко реда има всеки разрез — включително нулите, защото нулата е отговор. */
  public Map<BgPayrollLedgerVariant, Integer> census() {
    return Collections.unmodifiableMap(census);
  }

  /**
   * ДОКЛАДЪТ ЗА ГРАНИЦИТЕ — какво съдържа периодът и какво на този лист няма да се види.
   *
   * <p>⚠️ ПЪЛНОТО ПРЕБРОЯВАНЕ СТОИ И НА ПЕТТЕ ЛИСТА, не само на онзи, който го обяснява. Ведомостта
   * на трудовите договори, отпечатана сама, не казва нищо за трите граждански договора, които не са
   * на нея — а точно тя е листът, който отива за подпис.
   */
  public String boundaryReport(BgPayrollLedgerSelection selection) {
    List<String> out = new ArrayList<>();
    List<BgPayrollLedgerRow> selected = rows(selection);

    out.add(
        "Период: "
            + rows.size()
            + " фиша общо, от които на този лист — "
            + selected.size()
            + " ("
            + selection.getLabel()
            + ").");

    StringBuilder breakdown = new StringBuilder("Разбиване по вид правоотношение: ");
    boolean first = true;
    for (BgPayrollLedgerVariant variant : BgPayrollLedgerVariant.values()) {
      if (!first) {
        breakdown.append(" · ");
      }
      first = false;
      breakdown.append(variant.getLabel()).append(" ").append(census.get(variant));
    }
    out.add(breakdown.toString());

    if (selfInsuredOutsideScope > 0) {
      out.add(
          "⚠️ "
              + selfInsuredOutsideScope
              + " фиша на самоосигуряващи се лица са ИЗВЪН този обхват и не се броят по-горе."
              + " Причината е устройствена: фишът няма свое дружество — то се чете през договора, —"
              + " а самоосигуряващото се лице няма договор. За да ги видите, оставете полето"
              + " „Дружество“ празно.");
    }

    int unclassified = census.get(BgPayrollLedgerVariant.UNCLASSIFIED);
    if (unclassified > 0) {
      out.add(
          "⚠️ "
              + unclassified
              + " фиша нямат попълнен вид осигурен. Те НЕ влизат в нито една от четирите ведомости"
              + " на образеца и се печатат само на „Без определен вид осигурен“. Поправката е на"
              + " договора (поле „Вид осигурен (НАП)“), не тук.");
    }

    List<BgPayrollLedgerRow> unreconciled =
        selected.stream()
            .filter(BgPayrollLedgerRow::hasReconciliationFindings)
            .collect(Collectors.toList());
    if (!unreconciled.isEmpty()) {
      out.add(
          "⚠️ "
              + unreconciled.size()
              + " реда не се сверяват с перата си — сумите им на този лист са заглавните суми на"
              + " фиша, а перата казват друго. Поименно:");
      for (BgPayrollLedgerRow row : unreconciled) {
        out.add(
            "   · "
                + row.getReference()
                + " ("
                + (row.getEmployeeNames().isEmpty() ? "без име" : row.getEmployeeNames())
                + "): "
                + row.getReconciliation().replace("\n", " | "));
      }
    }

    return String.join("\n", out);
  }

  /** Редовете, които не се сверяват със собствените си пера — празно значи „чисто“. */
  public List<BgPayrollLedgerRow> unreconciled() {
    return rows.stream()
        .filter(BgPayrollLedgerRow::hasReconciliationFindings)
        .collect(Collectors.toList());
  }
}
