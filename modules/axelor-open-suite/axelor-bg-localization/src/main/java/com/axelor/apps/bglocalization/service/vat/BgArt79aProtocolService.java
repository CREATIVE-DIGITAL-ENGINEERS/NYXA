package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.move.MoveCreateService;
import com.axelor.apps.account.service.moveline.MoveLineCreateService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgVatAnnualCorrection;
import com.axelor.apps.bglocalization.db.BgVatAnnualCorrectionLine;
import com.axelor.apps.bglocalization.db.repo.BgVatAnnualCorrectionLineRepository;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Протоколът по <b>чл. 79а ал. 4</b> / <b>чл. 79б ал. 4 ЗДДС</b> — статията, с която годишната
 * корекция стига до дневника за покупките.
 *
 * <p>Реквизитите идват от <b>ППЗДДС чл. 66 ал. 5</b> (стоки и недвижими имоти, 11 точки) и <b>ал.
 * 6</b> (услуги, 9 точки): начислен ДДС при придобиване, пропорциите ПрНИД₀ и ПрНИДх, коефициентите
 * К₀ и Кх, и размерът на увеличението/намалението. Те се изписват в описанието на статията, тъй че
 * протоколът е четим от самата книга, а не само от екрана.
 *
 * <p>⚠️ <b>„БРОЙ ГОДИНИ“ НЕ Е РЕКВИЗИТ ТУК.</b> Той е т. 7 на ал. 3 — протокола по чл. 79, където
 * корекцията се прави веднъж за целия остатък. Годишната е 1/n за ЕДНА година; изписан брой години,
 * протоколът щеше да обещава корекция, каквато не е извършена.
 *
 * <p>⚠️ <b>ЕДНА ДДС СМЕТКА, ДВЕ ПОСОКИ — но ДВЕ РАЗЛИЧНИ НАСРЕЩНИ.</b> И увеличението, и
 * намалението минават по 4531 (данъчен кредит), само дебитът и кредитът си разменят местата: оттам
 * колекторът получава Σ(дебит − кредит) и к.11 излиза със знак, точно както ал. 4 т. 1 и т. 2 го
 * искат. Второ перо по 4532 би направило намалението „начислен данък“ и би го изпратило в дневника
 * за ПРОДАЖБИТЕ, където законът не го праща. Насрещната страна обаче НЕ е една: увеличението е
 * приход (данък се връща на лицето), намалението е РАЗХОД (ползван кредит се връща на бюджета).
 *
 * <p>⚠️ <b>ОТДЕЛНА СТЪПКА, НЕ ЧАСТ ОТ ПРЕДЛАГАНЕТО.</b> Създаването на {@code Move} хвърля при
 * затворен период и при непозволен тип сметка, при това ПРИ ЗАПИС, вътре в транзакцията на викащия.
 * Вградено в обхождането, едно такова хвърляне би върнало ЦЕЛИЯ предложен списък заради един актив.
 */
public class BgArt79aProtocolService {

  /** „09 – Протокол или друг документ“ по {@code bg.nap.document.type.select}. */
  protected static final String NAP_DOCUMENT_PROTOCOL = "09";

  /** Стойността от {@code bg.vat.move.operation.select}, която колекторът насочва към к.11. */
  protected static final String VAT_OPERATION_ANNUAL_79A = "ANNUAL_79A";

  protected final MoveCreateService moveCreateService;
  protected final MoveLineCreateService moveLineCreateService;
  protected final MoveRepository moveRepository;
  protected final BgVatAnnualCorrectionLineRepository lineRepository;

  @Inject
  public BgArt79aProtocolService(
      MoveCreateService moveCreateService,
      MoveLineCreateService moveLineCreateService,
      MoveRepository moveRepository,
      BgVatAnnualCorrectionLineRepository lineRepository) {
    this.moveCreateService = moveCreateService;
    this.moveLineCreateService = moveLineCreateService;
    this.moveRepository = moveRepository;
    this.lineRepository = lineRepository;
  }

