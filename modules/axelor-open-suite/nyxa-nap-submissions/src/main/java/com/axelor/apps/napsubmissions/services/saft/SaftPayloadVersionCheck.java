package com.axelor.apps.napsubmissions.services.saft;

import java.time.LocalDate;

/**
 * ТОЧКА ЗА РАЗШИРЕНИЕ: ВЕРСИЯТА НА SAF-T ВЪВ ФАЙЛА СРЕЩУ ВЕРСИЯТА ЗА ДНЕС.
 *
 * <p>Версията на SAF-T е по момента на ПОДАВАНЕ (QA „Въпроси и отговори“ на НАП, март 2026 г.,
 * версия 2, II.2.4, с. 13), а Nyxa не знае кога файлът ще бъде качен — затова се сверява при
 * въвеждане на входящия номер ({@code ManualSubmissionService}) и при изтегляне.
 *
 * <p>Този модул не познава схемите на SAF-T, затова по подразбиране не проверява нищо;
 * axelor-bg-localization заменя класа ({@code BgSaftPayloadVersionCheck}).
 */
public class SaftPayloadVersionCheck {

  /**
   * Защо файлът не бива да се подава днес; {@code null}, когато може.
   *
   * @param payload байтовете на SAF-T файла
   * @param today днешната дата (на подаването)
   */
  public String refusal(byte[] payload, LocalDate today) {
    return null;
  }
}
