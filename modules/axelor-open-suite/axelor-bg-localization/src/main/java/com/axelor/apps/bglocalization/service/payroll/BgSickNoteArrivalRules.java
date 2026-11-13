package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * ПРАВИЛАТА НА ПРИСТИГНАЛИЯ БОЛНИЧЕН — чиста аритметика над дати. Без база, без Guice, без Axelor.
 *
 * <p><b>Какво решава този клас.</b> Дали пристигналият лист МОЖЕ да отрази себе си върху вече
 * затворен месец, и ако да — коя заявка за отпуск бива отменена. Всичко, което иска база (кой е
 * фишът, кои са заявките, какво пише речникът), живее в {@code BgSickNoteArrivalService}; тук стоят
 * само въпросите, на които се отговаря с дати и числа.
 *
 * <p><b>⚠️ ОТКАЗИТЕ СА ТВЪРДИ, А НЕ НАХОДКИ, И ТОВА Е РАЗЛИКАТА С {@code BgD1Validator}.</b> Там
 * находката стои върху готова декларация за десетки лица и не бива да я спира; тук всеки отказ е
 * ПРЕДИ да се е родило каквото и да било — нито копие, нито отменена заявка, нито преместен период.
 * Спрян рано, случаят не е струвал нищо; пуснат, той мести пари и декларация наведнъж.
 */
public final class BgSickNoteArrivalRules {

  private BgSickNoteArrivalRules() {}

  /**
   * ДАТАТА НА ПРЕДСТАВЯНЕ ПРИ ОСИГУРИТЕЛЯ — попълнена, не преди началото на листа, не в бъдещето.
   *
   * <p><b>⚠️ СРАВНЯВА СЕ С НАЧАЛОТО НА БОЛНИЧНИЯ, А НЕ С НАЧАЛОТО НА ПЕРИОДА.</b> Периодът е на
   * ФИША и е избираем; листът е документ и носи своето начало. Лист за 28.12–30.12, представен на
   * 02.01, е напълно нормален и сравнен с началото на ЯНУАРСКИЯ период би минал, а сравнен с
   * декемврийския — би паднал. Меродавно е кога е започнала неработоспособността.
   *
   * <p><b>⚠️ И ЗАЩО Е ОТКАЗ, А НЕ НАХОДКА.</b> От тази дата се попълва {@code bgExportMonth} —
   * месецът, в който удостоверението по Приложение № 9 отива към НОИ. Сгрешена назад, тя праща
   * пакета в месец, който вече е подаден; сгрешена напред — в месец, който още не съществува.
   * Числото не е диагностика, а вход на друг документ.
   *
   * @param received {@code BgSickLeave.bgEmployerReceivedDate}
   * @param sickFrom началото на болничния лист ({@code fromDate}), не началото на периода
   * @param today днешната дата — идва през шев, за да е измерима
   */
  public static String refuseReceivedDate(LocalDate received, LocalDate sickFrom, LocalDate today) {
    if (received == null) {
      return "Датата на представяне при осигурителя (т. 25) не е попълнена. От нея се определя"
          + " месецът, в който удостоверението по Приложение № 9 отива към НОИ, а при закъснял лист"
          + " той НЕ е месецът на самия болничен. Попълнете я на листа и повторете.";
    }
    if (sickFrom != null && received.isBefore(sickFrom)) {
      return "Листът е представен на "
          + day(received)
          + ", а неработоспособността започва на "
          + day(sickFrom)
          + " — документът не може да е предаден, преди да съществува. ⚠️ Сравнението е с НАЧАЛОТО"
          + " НА ЛИСТА, а не с началото на периода: лист за края на декември, представен през"
          + " януари, е нормален случай. Поправете датата на представяне.";
    }
    if (today != null && received.isAfter(today)) {
      return "Датата на представяне е "
          + day(received)
          + ", тоест В БЪДЕЩЕТО (днес е "
          + day(today)
          + "). От нея се попълва месецът за Приложение № 9; бъдеща дата насочва пакета към месец,"
          + " който още не е настъпил.";
    }
    return null;
  }

