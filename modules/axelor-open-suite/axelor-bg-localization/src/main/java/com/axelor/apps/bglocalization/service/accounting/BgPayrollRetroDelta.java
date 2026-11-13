package com.axelor.apps.bglocalization.service.accounting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * РЕТРО-СТАТИЯТА: разликата между коригиращия и оригиналния план, сметка по сметка.
 *
 * <p><b>⚠️ ДЕЛТА, А НЕ ПЪЛНИТЕ ЧИСЛА, И РАЗЛИКАТА Е ДВОЙНО БРОЕНЕ.</b> Коригиращата Д1/Д6 носи
 * ПЪЛНИТЕ верни числа за миналия месец, защото декларацията ЗАМЕСТВА подадената. Счетоводството не
 * работи така: статията на оригинала е вече осчетоводена и НЕ изчезва. Проведена с пълните числа,
 * ретро-статията щеше да отчете март ВТОРИ ПЪТ — разход, задължения и данък в двоен размер, при
 * напълно балансирани документи. Затова тук се провежда само онова, което оригиналът НЕ е казал:
 *
 * <pre>
 *   делта(сметка) = (Dr − Cr) в коригиращия − (Dr − Cr) в оригиналния
 * </pre>
 *
 * <p>Сборът от делтите е НУЛА по устройство (и двата плана балансират), тоест ретро-статията също
 * балансира — не по аритметика, а защото е разлика на две балансирани неща.
 *
 * <p><b>⚠️ И ЗАЩО НЕ „сторно на оригинала + пълна нова статия“.</b> Това е другият верен начин и
 * дава същия нетен ефект с два документа. Отхвърлен е по две причини, и двете практически:
 * сторнирането пипа ОСЧЕТОВОДЕН документ в период, който може да е счетоводно затворен (тоест иска
 * права и отваряне на период, каквито изплащането на заплата не бива да иска), а два документа за
 * една корекция правят одитната следа по-дълга, без да казват нищо повече. Делтата е един документ
 * в коригирания период, точно срещу коригиращата декларация за него.
 *
 * <p><b>⚠️ И ЕДНА РАЗЛИКА ВЪВ ВИДА НА ДОКУМЕНТА, КОЯТО НЕ Е ПРОПУСК.</b> Редовната статия НЕ нетира
 * сметка 4211 — тя се появява два пъти, Cr с брутото и Dr с удръжките, защото оборотът ѝ е онова,
 * по което се сверява ведомостта ({@link BgPayrollPosting}). Ретро-статията я нетира до ЕДИН ред
 * със знак, и няма как иначе: разликата на едно салдо е едно число. Оборотът за онзи месец вече
 * стои в статията на оригинала и не се преписва — тук се провежда само това, което се е ПРОМЕНИЛО.
 * Оттам следва, че ретро-статията НЕ може да се сверява с оборотите на оригинала, а само със
 * салдата: „оригинал + делта = верният месец, сметка по сметка“.
 *
 * <p><b>⚠️ ЗАЛИЧАВАНЕТО Е СЪЩАТА АРИТМЕТИКА.</b> При заличаващ фиш „коригиращият план“ е този на
 * заличаващото копие, а числата му са тези на оригинала — тоест делтата излиза нула. НЕ е така:
 * заличаването значи, че записът трябва да ИЗЧЕЗНЕ, тоест верният втори аргумент е ПРАЗЕН план и
 * делтата е минус целият оригинал. Затова услугата подава {@code null} за коригиращия план при
 * заличаване — виж {@link #of}.
 */
public final class BgPayrollRetroDelta {

  private final List<BgPayrollPosting> postings;

  private BgPayrollRetroDelta(List<BgPayrollPosting> postings) {
    this.postings = Collections.unmodifiableList(postings);
  }

  /**
   * Делтата между двата плана.
   *
   * @param corrected верният план за онзи месец, или {@code null} при ЗАЛИЧАВАНЕ (нищо не остава)
   * @param original планът, с който месецът е бил осчетоводен; {@code null} значи „оригиналът не е
   *     осчетоводяван“ и това е състояние, което услугата отказва ПРЕДИ да стигне дотук
   */
  public static BgPayrollRetroDelta of(
      BgPayrollPostingPlan.Plan corrected, BgPayrollPostingPlan.Plan original) {
    Map<String, BigDecimal> net = new LinkedHashMap<>();
    Map<String, BgPayrollPosting> shapes = new LinkedHashMap<>();
    accumulate(net, shapes, corrected, false);
    accumulate(net, shapes, original, true);

    List<String> keys = new ArrayList<>(shapes.keySet());
    Collections.sort(keys);
    List<BgPayrollPosting> out = new ArrayList<>();
    for (String key : keys) {
      BigDecimal value = net.get(key).setScale(2, RoundingMode.HALF_UP);
      if (value.signum() == 0) {
        // ⚠️ НУЛЕВАТА ДЕЛТА НЕ Е РЕД. Корекция, която мести числа между две пера с една и същата
        // сметка, оставя сметката непроменена — ред за нула би твърдял, че нещо се е случило,
        // а Axelor и без това отказва ред с нулев дебит И нулев кредит.
        continue;
      }
      BgPayrollPosting shape = shapes.get(key);
      out.add(
          new BgPayrollPosting(
              shape.getAccountCode(), value.signum() > 0, value.abs(), shape.getAnalytic()));
    }
    return new BgPayrollRetroDelta(out);
  }

  /**
   * Насъбира „(Dr − Cr) по сметка и аналитичност“ от един план.
   *
   * <p>⚠️ ПОСОКАТА СЕ СВИВА ДО ЗНАК ИМЕННО ТУК. Двата плана могат да ползват една сметка от
   * РАЗЛИЧНИ страни — корекция, която превръща доплащане в удръжка, е точно това, — и без общо
   * знаково представяне разликата щеше да се смята между „кредит“ и „дебит“, тоест да се обърка със
   * сума.
   */
  private static void accumulate(
      Map<String, BigDecimal> net,
      Map<String, BgPayrollPosting> shapes,
      BgPayrollPostingPlan.Plan plan,
      boolean negate) {
    if (plan == null) {
      return;
    }
    for (BgPayrollPosting posting : plan.getPostings()) {
      String key =
          posting.getAccountCode()
              + "|"
              + (posting.getAnalytic() == null ? "" : posting.getAnalytic());
      BigDecimal signed = posting.isDebit() ? posting.getAmount() : posting.getAmount().negate();
      if (negate) {
        signed = signed.negate();
      }
      net.merge(key, signed, BigDecimal::add);
      shapes.putIfAbsent(
          key, new BgPayrollPosting(posting.getAccountCode(), true, signed, posting.getAnalytic()));
    }
  }

  /** Редовете на ретро-статията. Празен списък значи, че корекцията не мени нито едно салдо. */
  public List<BgPayrollPosting> getPostings() {
    return postings;
  }

  public boolean isEmpty() {
    return postings.isEmpty();
  }

  public BigDecimal totalDebit() {
    return total(true);
  }

  public BigDecimal totalCredit() {
    return total(false);
  }

  private BigDecimal total(boolean debit) {
    BigDecimal sum = BigDecimal.ZERO;
    for (BgPayrollPosting posting : postings) {
      if (posting.isDebit() == debit) {
        sum = sum.add(posting.getAmount());
      }
    }
    return sum.setScale(2, RoundingMode.HALF_UP);
  }

  /** Балансът е разлика на две балансирани неща — проверката е свидетел, не изчисление. */
  public boolean isBalanced() {
    return totalDebit().compareTo(totalCredit()) == 0;
  }

  /**
   * Салдото на 4211 в ретро-статията СЪС ЗНАК: плюс значи „дължи се още на лицето“.
   *
   * <p>⚠️ ТОВА Е МОСТЪТ. Нетната делта излиза с ТЕКУЩОТО нето (перо 620 в текущия фиш), а
   * осчетоводяването ѝ стои в КОРИГИРАНИЯ период. Двете се срещат единствено в 4211: кредитното
   * салдо, което ретро-статията оставя там, се закрива от превода, направен по текущата ведомост.
   * Ако това число не е равно на перото 620 в текущия фиш, някъде има двойно броене — и точно това
   * сверява ретро-тестът.
   */
  public BigDecimal personnelBridge() {
    BigDecimal sum = BigDecimal.ZERO;
    for (BgPayrollPosting posting : postings) {
      if (BgPayrollAccount.PERSONNEL_NET_WAGE.equals(posting.getAccountCode())) {
        sum = posting.isDebit() ? sum.subtract(posting.getAmount()) : sum.add(posting.getAmount());
      }
    }
    return sum.setScale(2, RoundingMode.HALF_UP);
  }
}
