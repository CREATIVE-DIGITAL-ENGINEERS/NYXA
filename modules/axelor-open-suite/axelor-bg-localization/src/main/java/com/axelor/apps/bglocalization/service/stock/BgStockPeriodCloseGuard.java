package com.axelor.apps.bglocalization.service.stock;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.base.service.PeriodServiceImpl;
import com.axelor.apps.bglocalization.db.repo.BgStockValuationTaskRepository;
import com.axelor.db.JPA;
import com.google.inject.matcher.AbstractMatcher;
import com.google.inject.matcher.Matcher;
import com.google.inject.matcher.Matchers;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

/**
 * ИЗП, решение 4: фискалният период не се затваря (нито временно), докато в него има неосчетоводени
 * изписвания — задачи „чака оценка“ или с ГРЕШКА, чиято дата на документа пада в периода.
 *
 * <p>Прихващане, а не наследяване — по същия довод като {@link BgStockMoveGuard}: услугата на
 * периода има наследници в account и надолу, които този модул не вижда всички. Хващат се {@code
 * close}, {@code closeTemporarily}, {@code validateClosure} и {@code validateTempClosure} с първи
 * аргумент {@link Period}.
 *
 * <p>⚠️ Това НЕ е второ мнение за статуса на периода (BgPaySlipLockedPeriodTest): пазачът не пита
 * дали периодът е отворен, а дали изписванията в него са осчетоводени.
 */
public class BgStockPeriodCloseGuard implements MethodInterceptor {

  static final Set<String> CLOSE_METHODS =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  "close", "closeTemporarily", "validateClosure", "validateTempClosure")));

  public static Matcher<Class> classes() {
    return Matchers.subclassesOf(PeriodServiceImpl.class);
  }

  public static Matcher<Method> methods() {
    return new AbstractMatcher<Method>() {
      @Override
      public boolean matches(Method m) {
        return !m.isSynthetic()
            && !m.isBridge()
            && CLOSE_METHODS.contains(m.getName())
            && m.getParameterCount() == 1
            && Period.class.equals(m.getParameterTypes()[0]);
      }
    };
  }

  @Override
  public Object invoke(MethodInvocation invocation) throws Throwable {
    Object[] args = invocation.getArguments();
    if (args.length == 1 && args[0] instanceof Period) {
      String refusal = refusal((Period) args[0]);
      if (refusal != null) {
        throw new AxelorException(
            (Period) args[0], TraceBackRepository.CATEGORY_INCONSISTENCY, refusal);
      }
    }
    return invocation.proceed();
  }

  /** Отказът за периода, или {@code null}. Само фискален период с дати и дружество. */
  protected String refusal(Period period) {
    Year year = period.getYear();
    if (year == null
        || year.getTypeSelect() != YearRepository.TYPE_FISCAL
        || year.getCompany() == null
        || period.getFromDate() == null
        || period.getToDate() == null) {
      return null;
    }
    List<String> moves = unvalued(period);
    return refusal(period.getName(), moves);
  }

  /** Текстът на отказа: до пет номера, после броят. Чиста функция. */
  static String refusal(String periodName, List<String> moves) {
    if (moves == null || moves.isEmpty()) {
      return null;
    }
    List<String> shown = moves.subList(0, Math.min(5, moves.size()));
    return String.format(
        "Периодът „%s“ има %d неосчетоводени изписвания на материални запаси (%s%s). Обработете"
            + " ги от списъка „Неосчетоводени изписвания“, преди да затворите периода.",
        periodName,
        moves.size(),
        String.join(", ", shown),
        moves.size() > shown.size() ? ", …" : "");
  }

  protected List<String> unvalued(Period period) {
    return JPA.em()
        .createQuery(
            "SELECT t.stockMove.stockMoveSeq FROM BgStockValuationTask t"
                + " WHERE t.company = :company AND t.statusSelect IN (:pending, :error)"
                + " AND t.documentDate >= :from AND t.documentDate <= :to ORDER BY t.id",
            String.class)
        .setParameter("company", period.getYear().getCompany())
        .setParameter("pending", BgStockValuationTaskRepository.STATUS_PENDING)
        .setParameter("error", BgStockValuationTaskRepository.STATUS_ERROR)
        .setParameter("from", period.getFromDate())
        .setParameter("to", period.getToDate())
        .getResultList();
  }
}
