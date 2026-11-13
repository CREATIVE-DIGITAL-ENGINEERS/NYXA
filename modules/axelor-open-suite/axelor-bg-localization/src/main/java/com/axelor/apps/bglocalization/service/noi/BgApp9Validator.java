package com.axelor.apps.bglocalization.service.noi;

import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;

/**
 * ОФЛАЙН ПРОВЕРКАТА НА ФАЙЛА — два слоя, нула мрежа, детерминистична.
 *
 * <p><b>Слой 1 — СХЕМАТА.</b> Генерираният XML минава срещу закрепеното копие на {@code Pril9.xsd}
 * ({@link BgApp9Xsd}). Хваща липсващ таг, разбъркан ред, стойност извън изброяване, ден извън 1–31,
 * номер на лист, който не е 12 знака.
 *
 * <p><b>Слой 2 — КОНТРОЛИТЕ, КОИТО СХЕМАТА НЕ МОЖЕ ДА ИЗРАЗИ.</b> Кръстосаните проверки от
 * таблицата на НОИ ({@link BgApp9Xsd#CONTROL_SPEC_URL}): съответствие между границите на т. 9 и
 * периода на листа, брой комплекти = брой календарни месеци, дати в допустимите прозорци, формат на
 * IBAN, контролна сума на ЕГН. ⚠️ САМО ЕДНОЗНАЧНИТЕ. Правило, което зависи от регистър на НОИ
 * („номерът на листа е измежду предоставените на лечебните заведения“) или от данни, каквито
 * модулът няма (графикът при СИРВ), се ФЛАГВА — не се изобретява негов заместител.
 *
 * <p><b>⚠️ ЗАЩО ОФЛАЙН, ПРИ ПОЛОЖЕНИЕ ЧЕ НОИ ИМА УЕБ-ВАЛИДАТОР „БЕЗ КЕП“.</b> Има го и той е
 * полезен — като РЪЧНА кръстосана проверка за човека преди подаване. Извикан от код, той прави
 * зеленото на тестовете зависимо от чужда услуга: профилактика в НОИ би валяла билда, а промяна в
 * отговора му би сменила поведението ни, без нито един ред тук да е пипнат. Затова котвата е
 * копието на схемата, а адресът на уеб-валидатора стои в доклада, за човека.
 *
 * <p><b>⚠️ И ЗАЩО ЗЕЛЕНОТО ТУК НЕ Е ДОКАЗАТЕЛСТВО, ЧЕ НОИ ЩЕ ПРИЕМЕ ФАЙЛА.</b> Схемата приема
 * удостоверение, в което всяко „да/не“ е попълнено с 9 „не е избрано“. Пълнотата се пази ПРЕДИ
 * емисията, от {@link BgApp9MandatoryPoints}; валидаторът пази формата.
 */
public class BgApp9Validator {

  /** Максималният брой съобщения от схемата, които влизат в отчета. */
  private static final int MAX_SCHEMA_ERRORS = 50;

  /**
   * ЦЯЛАТА проверка: схемата и контролите.
   *
   * @param xml готовият файл
   * @param declarations удостоверенията, от които е построен — за кръстосаните контроли
   */
  public List<BgApp9Finding> validate(String xml, List<BgApp9Declaration> declarations) {
    List<BgApp9Finding> findings = new ArrayList<>(validateAgainstSchema(xml));
    if (declarations != null) {
      for (BgApp9Declaration declaration : declarations) {
        findings.addAll(controls(declaration));
      }
    }
    return findings;
  }

  /** СЛОЙ 1 — само схемата. Отделен метод, за да може да се пуска и сам. */
  public List<BgApp9Finding> validateAgainstSchema(String xml) {
    List<BgApp9Finding> findings = new ArrayList<>();
    if (xml == null || xml.trim().isEmpty()) {
      findings.add(BgApp9Finding.schema("файл", "няма съдържание за проверка"));
      return findings;
    }
    try (InputStream xsd = BgApp9Validator.class.getResourceAsStream(BgApp9Xsd.RESOURCE)) {
      if (xsd == null) {
        findings.add(
            BgApp9Finding.schema(
                BgApp9Xsd.RESOURCE,
                "схемата не е в classpath-а — без нея проверката е безсмислена и НЕ се приема"
                    + " за минала"));
        return findings;
      }
      SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      Schema schema = factory.newSchema(new StreamSource(xsd));

      Validator validator = schema.newValidator();
      validator.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

      final List<BgApp9Finding> collected = findings;
      validator.setErrorHandler(
          new ErrorHandler() {
            @Override
            public void warning(SAXParseException e) {
              add(collected, e, "предупреждение");
            }

            @Override
            public void error(SAXParseException e) {
              add(collected, e, "грешка");
            }

            @Override
            public void fatalError(SAXParseException e) {
              add(collected, e, "фатална грешка");
            }
          });
      validator.validate(hardenedSource(xml));

    } catch (Exception e) {
      findings.add(
          BgApp9Finding.schema(
              "валидация",
              "проверката срещу " + BgApp9Xsd.describe() + " не завърши: " + e.getMessage()));
    }
    return findings;
  }