  /**
   * ЕДИН КАЛЕНДАРЕН МЕСЕЦ — обявена граница на първата версия, не пропуск.
   *
   * <p><b>Защо е граница.</b> Лист през граница засяга ДВА затворени фиша, тоест две отделни
   * корекции, два периода в ADJUSTING едновременно и две делти в текущия месец. Всяка от трите
   * двойки има свой ред на отказите и свой изход при провал по средата; направени наведнъж, те
   * оставят състояние, в което единият месец е коригиран, а другият — не.
   *
   * <p>⚠️ Самото РАЗДЕЛЯНЕ на дните по месеци вече съществува и е вярно ({@code
   * BgSickLeaveResolver#split} реже по периода на фиша, а първите два работни дни се броят от
   * началото на ЕПИЗОДА). Липсва оркестрацията на двете корекции, не аритметиката.
   */
  public static String refuseSpansTwoMonths(LocalDate from, LocalDate to) {
    if (from == null || to == null) {
      return "Болничният лист няма начална или крайна дата — без тях не се знае кой месец засяга.";
    }
    if (from.getYear() == to.getYear() && from.getMonthValue() == to.getMonthValue()) {
      return null;
    }
    return "Болничният лист "
        + day(from)
        + " – "
        + day(to)
        + " обхваща ПОВЕЧЕ ОТ ЕДИН календарен месец. ⚠️ ОБЯВЕНА ГРАНИЦА НА ТАЗИ ВЕРСИЯ, не дефект:"
        + " такъв лист засяга два затворени фиша, тоест две корекции, два периода под корекция"
        + " едновременно и две делти в текущия месец. Направени наведнъж, те оставят състояние, в"
        + " което единият месец е коригиран, а другият не. Разделянето на дните по месеци вече е"
        + " вярно — липсва оркестрацията на двете корекции. Дотогава отразете листа на ръка:"
        + " „Коригирай“ върху всеки от двата фиша поотделно.";
  }

  /** Един отрязък заявка за отпуск — само датите и името, колкото да се назове записът. */
  public static final class LeaveSpan {
    public final Long id;
    public final LocalDate from;
    public final LocalDate to;
    public final String label;

    public LeaveSpan(Long id, LocalDate from, LocalDate to, String label) {
      this.id = id;
      this.from = from;
      this.to = to;
      this.label = label;
    }

    @Override
    public String toString() {
      return (label == null ? "заявка" : label) + " " + day(from) + " – " + day(to);
    }
  }

  /**
   * Етикетът на {@code LeaveRequestRepository.STATUS_CANCELED} на екрана: опцията „Canceled“ на
   * {@code hrs.leave.request.status.select} в превода на HR модула. Свидетел, че двете не са се
   * разминали: {@code BgSickNoteArrivalRulesTest}.
   */
  public static final String LEAVE_CANCELED_LABEL = "Отменен";

  /** Едно вече съществуващо ретро-копие на оригинала — колкото да се назове в отказа. */
  public static final class RetroCopy {
    public final String reference;
    public final String status;
    public final boolean deletion;

    public RetroCopy(String reference, String status, boolean deletion) {
      this.reference = reference;
      this.status = status;
      this.deletion = deletion;
    }
  }

