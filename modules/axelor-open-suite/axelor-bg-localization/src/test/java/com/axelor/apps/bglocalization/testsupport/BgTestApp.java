package com.axelor.apps.bglocalization.testsupport;

import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.studio.db.AppBase;
import com.axelor.studio.db.repo.AppBaseRepository;

/**
 * Настройката на приложението, която всяка инсталация има, а празната тестова схема — не.
 *
 * <p>⚠️ Номерът от редицата на Axelor ({@code SequenceService#getSequenceNumber}) чете {@code
 * AppBase.checkExistingSequenceOnGeneration} без проверка за {@code null}, а всеки нов фиш минава
 * оттам. Вика се в транзакция.
 *
 * <p>⚠️ {@code checkExistingSequenceOnGeneration = false} — САМО В ТЕСТОВАТА СХЕМА. Ново дружество
 * през хранилището ражда и своя партньор с номер от редицата ({@code
 * BgCompanyManagementRepository#createOwnPartner}); проверката за вече даден номер чете Partner с
 * формулните му колони, а HSQLDB не изпълнява формулата за DSO („incompatible data types in
 * combination“). В production проверката си стои включена. Повторът на ТРЗ номер свидетелите го
 * мерят от ключа в базата {@code (company, reference)}, не от тази проверка.
 */
public final class BgTestApp {

  private BgTestApp() {}

  public static void ensureAppBase() {
    if (Beans.get(AppBaseRepository.class).all().fetchOne() == null) {
      AppBase appBase = new AppBase();
      appBase.setCheckExistingSequenceOnGeneration(false); // HSQLDB не чете Partner
      JPA.save(appBase);
    }
  }
}
