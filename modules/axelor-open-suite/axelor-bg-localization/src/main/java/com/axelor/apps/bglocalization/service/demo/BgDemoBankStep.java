package com.axelor.apps.bglocalization.service.demo;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.bankpayment.db.BankPaymentConfig;
import com.axelor.apps.bankpayment.db.BankReconciliation;
import com.axelor.apps.bankpayment.db.BankStatement;
import com.axelor.apps.bankpayment.db.BankStatementFileFormat;
import com.axelor.apps.bankpayment.db.BankStatementLineAFB120;
import com.axelor.apps.bankpayment.db.repo.BankReconciliationRepository;
import com.axelor.apps.bankpayment.db.repo.BankStatementFileFormatRepository;
import com.axelor.apps.bankpayment.db.repo.BankStatementLineAFB120Repository;
import com.axelor.apps.bankpayment.db.repo.BankStatementRepository;
import com.axelor.apps.bankpayment.service.bankreconciliation.BankReconciliationBalanceComputationService;
import com.axelor.apps.bankpayment.service.bankreconciliation.BankReconciliationComputeService;
import com.axelor.apps.bankpayment.service.bankreconciliation.BankReconciliationCreateService;
import com.axelor.apps.bankpayment.service.bankreconciliation.BankReconciliationLoadBankStatementService;
import com.axelor.apps.bankpayment.service.bankreconciliation.BankReconciliationReconciliationService;
import com.axelor.apps.bankpayment.service.bankreconciliation.BankReconciliationValidateService;
import com.axelor.apps.bankpayment.service.bankstatement.BankStatementBankDetailsService;
import com.axelor.apps.bankpayment.service.bankstatement.BankStatementImportCheckService;
import com.axelor.apps.bankpayment.service.bankstatement.BankStatementValidateService;
import com.axelor.apps.bankpayment.service.bankstatement.afb120.BankStatementImportAFB120Service;
import com.axelor.apps.bankpayment.service.bankstatementline.afb120.BankStatementLineCreationAFB120Service;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * БАНКОВИ ИЗВЛЕЧЕНИЯ И РАВНЕНИЕ — {@code bank_statements.csv}: {@code bank;month}.
 *
 * <p>Месечно извлечение на всяка банкова сметка на набора, каквото би изпратила банката: началното
 * салдо, движенията от месеца и крайното салдо. Движенията са тези, които банката е изпълнила —
 * тоест осчетоводените плащания по сметката на банката в дневник „Банка“, във валутата на сметката
 * (за EUR — дебит/кредит, за USD/CHF — сумата във валута). Началното салдо на януари е
 * встъпителното; на всеки следващ месец — крайното на предходния.
 *
 * <p>Извлечението е във формата „Relevé de compte“ (cfonb120.stm) — без файл, със същата услуга за
 * редове, която ползва вносът му; после проверката на вноса (салда и поредност), салдото на
 * банковата сметка и статус „Внесено“. Равнението е пътят на бутоните: „Създай равнение“ → „Зареди
 * извлечението“ → „Автоматично равнение“ (правилата на Axelor: сума и дата или произход) →
 * „Валидирай“. Нищо не се свързва ръчно; неравнен ред остава и се вижда в резултата.
 */
final class BgDemoBankStep {

  private BgDemoBankStep() {}

  static List<BgDemoEvent> events(BgDemoContext ctx) {
    List<BgDemoEvent> out = new ArrayList<>();
    for (BgDemoCsv.Row r : ctx.rows("bank_statements")) {
      int month = r.integer("month");
      LocalDate from = LocalDate.of(ctx.dates().first().getYear(), month, 1);
      LocalDate to = from.withDayOfMonth(from.lengthOfMonth());
      out.add(
          new BgDemoEvent(
              to,
              BgDemoEvent.RANK_MONTH_END + 8,
              "извлечение " + r.get("bank") + " " + String.format("%02d", month),
              () -> statement(ctx, r.get("bank"), from, to)));
    }
    return out;
  }

  /** Сметката на банката и дневник „Банка“ — както в картона на банковата сметка › Счетоводство. */
  static void setup(BgDemoContext ctx) throws Exception {
    if (ctx.rows("bank_statements").isEmpty()) {
      return;
    }
    BgDemoTx.run(
        () -> {
          Company company = ctx.company();
          if (company.getBankPaymentConfig() == null) {
            BankPaymentConfig config = new BankPaymentConfig();
            config.setCompany(company);
            config.setBnkStmtAutoReconcileAmountMargin(BigDecimal.ZERO);
            config.setBnkStmtAutoReconcileDateMargin(0);
            company.setBankPaymentConfig(JPA.save(config));
          }
          if (format() == null) {
            throw new IllegalStateException(
                "Форматът на банково извлечение „"
                    + BankStatementFileFormatRepository.FILE_FORMAT_CAMT_XXX_CFONB120_STM
                    + "“ липсва — той идва с инсталацията на приложението „Банкови плащания“.");
          }
        });
  }

