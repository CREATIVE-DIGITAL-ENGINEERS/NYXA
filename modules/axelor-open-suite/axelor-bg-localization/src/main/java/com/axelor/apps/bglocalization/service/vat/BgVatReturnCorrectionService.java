package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.EventsPlanning;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.base.service.publicHoliday.PublicHolidayService;
import com.axelor.apps.bglocalization.db.BgVatReturn;
import com.google.inject.Inject;
import java.util.List;

/**
 * Правилата на ДДС декларацията, които контролерът само вика: пазачът на „Генерирай“ по статус и
 * предупреждението на „Създай корекция“ след срока.
 */
public class BgVatReturnCorrectionService {

  /**
   * Статусите, от които „Генерирай“ върви: чернова, нова коригираща ({@code corrected} е само
   * началният ѝ статус) и генерирана — наново. Подадената, приетата и отхвърлената не се пипат.
   */
  static final List<String> GENERATABLE_STATUSES = List.of("draft", "corrected", "generated");

  protected final AppBaseService appBaseService;
  protected final PublicHolidayService publicHolidayService;

  @Inject
  public BgVatReturnCorrectionService(
      AppBaseService appBaseService, PublicHolidayService publicHolidayService) {
    this.appBaseService = appBaseService;
    this.publicHolidayService = publicHolidayService;
  }

  /**
   * Защо декларацията не се генерира по статуса си; {@code null} — генерира се. Без статус е нов
   * запис (чернова). Пазачът е тук, а не само в {@code showIf} на бутона, за да не мине и път извън
   * формата.
   */
  public String generateRefusal(String status) {
    if (status == null || GENERATABLE_STATUSES.contains(status)) {
      return null;
    }
    return "Подадена, приета или отхвърлена декларация не се генерира наново — файлът, който е"
        + " стигнал до НАП, остава какъвто е. За поправка натиснете „Създай корекция“.";
  }

  /**
   * Предупреждението при корекция от {@code source} ({@link BgVatCorrectionDeadline}); {@code null}
   * — няма. Празниците са от календара на дружеството ({@code
   * Company.publicHolidayEventsPlanning}); без календар срокът се мести само през събота и неделя.
   */
  public String correctionWarning(BgVatReturn source) {
    Company company = source.getCompany();
    EventsPlanning holidays = company == null ? null : company.getPublicHolidayEventsPlanning();
    return BgVatCorrectionDeadline.warning(
        source.getStatusSelect(),
        source.getPeriod(),
        appBaseService.getTodayDate(company),
        day -> holidays != null && publicHolidayService.checkPublicHolidayDay(day, holidays));
  }
}
