package com.axelor.apps.napsubmissions.common.signing.evrotrust;

import com.axelor.apps.napsubmissions.common.signing.CloudQesProvider;
import com.axelor.apps.napsubmissions.common.signing.CloudQesProviderIds;
import com.axelor.apps.napsubmissions.common.signing.UnconfiguredCloudQesProvider;
import com.axelor.inject.Beans;

/**
 * EVROTRUST БЕЗ API ИНТЕГРАЦИЯ: подписване в портала на доставчика и качване на подписаните
 * файлове.
 *
 * <p>Докато сървърът няма данни за API потока на Evrotrust (vendor номер, API ключ, нашата ключова
 * двойка), изборът „Evrotrust“ не отказва, а води към портала: файловете се свалят, подписват се в
 * {@value #PORTAL_URL} (отваря се в нов таб) и подписаните се качват обратно. Конфигурира ли се API
 * потокът, той се ползва както досега.
 */
public final class EvrotrustPortal {

  /** Порталът, в който притежателят на облачен КЕП подписва файл. */
  public static final String PORTAL_URL = "https://portal.evrotrust.com";

  /** Поток „API“ (асинхронен, през сървъра) или „портал“ (ръчно подписване и качване). */
  public static final String FLOW_API = "api";

  public static final String FLOW_PORTAL = "portal";

  private EvrotrustPortal() {}

  /** Кой поток ползва изборът „Evrotrust“ на тази инсталация. */
  public static String flow() {
    return apiFlowConfigured() ? FLOW_API : FLOW_PORTAL;
  }

  /** Пълна ли е конфигурацията на API потока (доставчик + нашата ключова двойка). */
  public static boolean apiFlowConfigured() {
    String providerId = Beans.get(CloudQesProvider.class).getProviderId();
    if (providerId == null
        || providerId.isBlank()
        || UnconfiguredCloudQesProvider.PROVIDER_ID.equals(providerId)) {
      return false;
    }
    return !CloudQesProviderIds.EVROTRUST.equals(providerId)
        || Beans.get(EvrotrustConfig.class).isOnlineFlowConfigured();
  }
}