  /**
   * Съставя протокола за един ред и го закача за него.
   *
   * <p>Връща {@code null}, без да хвърля, в четири случая, всеки от които е ОТГОВОР, а не срив:
   * редът вече носи статия (двоен протокол значи двойно движение на кредита), сумата е нула,
   * увеличението е пропуснато по ал. 6, или настройката липсва — последното се обявява в
   * забележките на реда.
   */
  @Transactional(rollbackOn = {Exception.class})
  public Move generate(BgVatAnnualCorrectionLine line) throws AxelorException {
    if (line == null || line.getCorrectionMove() != null) {
      return null;
    }
    BigDecimal amount = line.getCorrectionAmount();
    if (amount.signum() == 0) {
      return null;
    }
    // Чл. 79а ал. 6 / чл. 79б ал. 5 — пропускането важи САМО за увеличение.
    if (line.getIsSkippedUnderPara6() && amount.signum() > 0) {
      return null;
    }

    BgVatAnnualCorrection correction = line.getAnnualCorrection();
    Company company = correction.getCompany();
    AccountConfig config = company == null ? null : company.getAccountConfig();
    Journal journal = config == null ? null : config.getBgVatCorrectionJournal();
    Account vatCreditAccount = config == null ? null : config.getBgVatDeductibleAccount();

    // ⚠️ ДВЕ РАЗЛИЧНИ НАСРЕЩНИ СМЕТКИ, според ПОСОКАТА — не една.
    // Увеличението връща данък на лицето: насрещната е приходната (същата, която ползва и
    // приспадането по чл. 79 ал. 5). Намалението връща ползван кредит на бюджета: това е РАЗХОД.
    // Прекарано през приходната сметка, намалението би намалило приходите вместо да вдигне
    // разходите — разчетите с бюджета излизат верни, а отчетът за приходите и разходите не.
    boolean increase = amount.signum() > 0;
    Account counterpartAccount =
        config == null
            ? null
            : increase
                ? config.getBgVatCorrectionIncomeAccount()
                : config.getBgVatAnnualCorrectionExpenseAccount();

    if (journal == null || vatCreditAccount == null || counterpartAccount == null) {
      note(line, missingSetupNote(journal, vatCreditAccount, counterpartAccount, increase));
      lineRepository.save(line);
      return null;
    }

    // Увеличение: дебит 4531 (кредитът расте). Намаление: кредит 4531 (кредитът се връща).
    BigDecimal absolute = amount.abs();
    Account debitAccount = increase ? vatCreditAccount : counterpartAccount;
    Account creditAccount = increase ? counterpartAccount : vatCreditAccount;

    LocalDate date = protocolDate(correction);
    String origin = protocolOrigin(line);
    String description = description(line);

    Move move =
        moveCreateService.createMove(
            journal,
            company,
            company.getCurrency(),
            null,
            date,
            date,
            null,
            null,
            MoveRepository.TECHNICAL_ORIGIN_AUTOMATIC,
            MoveRepository.FUNCTIONAL_ORIGIN_PURCHASE,
            origin,
            description,
            null);

    // ⚠️ Видът документ и ДДС операцията СА самият мост към дневника: без тях статията е обикновен
    // ръчен запис и BgVatDocumentCollector няма как да я насочи към к.11.
    move.setNapDocumentTypeSelect(NAP_DOCUMENT_PROTOCOL);
    move.setBgVatMoveVatOperation(VAT_OPERATION_ANNUAL_79A);

    List<MoveLine> lines = new ArrayList<>();
    lines.add(
        moveLineCreateService.createMoveLine(
            move, null, debitAccount, absolute, true, date, 1, origin, description));
    lines.add(
        moveLineCreateService.createMoveLine(
            move, null, creditAccount, absolute, false, date, 2, origin, description));
    // Данъчният ред на реда по 4531 — правото на кредит на актива (виж BgVatAccountTaxLines).
    TaxLine[] taxLines = BgVatAccountTaxLines.forAsset(line.getFixedAsset());
    BgVatAccountTaxLines.apply(
        lines, vatCreditAccount, config.getBgVatChargedAccount(), taxLines[0], taxLines[1]);
    // ⚠️ ДОБАВЯ се към списъка на статията, не го подменя. createMove вече я е записала: тя слага
    // new ArrayList и вика moveRepository.save (MoveCreateServiceImpl, редове 277 и 311), тоест
    // списъкът е управляван от Hibernate. Подменен, той гърми при първата заявка преди комита
    // („collection with cascade=all-delete-orphan was no longer referenced“), а save() по-долу
    // прави такава заявка (MoveBudgetManagementRepository → AppService.isApp). Протоколът не
    // се съставяше изобщо.
    lines.forEach(move::addMoveLineListItem);

    Move saved = moveRepository.save(move);
    line.setCorrectionMove(saved);
    lineRepository.save(line);
    return saved;
  }

