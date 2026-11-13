package com.axelor.apps.bglocalization.service.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * ПРАВИЛАТА НА ПЛАТЕЖНИЯ ПРОБЕГ — чисти функции, нула Beans, нула база.
 *
 * <p><b>⚠️ ВСЕКИ ОТКАЗ Е ПОИМЕНЕН, И ТОВА НЕ Е ЛЮБЕЗНОСТ.</b> Файлът, който тръгва към банката, е
 * последната стъпка преди чужди пари. Изпуснато лице не се открива при преглед — открива се, когато
 * човекът не получи заплатата си и се обади. Затова нито едно правило тук не връща число: всяко
 * връща ИЗРЕЧЕНИЕ СЪС ЗАСЕГНАТИТЕ ИМЕНА, или {@code null}, когато няма какво да се откаже.
 *
 * <p><b>⚠️ И ЗАТОВА ОТКАЗЪТ Е ЦЕЛИЯТ ФАЙЛ, А НЕ РЕДЪТ.</b> Пропуснатият ред е тихата повреда:
 * сборът пак се сверява сам със себе си, банката приема файла, ведомостта изглежда изпълнена.
 * Отказан пробег е шумен, поправя се за две минути и не оставя никого неплатен.
 *
 * <p><b>⚠️ НУЛЕВОТО НЕТО НЕ Е ЛИПСА И НЕ ИСКА СМЕТКА.</b> Цял месец неплатен отпуск дава нула — виж
 * {@code BgPaySlip.bgWorkedDays} за същия довод. Такова лице се ИЗБРОЯВА в доклада, но не поражда
 * ред (SEPA не носи нулеви преводи) и не се иска да има банкова сметка. Обратното би отказвало цели
 * ведомости заради човек, на когото и без това нищо не се дължи.
 */
public final class BgPayrollSepaRules {

  /** Форматът, който този модул генерира — редът е seed-нат от upstream (importId 6). */
  public static final String FILE_FORMAT_SELECT = "pain.001.001.03.sct";

  /** Валутата след 2026 г. Файл в друга валута НЕ се генерира — виж {@link #refuseCurrency}. */
  public static final String CURRENCY_ISO = "EUR";

  /** Схемата, срещу която се сверява готовият файл. Пътят е в jar-а на axelor-bank-payment. */
  public static final String XSD_RESOURCE = "/xsd/sepa/pain.001.001.03.xsd";

  private static final DateTimeFormatter PERIOD_LABEL = DateTimeFormatter.ofPattern("MM.yyyy");

  private BgPayrollSepaRules() {}

  /**
   * ОСНОВАНИЕТО, КОЕТО ЛИЦЕТО ВИЖДА В ИЗВЛЕЧЕНИЕТО СИ — {@code <RmtInf><Ustrd>}.
   *
   * <p>⚠️ МЕСЕЦЪТ СЕ ИЗВЕЖДА ОТ ДАТАТА, А НЕ СЕ ЧЕТЕ ОТ {@code Period.name}. Името на периода го
   * пише операторът и е каквото реши („Септември“, „09/2026“, „м.9“); основанието в банково
   * извлечение се чете от лицето и от съдебен изпълнител при запор и трябва да значи едно и също
   * нещо всеки месец.
   */
  public static String remittance(LocalDate periodStart) {
    if (periodStart == null) {
      return "Работна заплата";
    }
    return "Работна заплата " + periodStart.format(PERIOD_LABEL);
  }

