package com.axelor.apps.bglocalization.service.noi;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * ИЗХОДЪТ НА {@link BgApp9ObstoyatelstvaResolver} — изведените стойности И присъдите за всичките
 * двайсет и шест точки.
 *
 * <p><b>⚠️ ДВЕТЕ ПОЛОВИНИ СА НЕРАЗДЕЛНИ, И ТОВА Е ЦЯЛАТА ИДЕЯ НА КЛАСА.</b> Резолверът НЕ връща
 * само стойности: за всяка точка връща и изречение защо стойността е тази или защо я няма. Върнати
 * поотделно — стойности от един метод, обяснения от друг — обясненията щяха да са по избор, а по
 * избор значи неползвани. Генераторите на фиша следват същия образец чрез {@code
 * BgPaySlipVerdicts}.
 *
 * <p><b>Стойностите са ПЛОСКИ, не entity-та.</b> {@link #p13ChildId} е {@code Long}, а не {@code
 * BgEmployeeChild}: резолверът е чиста функция и не докосва базата. Съпоставянето върху записа е на
 * {@link BgApp9DeclarationService}.
 *
 * <p><b>⚠️ РЪЧНИТЕ ТОЧКИ ГИ НЯМА ТУК.</b> Т. 8, 10, 11, 12, 14–24 не са полета на този запис,
 * защото резолверът не бива да може да ги напише — а метод, който ги връща, рано или късно ще бъде
 * записан от някого. Присъдите им са в {@link #outcomes} със статус {@code MANUAL} и там носят
 * предложенията.
 */
public final class BgApp9Resolution {

  /** Т. 1 — „YES“/„NO“ по {@code bg.app9.yes.no.select}, или {@code null} при отказ. */
  public final String p1Insured;

  /** Т. 1.1 — по {@code bg.app9.term.type.select}. */
  public final String p1TermType;

  /** Т. 1.1 — датата, на която изтича срокът на договора. */
  public final LocalDate p1TermEndDate;

  /** Т. 1.2 — срочността на регистрацията като СОЛ. */
  public final String p1SolRegType;

  /** Т. 1.2 — датата, на която изтича регистрацията. */
  public final LocalDate p1SolRegEndDate;

  /** Т. 2 — по {@code bg.app9.stay.select}. ⚠️ Полярността е на ПРАВОТО, не на отметката. */
  public final String p2SixMonthStay;

  /** Т. 3 — по {@code bg.app9.stay.select}. */
  public final String p3TwelveMonthStay;

  /** Т. 4 — кодът за вид осигурен, гол низ. */
  public final String p4InsuredTypeCode;

  /** Т. 5 — броят правоотношения при осигурителя. */
  public final Integer p5RelationshipCount;

  /** Т. 6 — поредният номер на удостоверението, в 1..{@link #p5RelationshipCount}. */
  public final Integer certificateSeqNo;

  /** Т. 7 — прекратено СЧИТАНО ОТ, тоест последният осигурен ден + 1. */
  public final LocalDate p7TerminatedFrom;

  /** Т. 13 — идентификаторът на детето, когато е еднозначно. */
  public final Long p13ChildId;

  /** Т. 26 — IBAN, копиран от лицето при извеждането. */
  public final String benefitIban;

  /** Т. 26 — първо представяне ли е, по {@code bg.app9.yes.no.select}. */
  public final String p26FirstSubmission;

  /** ПРИСЪДИТЕ — по една за всяка точка, в реда на образеца. Никога празен списък. */
  public final List<BgApp9Outcome> outcomes;

  BgApp9Resolution(
      String p1Insured,
      String p1TermType,
      LocalDate p1TermEndDate,
      String p1SolRegType,
      LocalDate p1SolRegEndDate,
      String p2SixMonthStay,
      String p3TwelveMonthStay,
      String p4InsuredTypeCode,
      Integer p5RelationshipCount,
      Integer certificateSeqNo,
      LocalDate p7TerminatedFrom,
      Long p13ChildId,
      String benefitIban,
      String p26FirstSubmission,
      List<BgApp9Outcome> outcomes) {
    this.p1Insured = p1Insured;
    this.p1TermType = p1TermType;
    this.p1TermEndDate = p1TermEndDate;
    this.p1SolRegType = p1SolRegType;
    this.p1SolRegEndDate = p1SolRegEndDate;
    this.p2SixMonthStay = p2SixMonthStay;
    this.p3TwelveMonthStay = p3TwelveMonthStay;
    this.p4InsuredTypeCode = p4InsuredTypeCode;
    this.p5RelationshipCount = p5RelationshipCount;
    this.certificateSeqNo = certificateSeqNo;
    this.p7TerminatedFrom = p7TerminatedFrom;
    this.p13ChildId = p13ChildId;
    this.benefitIban = benefitIban;
    this.p26FirstSubmission = p26FirstSubmission;
    this.outcomes =
        outcomes == null
            ? Collections.<BgApp9Outcome>emptyList()
            : Collections.unmodifiableList(outcomes);
  }

  /** Присъдата за дадена точка — за тестовете и за съобщението към оператора. */
  public BgApp9Outcome outcomeOf(String clause) {
    for (BgApp9Outcome outcome : outcomes) {
      if (outcome.clause.equals(clause)) {
        return outcome;
      }
    }
    return null;
  }

  /** Колко точки искат някой да поправи нещо. Влиза в съобщението на бутона. */
  public int refusalCount() {
    int count = 0;
    for (BgApp9Outcome outcome : outcomes) {
      if (outcome.isRefusal()) {
        count++;
      }
    }
    return count;
  }

  /** Колко точки са изведени. */
  public int derivedCount() {
    int count = 0;
    for (BgApp9Outcome outcome : outcomes) {
      if (outcome.isDerived()) {
        count++;
      }
    }
    return count;
  }

  /**
   * ВСИЧКИТЕ присъди, слепени в текста, който отива в {@code BgApp9Declaration.resolverNotes}.
   *
   * <p>⚠️ ВСИЧКИТЕ, не само отказите. „Т. 3 не се отнася, защото рискът е общо заболяване“ е
   * отговорът на въпрос, който операторът ще зададе, като види точката празна — а отговор, който го
   * няма, се превръща в дефект, докладван от потребител.
   */
  public String asNotes() {
    StringBuilder sb = new StringBuilder();
    for (BgApp9Outcome outcome : outcomes) {
      if (sb.length() > 0) {
        sb.append('\n');
      }
      sb.append(outcome.asNote());
    }
    return sb.toString();
  }
}
