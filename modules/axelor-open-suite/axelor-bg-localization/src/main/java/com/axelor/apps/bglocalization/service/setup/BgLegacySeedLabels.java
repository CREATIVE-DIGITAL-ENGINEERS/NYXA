package com.axelor.apps.bglocalization.service.setup;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.PaymentCondition;
import com.axelor.apps.bankpayment.db.BankStatementQuery;
import com.axelor.db.JPA;
import com.google.inject.persist.Transactional;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * АНГЛИЙСКИТЕ И СГРЕШЕНИТЕ ТЕКСТОВЕ ОТ НАЧАЛНИТЕ ДАННИ — В ЗАВАРЕНИТЕ БАЗИ.
 *
 * <p>Заварените бази носят: „Cust. Inv.“/„Suppl. Inv.“ в описанията на редовете от движенията
 * (моделът на описанието на дневника, BG_COA {@code account_journal.csv}), „30 days net“ на
 * фактурата (условието за плащане NET30, BG_COA {@code account_paymentCondition.csv}), „Origin and
 * amount equal“ на банковото съпоставяне (axelor-bank-payment {@code
 * bankpayment_bankStatementQuery.csv}) и сметка 913 с ЛАТИНСКО „M“ в „Mатериални“. Файловете са
 * поправени, но те се четат само при нова инсталация/ново дружество — заварените записи ги
 * получават тук, при стартиране.
 *
 * <p>⚠️ САМО ТОЧНО СТАРИЯТ ТЕКСТ СЕ СМЕНЯ: стойност, която потребителят е редактирал, не съвпада и
 * не се пипа. Повторният старт не намира нищо. Редовете, вече записани със старото описание
 * (история), не се пипат.
 */
public class BgLegacySeedLabels {

  /** Моделът на описанието на дневника по код: старият текст → новият. */
  public static final Map<String, String[]> JOURNAL_DESCRIPTIONS = new LinkedHashMap<>();

  /** Името на условието за плащане по код. */
  public static final Map<String, String[]> PAYMENT_CONDITION_NAMES = new LinkedHashMap<>();

  /** Името на сметката по код. */
  public static final Map<String, String[]> ACCOUNT_NAMES = new LinkedHashMap<>();

  /** Името на правилото за банково съпоставяне: старото → новото (кодът няма). */
  public static final Map<String, String> BANK_STATEMENT_QUERY_NAMES = new LinkedHashMap<>();

  static {
    JOURNAL_DESCRIPTIONS.put("SAL", new String[] {"Cust. Inv.", "Фактура за продажба"});
    JOURNAL_DESCRIPTIONS.put("RFS", new String[] {"Cust. Refund", "Кредитно известие за продажба"});
    JOURNAL_DESCRIPTIONS.put("PCH", new String[] {"Suppl. Inv.", "Фактура за покупка"});
    JOURNAL_DESCRIPTIONS.put("RFP", new String[] {"Suppl. Refund", "Кредитно известие за покупка"});
    JOURNAL_DESCRIPTIONS.put("RPB", new String[] {"Reported balance", "Начален баланс"});

    PAYMENT_CONDITION_NAMES.put(
        "NET30", new String[] {"30 days net", "30 дни от датата на фактурата"});

    ACCOUNT_NAMES.put(
        "913",
        new String[] {
          "Mатериални активи, приети на съхранение", "Материални активи, приети на съхранение"
        });

    Map<String, String> q = BANK_STATEMENT_QUERY_NAMES;
    q.put(
        "Description containing & Code 01 (Bank cheque issued)",
        "Описанието съдържа и код 01 (издаден чек)");
    q.put("Description containing", "Описанието съдържа");
    q.put("Description starting with an", "Описанието започва с");
    q.put("Description ending with an", "Описанието завършва на");
    q.put("Origin containing", "Произходът съдържа");
    q.put("Origin starting with an", "Произходът започва с");
    q.put("Origin ending with an", "Произходът завършва на");
    q.put("Reference containing", "Референцията съдържа");
    q.put("Reference starting with an", "Референцията започва с");
    q.put("Reference ending with an", "Референцията завършва на");
    q.put("Origin and amount equal", "Еднакви произход и сума");
    q.put("Equal amount, payment mode and dates", "Еднакви сума, начин на плащане и дати");
    q.put("Equal amount and dates", "Еднакви сума и дати");
    q.put("Equal amount and date within ranges", "Еднаква сума и дата в интервала");
    q.put(
        "Amount and mode of payment equal and date within the ranges",
        "Еднакви сума и начин на плащане, дата в интервала");
    q.put("Equal amount", "Еднаква сума");
    q.put("Amount and date within ranges", "Сума и дата в интервала");
    q.put("Equal amount and origin", "Еднакви сума и произход");
  }

  /** Новият текст, ако {@code current} е точно старият; иначе {@code null} (не се пипа). */
  static String renamed(String[] oldAndNew, String current) {
    return oldAndNew != null && oldAndNew[0].equals(current) ? oldAndNew[1] : null;
  }

  /** Колко записа са сменени; за лога при стартиране. */
  @Transactional(rollbackOn = Exception.class)
  public String run() {
    int journals = 0;
    for (Journal journal :
        JPA.all(Journal.class).filter("self.code IN ?1", JOURNAL_DESCRIPTIONS.keySet()).fetch()) {
      String to =
          renamed(JOURNAL_DESCRIPTIONS.get(journal.getCode()), journal.getDescriptionModel());
      if (to != null) {
        journal.setDescriptionModel(to);
        JPA.save(journal);
        journals++;
      }
    }
    int conditions = 0;
    for (PaymentCondition condition :
        JPA.all(PaymentCondition.class)
            .filter("self.code IN ?1", PAYMENT_CONDITION_NAMES.keySet())
            .fetch()) {
      String to = renamed(PAYMENT_CONDITION_NAMES.get(condition.getCode()), condition.getName());
      if (to != null) {
        condition.setName(to);
        JPA.save(condition);
        conditions++;
      }
    }
    int accounts = 0;
    for (Account account :
        JPA.all(Account.class).filter("self.code IN ?1", ACCOUNT_NAMES.keySet()).fetch()) {
      String to = renamed(ACCOUNT_NAMES.get(account.getCode()), account.getName());
      if (to != null) {
        account.setName(to);
        JPA.save(account);
        accounts++;
      }
    }
    int queries = 0;
    for (BankStatementQuery query :
        JPA.all(BankStatementQuery.class)
            .filter("self.name IN ?1", BANK_STATEMENT_QUERY_NAMES.keySet())
            .fetch()) {
      query.setName(BANK_STATEMENT_QUERY_NAMES.get(query.getName()));
      JPA.save(query);
      queries++;
    }
    return "Legacy seed labels: journals "
        + journals
        + ", payment conditions "
        + conditions
        + ", accounts "
        + accounts
        + ", bank statement rules "
        + queries
        + ".";
  }
}
