package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.google.inject.persist.Transactional;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * „ОТРАЗИ ПРИСТИГНАЛ БОЛНИЧЕН“ Е ЕДНА ТРАНЗАКЦИЯ — евтиният пазач, който CI наистина пуска.
 *
 * <p><b>Защо отделно от поведенческия свидетел.</b> {@code BgSickNoteArrivalAtomicityTest} минава
 * през истинския контролер и доказва rollback-а, но е {@code @Tag("integration")} и {@code
 * ./gradlew build} го изключва. Тук се пази само предпоставката му: махне ли някой анотацията, CI
 * пада, вместо да остави отменена заявка без коригиращо копие.
 *
 * <p>⚠️ Проверява се и това, без което Guice AOP мълчи: публичен, не-final метод на не-final клас.
 */
class BgSickNoteArrivalTransactionGuardTest {

  @Test
  @DisplayName("⚠️ apply е @Transactional с rollbackOn = Exception — всичко или нищо")
  void applyIsOneTransactionThatRollsBackOnAnyException() throws Exception {
    Method apply = BgSickNoteArrivalService.class.getMethod("apply", BgSickLeave.class);

    Transactional transactional = apply.getAnnotation(Transactional.class);
    assertNotNull(
        transactional,
        "⚠️ БЕЗ АНОТАЦИЯТА ДЕЙСТВИЕТО НЕ Е АТОМАРНО. Axelor не отваря транзакция за action-method:"
            + " първият save хвърля „no transaction is in progress“, а следата в контролера"
            + " commit-ва вече отменената заявка без копие.");
    assertTrue(
        Arrays.asList(transactional.rollbackOn()).contains(Exception.class),
        "⚠️ rollbackOn трябва да съдържа Exception, както при correct() и confirm(): по"
            + " подразбиране guice-persist връща само при RuntimeException, а при проверимо"
            + " изключение COMMIT-ва — тоест бъдещ throws в подписа би превърнал провала в запис.");

    assertTrue(
        Modifier.isPublic(apply.getModifiers()) && !Modifier.isFinal(apply.getModifiers()),
        "Guice прихваща само публичен, не-final метод");
    assertTrue(
        !Modifier.isFinal(BgSickNoteArrivalService.class.getModifiers()),
        "и само клас, който може да бъде наследен от прокси-то");
  }
}