  /**
   * ДВЕТЕ НАСТРОЙКИ НА ДРУЖЕСТВОТО, БЕЗ КОИТО ФАЙЛ НЕ СЕ РАЖДА.
   *
   * <p>⚠️ ПИТА СЕ ТУК, А НЕ СЕ ОСТАВЯ НА UPSTREAM. Без начин на плащане {@code
   * BankOrderFileService#getFolderPath} гърми с „no folder path“, а без сметка на платеца {@code
   * checkBankDetails} гърми с „bank details missing“ — и двете съобщения идват от чужд модул,
   * говорят за банкови нареждания изобщо и не казват нито кое дружество, нито кой екран. Тук полето
   * се назовава заедно с пътя до него.
   */
  public static String refuseMissingConfig(
      String companyName, boolean hasSenderBankDetails, boolean hasPaymentMode) {
    List<String> missing = new ArrayList<>();
    if (!hasSenderBankDetails) {
      missing.add(
          "• заплатната сметка на дружеството (Счетоводство › Настройки › Заплати (BG) › "
              + "„Банкова сметка за заплати · платец“, поле AccountConfig.bgPayrollBankDetails)");
    }
    if (!hasPaymentMode) {
      missing.add(
          "• начинът на плащане (Счетоводство › Настройки › Заплати (BG) › "
              + "„Начин на плащане · заплати“, поле AccountConfig.bgPayrollPaymentMode) — той носи "
              + "формата „"
              + FILE_FORMAT_SELECT
              + "“ и папката за експорт");
    }
    if (missing.isEmpty()) {
      return null;
    }
    return "SEPA файл не се генерира за „"
        + name(companyName)
        + "“: липсва настройка.\n"
        + String.join("\n", missing);
  }

  /**
   * ФОРМАТЪТ НА НАЧИНА НА ПЛАЩАНЕ — сверява се ПРЕДИ да се строи каквото и да е.
   *
   * <p>⚠️ Без тази проверка грешно избран начин на плащане (например CFONB320) стига чак до {@code
   * BankOrderServiceImpl#generateFile}, който строи ДРУГ файл и го записва мълчаливо — тоест в
   * банката отива формат, който тя не чака, а екранът е зелен.
   */
  public static String refuseFileFormat(String paymentModeName, String fileFormatSelect) {
    if (FILE_FORMAT_SELECT.equals(fileFormatSelect)) {
      return null;
    }
    return "Начинът на плащане „"
        + name(paymentModeName)
        + "“ не сочи SEPA формата: очаква се „"
        + FILE_FORMAT_SELECT
        + "“, а е "
        + (fileFormatSelect == null || fileFormatSelect.trim().isEmpty()
            ? "празно (BankOrderFileFormat не е избран)"
            : "„" + fileFormatSelect + "“")
        + ". Редът за SEPA превод е seed-нат от axelor-bank-payment "
        + "(BankOrderFileFormat, importId 6).";
  }

  /**
   * ВАЛУТАТА — EUR, и несъответствието е ОТКАЗ, а не превалутиране.
   *
   * <p>⚠️ {@code InstdAmt Ccy} се пълни от валутата на нареждането, а нетото на фиша е сметнато в
   * друга. Пуснат така, файлът щеше да поиска от банката СЪЩОТО ЧИСЛО в друга валута — не грешка на
   * формата, а грешна сума, която XSD-то приема.
   */
  public static String refuseCurrency(String companyName, String currencyIso) {
    if (CURRENCY_ISO.equals(currencyIso)) {
      return null;
    }
    return "SEPA файл се генерира само в "
        + CURRENCY_ISO
        + ". Валутата на „"
        + name(companyName)
        + "“ е "
        + (currencyIso == null || currencyIso.trim().isEmpty()
            ? "непопълнена (Company.currency)"
            : currencyIso)
        + ", а сумите на фишовете са сметнати в нея — превалутиране тук би поискало от банката "
        + "същото число в друга валута.";
  }

