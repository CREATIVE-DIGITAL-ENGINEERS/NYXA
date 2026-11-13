package com.axelor.apps.bglocalization.service.stock;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.base.service.PeriodService;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.stock.db.StockCorrection;
import com.axelor.apps.stock.db.StockCorrectionReason;
import com.axelor.apps.stock.db.StockMove;
import com.axelor.apps.stock.db.repo.StockMoveRepository;
import com.axelor.apps.stock.service.StockMoveServiceImpl;
import com.axelor.inject.Beans;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.google.inject.matcher.AbstractMatcher;
import com.google.inject.matcher.Matcher;
import com.google.inject.matcher.Matchers;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * БГ правилата върху отказа и реализирането на складово движение — Guice прихващане, а не
 * наследяване.
 *
 * <p><b>⚠️ ЗАЩО ПРИХВАЩАНЕ.</b> Веригата на услугата е {@code StockMoveServiceImpl →
 * StockMoveServiceSupplychainImpl → StockMoveServiceProductionImpl}, а този модул не вижда
 * axelor-production. Наследник на supplychain, вързан оттук, би загубил реда спрямо production.
 * Прихващането важи за всеки наследник на {@link StockMoveServiceImpl}, който Guice строи — и за
 * този на production, без да го познава.
 *
 * <p><b>Точките.</b> Отказът се хваща на {@code cancel}, {@code cancelFromManufOrder} и {@code
 * setCancelStatus}. Последната е задължителна: пътят през производствена поръчка вика {@code
 * super.cancel(...)}, което минава покрай прихванатия публичен метод, но {@code setCancelStatus} се
 * вика виртуално и се хваща винаги. Реализирането се хваща на {@code realizeStockMove} — през него
 * минават UI, REST, корекцията и инвентаризацията.
 *
 * <p><b>След реализирането</b> се викат обработчиците на {@link BgStockMoveRealizedHandler} — така
 * ИЗП закача осчетоводяването на изписването на същото място, без свой прихващач.
 */
public class BgStockMoveGuard implements MethodInterceptor {

  private static final Logger LOG = LoggerFactory.getLogger(BgStockMoveGuard.class);

  static final Set<String> CANCEL_METHODS =
      Collections.unmodifiableSet(
          new HashSet<>(Arrays.asList("cancel", "cancelFromManufOrder", "setCancelStatus")));

  static final String REALIZE_METHOD = "realizeStockMove";

  static final String CANCEL_REFUSAL =
      "Реализиран складов документ не се отказва. Отказът би презаписал датата му на"
          + " реализиране с днешната и SAF-T би загубил документа от периода, в който е"
          + " реализиран. Направете обратен складов документ (бутон „Генериране на обратен"
          + " складов документ“).";

  /** Класовете: всеки наследник на {@link StockMoveServiceImpl}, включително този на production. */
  public static Matcher<Class> classes() {
    return Matchers.subclassesOf(StockMoveServiceImpl.class);
  }

  /** Методите: отказ и реализиране с първи аргумент {@link StockMove}. */
  public static Matcher<Method> methods() {
    return new AbstractMatcher<Method>() {
      @Override
      public boolean matches(Method m) {
        return !m.isSynthetic()
            && !m.isBridge()
            && (CANCEL_METHODS.contains(m.getName()) || REALIZE_METHOD.equals(m.getName()))
            && m.getParameterCount() > 0
            && StockMove.class.equals(m.getParameterTypes()[0]);
      }
    };
  }

  @Override
  public Object invoke(MethodInvocation invocation) throws Throwable {
    Object[] args = invocation.getArguments();
    if (args.length > 0 && args[0] instanceof StockMove) {
      StockMove move = (StockMove) args[0];
      String name = invocation.getMethod().getName();
      if (CANCEL_METHODS.contains(name)) {
        refuseCancelOfRealized(move);
      } else if (REALIZE_METHOD.equals(name)) {
        beforeRealize(move);
        Object result = invocation.proceed();
        afterRealize(move, handlers());
        return result;
      }
    }
    return invocation.proceed();
  }

  /**
   * Обработчиците след реализиране, закачени през Multibinder — виж {@link
   * BgStockMoveRealizedHandler}.
   */
  static final Key<Set<BgStockMoveRealizedHandler>> HANDLERS =
      Key.get(new TypeLiteral<Set<BgStockMoveRealizedHandler>>() {});

  private static Collection<BgStockMoveRealizedHandler> handlers() {
    return Beans.get(Injector.class).getInstance(HANDLERS);
  }

