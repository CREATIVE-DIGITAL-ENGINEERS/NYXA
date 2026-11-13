package com.axelor.apps.bglocalization.service.accounting;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.move.MoveValidateServiceImpl;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.google.inject.matcher.AbstractMatcher;
import com.google.inject.matcher.Matcher;
import com.google.inject.matcher.Matchers;
import java.lang.reflect.Method;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

/**
 * „Валидирана от“ ({@code Move.bgValidatedByUser}) — при ВСЯКО валидиране на статия: автоматичното
 * на ИЗП → потребителят за автоматично осчетоводяване (задачата работи под неговата идентичност),
 * ръчното → човекът. Заварените статии остават празни.
 *
 * <p>Прихваща се {@code updateValidateStatus(Move, boolean)} — мястото, където Axelor сменя статуса
 * на DAYBOOK/ACCOUNTED, още в транзакцията на валидирането и преди записа. Прихващане, а не
 * наследяване: услугата има наследници в budget/HR, които този модул не вижда всички.
 *
 * <p>В Nyxa всяко дружество е БГ — модулът няма друг предикат „БГ дружество“, затова пазачът важи
 * за всички.
 */
public class BgMoveValidatedByGuard implements MethodInterceptor {

  static final String METHOD = "updateValidateStatus";

  public static Matcher<Class> classes() {
    return Matchers.subclassesOf(MoveValidateServiceImpl.class);
  }

  public static Matcher<Method> methods() {
    return new AbstractMatcher<Method>() {
      @Override
      public boolean matches(Method m) {
        return !m.isSynthetic()
            && !m.isBridge()
            && METHOD.equals(m.getName())
            && m.getParameterCount() == 2
            && Move.class.equals(m.getParameterTypes()[0]);
      }
    };
  }

  @Override
  public Object invoke(MethodInvocation invocation) throws Throwable {
    Object result = invocation.proceed();
    stamp((Move) invocation.getArguments()[0], AuthUtils.getUser());
    return result;
  }

  /** Записва потребителя, щом статията е валидирана; без потребител — нищо (не „някой“). */
  static void stamp(Move move, User user) {
    if (move == null || user == null) {
      return;
    }
    int status = move.getStatusSelect();
    if (status == MoveRepository.STATUS_ACCOUNTED || status == MoveRepository.STATUS_DAYBOOK) {
      move.setBgValidatedByUser(user);
    }
  }
}
