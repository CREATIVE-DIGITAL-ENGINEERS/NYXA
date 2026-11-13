package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.base.service.BankDetailsService;
import com.axelor.apps.bglocalization.service.saft.dto.SaftBankAccount;
import com.axelor.apps.bglocalization.service.saft.dto.SaftCompanyHeader;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.iban4j.IbanFormatException;
import org.iban4j.InvalidCheckDigitException;
import org.iban4j.UnsupportedCountryException;

/**
 * IBAN НА ПОДАВАЩИЯ (S.CMH.6 / S.BA.1) — ЗАДЪЛЖИТЕЛЕН И ВАЛИДЕН ПО ISO 13616. Чиста функция.
 *
 * <p><b>Източникът.</b> Книгата (Приложение №2), лист „5. Structures“: S.CMH.6 BankAccountStructure
 * на дружеството — Mandatory; S.BA.1 IBANNumber — Mandatory, „Валидиране срещу IBAN-ISO13616-1997“:
 * дължината и видът на знаците по държава (за BG — 22 знака, 4a,6n,8c, листът
 * „IBAN-ISO13616-1997“). Протокол 30TST000014738 на тестовата е-услуга отказа празния {@code
 * <IBANNumber/>} с „посочен невалиден IBAN“.
 *
 * <p><b>⚠️ ВАЛИДАТОРЪТ Е НА AXELOR, НЕ ВТОРИ.</b> {@code BankDetailsService.validateIban} (iban4j:
 * дължина по държава, знаци, контролни цифри mod-97) — същият, през който минава всяка банкова
 * сметка във формата. Подава се отвън като функция, за да остане сглобяването чисто.
 *
 * <p><b>⚠️ ПАРТНЬОРИТЕ.</b> S.C.6 (банкови сметки на клиент/доставчик) е Optional по книгата и
 * генераторът не я пише — днес единственият IBAN във файла е на подаващия. Пише ли се някога,
 * проверката се добавя тук; празен там НЕ е грешка (Optional).
 */
public final class BgSaftIbanCheck {

  private BgSaftIbanCheck() {}

  /** Без интервали, с главни букви — формата на ISO 13616; {@code null} за празен. */
  public static String normalize(String iban) {
    if (iban == null) {
      return null;
    }
    String compact = iban.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    return compact.isEmpty() ? null : compact;
  }

  /**
   * Защо IBAN-ът е невалиден по {@code validateIban}; {@code null}, когато е валиден.
   *
   * @param service проверката на Axelor
   */
  public static Function<String, String> refusal(BankDetailsService service) {
    return iban -> {
      try {
        service.validateIban(iban);
        return null;
      } catch (IbanFormatException | InvalidCheckDigitException | UnsupportedCountryException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      }
    };
  }

  /** Находките за банковите сметки на подаващия — без указател; сглобяването сочи дружеството. */
  public static List<BgDeclarationFileFinding> check(
      SaftCompanyHeader company, Function<String, String> refusal) {
    List<BgDeclarationFileFinding> findings = new ArrayList<>();
    boolean any = false;
    for (SaftBankAccount account : company.getBankAccounts()) {
      String iban = normalize(account.getIbanNumber());
      if (iban == null) {
        continue;
      }
      any = true;
      String reason = refusal.apply(iban);
      if (reason != null) {
        findings.add(
            BgDeclarationFileFinding.error(
                "SAFT_HEADER_IBAN_INVALID",
                "Невалиден IBAN на дружеството (S.BA.1, ISO 13616): "
                    + iban
                    + " — "
                    + reason
                    + ". НАП отказва файла."));
      }
    }
    if (!any) {
      findings.add(
          BgDeclarationFileFinding.error(
              "SAFT_HEADER_IBAN_MISSING",
              "Дружеството няма банкова сметка с IBAN — S.CMH.6 и S.BA.1 са задължителни; празен"
                  + " IBAN НАП отказва (протокол 30TST000014738)."));
    }
    return findings;
  }
}
