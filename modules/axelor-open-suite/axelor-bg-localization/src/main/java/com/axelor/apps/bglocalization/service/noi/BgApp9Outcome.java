package com.axelor.apps.bglocalization.service.noi;

/**
 * ЕДНА ПРИСЪДА ЗА ЕДНА ТОЧКА ОТ ПРИЛОЖЕНИЕ № 9 — стойност ИЛИ обявена причина да няма стойност.
 *
 * <p>Огледален на {@link com.axelor.apps.bglocalization.service.payroll.BgSickLeaveOutcome} и по
 * същия довод: четирите изхода НЕ са едно и също нещо. „Т. 2 е изпълнена, защото стажът е 12 ≥ 6“,
 * „т. 3 не се отнася, защото рискът е общо заболяване“, „т. 11 е входна точка“ и „т. 4 не мога,
 * защото кодът за вид осигурен е празен на договора“ са ЧЕТИРИ РАЗЛИЧНИ факта. Свити до празно
 * поле, и четирите стават едно — а празно поле в удостоверение пред НОИ значи „обстоятелството не е
 * налице“, тоест точно твърдението, което никой не е проверявал.
 *
 * <p><b>⚠️ ЗАЩО ТОЗИ КЛАС ИЗОБЩО СЪЩЕСТВУВА.</b> Присъдите не се произвеждат, за да бъдат
 * изхвърлени: правило, което отказва точно както трябва, е безполезно, ако повикващият изхвърли
 * върнатия {@code List<Outcome>} и никой не научи за отказа. Тук изходът е ЗАДЪЛЖИТЕЛНО консумиран:
 * {@link BgApp9DeclarationService#resolveInto} записва ВСЯКА присъда в {@code
 * BgApp9Declaration.resolverNotes} и полето стои на формата на болничния, до точката, останала
 * празна заради него. Генераторите на фиша ({@code BgEarningsService}, {@code
 * BgCompensationLineService}, {@code BgContributionLineService} и {@code BgSickLeaveService}) имат
 * същото — {@code BgPaySlipVerdicts} и полетата {@code BgPaySlip.bgGeneratorNotes} / {@code
 * bgGeneratorGaps}.
 *
 * <p>Обектът е НЕИЗМЕНЯЕМ и не пази нищо от базата.
 */
public final class BgApp9Outcome {

  /** Четирите изхода. Виж класовия javadoc за това защо не се сливат. */
  public enum Status {
    /** Точката е ИЗВЕДЕНА. {@link #value} носи стойността, {@link #witness} — защо е тя. */
    DERIVED,

    /**
     * Точката НЕ СЕ ОТНАСЯ за този лист — и това е отговор, не липса. Т. 3 при болничен за общо
     * заболяване, т. 13–23 при лист, който не е за майчинство след 42-рия ден, т. 25 при
     * самоосигуряващо се лице. Празна е ЗАКОННО, и {@link #witness} казва по силата на какво.
     */
    NOT_APPLICABLE,

    /**
     * Точката е ВХОДНА — резолверът няма право да я пълни. Т. 10 (заповед за трудоустрояване), т.
     * 11 (анкетен лист на НОИ), т. 12 (заповед за отпуск), т. 14–23 (факти от чужди регистри), т.
     * 24. {@link #witness} казва защо не е изводима, а при т. 8 и т. 12 носи и ПРЕДЛОЖЕНИЕ.
     *
     * <p>⚠️ ПРЕДЛОЖЕНИЕТО НЕ Е СТОЙНОСТ и никога не се записва в полето: припокриване по календар
     * не е „разрешен отпуск по заповед“, а точката удостоверява заповедта.
     */
    MANUAL,

    /**
     * Резолверът ТРЯБВАШЕ да изведе точката и НЕ МОЖА. {@link #witness} казва какво липсва и къде
     * се попълва. Адресат: операторът, не бъдещата фаза.
     *
     * <p>⚠️ Не се слива с {@link #NOT_APPLICABLE}, колкото и еднакво да изглеждат отвън — и при
     * двете полето остава празно. Първото значи „празно е верният отговор“; второто — „поправи
     * нещо, инак удостоверението тръгва с недекларирано обстоятелство“.
     */
    REFUSED
  }

  /** Номерът на точката, както е на образеца: „т. 2“, „т. 10.1“, „т. 14–23“. Никога празно. */
  public final String clause;

  /** Кой от четирите изхода. Никога {@code null}. */
  public final Status status;

  /**
   * Изведената стойност, като текст за човек — САМО при {@link Status#DERIVED}.
   *
   * <p>{@code null} при останалите три, и това е нарочно: поле, което при отказ носи празен низ,
   * рано или късно се записва от някого като стойност.
   */
  public final String value;

  /** ЦЯЛО ИЗРЕЧЕНИЕ за човек — присъдата. Никога празно, включително при {@link Status#DERIVED}. */
  public final String witness;

  private BgApp9Outcome(String clause, Status status, String value, String witness) {
    this.clause = clause;
    this.status = status;
    this.value = value;
    this.witness = witness;
  }

  /** Изведено: стойността плюс изречението, което я обяснява. */
  public static BgApp9Outcome derived(String clause, String value, String witness) {
    return new BgApp9Outcome(clause, Status.DERIVED, value, witness);
  }

  /** Точката не се отнася за този лист. Празна е законно. */
  public static BgApp9Outcome notApplicable(String clause, String witness) {
    return new BgApp9Outcome(clause, Status.NOT_APPLICABLE, null, witness);
  }

  /** Точката е входна. При т. 8 и т. 12 свидетелят носи и предложение. */
  public static BgApp9Outcome manual(String clause, String witness) {
    return new BgApp9Outcome(clause, Status.MANUAL, null, witness);
  }

  /** Трябваше да се изведе и не можа. {@code witness} казва какво липсва. */
  public static BgApp9Outcome refused(String clause, String witness) {
    return new BgApp9Outcome(clause, Status.REFUSED, null, witness);
  }

  /** Иска ли тази присъда някой да поправи нещо. */
  public boolean isRefusal() {
    return status == Status.REFUSED;
  }

  /** Има ли изведена стойност. */
  public boolean isDerived() {
    return status == Status.DERIVED;
  }

  /**
   * РЕДЪТ, КОЙТО ВЛИЗА В {@code resolverNotes} — това е форматът, който операторът чете.
   *
   * <p>Води с маркер, не с думата „статус“: при двайсет и няколко реда окото търси отказите, а не
   * чете подред.
   */
  public String asNote() {
    StringBuilder sb = new StringBuilder();
    sb.append(marker()).append(' ').append(clause).append(" · ");
    if (value != null) {
      sb.append(value).append(" — ");
    }
    sb.append(witness);
    return sb.toString();
  }

  /** Маркерът пред реда. ⚠️ Отказът е единственият с удивителна — той иска действие. */
  private String marker() {
    switch (status) {
      case DERIVED:
        return "[изведено]";
      case NOT_APPLICABLE:
        return "[не се отнася]";
      case MANUAL:
        return "[ръчно]";
      default:
        return "[! ОТКАЗ]";
    }
  }

  @Override
  public String toString() {
    return asNote();
  }
}
