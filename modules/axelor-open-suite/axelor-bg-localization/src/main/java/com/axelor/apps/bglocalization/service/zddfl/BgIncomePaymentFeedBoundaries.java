package com.axelor.apps.bglocalization.service.zddfl;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * КАКВО ЗАХРАНВАНЕТО НЕ Е НАПРАВИЛО И ЗАЩО — написано НА прогона, а не оставено в лога.
 *
 * <p><b>⚠️ ЗАЩО ЧИСЛА, А НЕ „ГОТОВО“.</b> Прогон, който е създал 3 реда от 40 фиша, изглежда
 * ЕДНАКВО успешен като прогон, създал 40 от 40 — и в двата случая екранът казва „готово“. Разликата
 * е 37 дохода, които няма да влязат в справката по чл. 73 ал. 1, а тя се подава веднъж годишно и
 * пропускът се вижда от НАП, не от нас. Затова всеки отказ се БРОИ по причина и всяка причина се
 * назовава с изречение.
 *
 * <p><b>⚠️ И ЗАЩО ОТКАЗИТЕ НЕ СА ГРЕШКИ.</b> Фиш без дата на изплащане, партньор без тип
 * идентификатор, договор без вид доход — това са попълними полета, не дефекти. Прогонът не спира
 * заради тях и не създава половин ред: той минава нататък и оставя списък, по който човек оправя
 * данните и пуска пак. Повторният прогон е безопасен по устройство (виж {@link
 * BgIncomePaymentFeedService}).
 */
public final class BgIncomePaymentFeedBoundaries {

  private BgIncomePaymentFeedBoundaries() {}

  /** ЧИСЛАТА, ИЗМЕРЕНИ ПРИ ПРОГОНА. Носач между услугата и текста, не част от домейна. */
  public static final class Facts {

    /** Данъчната година и (по избор) тримесечието на обхвата. */
    public int taxYear;

    /** {@code null} значи „цялата година“ — чл. 73 е годишна, чл. 55 е тримесечна. */
    public Integer quarter;

    /** Пробен ли беше прогонът: нищо не се записва, само се брои. */
    public boolean dryRun;

    /** Разгледани изходни документи, по източник. */
    public int payrollScanned;

    public int invoiceScanned;

    /** Създадени нови редове на регистъра. */
    public int created;

    /** Намерени и обновени свои редове (по референция). */
    public int updated;

    /** Приети кандидати, чието изчисление е ОТКАЗАНО от резолвера — виж {@link #refusalReasons}. */
    public int computeRefused;

    /** Отказани кандидати по причина: код → брой. */
    public final Map<String, Integer> refusals = new LinkedHashMap<>();

    /** Причините, назовани веднъж всяка: код → изречението на първия такъв отказ. */
    public final Map<String, String> refusalReasons = new LinkedHashMap<>();

    /** Обновени редове, на които код корекция е бил различен от „0“ и е ЗАПАЗЕН. */
    public int correctionCodeKept;

    /** Обновени редове, на които признакът по чл. 43 ал. 5 се е ПРОМЕНИЛ при преизвеждането. */
    public int selfInsuredChanged;

    /** Фишове по граждански договор в периода БЕЗ дата на изплащане — не се виждат от обхода. */
    public int payrollWithoutPaidDate;

    /** Ръчно въведени редове в обхвата — преброени, за да личи, че не са пипани. */
    public int manualUntouched;

    /** Редове на фактури БЕЗ вид доход — не пораждат запис и не се долепят към съседния код. */
    public int invoiceLinesWithoutIncomeType;

    /** Плащания по фактура с нулева или липсваща обща сума — делът им не се определя. */
    public int invoicesWithoutTotal;

    /** Записва един отказ и запомня изречението му. */
    public void refuse(BgIncomePaymentFeedOutcome outcome) {
      String code = outcome.getReasonCode();
      refusals.merge(code, 1, Integer::sum);
      refusalReasons.putIfAbsent(code, outcome.getReason());
    }

    /** Записва отказ на резолвера (аритметиката), който няма свой код. */
    public void refuseCompute(String reason) {
      computeRefused++;
      refusals.merge(COMPUTE_REFUSED, 1, Integer::sum);
      refusalReasons.putIfAbsent(COMPUTE_REFUSED, reason);
    }
  }

  /** Отказ на {@link BgIncomeTaxResolver} — аритметиката, не годността на кандидата. */
  public static final String COMPUTE_REFUSED = "COMPUTE_REFUSED";