  /**
   * ВТОРО ОТРАЗЯВАНЕ ВЪРХУ СЪЩИЯ ОРИГИНАЛ — отказ, докато към него сочи неотказано ретро-копие.
   *
   * <p><b>⚠️ ЗАЩО ОТКАЗ, А НЕ ВТОРО КОПИЕ.</b> {@code retroCopyOf} тръгва винаги от ОРИГИНАЛА, а
   * {@code settleRetroDelta} смята разликата спрямо оригинала. Второ копие след потвърдено първо
   * или изпуска първата корекция от коригиращата Д1, или плаща нетната ѝ разлика втори път. Двете
   * причини, които стигат дотук, са едно и също: второ натискане на бутона след успех и корекция
   * („Коригирай“) на същия фиш, направена по друг повод.
   *
   * <p>⚠️ Черновата се назовава отделно: тя е започната и още може да се довърши или откаже.
   * Отказаното копие изобщо не стига дотук — читателят го изключва, защото отказът връща периода в
   * CLOSED и повторното отразяване е законният изход.
   *
   * @param existing неотказаните коригиращи и заличаващи копия на оригинала
   */
  public static String refuseExistingRetro(
      String originalReference, String periodName, List<RetroCopy> existing) {
    if (existing == null || existing.isEmpty()) {
      return null;
    }
    RetroCopy copy = existing.get(0);
    String original =
        "Оригиналният фиш "
            + name(originalReference)
            + " за „"
            + (periodName == null ? "(без име)" : periodName)
            + "“";
    String kind = copy.deletion ? "заличаващ" : "коригиращ";
    if (BgPaySlipResolver.DRAFT.equals(copy.status)) {
      return original
          + " вече има започната корекция: "
          + kind
          + " фиш "
          + name(copy.reference)
          + " в ЧЕРНОВА. Повторно отразяване не е разрешено. Довършете или откажете това копие —"
          + " ако е от предишно натискане на бутона, болничният вече е отразен в него.";
    }
    return original
        + " вече е "
        + (copy.deletion ? "заличен" : "коригиран")
        + " ("
        + kind
        + " фиш "
        + name(copy.reference)
        + ", „"
        + statusLabel(copy.status)
        + "“). Повторно отразяване не е разрешено: ново копие би тръгнало от ОРИГИНАЛА и разликата"
        + " би се сметнала спрямо него — тоест първата корекция или изпада от декларацията, или се"
        + " плаща втори път.";
  }

  /** Етикетите на {@code bg.payslip.status.select} — същите думи, които операторът вижда. */
  private static String statusLabel(String status) {
    if (BgPaySlipResolver.CONFIRMED.equals(status)) {
      return "Потвърден";
    }
    if (BgPaySlipResolver.PAID.equals(status)) {
      return "Изплатен";
    }
    return status == null ? "(без състояние)" : status;
  }

  /**
   * ЗАЯВКАТА СЕ ПОКРИВА ТОЧНО ОТ ЛИСТА, ИЛИ НИЩО НЕ СЕ ПИПА.
   *
   * <p><b>⚠️ БЕЗ АВТОМАТИЧНО ЦЕПЕНЕ, И ТОВА Е РЕШЕНИЕ.</b> Заявка 05.12–12.12 и лист 07.12–09.12
   * описват различни твърдения за петте дни, които листът не покрива: те са отсъствие, но по коя
   * причина — не се знае. Разцепена автоматично, заявката би родила остатък, който НИКОЙ не е
   * въвел, и този остатък пак ще карвира заплата. Цепенето е решение на човек и се прави на екрана
   * на отпуските, преди да се натисне бутонът.
   *
   * <p>⚠️ ТОЧНО значи по КАЛЕНДАРНИ граници, не по работни дни: заявка, която включва събота и
   * неделя вътре в листа, пак е същото отсъствие. Разминаване има само когато заявката излиза ИЗВЪН
   * листа.
   */
  public static String refuseInexactCover(
      LocalDate sickFrom, LocalDate sickTo, List<LeaveSpan> spans, String documentNumber) {
    if (spans == null || spans.isEmpty()) {
      return null;
    }
    List<String> partial = new ArrayList<>();
    for (LeaveSpan span : spans) {
      if (span == null || span.from == null || span.to == null) {
        continue;
      }
      if (span.from.isBefore(sickFrom) || span.to.isAfter(sickTo)) {
        partial.add(span.toString());
      }
    }
    if (partial.isEmpty()) {
      return null;
    }
    return "Заявката за отпуск излиза ИЗВЪН болничния лист и затова не може да бъде отменена цяла:"
        + " засегнати са "
        + String.join("; ", partial)
        + ", а листът "
        + name(documentNumber)
        + " покрива "
        + day(sickFrom)
        + " – "
        + day(sickTo)
        + ". ⚠️ БЕЗ АВТОМАТИЧНО ЦЕПЕНЕ: дните, които листът не покрива, са отсъствие по НЕИЗВЕСТНА"
        + " причина, а разцепена автоматично, заявката би родила остатък, който никой не е въвел — и"
        + " той пак ще сваля заплата. Разделете заявката на екрана на отпуските така, че една от"
        + " частите да съвпада с листа, и повторете.";
  }

