package com.axelor.apps.bglocalization.service.demo;

import com.axelor.db.JPA;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Една стъпка = една транзакция, както едно натискане на бутон на екрана. Провал връща само
 * текущата стъпка; изключението стига до наливането с причината си.
 */
final class BgDemoTx {

  interface Work {
    void run() throws Exception;
  }

  private BgDemoTx() {}

  static void run(Work work) throws Exception {
    call(
        () -> {
          work.run();
          return null;
        });
  }

  static <T> T call(Callable<T> work) throws Exception {
    AtomicReference<T> out = new AtomicReference<>();
    AtomicReference<Exception> failure = new AtomicReference<>();
    try {
      JPA.runInTransaction(
          () -> {
            try {
              out.set(work.call());
            } catch (RuntimeException e) {
              throw e;
            } catch (Exception e) {
              failure.set(e);
              throw new IllegalStateException(e);
            }
          });
    } catch (IllegalStateException e) {
      if (failure.get() != null) {
        throw failure.get();
      }
      throw e;
    }
    return out.get();
  }
}