  private static BankStatementFileFormat format() {
    return JPA.all(BankStatementFileFormat.class)
        .filter(
            "self.statementFileFormatSelect = ?1",
            BankStatementFileFormatRepository.FILE_FORMAT_CAMT_XXX_CFONB120_STM)
        .fetchOne();
  }

  /** Сумата на реда във валутата на сметката, със знак: + постъпление, − плащане. */
  private static BigDecimal signed(MoveLine ml, boolean companyCurrency) {
    if (companyCurrency) {
      return ml.getDebit().subtract(ml.getCredit());
    }
    BigDecimal amount = ml.getCurrencyAmount().abs();
    return ml.getDebit().signum() > 0 ? amount : amount.negate();
  }

  /** Изпълнено от банката: статия в дневника на банковата сметка („Банка“). */
  private static boolean bankMove(MoveLine ml, Journal journal) {
    return journal != null && journal.equals(ml.getMove().getJournal());
  }

  private static List<MoveLine> lines(
      Company company, Account cash, Currency currency, LocalDate from, LocalDate to) {
    return JPA.all(MoveLine.class)
        .filter(
            "self.move.company = ?1 AND self.account = ?2 AND self.move.currency = ?3"
                + " AND self.move.statusSelect = ?4 AND self.date >= ?5 AND self.date <= ?6",
            company,
            cash,
            currency,
            MoveRepository.STATUS_ACCOUNTED,
            from,
            to)
        .order("date")
        .order("id")
        .fetch();
  }

  static void statement(BgDemoContext ctx, String bankCode, LocalDate from, LocalDate to)
      throws Exception {
    Long statementId =
        BgDemoTx.call(
            () -> {
              Company company = ctx.company();
              BankDetails bank = ctx.find(BankDetails.class, "bank", bankCode);
              Currency currency = bank.getCurrency();
              boolean companyCurrency = currency.equals(company.getCurrency());
              Account cash = bank.getBankAccount();
              Journal journal = bank.getJournal();
              // Началното салдо — встъпителното (статия с произход „откриване“, вкл. от 01.01) и
              // изпълнените плащания преди месеца; движенията — дневник „Банка“ в месеца.
              BigDecimal opening = BigDecimal.ZERO;
              for (MoveLine ml : lines(company, cash, currency, LocalDate.of(1900, 1, 1), to)) {
                boolean openingMove =
                    Integer.valueOf(MoveRepository.FUNCTIONAL_ORIGIN_OPENING)
                        .equals(ml.getMove().getFunctionalOriginSelect());
                boolean bankBefore = ml.getDate().isBefore(from) && bankMove(ml, journal);
                if (openingMove || bankBefore) {
                  opening = opening.add(signed(ml, companyCurrency));
                }
              }
              BankStatement bs = new BankStatement();
              bs.setName(bankCode + " " + from + " – " + to);
              bs.setFromDate(from);
              bs.setToDate(to);
              bs.setStatusSelect(BankStatementRepository.STATUS_RECEIVED);
              bs.setBankStatementFileFormat(format());
              bs = Beans.get(BankStatementRepository.class).save(bs);

              BankStatementLineCreationAFB120Service creation =
                  Beans.get(BankStatementLineCreationAFB120Service.class);
              BankStatementLineAFB120Repository repo =
                  Beans.get(BankStatementLineAFB120Repository.class);
              int seq = 0;
              repo.save(
                  balance(
                      creation,
                      bs,
                      ++seq,
                      bank,
                      currency,
                      opening,
                      from,
                      BankStatementLineAFB120Repository.LINE_TYPE_INITIAL_BALANCE));
              BigDecimal closing = opening;
              int movements = 0;
              for (MoveLine ml : lines(company, cash, currency, from, to)) {
                if (!bankMove(ml, journal)) {
                  continue;
                }
                BigDecimal amount = signed(ml, companyCurrency);
                closing = closing.add(amount);
                movements++;
                // Знакът на извлечението е на банката: постъплението е кредит, плащането — дебит.
                repo.save(
                    creation.createBankStatementLine(
                        bs,
                        ++seq,
                        bank,
                        amount.signum() < 0 ? amount.negate() : BigDecimal.ZERO,
                        amount.signum() > 0 ? amount : BigDecimal.ZERO,
                        currency,
                        ml.getDescription(),
                        ml.getDate(),
                        ml.getDate(),
                        null,
                        null,
                        ml.getOrigin(),
                        ml.getMove().getReference(),
                        BankStatementLineAFB120Repository.LINE_TYPE_MOVEMENT,
                        null,
                        null));
              }
              repo.save(
                  balance(
                      creation,
                      bs,
                      ++seq,
                      bank,
                      currency,
                      closing,
                      to,
                      BankStatementLineAFB120Repository.LINE_TYPE_FINAL_BALANCE));
              ctx.log(
                  "Извлечение "
                      + bankCode
                      + " "
                      + from.getMonthValue()
                      + ": "
                      + movements
                      + " движения, "
                      + opening
                      + " → "
                      + closing
                      + " "
                      + currency.getCodeISO());
              return movements == 0 ? null : bs.getId();
            });
    if (statementId == null) {
      return;
    }
    BgDemoTx.run(
        () -> {
          BankStatement bs = JPA.find(BankStatement.class, statementId);
          Beans.get(BankStatementImportCheckService.class).checkImport(bs);
          Beans.get(BankStatementBankDetailsService.class).updateBankDetailsBalance(bs);
          Beans.get(BankStatementImportAFB120Service.class).setBankStatementImported(bs);
        });
    reconcile(ctx, bankCode, statementId);
  }

