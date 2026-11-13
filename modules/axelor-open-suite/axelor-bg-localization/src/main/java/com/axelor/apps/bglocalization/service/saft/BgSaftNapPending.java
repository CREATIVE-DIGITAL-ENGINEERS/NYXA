package com.axelor.apps.bglocalization.service.saft;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ДОКУМЕНТИТЕ, ЧИЙТО КОД ЧАКА НАП — ГРЕШКА ПО ВСИЧКИ ПЪТИЩА, ВКЛЮЧИТЕЛНО ДНЕВНИКА.
 *
 * <p><b>⚠️ ЕДИН СПИСЪК ЗА ТРИТЕ ПЪТЯ</b> (решение 6 от 14.10.2026). Фактурата ({@link
 * BgSaftInvoiceTaxRouter}), протоколът ({@link BgSaftDiaryCells}) и редът в дневника ({@link
 * BgSaftGlEntriesAssembler}) питат ТУК, тоест отговорът на НАП се отразява с един ред.
 *
 * <p><b>⚠️ ОТГОВОРЪТ НА НАП ПО ИМЕЙЛ ОТ 19.10.2026</b> на запитването от 05.10.2026 реши покупната
 * страна по чл. 163а (Прил. № 2, части I и II), вноса по чл. 167а и касовата отчетност при
 * получател, който не е по режима. Те вече НЕ са тук — кодовете им са в {@link BgSaftDiaryCells},
 * {@link BgSaftInvoicesAssembler} и {@link BgSaftCashAccounting}.
 *
 * <p><b>Останалото — ГРЕШКА „да се потвърди“:</b>
 *
 * <ul>
 *   <li>златото и маржовата схема — не са били в запитването (решение от 15.10.2026);
 *   <li>обратното начисляване извън Прил. № 2 ({@code BG_RCM_BUILD/MOBILE/ENERGY_P}) — по
 *       консолидирания ЗДДС (lex.bg, последно изм. ДВ бр. 69/2026, сверено 26.10.2026) Прил. № 2 е
 *       само отпадъците (част I) и зърното и техническите култури (част II), а отговорът на НАП е
 *       за доставките „по чл. 163а …, описани в приложение №2“;
 *   <li>получените документи по касова отчетност, когато самото дружество е по режима — НАП не ги е
 *       описал ({@link #OWN_CASH_ACCOUNTING}, запитване до НАП от 26.10.2026).
 * </ul>
 */
public final class BgSaftNapPending {

  /** Текстът за незапитаното — златото, маржовата схема и обратното начисляване извън Прил. № 2. */
  public static final String OPINION = "чака становище от НАП — да се потвърди";

  /** Текстът за получените документи при дружество по касова отчетност. */
  public static final String OWN_CASH_ACCOUNTING =
      "да се потвърди (запитване до НАП от 26.10.2026)";

  /** Данъците, чийто код зависи от бъдещ отговор — {@code Tax.code} → какво е. */
  public static final Map<String, String> TAXES = taxes();

  private BgSaftNapPending() {}

  /** Причината за данък от {@link #TAXES}. */
  public static String reasonFor(String tax) {
    return TAXES.get(tax) + " — " + OPINION;
  }

  /** Дали съобщението е „чака НАП“ — с който и да е от двата текста. */
  public static boolean isPending(String message) {
    return message != null && (message.contains(OPINION) || message.contains(OWN_CASH_ACCOUNTING));
  }

  private static Map<String, String> taxes() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("BG_GOLD_ART163G", "инвестиционно злато (чл. 160 освободено срещу чл. 161)");
    String margin = "маржова схема (облагаем марж 100211 срещу нулева ставка 100216)";
    m.put("BG_MARGIN", margin);
    m.put("BG_TOUR", margin);
    m.put("BG_SECONDHAND", margin);
    String outside =
        "обратно начисляване извън Прил. № 2 ЗДДС (отговорът на НАП по имейл от 19.10.2026 е само"
            + " за Прил. № 2 — отпадъци и зърно)";
    for (String tax : new String[] {"BG_RCM_BUILD_P", "BG_RCM_MOBILE_P", "BG_RCM_ENERGY_P"}) {
      m.put(tax, outside);
    }
    return Collections.unmodifiableMap(m);
  }
}