  /**
   * ПОИМЕННИЯТ ОТКАЗ ЗА ЛИПСВАЩА ПЛАТЕЖНА СМЕТКА — сърцевината на цялото упражнение.
   *
   * <p>⚠️ ПИТАТ СЕ САМО ОНЕЗИ, НА КОИТО СЕ ДЪЛЖИ НЕЩО. Лице с нулево нето няма ред във файла и
   * затова няма нужда от сметка; изисквано и от него, правилото би отказвало цели ведомости заради
   * човек в неплатен отпуск.
   */
  public static String refuseMissingIban(List<BgPayrollSepaCandidate> candidates) {
    List<BgPayrollSepaCandidate> broken = new ArrayList<>();
    for (BgPayrollSepaCandidate candidate : safe(candidates)) {
      if (candidate.hasNet() && !candidate.hasUsableAccount()) {
        broken.add(candidate);
      }
    }
    if (broken.isEmpty()) {
      return null;
    }
    StringBuilder text = new StringBuilder();
    text.append("SEPA файлът се ОТКАЗВА: ")
        .append(broken.size())
        .append(broken.size() == 1 ? " лице няма" : " лица нямат")
        .append(" годна платежна сметка. Файл без тях щеше да остави точно толкова души без ")
        .append("заплата, без нито един червен екран.\n");
    int index = 0;
    for (BgPayrollSepaCandidate candidate : broken) {
      text.append("\n")
          .append(++index)
          .append(". ")
          .append(candidate.getPersonName())
          .append(" (фиш ")
          .append(candidate.getSlipReference())
          .append(", нето ")
          .append(candidate.getNet().toPlainString())
          .append(") — ")
          .append(candidate.getIbanProblem());
    }
    return text.toString();
  }

  /**
   * ОТРИЦАТЕЛНО НЕТО — отказ, защото кредитен превод с отрицателна сума не съществува.
   *
   * <p>⚠️ И ТОВА НЕ Е ДЕФЕКТ НА ФИША. Надвзет аванс или запор над брутото дава законно отрицателно
   * нето; то значи, че лицето ДЪЛЖИ на работодателя. Какво се прави с този дълг е решение на човек
   * — прихващане следващия месец или вноска, — а не мълчаливо изпускане от файла.
   */
  public static String refuseNegativeNet(List<BgPayrollSepaCandidate> candidates) {
    List<BgPayrollSepaCandidate> negative = new ArrayList<>();
    for (BgPayrollSepaCandidate candidate : safe(candidates)) {
      if (candidate.hasNegativeNet()) {
        negative.add(candidate);
      }
    }
    if (negative.isEmpty()) {
      return null;
    }
    StringBuilder text = new StringBuilder();
    text.append("SEPA файлът се ОТКАЗВА: ")
        .append(negative.size())
        .append(negative.size() == 1 ? " фиш е с" : " фиша са с")
        .append(" ОТРИЦАТЕЛНО нето. Кредитен превод с отрицателна сума няма, а изпуснат ред би ")
        .append("скрил дълг на лицето към работодателя.\n");
    int index = 0;
    for (BgPayrollSepaCandidate candidate : negative) {
      text.append("\n")
          .append(++index)
          .append(". ")
          .append(candidate.getPersonName())
          .append(" (фиш ")
          .append(candidate.getSlipReference())
          .append(") — нето ")
          .append(candidate.getNet().toPlainString());
    }
    return text.toString();
  }

  /** Редовете, които влизат във файла: положително нето и годна сметка. */
  public static List<BgPayrollSepaCandidate> payableLines(List<BgPayrollSepaCandidate> candidates) {
    List<BgPayrollSepaCandidate> lines = new ArrayList<>();
    for (BgPayrollSepaCandidate candidate : safe(candidates)) {
      if (candidate.hasNet() && candidate.hasUsableAccount()) {
        lines.add(candidate);
      }
    }
    return lines;
  }

