package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.service.invoice.workflow.cancel.WorkflowCancelServiceImpl;
import com.axelor.inject.Beans;
import com.google.inject.matcher.AbstractMatcher;
import com.google.inject.matcher.Matcher;
import com.google.inject.matcher.Matchers;
import java.lang.reflect.Method;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

/**
 * Авансова фактура с ДДС статия не се анулира: издадена е и ДДС-ът ѝ е начислен — поправя се с
 * кредитно известие (ЗДДС чл. 115). Отказът е в {@link BgVatAdvanceService#refuseCancel}.
 *
 * <p>Прихваща се {@code afterCancel(Invoice)} — вика се от {@code InvoiceServiceImpl.cancel} в
 * транзакцията на анулирането, и отказът я връща назад. Прихващане, а не наследяване: веригата
 * {@code WorkflowCancelServiceImpl} → {@code …SupplychainImpl} свършва в модул, който този не вижда
 * (същият довод като при {@code BgMoveValidatedByGuard}); а {@code CancelState} има конструктор,
 * видим само в пакета си.
 */
public class BgVatAdvanceCancelGuard implements MethodInterceptor {

  static final String METHOD = "afterCancel";

  public static Matcher<Class> classes() {
    return Matchers.subclassesOf(WorkflowCancelServiceImpl.class);
  }

  public static Matcher<Method> methods() {
    return new AbstractMatcher<Method>() {
      @Override
      public boolean matches(Method m) {
        return !m.isSynthetic()
            && !m.isBridge()
            && METHOD.equals(m.getName())
            && m.getParameterCount() == 1
            && Invoice.class.equals(m.getParameterTypes()[0]);
      }
    };
  }

  @Override
  public Object invoke(MethodInvocation invocation) throws Throwable {
    Beans.get(BgVatAdvanceService.class).refuseCancel((Invoice) invocation.getArguments()[0]);
    return invocation.proceed();
  }
}
