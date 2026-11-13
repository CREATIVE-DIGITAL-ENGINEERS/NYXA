package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Sequence;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * БРОЯЧИТЕ В ТРЗ — ПО ЕДИН ЗА ВСЯКО ДРУЖЕСТВО. Чиста функция: строи и назовава, не записва.
 *
 * <p><b>⚠️ ЗАЩО НЕ ОБЩА РЕДИЦА.</b> {@code SequenceService.getSequence(код, null)} дава първия ред
 * с този код от КОЕТО И ДА Е дружество, засят от {@code base_sequence.csv} без дружество. Тогава
 * две дружества в една база броят по една обща редица: FISH-2026-0007 в А, FISH-2026-0008 в Б, а
 * всяко от тях вижда дупки в собствената си номерация. Резервен брояч ({@code count + 1}, отново
 * общ), който мълчаливо поема, когато редицата липсва, също няма.
 *
 * <p><b>⚠️ ДРУЖЕСТВОТО Е НА РАБОТОДАТЕЛЯ.</b> Платецът по трудовия договор ({@code payCompany}), а
 * за самоосигуряващия се — по правилото на {@code BgPaySlipWriteGuard#companyOf}. Не трето правило:
 * каталогът на правата вижда фиша там, тук той се брои там.
 *
 * <p><b>⚠️ БЕЗ РЕЗЕРВЕН ПЪТ.</b> Няма ли редица за дружеството, записът се ОТКАЗВА с името на
 * дружеството и вида документ ({@link #missingRefusal}). Тихото връщане към общата редица щеше да
 * върне точно грешката, която тук се маха, и да я направи невидима.
 *
 * <p>Новото дружество получава двете редици при създаване, по всеки път ({@link
 * BgTrzCompanySequenceListener}), заварените — при старт ({@link BgTrzSequenceBackfill}),
 * продължени от най-големия номер на всяко дружество. Колоната и ключът (company, reference) — от
 * миграцията {@code db/migration/2026-10-21-trz-numbering-per-company.sql}.
 */
public final class BgTrzSequences {

  /** Фишът за заплата: {@code FISH-<година на периода>-NNNN}. */
  public static final String PAY_SLIP = "bgPaySlip";

  /** Обезщетението при прекратяване: {@code OBEZ-<година на събитието>-NNNN}. */
  public static final String COMPENSATION = "bgCompensation";

  /** Всички ТРЗ редици — каквото се създава за всяко ново дружество. */
  public static final List<String> CODES =
      Collections.unmodifiableList(Arrays.asList(PAY_SLIP, COMPENSATION));

  public static final int PADDING = 4;

  private BgTrzSequences() {}

  /** Префиксът на кода; годината идва от датата на документа, не от днешния ден. */
  public static String prefixFor(String code) {
    if (PAY_SLIP.equals(code)) {
      return "FISH-%YYYY-";
    }
    if (COMPENSATION.equals(code)) {
      return "OBEZ-%YYYY-";
    }
    throw new IllegalArgumentException("Не е ТРЗ редица: " + code);
  }

  /** Видът документ, с който редицата се назовава в отказите и на екрана. */
  public static String documentKind(String code) {
    if (PAY_SLIP.equals(code)) {
      return "фиш за заплата";
    }
    if (COMPENSATION.equals(code)) {
      return "обезщетение при прекратяване";
    }
    throw new IllegalArgumentException("Не е ТРЗ редица: " + code);
  }

  /**
   * Редицата на кода за едно дружество. Без версии: Axelor създава годишната при първия номер (от
   * 01.01 до 31.12 на годината на документа, от номер 1) — точно каквото трябва на ново дружество.
   */
  public static Sequence newFor(Company company, String code) {
    Sequence sequence = new Sequence();
    sequence.setCompany(company);
    sequence.setCodeSelect(code);
    sequence.setName(nameFor(company, code));
    sequence.setPrefixe(prefixFor(code));
    sequence.setSuffixe(null);
    sequence.setPadding(PADDING);
    sequence.setToBeAdded(1);
    sequence.setYearlyResetOk(true);
    sequence.setMonthlyResetOk(false);
    // Както SequenceService#computeFullName: FISH-%YYYY-XXXX - <име>.
    sequence.setFullName(sequence.getPrefixe() + "XXXX - " + sequence.getName());
    return sequence;
  }

  static String nameFor(Company company, String code) {
    String kind = documentKind(code);
    String label = Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
    return label + " (Nyxa) · " + companyLabel(company);
  }

  /** Отказът, когато дружеството няма редица за вида документ. */
  public static String missingRefusal(Company company, String code, String documentLabel) {
    return capitalized(documentKind(code))
        + " "
        + documentLabel
        + " не се номерира: дружество "
        + companyLabel(company)
        + " няма последователност „"
        + code
        + "“. Номерът се дава САМО от последователността на дружеството — общ брояч няма. Нова"
        + " фирма я получава при създаване, а заварена — от стъпката при старта"
        + " (BgTrzSequenceBackfill; виж лога на старта).";
  }

  /** Отказът, когато редицата на дружеството я има, но Axelor не даде номер (напр. повтарящ се). */
  public static String numberingFailed(
      Company company, String code, String documentLabel, String reason) {
    return capitalized(documentKind(code))
        + " "
        + documentLabel
        + " не се номерира в дружество "
        + companyLabel(company)
        + ": "
        + reason;
  }

  /** Отказът, когато документът няма от какво дружество да се брои. */
  public static String noCompanyRefusal(String code, String documentLabel, String why) {
    return capitalized(documentKind(code)) + " " + documentLabel + " не се номерира: " + why;
  }

  /**
   * ДРУЖЕСТВОТО НА НОМЕРА Е ПЛАТЕЦЪТ — причина за отказ, или {@code null}.
   *
   * <p>Колоната е копие на платеца, направено при номерирането, и съществува САМО за уникалността
   * (дружество, номер). Различна стойност значи или подадена отвън (REST, внос), или документ,
   * който днес се извежда към друго дружество (договор от друг платец, сменен основен договор) —
   * тогава номерът му е от брояча на първото и в новото би бил чужд. Поправката е нов документ в
   * правилното дружество.
   */
  public static String refusePayerMismatch(
      String code, String documentLabel, Company stored, Company payer) {
    if (stored == null || payer == null || sameCompany(stored, payer)) {
      return null;
    }
    return capitalized(documentKind(code))
        + " "
        + documentLabel
        + " не се записва: дружеството на номера е "
        + companyLabel(stored)
        + ", а платецът му е "
        + companyLabel(payer)
        + ". Двете трябва да съвпадат — номерът е от брояча на дружеството и документ не се мести"
        + " между дружества. Създайте нов документ в "
        + companyLabel(payer)
        + ".";
  }

  /** Записаното дружество на номера се сменя (по старото състояние) — отказ. */
  public static String refuseCompanyChange(
      String code, String documentLabel, Company before, Company after) {
    if (before == null || after == null || sameCompany(before, after)) {
      return null;
    }
    return capitalized(documentKind(code))
        + " "
        + documentLabel
        + " не се записва: номерът му е в брояча на "
        + companyLabel(before)
        + " и дружеството му не може да стане "
        + companyLabel(after)
        + ". Записва се веднъж, при номерирането.";
  }

  /** Записаното дружество на номера се изтрива — отказ. */
  public static String refuseCompanyCleared(String code, String documentLabel, Company before) {
    return capitalized(documentKind(code))
        + " "
        + documentLabel
        + " не се записва: дружеството на номера ("
        + companyLabel(before)
        + ") не може да се изтрие. Записва се веднъж, при номерирането.";
  }

  static boolean sameCompany(Company a, Company b) {
    if (a == b) {
      return true;
    }
    if (a.getId() != null || b.getId() != null) {
      return Objects.equals(a.getId(), b.getId());
    }
    return false;
  }

  public static String companyLabel(Company company) {
    if (company == null) {
      return "(без дружество)";
    }
    String name = company.getName();
    String code = company.getCode();
    if (name != null && code != null) {
      return "„" + name + "“ (" + code + ")";
    }
    if (name != null) {
      return "„" + name + "“";
    }
    return code != null ? code : "#" + company.getId();
  }

  private static String capitalized(String text) {
    return Character.toUpperCase(text.charAt(0)) + text.substring(1);
  }
}