  /**
   * Датата на протокола — последният ден на годината на изменението.
   *
   * <p>Ал. 4 иска „последния данъчен период на календарната година“. Когато шапката сочи конкретен
   * период (ППЗДДС чл. 66 ал. 10 при дерегистрация го мести), той бие подразбирането.
   */
  protected LocalDate protocolDate(BgVatAnnualCorrection correction) {
    String period =
        correction.getVatReturn() == null ? null : correction.getVatReturn().getPeriod();
    if (period != null && period.matches("\\d{6}")) {
      int year = Integer.parseInt(period.substring(0, 4));
      int month = Integer.parseInt(period.substring(4, 6));
      LocalDate firstOfMonth = LocalDate.of(year, month, 1);
      return firstOfMonth.withDayOfMonth(firstOfMonth.lengthOfMonth());
    }
    return LocalDate.of(correction.getYear(), 12, 31);
  }

  /** Описанието носи реквизитите по ППЗДДС чл. 66 ал. 5/6, за да е протоколът четим от книгата. */
  protected String description(BgVatAnnualCorrectionLine line) {
    StringBuilder text = new StringBuilder("Протокол по ");
    text.append(line.getLegalGround());
    text.append(" (годишна корекция за ")
        .append(line.getAnnualCorrection().getYear())
        .append(" г.)");
    text.append("; НДДС = ").append(line.getVatCharged());
    text.append("; 1/").append(line.getPeriodYears());
    text.append("; срокът тече от ").append(line.getTermStartYear());
    text.append("; К0 = ").append(line.getK0());
    // ⚠️ ПРИЛОЖЕНИЯТ Кх, не въведеният: чл. 79в го заковава на 1 или 0 при чист случай, а точно
    // приложеният е реквизитът по ППЗДДС чл. 66 ал. 5 т. 10 (съответно ал. 6 т. 8).
    text.append("; Кх = ").append(line.getAppliedKx());
    if (line.getProportion0().signum() != 0) {
      text.append("; ПрНИД0 = ").append(line.getProportion0());
    }
    if (line.getAppliedProportionX().signum() != 0) {
      text.append("; ПрНИДх = ").append(line.getAppliedProportionX());
    }
    text.append("; изменение = ").append(line.getCorrectionAmount());
    return text.toString();
  }

  protected String protocolOrigin(BgVatAnnualCorrectionLine line) {
    return "ЧЛ79А-" + line.getAnnualCorrection().getYear() + "-" + line.getId();
  }

  protected String missingSetupNote(
      Journal journal, Account vatCreditAccount, Account counterpartAccount, boolean increase) {
    StringBuilder note =
        new StringBuilder("Протокол по чл. 79а/79б ал. 4 не е съставен — липсва настройка:");
    if (journal == null) {
      note.append(" журнал за ДДС протоколи (AccountConfig.bgVatCorrectionJournal);");
    }
    if (vatCreditAccount == null) {
      note.append(" сметка „Данъчен кредит“ (AccountConfig.bgVatDeductibleAccount);");
    }
    if (counterpartAccount == null) {
      note.append(
          increase
              ? " насрещна сметка при увеличение (AccountConfig.bgVatCorrectionIncomeAccount);"
              : " разходна сметка при намаление"
                  + " (AccountConfig.bgVatAnnualCorrectionExpenseAccount);");
    }
    note.append(" корекцията се отразява ръчно.");
    return note.toString();
  }

  protected void note(BgVatAnnualCorrectionLine line, String text) {
    String existing = line.getComments();
    line.setComments(existing == null || existing.trim().isEmpty() ? text : existing + "\n" + text);
  }
}