  /**
   * ПЛАНЪТ, показан на оператора ПРЕДИ да се пипне каквото и да било.
   *
   * <p>⚠️ Изброява и трите последици поименно — коя заявка се отменя, кой месец се коригира и че
   * разликата отива в ТЕКУЩИЯ фиш. Съобщение „сигурни ли сте“ без тях е въпрос без съдържание.
   */
  public static String plan(
      String documentNumber,
      LocalDate sickFrom,
      LocalDate sickTo,
      String periodName,
      List<LeaveSpan> toCancel,
      LocalDate exportMonth) {
    return plan(
        documentNumber,
        sickFrom,
        sickTo,
        periodName,
        toCancel,
        exportMonth,
        java.util.Collections.<String>emptyList());
  }

  /**
   * Както горното, и КАКВО ЩЕ СЕ ПОПЪЛНИ ОТ ТЕКУЩИТЕ ДАННИ (3А) — режимът на осигуряване и/или
   * дните на карва, когато оригиналът е отпреди замразяването им.
   *
   * <p><b>⚠️ СТОЙНОСТИТЕ СЕ ПОКАЗВАТ ПРЕДИ OK, И OK Е СЪГЛАСИЕТО.</b> Копие на заварен фиш без
   * режим не може да се пресметне, а копие от отразяването няма къде да чака бутона „Попълни“ — то
   * се ражда и смята в една транзакция. Затова попълването е част от ИЗРИЧНОТО действие: човекът
   * вижда вида осигурен, ТЕЛК и останалите, които ще бъдат записани, и следата в одита казва „при
   * отразяване на болничен лист № …“.
   *
   * @param fill редовете „поле: стойност“, които ще се попълнят, или празен списък
   */
  public static String plan(
      String documentNumber,
      LocalDate sickFrom,
      LocalDate sickTo,
      String periodName,
      List<LeaveSpan> toCancel,
      LocalDate exportMonth,
      List<String> fill) {

    StringBuilder out = new StringBuilder();
    out.append("Болничен лист ")
        .append(name(documentNumber))
        .append(" за ")
        .append(day(sickFrom))
        .append(" – ")
        .append(day(sickTo))
        .append(" ще бъде отразен върху ЗАТВОРЕНИЯ период „")
        .append(periodName == null ? "(без име)" : periodName)
        .append("“.\n\n");

    out.append("Ще се случи следното:\n");
    out.append(
        "• отваря се КОРИГИРАЩО копие на фиша за онзи месец; оригиналът остава непокътнат;\n");
    out.append(
        "• дните минават от „непредставен болничен“ към осигурител/ДОО по чл. 40 ал. 5 КСО и се"
            + " начислява перото по чл. 40 ал. 5;\n");
    // ⚠️ ОБЕЩАНИЕТО Е ВЯРНО: копието се ПРЕСМЯТА (calculate), тоест вноските, перо 516 по ЗЗО
    // чл. 40 ал. 1 т. 5 и данъкът са на него — по режима и параметрите на ОНЗИ месец.
    out.append(
        "• пресмятат се вноските (5xx) върху новия осигурителен доход, здравната вноска по ЗЗО чл."
            + " 40 ал. 1 т. 5 за дните за сметка на ДОО и данъкът — по режима и параметрите на онзи"
            + " месец;\n");
    // ⚠️ И Т. 9 СЕ ОБЕЩАВА: разбивът пише редовете по месеци върху листа, а оттам ги чете
    // Приложение № 9.
    out.append(
        "• в т. 9 на листа се записват дните по месеци — за сметка на осигурителя и на ДОО — за"
            + " Приложение № 9;\n");
    if (toCancel == null || toCancel.isEmpty()) {
      out.append(
          "• ⚠️ НЯМА заявка за отпуск за тези дни — ако дните са били свалени РЪЧНО от"
              + " отработените на фиша, проверете числото след корекцията;\n");
    } else {
      for (LeaveSpan span : toCancel) {
        out.append("• ОТМЕНЯ СЕ (статус „")
            .append(LEAVE_CANCELED_LABEL)
            .append("“, не се трие) ")
            .append(span.toString())
            .append(";\n");
      }
    }
    if (fill != null && !fill.isEmpty()) {
      String header =
          "• ⚠️ оригиналът е отпреди замразяването: в копието се ПОПЪЛВАТ от текущите данни на"
              + " договора и служителя (OK = съгласие; следата остава в одита като „при"
              + " отразяване на болничен лист ";
      out.append(header).append(name(documentNumber)).append("“):\n");
      for (String line : fill) {
        out.append("   – ").append(line).append(";\n");
      }
    }
    if (exportMonth != null) {
      out.append("• месецът за Приложение № 9 се попълва с ")
          .append(month(exportMonth))
          .append(";\n");
    }
    out.append(
        "\n⚠️ Разликата в нетото НЕ се плаща за онзи месец — тя влиза в ТЕКУЩИЯ отворен фиш като"
            + " отделно перо, след като потвърдите коригиращото копие. Копието се отваря за преглед"
            + " и НЕ се потвърждава само.");
    return out.toString();
  }

