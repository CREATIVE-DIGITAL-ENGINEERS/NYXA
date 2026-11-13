package com.axelor.apps.bglocalization.service.etz;

import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * ВХОДНАТА ТОЧКА ЗА ЧОВЕК — „произведи ЕТЗ за този договор“, с <b>трите проверки в верния ред</b>.
 *
 * <p>Редът е: <b>условия → байтове → схема</b>, и всяка стъпка хваща различно нещо.
 *
 * <ol>
 *   <li>{@link BgEtzMandatory} — условната задължителност от Приложения № 1 и № 2. Схемата НЕ може
 *       да я изрази: 24 от 33 полета са {@code minOccurs="0"}.
 *   <li>{@link BgEtzEmitter} — байтовете в WINDOWS-1251; непредставим знак отказва тук.
 *   <li>{@link BgEtzValidator} — формата срещу закрепената схема.
 * </ol>
 *
 * <p><b>⚠️ ОБРАТНИЯТ РЕД БИ ДАЛ „0 ГРЕШКИ“ ВЪРХУ НЕПЪЛЕН ФАЙЛ</b> — тоест зелено, което не значи
 * нищо. А проверката СЛЕД закачането би оставила в базата артефакт и подаване, които не могат да
 * успеят.
 */
@Singleton
public class BgEtzExportService {

  /** Денят на еврото: събитие преди него с т. 18 не се генерира. */
  public static final LocalDate EURO_DAY = LocalDate.of(2026, 1, 1);

  /** Отказът по 1в — дословно по решението. */
  public static final String BEFORE_EURO_REFUSAL =
      "Събитието е преди 01.01.2026 – подай ръчно в портала на НАП";

  /**
   * ОТКАЗЪТ ПО 1в — един за всеки път към файла (сключване, прекратяване, после изменение).
   *
   * <p>т. 18 иска размера „в националната валута“; § 1в на НВРЗ казва за служебно правоотношение,
   * възникнало или изменено преди 01.01.2026, — в лева. Nyxa не превръща лв → EUR и не гадае коя
   * валута важи за събитие отпреди еврото: такова събитие с попълнена т. 18 се подава ръчно в
   * портала. Запис без т. 18 (прекратяването не я носи) правилото не засяга.
   *
   * @return текстът на отказа, или {@code null}
   */
  public static String beforeEuroRefusal(BgEtzFile file, LocalDate eventDate) {
    if (file == null || eventDate == null || !eventDate.isBefore(EURO_DAY)) {
      return null;
    }
    for (BgEtzRecord row : file.getRows()) {
      String salary = row.get(BgEtz62Field.SALARY);
      if (salary != null && !salary.isEmpty()) {
        return BEFORE_EURO_REFUSAL;
      }
    }
    return null;
  }

  /**
   * ПРЕГЛЕД: сглобява записа и връща пречките, без да хвърля.
   *
   * @param gaps списък, който се пълни с намереното
   */
  public BgEtzFile preview(EmploymentContract contract, BgEtzEventKind kind, List<BgEtzGap> gaps) {
    return preview(contract, kind, gaps, null);
  }

  /** Същото, с изричните реквизити на прекратяването (т. 25, 28–33). */
  public BgEtzFile preview(
      EmploymentContract contract,
      BgEtzEventKind kind,
      List<BgEtzGap> gaps,
      BgEtzTerminationInput input) {
    return resolver().resolve(BgEtzFacts.of(contract), kind, gaps, input);
  }

  /**
   * ПЪЛНИЯТ ХОД: сглобява, проверява условията, произвежда байтове, валидира и закача.
   *
   * @return подаването-чернова
   * @throws BgEtzRefusalException при пречка — назовава всяко поле и екрана, където се попълва;
   *     нищо не е записано
   */
  public NapSubmission export(
      EmploymentContract contract, BgEtzEventKind kind, LocalDate eventDate) {
    return export(contract, kind, eventDate, null, null, null);
  }

  /**
   * Същото, с реквизитите на прекратяването и с изрична закачалка — записът, породил събитието (при
   * прекратяване — {@code BgContractTermination}, не договорът; виж там защо).
   */
  public NapSubmission export(
      EmploymentContract contract,
      BgEtzEventKind kind,
      LocalDate eventDate,
      BgEtzTerminationInput input,
      Class<?> relatedModel,
      Long relatedId) {
    List<BgEtzGap> gaps = new ArrayList<>();
    BgEtzFile file = preview(contract, kind, gaps, input);

    if (!gaps.isEmpty()) {
      throw new BgEtzRefusalException(
          "ЕТЗ файл НЕ се произвежда — попълнете първо:\n" + BgEtzScreens.describe(gaps));
    }
    // ⚠️ СЛЕД пречките и ПРЕДИ байтовете: отказът по 1в не оставя нито артефакт, нито подаване.
    String beforeEuro = beforeEuroRefusal(file, eventDate);
    if (beforeEuro != null) {
      throw new BgEtzRefusalException(beforeEuro);
    }

    byte[] bytes = emitter().emit(file);

    List<BgEtzFinding> findings = validator().validate(bytes, file);
    if (BgEtzValidator.hasFindings(findings)) {
      throw new BgEtzRefusalException(
          "ЕТЗ файлът не мина проверката срещу "
              + file.getSchema().describe()
              + ":\n"
              + describeFindings(findings));
    }

    try {
      return Beans.get(BgEtzSubmissionService.class)
          .attach(contract, kind, bytes, eventDate, relatedModel, relatedId);
    } catch (IOException e) {
      throw new IllegalStateException(
          "закачането на ЕТЗ подаването не завърши: " + e.getMessage(), e);
    }
  }

  /** Отказ за човек — контролерът го показва като грешка, без трасе. */
  public static class BgEtzRefusalException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public BgEtzRefusalException(String message) {
      super(message);
    }
  }

  /** Пречките, изредени за човек. */
  public static String describeGaps(List<BgEtzGap> gaps) {
    StringBuilder sb = new StringBuilder();
    if (gaps != null) {
      for (BgEtzGap gap : gaps) {
        if (sb.length() > 0) {
          sb.append('\n');
        }
        sb.append("• ").append(gap.describe());
      }
    }
    return sb.toString();
  }

  /** Констатациите, изредени за човек. */
  public static String describeFindings(List<BgEtzFinding> findings) {
    StringBuilder sb = new StringBuilder();
    if (findings != null) {
      for (BgEtzFinding finding : findings) {
        if (sb.length() > 0) {
          sb.append('\n');
        }
        sb.append("• ").append(finding.describe());
      }
    }
    return sb.toString();
  }

  protected BgEtzResolver resolver() {
    return new BgEtzResolver();
  }

  protected BgEtzEmitter emitter() {
    return new BgEtzEmitter();
  }

  protected BgEtzValidator validator() {
    return new BgEtzValidator();
  }
}
