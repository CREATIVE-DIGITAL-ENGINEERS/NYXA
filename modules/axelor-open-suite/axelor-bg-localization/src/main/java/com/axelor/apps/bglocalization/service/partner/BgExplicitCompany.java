package com.axelor.apps.bglocalization.service.partner;

import com.axelor.apps.base.db.Company;
import java.util.concurrent.Callable;

/**
 * ИЗРИЧНОТО ДРУЖЕСТВО НА КОНТЕКСТА — за пътищата без потребител (внос, AI, задачи).
 *
 * <p>Там няма активно дружество, от което да се вземе чий е новият запис. Кодът, който знае
 * дружеството (качването на OCR, вносът на Microinvest, задачата по дружество), го обявява тук за
 * времето на работата си:
 *
 * <pre>{@code
 * BgExplicitCompany.call(company, () -> supplierService.create(...));
 * }</pre>
 *
 * <p>Обявеното важи само в същата нишка и само до края на блока; вложен блок временно го подменя и
 * после връща предишното. Изричното е по-силно от активното дружество на потребителя: ако
 * потребител пусне внос за дружество Б, докато е активен в А, записите са на Б.
 *
 * <p>Засега го чете само партньорът ({@link BgPartnerCompany}). Общият контекст на дружеството
 * (изолацията) ще го поеме; тогава този клас става тънка обвивка върху него.
 */
public final class BgExplicitCompany {

  private static final ThreadLocal<Company> CURRENT = new ThreadLocal<>();

  private BgExplicitCompany() {}

  /** Обявеното дружество; {@code null}, ако никой не е обявил. */
  public static Company current() {
    return CURRENT.get();
  }

  /** Изпълнява {@code work} с {@code company} като изрично дружество и връща резултата му. */
  public static <T> T call(Company company, Callable<T> work) throws Exception {
    Company previous = CURRENT.get();
    CURRENT.set(company);
    try {
      return work.call();
    } finally {
      restore(previous);
    }
  }

  /** Като {@link #call}, без резултат и без проверими изключения. */
  public static void run(Company company, Runnable work) {
    Company previous = CURRENT.get();
    CURRENT.set(company);
    try {
      work.run();
    } finally {
      restore(previous);
    }
  }

  private static void restore(Company previous) {
    if (previous == null) {
      CURRENT.remove();
    } else {
      CURRENT.set(previous);
    }
  }
}