  /**
   * Вика обработчиците, само ако движението наистина е реализирано. Грешката на обработчик се
   * записва в лога и не спира нито другите обработчици, нито реализирането.
   */
  static void afterRealize(StockMove move, Collection<BgStockMoveRealizedHandler> handlers) {
    if (move.getStatusSelect() != StockMoveRepository.STATUS_REALIZED) {
      return;
    }
    for (BgStockMoveRealizedHandler handler : handlers) {
      try {
        handler.afterRealize(move);
      } catch (Exception e) {
        LOG.error(
            "Stock move {} is realized, but the after-realize handler {} failed.",
            move.getStockMoveSeq(),
            handler.getClass().getName(),
            e);
      }
    }
  }

  static void refuseCancelOfRealized(StockMove move) throws AxelorException {
    if (move.getStatusSelect() == StockMoveRepository.STATUS_REALIZED) {
      throw new AxelorException(move, TraceBackRepository.CATEGORY_INCONSISTENCY, CANCEL_REFUSAL);
    }
  }

  /**
   * Преди реализиране: кодът по „Stock movement“ съвпада с посоката, а датата на документа е
   * попълнена (празна → днес) и допустима. Движение, което не е планирано, се пропуска — Axelor сам
   * го отказва със свое съобщение.
   */
  static void beforeRealize(StockMove move) throws AxelorException {
    if (move.getStatusSelect() != StockMoveRepository.STATUS_PLANNED) {
      return;
    }
    String partnerRefusal = requirePartner(move);
    if (partnerRefusal != null) {
      throw new AxelorException(move, TraceBackRepository.CATEGORY_INCONSISTENCY, partnerRefusal);
    }
    String codeRefusal =
        BgStockMovementCodes.refusal(movementCode(move), BgStockMovementCodes.directionOf(move));
    if (codeRefusal != null) {
      throw new AxelorException(move, TraceBackRepository.CATEGORY_INCONSISTENCY, codeRefusal);
    }

    Company company = move.getCompany();
    LocalDate today = Beans.get(AppBaseService.class).getTodayDate(company);
    if (move.getBgDocumentDate() == null) {
      move.setBgDocumentDate(today);
    }
    String dateRefusal =
        BgStockMoveDates.refusal(
            move.getBgDocumentDate(), today, periodState(move.getBgDocumentDate(), company));
    if (dateRefusal != null) {
      throw new AxelorException(move, TraceBackRepository.CATEGORY_INCONSISTENCY, dateRefusal);
    }
  }

  static final String PARTNER_REFUSAL =
      "Причината „%s“ изисква виновно лице. Посочете го в корекцията (поле „Виновно лице“) —"
          + " стойността на липсата е вземане от него.";

  /**
   * Причина с {@code bgRequiresPartner} иска лице на движението. Корекцията го носи в {@code
   * bgResponsiblePartner}, а движението, което тя генерира, го получава оттук в {@code partner}.
   * Връща отказа или {@code null}.
   */
  static String requirePartner(StockMove move) {
    StockCorrectionReason reason = move.getStockCorrectionReason();
    if (reason == null || !Boolean.TRUE.equals(reason.getBgRequiresPartner())) {
      return null;
    }
    StockCorrection correction = move.getStockCorrection();
    if (move.getPartner() == null && correction != null) {
      move.setPartner(correction.getBgResponsiblePartner());
    }
    return move.getPartner() == null ? String.format(PARTNER_REFUSAL, reason.getName()) : null;
  }

  /** Ръчният код бие кода на причината за корекция. */
  static String movementCode(StockMove move) {
    String manual = move.getBgSaftMovementCode();
    if (manual != null && !manual.trim().isEmpty()) {
      return manual.trim();
    }
    return move.getStockCorrectionReason() == null
        ? null
        : move.getStockCorrectionReason().getBgStockMovementCode();
  }

  /**
   * Състоянието на ФИСКАЛНИЯ период за датата — единственият предикат на модула за отворен период
   * (заедно с BgPaySlipMoveService), а не трета логика. Ползва го и протоколът по чл. 117 от
   * фактура ({@code BgVatInvoiceProtocolService.isClosed}).
   */
  public static BgStockMoveDates.PeriodState periodState(LocalDate date, Company company)
      throws AxelorException {
    PeriodService periods = Beans.get(PeriodService.class);
    Period period = periods.getPeriod(date, company, YearRepository.TYPE_FISCAL);
    if (period == null) {
      return BgStockMoveDates.PeriodState.MISSING;
    }
    // „Временно затворен“ и „в затваряне“ се отказват за всички — иначе изписването пише в
    // период, който се затваря. Същият предикат като при фиша, не трета логика за периода.
    if (BgPaySlipResolver.isClosingPeriod(period)) {
      return BgStockMoveDates.PeriodState.CLOSING;
    }
    return periods.isClosedPeriod(period)
        ? BgStockMoveDates.PeriodState.CLOSED
        : BgStockMoveDates.PeriodState.OPEN;
  }
}