  /**
   * ВХОДЪТ СЕ ЧЕТЕ ОТ ЗАТВОРЕН ЧЕТЕЦ — без DTD, без външни обекти, без мрежа.
   *
   * <p><b>⚠️ Защо не през {@code setProperty(ACCESS_EXTERNAL_DTD, "")}, както е обичайно.</b>
   * ИЗМЕРЕНО, а не предположено: върху JAXP-а на този билд {@code Validator} отхвърля точно това
   * свойство с „Property … is not recognized“, тоест втвърдяването не се прилага и — по-лошо —
   * изключението пада В СРЕДАТА НА валидацията и се докладва като „проверката не завърши“. Файл,
   * който е напълно валиден, изглежда като файл с грешка.
   *
   * <p>Затова забраната се слага там, където се поддържа навсякъде: {@code disallow-doctype-decl}
   * върху четеца. Тя е и по-силна — документ с DOCTYPE изобщо не се чете, вместо да се чете без да
   * разрешава външните обекти. НОИ файловете нямат DTD, тоест нищо законно не се губи.
   *
   * <p>Това има значение и извън тестовете: същият валидатор ще чете и файл, ДОНЕСЕН отвън
   * (например върнат от НОИ), а тогава DOCTYPE към чужд адрес е и мрежа, и XXE.
   */
  private static SAXSource hardenedSource(String xml) throws Exception {
    SAXParserFactory parsers = SAXParserFactory.newInstance();
    parsers.setNamespaceAware(true);
    parsers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    parsers.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    parsers.setFeature("http://xml.org/sax/features/external-general-entities", false);
    parsers.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    XMLReader reader = parsers.newSAXParser().getXMLReader();
    return new SAXSource(
        reader, new InputSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
  }

  private static void add(List<BgApp9Finding> findings, SAXParseException e, String kind) {
    if (findings.size() >= MAX_SCHEMA_ERRORS) {
      return;
    }
    findings.add(BgApp9Finding.schema("ред " + e.getLineNumber(), kind + ": " + e.getMessage()));
  }

  /**
   * СЛОЙ 2 — кръстосаните контроли за ЕДНО удостоверение.
   *
   * <p>Работи върху ЗАПИСИТЕ, не върху текста: правилата говорят за дати и дни, а не за тагове, и
   * прочетени от XML-а, те щяха да се проверяват срещу собствения си изход.
   */
  public List<BgApp9Finding> controls(BgApp9Declaration d) {
    List<BgApp9Finding> out = new ArrayList<>();
    if (d == null || d.getSickLeave() == null) {
      return out;
    }
    BgSickLeave sick = d.getSickLeave();
    String doc = BgApp9MandatoryPoints.label(d);
    List<BgApp9Point9.Month> months = BgApp9Emitter.monthsOf(d);

    // ── дати на самия лист ──
    LocalDate from = sick.getFromDate();
    LocalDate to = sick.getToDate();
    LocalDate issued = sick.getBgIssueDate();
    LocalDate floor2007 = LocalDate.of(2007, 1, 1);

    if (issued != null && issued.isBefore(floor2007)) {
      out.add(
          BgApp9Finding.control(
              doc + " · дата на издаване",
              "контролът иска датата да е >= 01.01.2007 г., а тя е " + issued + "."));
    }
    if (from != null && from.isBefore(floor2007)) {
      out.add(
          BgApp9Finding.control(
              doc + " · в отпуск от",
              "контролът иска датата да е >= 01.01.2007 г., а тя е " + from + "."));
    }
    if (from != null && to != null && to.isBefore(from)) {
      out.add(
          BgApp9Finding.control(
              doc + " · в отпуск до",
              "контролът иска краят да е >= началото, а " + to + " е преди " + from + "."));
    }
    if (issued != null && to != null && sick.getBgEmployerReceivedDate() != null) {
      LocalDate received = sick.getBgEmployerReceivedDate();
      if (received.isBefore(issued)) {
        out.add(
            BgApp9Finding.control(
                doc + " · т. 25 · дата на представяне",
                "контролът иска датата да е >= датата на ИЗДАВАНЕ на листа ("
                    + issued
                    + "), а тя е "
                    + received
                    + "."));
      }
      if (received.isBefore(LocalDate.of(2016, 1, 1))) {
        out.add(
            BgApp9Finding.control(
                doc + " · т. 25 · дата на представяне",
                "контролът иска датата да е >= 01.01.2016 г., а тя е " + received + "."));
      }
    }

    // ── т. 9 срещу периода на листа ──
    if (!months.isEmpty() && from != null && to != null) {
      int expected = monthSpan(from, to);
      if (months.size() != expected) {
        out.add(
            BgApp9Finding.control(
                doc + " · т. 9",
                "контролът иска „толкова комплекта данни, колкото различни КАЛЕНДАРНИ месеца"
                    + " обхваща периодът“ — периодът обхваща "
                    + expected
                    + ", а редовете дават "
                    + months.size()
                    + ". Липсва пресметнат фиш за някой от месеците."));
      }
      BgApp9Point9.Month first = months.get(0);
      BgApp9Point9.Month last = months.get(months.size() - 1);
      if (!first.fromDate().equals(from)) {
        out.add(
            BgApp9Finding.control(
                doc + " · т. 9 · първи месец",
                "контролът иска денят „от“ на първия месец да СЪВПАДА с началото на отпуска ("
                    + from
                    + "), а е "
                    + first.fromDate()
                    + "."));
      }
      if (!last.toDate().equals(to)) {
        out.add(
            BgApp9Finding.control(
                doc + " · т. 9 · последен месец",
                "контролът иска денят „до“ на последния месец да СЪВПАДА с края на отпуска ("
                    + to
                    + "), а е "
                    + last.toDate()
                    + "."));
      }
    }

    for (BgApp9Point9.Month month : months) {
      int calendarDays = month.toDay - month.fromDay + 1;
      if (month.days > calendarDays) {
        out.add(
            BgApp9Finding.control(
                doc + " · т. 9 · " + month.month + "." + month.year,
                "контролът иска броят работни дни да е <= календарните дни между „от“ и „до“"
                    + " включително ("
                    + calendarDays
                    + "), а той е "
                    + month.days
                    + "."));
      }
      if (month.hoursWantedButMissing) {
        out.add(
            BgApp9Finding.flag(
                doc + " · т. 9 · " + month.month + "." + month.year,
                "подадени са ДНИ. Указанието иска ЧАСОВЕ при сумирано изчисляване на работното"
                    + " време и по ГРАФИКА на лицето, а модулът няма график — Ф0 записва часове"
                    + " само при подневно отчитане. Контролът допуска дни, когато часове не са"
                    + " попълнени; проверете ръчно, ако лицето е на СИРВ."));
      }
      if (month.legalHoursSubstituted) {
        out.add(
            BgApp9Finding.flag(
                doc + " · т. 9 · " + month.month + "." + month.year,
                "дневното работно време е подадено като "
                    + BgApp9Point9.SUBSTITUTE_LEGAL_HOURS
                    + " часа по ЗАМЕСТВАЩОТО правило на указанието („за лицата без определено"
                    + " работно време в часове“), защото по договора не е уговорено в часове."));
      }
      if (month.legalHoursRounded) {
        out.add(
            BgApp9Finding.flag(
                doc + " · т. 9 · " + month.month + "." + month.year,
                "дневното работно време "
                    + month.dailyHours
                    + " ч. е ЗАКРЪГЛЕНО до "
                    + month.legalWorkHours
                    + " по правилото „при остатък под 30 минути към пълния час“ — схемата приема"
                    + " само цели часове 1–8."));
      }
      if (month.sourceLineCount > 1) {
        out.add(
            BgApp9Finding.flag(
                doc + " · т. 9 · " + month.month + "." + month.year,
                "редовете на Ф0 за този месец са "
                    + month.sourceLineCount
                    + " (осигурител и НОИ) и са СБРАНИ в един комплект: официалният ред НЕ дели"
                    + " по платец, а контролът допуска два комплекта за месец само при попълнена"
                    + " т. 7."));
      }
    }

    // ── т. 7 и т. 8 не се попълват едновременно ──
    if (d.getBgP7TerminatedFrom() != null && d.getBgP8InterruptedFrom() != null) {
      out.add(
          BgApp9Finding.control(
              doc + " · т. 7 / т. 8",
              "контролът е дословен: „НЕ е допустимо едновременно да е попълнена и т. 8“ —"
                  + " прекратено и прекъснато се изключват."));
    }
    if (BgApp9ObstoyatelstvaResolver.YES.equals(d.getBgP1Insured())
        && d.getBgP8InterruptedFrom() != null) {
      out.add(
          BgApp9Finding.control(
              doc + " · т. 8",
              "контролът допуска дата на прекъсване САМО когато е избрано, че лицето НЕ е"
                  + " осигурено към деня на настъпване на риска, а т. 1 казва „осигурен“."));
    }
    if (d.getBgP7TerminatedFrom() != null && to != null && d.getBgP7TerminatedFrom().isAfter(to)) {
      out.add(
          BgApp9Finding.control(
              doc + " · т. 7",
              "контролът иска датата да е <= края на отпуска ("
                  + to
                  + "), а тя е "
                  + d.getBgP7TerminatedFrom()
                  + "."));
    }

    // ── ЕГН по модул ──
    String egn = personalId(d);
    if (egn != null && egn.length() == 10 && isEgnShaped(d)) {
      if (!isValidEgnChecksum(egn)) {
        out.add(
            BgApp9Finding.control(
                doc + " · ЕГН",
                "контролът е „проверява се за вярност на ЕГН по МОДУЛ“, а контролната цифра на „"
                    + egn
                    + "“ не съвпада."));
      }
    }

    // ── IBAN ──
    String iban = BgApp9MandatoryPoints.trim(d.getBenefitIban());
    if (iban != null) {
      String compact = iban.replace(" ", "").toUpperCase();
      if (!isValidBgIban(compact)) {
        out.add(
            BgApp9Finding.control(
                doc + " · т. 26 · IBAN",
                "контролът е „контролира се според Наредбата на БНБ“ — български IBAN е 22 знака"
                    + " „BG“ + 2 контролни + 4 буквен банков код + 16, и минава проверката по"
                    + " ISO 13616 (mod-97). Стойността „"
                    + iban
                    + "“ не минава."));
      }
    }

    // ── т. 11 без код на нарушението ──
    // ⚠️ ОСТА Е ЦЯЛАТА ТОЧКА, не само номерът. Образецът е едно изречение („С анкетен лист № … от
    // … е установено …“), затова всяко от трите полета отваря точката. Докато условието се четеше
    // само от номера, попълнено „установено“ без номер не вдигаше НИЩО — а точно то е фактът по
    // чл. 46 ал. 1 КСО, който никой елемент не носи.
    boolean surveyTouched =
        !BgApp9MandatoryPoints.blank(d.getBgP11SurveyNo())
            || d.getBgP11SurveyDate() != null
            || !BgApp9MandatoryPoints.blank(d.getBgP11FindingSelect());
    if (surveyTouched) {
      out.add(
          BgApp9Finding.flag(
              doc + " · т. 11 · вид на нарушението",
              "елементът AnketaCode е подаден ПРАЗЕН. bg.app9.survey.finding.select носи"
                  + " ОПИСАТЕЛНИ кодове нарочно (Ф1: образецът изброява случаите по чл. 46 ал. 1"
                  + " КСО без да ги номерира), а номенклатурата AnketaCode на НОИ не е набавена."
                  + " Попълва се ръчно през портала, докато съответствието не бъде сверено."));
    }

    // ── т. 11 отворена, но непълна ──
    // Точката се подава на НОИ като номер + дата; „установено“ няма свой елемент. Липсващият номер
    // или дата означава, че единственото, което би стигнало до НОИ, е гола отметка Anketa = 1.
    if (surveyTouched
        && (BgApp9MandatoryPoints.blank(d.getBgP11SurveyNo()) || d.getBgP11SurveyDate() == null)) {
      out.add(
          BgApp9Finding.flag(
              doc + " · т. 11 · непълен анкетен лист",
              "т. 11 е отворена, но „С анкетен лист № … от … 20.. г.“ е НЕПЪЛНО (номер: „"
                  + (BgApp9MandatoryPoints.blank(d.getBgP11SurveyNo())
                      ? "празен"
                      : d.getBgP11SurveyNo())
                  + "“, дата: "
                  + (d.getBgP11SurveyDate() == null ? "празна" : d.getBgP11SurveyDate())
                  + "). До НОИ стига само отметката Anketa = 1 без документа, който я"
                  + " доказва — а установеното по чл. 46 ал. 1 КСО няма свой елемент в схемата."));
    }

    return out;
  }

  /** Броят различни календарни месеца, които периодът обхваща. */
  static int monthSpan(LocalDate from, LocalDate to) {
    return (to.getYear() - from.getYear()) * 12 + (to.getMonthValue() - from.getMonthValue()) + 1;
  }

  private static String personalId(BgApp9Declaration d) {
    if (d.getSickLeave() == null || d.getSickLeave().getEmploymentContract() == null) {
      return null;
    }
    com.axelor.apps.hr.db.Employee employee =
        d.getSickLeave().getEmploymentContract().getEmployee();
    return employee == null ? null : BgApp9MandatoryPoints.trim(employee.getBgPersonalIdNumber());
  }

  /** Само ЕГН се проверява по модул — ЛН/ЛНЧ/СлН имат друга структура. */
  private static boolean isEgnShaped(BgApp9Declaration d) {
    if (d.getSickLeave() == null || d.getSickLeave().getEmploymentContract() == null) {
      return false;
    }
    com.axelor.apps.hr.db.Employee employee =
        d.getSickLeave().getEmploymentContract().getEmployee();
    String type =
        employee == null ? null : BgApp9MandatoryPoints.trim(employee.getBgPersonalIdType());
    return type == null || "EGN".equalsIgnoreCase(type);
  }

  /**
   * КОНТРОЛНАТА ЦИФРА НА ЕГН — тежести 2, 4, 8, 5, 10, 9, 7, 3, 6, остатък по модул 11.
   *
   * <p>Остатък 10 дава контролна цифра 0 — правилото е на ГРАО и остатъкът 10 се среща.
   */
  static boolean isValidEgnChecksum(String egn) {
    int[] weights = {2, 4, 8, 5, 10, 9, 7, 3, 6};
    int sum = 0;
    for (int i = 0; i < 9; i++) {
      sum += (egn.charAt(i) - '0') * weights[i];
    }
    int check = sum % 11;
    if (check == 10) {
      check = 0;
    }
    return check == (egn.charAt(9) - '0');
  }

  /**
   * БЪЛГАРСКИ IBAN по ISO 13616 — дължина 22, префикс BG, и mod-97 = 1.
   *
   * <p>⚠️ Проверява се САМО българският формат: полето {@code IBAN} на раздел I е с дължина 22 и се
   * попълва само при БЪЛГАРСКА банка (чуждата има своя секция). Проверка „изобщо на IBAN“ тук би
   * приела 34-знаков номер в поле, което схемата реже на 22.
   */
  static boolean isValidBgIban(String iban) {
    if (iban == null || iban.length() != 22 || !iban.startsWith("BG")) {
      return false;
    }
    String rearranged = iban.substring(4) + iban.substring(0, 4);
    StringBuilder digits = new StringBuilder(rearranged.length() * 2);
    for (int i = 0; i < rearranged.length(); i++) {
      char c = rearranged.charAt(i);
      if (c >= '0' && c <= '9') {
        digits.append(c);
      } else if (c >= 'A' && c <= 'Z') {
        digits.append(c - 'A' + 10);
      } else {
        return false;
      }
    }
    // Остатъкът се смята на части: числото е над сто цифри и не се побира в long.
    int remainder = 0;
    for (int i = 0; i < digits.length(); i++) {
      remainder = (remainder * 10 + (digits.charAt(i) - '0')) % 97;
    }
    return remainder == 1;
  }

  /** Отчетът, който се записва на записа и се чете от човек. */
  public static String report(List<BgApp9Finding> findings) {
    StringBuilder sb = new StringBuilder();
    sb.append("Проверено срещу ").append(BgApp9Xsd.describe()).append('\n');
    int errors = 0;
    for (BgApp9Finding f : findings) {
      if (f.isError()) {
        errors++;
      }
    }
    sb.append("Грешки: ")
        .append(errors)
        .append(" · флагнати за проверка: ")
        .append(findings.size() - errors);
    for (BgApp9Finding f : findings) {
      sb.append('\n').append(f.describe());
    }
    return sb.toString();
  }

  /** Има ли находка, която ще върне файла. */
  public static boolean hasErrors(List<BgApp9Finding> findings) {
    for (BgApp9Finding f : findings) {
      if (f.isError()) {
        return true;
      }
    }
    return false;
  }
}