  /**
   * ОТКАЗАНА ПРИСЪДА НА РАЗБИВА — отказ на ЦЯЛОТО действие, никога тихо копие без промяна.
   *
   * <p><b>⚠️ ЗАЩО ВСИЧКО, А НЕ ПОЛОВИНАТА.</b> Обезщетение минава през контрол на правото. Отказан
   * разбив (например стажът не е деклариран, тоест не се знае дали осигурителят дължи първите дни)
   * значи, че копието не носи нищо от листа: дните не са преместени, перото не е начислено. Пуснато
   * така, действието отменя заявката, ражда празно копие и операторът вижда „успех“.
   *
   * <p>{@code NO_EMPLOYER_DAYS} НЕ е отказ — то е законно „няма перо“ (например продължение, изцяло
   * за сметка на ДОО). Празен списък е отказ: разбивът не е върнал нито една присъда.
   *
   * @param hint насока с думите от екрана, или {@code null}
   */
  public static String refuseOutcomes(
      String documentNumber, List<BgSickLeaveOutcome> outcomes, String hint) {
    String tail =
        (hint == null ? "" : " " + hint)
            + " Нищо не е отменено и копие не е създадено: поправете посоченото и натиснете бутона"
            + " отново.";
    if (outcomes == null || outcomes.isEmpty()) {
      return "Болничен лист "
          + name(documentNumber)
          + " НЕ е отразен: разбивът по чл. 40 ал. 5 КСО не върна нито една присъда."
          + tail;
    }
    List<String> reasons = new ArrayList<>();
    for (BgSickLeaveOutcome outcome : outcomes) {
      if (outcome == null
          || outcome.status == BgSickLeaveOutcome.Status.COMPUTED
          || outcome.status == BgSickLeaveOutcome.Status.NO_EMPLOYER_DAYS) {
        continue;
      }
      String reason = outcome.detail == null ? outcome.status.name() : outcome.detail;
      if (!reasons.contains(reason)) {
        reasons.add(reason);
      }
    }
    if (reasons.isEmpty()) {
      return null;
    }
    return "Болничен лист "
        + name(documentNumber)
        + " НЕ е отразен — разбивът по чл. 40 ал. 5 КСО е отказан: "
        + String.join(" · ", reasons)
        + tail;
  }

  static String day(LocalDate date) {
    return date == null
        ? "(без дата)"
        : String.format("%02d.%02d.%d", date.getDayOfMonth(), date.getMonthValue(), date.getYear());
  }

  static String month(LocalDate date) {
    return date == null
        ? "(без месец)"
        : String.format("%02d.%d", date.getMonthValue(), date.getYear());
  }

  private static String name(String documentNumber) {
    return documentNumber == null || documentNumber.trim().isEmpty()
        ? "без номер"
        : "№ " + documentNumber.trim();
  }
}