  /** Текстът за колоната {@code resultSummary} на wizard-а. */
  public static String report(Facts facts) {
    StringBuilder out = new StringBuilder();

    line(
        out,
        facts.dryRun
            ? "⚠️ ПРОБЕН ПРОГОН — НИЩО НЕ Е ЗАПИСАНО. Числата долу казват какво БИ СТАНАЛО."
            : "✔ ПРОГОНЪТ Е ЗАПИСАН.");
    line(out, "");
    line(
        out,
        "ОБХВАТ: "
            + facts.taxYear
            + (facts.quarter == null
                ? " г., цялата година"
                : " г., " + roman(facts.quarter) + " тримесечие"));
    line(out, "");

    line(out, "ПРОЧЕТЕНИ ИЗХОДНИ ДОКУМЕНТИ:");
    line(out, "  • фишове по граждански договор (вид осигурен 14): " + facts.payrollScanned);
    line(out, "  • плащания по фактури към физически лица: " + facts.invoiceScanned);
    line(out, "");
    line(out, "РЕЗУЛТАТ:");
    line(out, "  • нови редове: " + facts.created);
    line(out, "  • обновени свои редове: " + facts.updated);
    line(out, "");

    if (facts.manualUntouched > 0) {
      line(
          out,
          "РЪЧНО ВЪВЕДЕНИ РЕДОВЕ В ОБХВАТА: "
              + facts.manualUntouched
              + " — НЕ са пипани и няма как да бъдат.");
      line(out, "  Захранването чете само редове с попълнена референция към изходен документ;");
      line(out, "  ръчният ред няма такава. Разграничението е на ДАННИТЕ, не на флаг.");
      line(out, "");
    }

    if (facts.correctionCodeKept > 0 || facts.selfInsuredChanged > 0) {
      line(out, "ПРИ ОБНОВЯВАНЕТО:");
      if (facts.correctionCodeKept > 0) {
        line(
            out,
            "  • код корекция е ЗАПАЗЕН на "
                + facts.correctionCodeKept
                + " реда — човек го е сложил на „1“ или „8“ и прогонът не го връща на „0“.");
      }
      if (facts.selfInsuredChanged > 0) {
        line(
            out,
            "  • признакът по чл. 43 ал. 5 се ПРОМЕНИ на "
                + facts.selfInsuredChanged
                + " реда — той е ИЗВЕДЕН от датата на писмената декларация на лицето, а не");
        line(
            out,
            "    ръчно поле: подадена, поправена или заличена декларация мени и вече записан ред.");
      }
      line(out, "");
    }

    if (facts.invoiceLinesWithoutIncomeType > 0) {
      line(
          out,
          "⚠️ "
              + facts.invoiceLinesWithoutIncomeType
              + " РЕДА НА ФАКТУРИ НЯМАТ ВИД ДОХОД ПО ЗДДФЛ и затова не породиха запис.");
      line(out, "   Редът НЕ се долепя към съседния код: една фактура може да носи и наем");
      line(out, "   (шаблон B, чл. 31), и услуга (шаблон A, чл. 29) — мълчаливо слепени, единият");
      line(out, "   получава чужд член, чужда разходна ставка и чужд код на образеца. Попълни");
      line(out, "   „Вид доход по ЗДДФЛ“ на реда на фактурата.");
      line(out, "");
    }

    if (facts.invoicesWithoutTotal > 0) {
      line(
          out,
          "⚠️ "
              + facts.invoicesWithoutTotal
              + " ПЛАЩАНИЯ СА ПО ФАКТУРА С НУЛЕВА ИЛИ ЛИПСВАЩА ОБЩА СУМА — делът на плащането");
      line(out, "   в нея не се определя, тоест не се знае каква част от дохода е придобита.");
      line(out, "");
    }

    if (facts.payrollWithoutPaidDate > 0) {
      line(
          out,
          "⚠️ "
              + facts.payrollWithoutPaidDate
              + " ФИША ПО ГРАЖДАНСКИ ДОГОВОР В ПЕРИОДА НЯМАТ ДАТА НА ИЗПЛАЩАНЕ и затова");
      line(out, "   изобщо не влязоха в обхода. Чл. 11 ал. 1 връзва придобиването с ДЕНЯ на");
      line(out, "   плащането; изведена от периода, датата би сложила доход в грешно тримесечие");
      line(out, "   и оттам в грешна декларация по чл. 55. Попълни „Дата на изплащане“ на фиша.");
      line(out, "");
    }

    if (facts.refusals.isEmpty()) {
      line(out, "Нито един кандидат не е отказан.");
    } else {
      line(out, "ОТКАЗАНИ КАНДИДАТИ — попълними данни, не дефекти:");
      for (Map.Entry<String, Integer> entry : facts.refusals.entrySet()) {
        line(out, "");
        line(out, "  [" + entry.getKey() + "] × " + entry.getValue());
        line(out, "  " + facts.refusalReasons.get(entry.getKey()));
      }
    }

    line(out, "");
    line(out, "═══ ГРАНИЦИ НА ТОВА ЗАХРАНВАНЕ — ОБЯВЕНИ, А НЕ ПРОПУСНАТИ ═══");
    line(out, "");
    line(out, "1. ДИВИДЕНТИТЕ (чл. 38 ал. 1, шаблон C) НЕ идват оттук и няма да дойдат по този");
    line(out, "   път. Указание т. 4 към справката по ал. 1 ги слага в годината, през която е");
    line(out, "   взето РЕШЕНИЕТО за разпределяне — не на изплащането. Тоест носителят им е");
    line(out, "   решението на общото събрание, а не плащане. Въвеждат се ръчно.");
    line(out, "");
    line(out, "2. ЧУЖДЕСТРАННОТО ЛИЦЕ (колона 2) И ДЪРЖАВАТА (колона 9) не се извеждат.");
    line(out, "   Partner няма признак, от който „чуждестранно лице“ по чл. 4 и чл. 5 ЗДДФЛ да се");
    line(out, "   прочете: адресът не е местно лице, а гражданството не е данъчна принадлежност.");
    line(out, "   Захранването не ги пипа — при обновяване остават каквито човек ги е оставил.");
    line(out, "");
    line(
        out, "3. КРЕДИТНИТЕ ИЗВЕСТИЯ (сторно на фактура към физическо лице) НЕ се четат. Редът им");
    line(out, "   би бил отрицателно бруто, а изплатен доход под нула не съществува. Корекция на");
    line(out, "   вече подаден доход се прави с код корекция „1“ или „8“ върху съществуващия ред,");
    line(out, "   ръчно — кой ред се коригира е решение на човек, не на прогон.");
    line(out, "");
    line(out, "4. ПРАГЪТ 7920 ЛВ. при 50 и над 50 на сто намалена работоспособност (чл. 43 ал. 2,");
    line(out, "   чл. 44 ал. 2, чл. 44а ал. 3) НЕ се прилага. Той иска облагаемия доход на лицето");
    line(out, "   ОТ ВСИЧКИ ИЗТОЧНИЦИ от началото на годината — число, което този платец няма.");
    line(out, "   Остава ръчна корекция на реда.");
    line(out, "");
    line(out, "5. ⚠️ ПЕРОТО ЗА ДАНЪК НА ФИША ПО ГРАЖДАНСКИ ДОГОВОР СЕ СМЯТА ПО ЧЛ. 42, НЕ ПО");
    line(out, "   ЧЛ. 43 — измерено, и това е ОТДЕЛЕН дефект, който този прогон НЕ поправя.");
    line(out, "   BgIncomeTaxService прилага чл. 42 върху всеки фиш без разклонение по вид");
    line(out, "   осигурен: основата му е „облагаеми начисления − лични вноски“ и НЕ приспада");
    line(out, "   нормативните разходи по чл. 29. За 2000,00 при 25 на сто фишът удържа 200,00,");
    line(out, "   а чл. 43 ал. 1 и ал. 3 искат 150,00. РЕГИСТЪРЪТ ТУК Е ВЕРЕН — той смята сам");
    line(out, "   през BgIncomeTaxResolver и не чете данъка на фиша, — но докато перото не се");
    line(out, "   оправи, УДЪРЖАНОТО и ДЕКЛАРИРАНОТО се разминават пред НАП.");
    line(out, "");
    line(out, "6. ⚠️ И ФИШЪТ ПО ГРАЖДАНСКИ ДОГОВОР ВЛИЗА И В СПРАВКАТА ПО ЧЛ. 73 АЛ. 6 — тоест");
    line(
        out, "   рискът от ДВОЙНО ДЕКЛАРИРАНЕ е реален и е измерен. BgArt73Para6Aggregator#codeOf");
    line(out, "   връща „101“ (трудово правоотношение) за всеки неморски фиш и няма нито един");
    line(out, "   филтър по вид осигурен. Гражданският договор е ИЗВЪНТРУДОВ и мястото му е тук,");
    line(out, "   в справката по ал. 1. Докато ал. 6 не се стесни, един и същ доход се подава на");
    line(out, "   двата образеца. Обявено, а не оправено мълчаливо — стеснението е свой етап.");

    return out.toString();
  }

  private static String roman(int quarter) {
    switch (quarter) {
      case 1:
        return "І";
      case 2:
        return "ІІ";
      case 3:
        return "ІІІ";
      case 4:
        return "ІV";
      default:
        return String.valueOf(quarter);
    }
  }

  private static void line(StringBuilder out, String text) {
    out.append(text).append('\n');
  }
}