  private static BankStatementLineAFB120 balance(
      BankStatementLineCreationAFB120Service creation,
      BankStatement bs,
      int seq,
      BankDetails bank,
      Currency currency,
      BigDecimal amount,
      LocalDate date,
      int type) {
    return creation.createBankStatementLine(
        bs,
        seq,
        bank,
        amount.signum() < 0 ? amount.negate() : BigDecimal.ZERO,
        amount.signum() >= 0 ? amount : BigDecimal.ZERO,
        currency,
        type == BankStatementLineAFB120Repository.LINE_TYPE_INITIAL_BALANCE
            ? "Начално салдо"
            : "Крайно салдо",
        date,
        date,
        null,
        null,
        null,
        null,
        type,
        null,
        null);
  }

  /**
   * „Създай равнение“ → „Зареди“ → „Автоматично равнение“ → „Валидирай“, всяко в своя транзакция.
   */
  private static void reconcile(BgDemoContext ctx, String bankCode, Long statementId)
      throws Exception {
    List<Long> ids =
        BgDemoTx.call(
            () -> {
              List<Long> out = new ArrayList<>();
              for (BankReconciliation br :
                  Beans.get(BankReconciliationCreateService.class)
                      .createAllFromBankStatement(JPA.find(BankStatement.class, statementId))) {
                out.add(br.getId());
              }
              return out;
            });
    BankReconciliationRepository repo = Beans.get(BankReconciliationRepository.class);
    for (Long id : ids) {
      BgDemoTx.run(
          () -> {
            BankReconciliation br =
                Beans.get(BankReconciliationComputeService.class)
                    .computeInitialBalance(repo.find(id));
            if (br == null) {
              throw new IllegalStateException(
                  "Равнението на " + bankCode + " иска валидирано равнение за предходния месец");
            }
          });
      BgDemoTx.run(
          () ->
              Beans.get(BankReconciliationLoadBankStatementService.class)
                  .loadBankStatement(repo.find(id), true));
      BgDemoTx.run(
          () ->
              Beans.get(BankReconciliationReconciliationService.class)
                  .reconciliateAccordingToQueries(repo.find(id)));
      BgDemoTx.run(() -> Beans.get(BankReconciliationComputeService.class).compute(repo.find(id)));
      BgDemoTx.run(
          () -> Beans.get(BankReconciliationValidateService.class).validate(repo.find(id)));
      BgDemoTx.run(
          () ->
              Beans.get(BankReconciliationBalanceComputationService.class)
                  .computeBalances(repo.find(id)));
    }
    long open =
        BgDemoTx.call(
            () -> {
              BankStatement bs =
                  Beans.get(BankStatementValidateService.class)
                      .setIsFullyReconciled(JPA.find(BankStatement.class, statementId));
              return JPA.all(BankStatementLineAFB120.class)
                  .filter("self.bankStatement = ?1 AND self.amountRemainToReconcile > 0", bs)
                  .count();
            });
    ctx.log("  равнение " + bankCode + ": " + (open == 0 ? "пълно" : open + " неравнени реда"));
  }
}