  /**
   * ЛИЦАТА С НУЛА — ИЗБРОЯВАТ СЕ ПОИМЕННО В ДОКЛАДА, НО НЕ ПОРАЖДАТ РЕД.
   *
   * <p><b>⚠️ И ТОЧНО ЗАТОВА СЕ ИЗБРОЯВАТ, А НЕ СЕ ПРЕМЪЛЧАВАТ.</b> Генерираният getter за {@code
   * BigDecimal} връща ZERO за непопълнена колона (виж {@code BgDefaultedGetterTest}), тоест „нетото
   * е нула“ и „нетото никога не е било сметнато“ пристигат тук като ЕДНО И СЪЩО число. Първото е
   * законен случай (цял месец неплатен отпуск), второто е дефект — потвърден фиш, чиито суми не са
   * преизчислени. Различими по стойност те не са, но ИМЕНАТА им излизат в доклада и човекът, който
   * познава ведомостта си, вижда името, което не му се връзва.
   */
  public static List<BgPayrollSepaCandidate> zeroNetLines(List<BgPayrollSepaCandidate> candidates) {
    List<BgPayrollSepaCandidate> zeros = new ArrayList<>();
    for (BgPayrollSepaCandidate candidate : safe(candidates)) {
      if (!candidate.hasNet() && !candidate.hasNegativeNet()) {
        zeros.add(candidate);
      }
    }
    return zeros;
  }

  /**
   * СБОРЪТ НА ФАЙЛА — {@code <CtrlSum>}.
   *
   * <p>⚠️ ПРОСТ СБОР НА ВЕЧЕ ЗАКРЪГЛЕНИ НЕТА, без ново закръгляне. Всяко нето е сбор на пера,
   * закръглени до евроцент при записа им (виж коментара за сумите в {@code BgPaySlip}), тоест
   * „сборът на редовете дава точно контролната сума“ е вярно по устройство. Закръгляне тук би
   * внесло разлика от цент точно в полето, което банката сверява.
   */
  public static BigDecimal arithmeticTotal(List<BgPayrollSepaCandidate> lines) {
    BigDecimal total = BigDecimal.ZERO;
    for (BgPayrollSepaCandidate line : safe(lines)) {
      if (line.getNet() != null) {
        total = total.add(line.getNet());
      }
    }
    return total;
  }

  /**
   * ПРАЗНИЯТ ПРОБЕГ — отказ, а не празен файл.
   *
   * <p>⚠️ ПРАЗНОТО НАРЕЖДАНЕ НЕ ГЪРМИ НИКЪДЕ НАДОЛУ, И ТОВА Е ЦЯЛАТА ПРИЧИНА ПРАВИЛОТО ДА Е ТУК.
   * {@code BankOrderServiceImpl#generateFile} излиза с {@code return null} ПРЕДИ избора на формат,
   * когато списъкът с редове е празен — без изключение и без следа. Тоест вторият натиск за същия
   * период би завършил със зелен екран, нареждане без приложение и нула файла: най-тихият възможен
   * изход точно там, където се чакат пари.
   */
  public static String refuseNothingToPay(
      int scanned, int alreadyOrdered, int notConfirmed, int zeroNet) {
    if (scanned > 0) {
      return null;
    }
    StringBuilder text = new StringBuilder("Няма какво да се плати за този период.");
    if (alreadyOrdered > 0) {
      text.append("\n• ")
          .append(alreadyOrdered)
          .append(" фиша вече са в платежно нареждане (виж полето „Платежно нареждане (SEPA)“ на ")
          .append("фиша). Втори файл за тях би поискал второ плащане.");
    }
    if (notConfirmed > 0) {
      text.append("\n• ")
          .append(notConfirmed)
          .append(" фиша не са в състояние „Потвърден“ (чернова, изплатен или отказан). Плаща се ")
          .append("само потвърден фиш.");
    }
    if (zeroNet > 0) {
      text.append("\n• ")
          .append(zeroNet)
          .append(" фиша са с нулево нето — законен случай (цял месец неплатен отпуск), но ред в ")
          .append("SEPA файл не поражда.");
    }
    if (alreadyOrdered == 0 && notConfirmed == 0 && zeroNet == 0) {
      text.append("\nЗа този период и това дружество няма нито един фиш.");
    }
    return text.toString();
  }

  private static <T> List<T> safe(List<T> list) {
    return list == null ? new ArrayList<>() : list;
  }

  private static String name(String value) {
    return value == null || value.trim().isEmpty() ? "(без име)" : value.trim();
  }
}
