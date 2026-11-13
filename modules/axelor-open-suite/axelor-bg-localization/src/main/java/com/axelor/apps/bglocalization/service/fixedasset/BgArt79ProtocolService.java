package com.axelor.apps.bglocalization.service.fixedasset;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.AssetDisposalReason;
import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.FixedAssetRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.move.MoveCreateService;
import com.axelor.apps.account.service.moveline.MoveLineCreateService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgFixedAssetWriteOff;
import com.axelor.apps.bglocalization.db.repo.BgFixedAssetWriteOffRepository;
import com.axelor.apps.bglocalization.service.vat.BgVatAccountTaxLines;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Протоколът по <b>чл. 79 ЗДДС</b> — статията, с която изчисленото стига до дневника.
 *
 * <p>Една услуга за ДВЕТЕ посоки, защото документът е един и същи вид: ал. 4 за дължимия данък
 * (дневник за ПРОДАЖБИТЕ, к.12) и ал. 7 за приспадането на неползван кредит (дневник за ПОКУПКИТЕ,
 * к.11). Посоката идва от {@code vatCorrectionDirection} и решава и сметките, и ДДС операцията;
 * ППЗДДС чл. 66 ал. 3 и ал. 4 искат едни и същи реквизити за двете.
 *
 * <p>Дословно ал. 4: „Начисляването на данъка по ал. 1 - 3 се извършва в данъчния период, през
 * който е възникнало съответното обстоятелство, чрез съставяне на протокол за определяне размера на
 * дължимия данък и отразяването му в дневника за продажбите и справка-декларацията за този данъчен
 * период.“
 *
 * <p>Реквизитите, които протоколът трябва да носи, са в <b>ППЗДДС чл. 66 ал. 3</b> (съответно ал. 4
 * за ал. 5 и 6) — т. 5 начислен ДДС при придобиване, т. 7 брой години, т. 8 пропорция, т. 9
 * коефициент, т. 10 размер на дължимия данък. Всички те вече стоят върху отписването и се изписват
 * в описанието на статията, тъй че протоколът е четим от самата книга, а не само от екрана.
 *
 * <p><b>⚠️ ОТДЕЛНА СТЪПКА, А НЕ ЧАСТ ОТ ОТПИСВАНЕТО — и това е решение, не пропуск.</b> Създаването
 * на {@code Move} хвърля при ЗАТВОРЕН ПЕРИОД и при непозволен тип сметка, при това ПРИ ЗАПИС, вътре
 * в транзакцията на викащия. Вградено в {@code fullDisposal}, едно такова хвърляне би върнало
 * ЦЯЛОТО отписване на актива — включително верните ЗКПО редове — заради ДДС настройка. Затова
 * отписването само ИЗЧИСЛЯВА корекцията, а начисляването е свое действие със своя транзакция.
 *
 * <p>Другата причина е правна: ал. 4 иска начисляване „в данъчния период, през който е възникнало
 * съответното обстоятелство“. Когато този период вече е затворен, решението кой период да носи
 * протокола е на съставителя, не на автоматиката.
 */
public class BgArt79ProtocolService {

  /**
   * Функционална принадлежност: корекцията е НАЧИСЛЕН ДАНЪК, тоест продажбена страна. Същият
   * признак носят и протоколите за самоначисляване.
   */
  protected static final int FUNCTIONAL_ORIGIN = MoveRepository.FUNCTIONAL_ORIGIN_SALE;

  /** „09 – Протокол или друг документ“ по {@code bg.nap.document.type.select}. */
  protected static final String NAP_DOCUMENT_PROTOCOL = "09";

  /** Стойността от {@code bg.vat.move.operation.select}, която колекторът насочва към к.12. */
  protected static final String VAT_OPERATION_CORRECTION_79 = "CORRECTION_79";

  /** Стойността от същата селекция за обратната посока — колекторът я насочва към к.11. */
  protected static final String VAT_OPERATION_DEDUCTION_79 = "DEDUCTION_79";

  protected final MoveCreateService moveCreateService;
  protected final MoveLineCreateService moveLineCreateService;
  protected final MoveRepository moveRepository;
  protected final BgFixedAssetWriteOffRepository writeOffRepository;
  protected final BgRemovalAccountResolver removalAccountResolver;

  @Inject
  public BgArt79ProtocolService(
      MoveCreateService moveCreateService,
      MoveLineCreateService moveLineCreateService,
      MoveRepository moveRepository,
      BgFixedAssetWriteOffRepository writeOffRepository,
      BgRemovalAccountResolver removalAccountResolver) {
    this.moveCreateService = moveCreateService;
    this.moveLineCreateService = moveLineCreateService;
    this.moveRepository = moveRepository;
    this.writeOffRepository = writeOffRepository;
    this.removalAccountResolver = removalAccountResolver;
  }

  /**
   * Съставя протокола и го закача за отписването.
   *
   * <p>Връща {@code null}, без да хвърля, когато няма какво да се начисли или когато настройката
   * липсва — обявено в бележките на отписването. Липсващ ДДС журнал е недовършена настройка, а не
   * счетоводна грешка, и не бива да изглежда като срив.
   *
   * <p>⚠️ Повторното извикване НЕ прави втори протокол: вече начислена корекция се пази от {@code
   * vatCorrectionMove}. Двоен протокол значи двойно начислен данък.
   */
  @Transactional(rollbackOn = {Exception.class})
  public Move generate(BgFixedAssetWriteOff writeOff) throws AxelorException {
    if (writeOff == null || writeOff.getVatCorrectionMove() != null) {
      return null;
    }
    BigDecimal amount = writeOff.getVatCorrectionAmount();
    if (amount.signum() == 0) {
      return null;
    }
    boolean deduction = BgArt79Direction.DEDUCTION.equals(writeOff.getVatCorrectionDirection());
    if (!deduction
        && !BgAssetRemoval.VatTreatment.CORRECTION_DUE.equals(writeOff.getVatTreatmentSelect())) {
      return null;
    }

    Company company = writeOff.getCompany();
    AccountConfig config = company == null ? null : company.getAccountConfig();
    Journal journal = config == null ? null : config.getBgVatCorrectionJournal();

    // Дебит и кредит разменят местата си с посоката, и точно сметките казват на колектора коя е
    // тя: 4531 е входящ данък (к.11), 4532 — начислен (к.12).
    Account debitAccount;
    Account creditAccount;
    if (deduction) {
      debitAccount = config == null ? null : config.getBgVatDeductibleAccount();
      creditAccount = config == null ? null : config.getBgVatCorrectionIncomeAccount();
    } else {
      debitAccount = expenseAccountOf(writeOff);
      creditAccount = config == null ? null : config.getBgVatChargedAccount();
    }
    if (journal == null || debitAccount == null || creditAccount == null) {
      note(writeOff, missingSetupNote(journal, debitAccount, creditAccount, deduction));
      writeOffRepository.save(writeOff);
      return null;
    }

    LocalDate date = writeOff.getWriteOffDate();
    String origin = protocolOrigin(writeOff);
    String description = description(writeOff, deduction);

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
            deduction ? MoveRepository.FUNCTIONAL_ORIGIN_PURCHASE : FUNCTIONAL_ORIGIN,
            origin,
            description,
            null);

    // ⚠️ Видът документ и ДДС операцията СА самият мост към дневника: без тях статията е обикновен
    // ръчен запис и BgVatDocumentCollector няма как да я насочи към своята колона.
    move.setNapDocumentTypeSelect(NAP_DOCUMENT_PROTOCOL);
    move.setBgVatMoveVatOperation(
        deduction ? VAT_OPERATION_DEDUCTION_79 : VAT_OPERATION_CORRECTION_79);

    List<MoveLine> lines = new ArrayList<>();
    lines.add(
        moveLineCreateService.createMoveLine(
            move, null, debitAccount, amount, true, date, 1, origin, description));
    lines.add(
        moveLineCreateService.createMoveLine(
            move, null, creditAccount, amount, false, date, 2, origin, description));
    // ⚠️ ДОБАВЯ се към списъка на статията, не го подменя. createMove вече я е записала: тя слага
    // new ArrayList и вика moveRepository.save (MoveCreateServiceImpl, редове 277 и 311), тоест
    // списъкът е управляван от Hibernate. Подменен, той гърми при първата заявка преди комита
    // („collection with cascade=all-delete-orphan was no longer referenced“), а save() по-долу
    // прави такава заявка (MoveBudgetManagementRepository → AppService.isApp). Протоколът не
    // се съставяше изобщо.
    // Данъчните редове по 4531/4532 — правото на кредит на актива и начисленият данък (виж
    // BgVatAccountTaxLines); без тях AOS не осчетоводява корекцията с BG_COA.
    TaxLine[] taxLines = BgVatAccountTaxLines.forAsset(writeOff.getFixedAsset());
    BgVatAccountTaxLines.apply(
        lines,
        config.getBgVatDeductibleAccount(),
        config.getBgVatChargedAccount(),
        taxLines[0],
        taxLines[1]);
    lines.forEach(move::addMoveLineListItem);

    Move saved = moveRepository.save(move);
    writeOff.setVatCorrectionMove(saved);
    writeOffRepository.save(writeOff);
    return saved;
  }

  /**
   * Разходната сметка — същата, в която е отписан активът: разрешената сметка на причината, иначе
   * базовата за вида отписване ({@link BgRemovalAccountResolver#chargeAccount}).
   *
   * <p>⚠️ <b>НЕ се чете „първият дебитен ред“ на статията за отписване.</b> При напълно амортизиран
   * актив базата не създава ред за балансовата стойност ({@code chargeAmount.signum() != 0}) и
   * първият дебитен ред е натрупаната амортизация 241/242 — протокол Дт 241 би върнал корекцията в
   * амортизацията вместо в разхода. Резолвърът е същият като за отписването, тъй че корекцията пак
   * ляга там, където е легнала и балансовата стойност.
   */
  protected Account expenseAccountOf(BgFixedAssetWriteOff writeOff) {
    FixedAsset fixedAsset = writeOff.getFixedAsset();
    if (fixedAsset == null) {
      return null;
    }
    AssetDisposalReason reason =
        writeOff.getAssetDisposalReason() != null
            ? writeOff.getAssetDisposalReason()
            : fixedAsset.getAssetDisposalReason();
    return removalAccountResolver.chargeAccount(fixedAsset, reason, transferredReason(fixedAsset));
  }

  /**
   * Видът отписване, както го записва базата ({@code FixedAssetDisposalServiceImpl
   * #computeTransferredReason}); незаписан (0 — getter-ът никога не е null) → брак, по същото
   * правило „всичко останало“.
   */
  protected static int transferredReason(FixedAsset fixedAsset) {
    int reason = fixedAsset.getTransferredReasonSelect();
    return reason == 0 ? FixedAssetRepository.TRANSFERED_REASON_SCRAPPING : reason;
  }

  /** Описанието носи реквизитите по ППЗДДС чл. 66 ал. 3, за да е протоколът четим от книгата. */
  protected String description(BgFixedAssetWriteOff writeOff, boolean deduction) {
    StringBuilder text =
        new StringBuilder(
            deduction ? "Протокол по чл. 79 ал. 7 ЗДДС — " : "Протокол по чл. 79 ал. 4 ЗДДС — ");
    text.append(writeOff.getVatCorrectionGround());
    text.append("; БГ = ").append(writeOff.getVatCorrectionYears());
    text.append(" от ").append(writeOff.getVatCorrectionPeriodYears()).append(" г.");
    text.append("; срокът тече от ").append(writeOff.getVatCorrectionStartYear());
    text.append("; К0 = ").append(writeOff.getVatCorrectionK0());
    if (writeOff.getVatCorrectionProportion().signum() != 0) {
      text.append("; ПрНИД0 = ").append(writeOff.getVatCorrectionProportion());
    }
    return text.toString();
  }

  protected String protocolOrigin(BgFixedAssetWriteOff writeOff) {
    String document = writeOff.getDocumentNumber();
    return document == null || document.trim().isEmpty()
        ? "ЧЛ79-" + writeOff.getId()
        : document.trim();
  }

  protected String missingSetupNote(
      Journal journal, Account debitAccount, Account creditAccount, boolean deduction) {
    StringBuilder note =
        new StringBuilder(
            deduction
                ? "Протокол по чл. 79 ал. 7 не е съставен — липсва настройка:"
                : "Протокол по чл. 79 ал. 4 не е съставен — липсва настройка:");
    if (journal == null) {
      note.append(" журнал за ДДС протоколи (AccountConfig.bgVatCorrectionJournal);");
    }
    if (debitAccount == null) {
      note.append(
          deduction
              ? " сметка „Данъчен кредит“ (AccountConfig.bgVatDeductibleAccount);"
              : " разходна сметка — статията за отписване няма дебитен ред;");
    }
    if (creditAccount == null) {
      note.append(
          deduction
              ? " насрещна сметка (AccountConfig.bgVatCorrectionIncomeAccount);"
              : " сметка „Начислен ДДС“ (AccountConfig.bgVatChargedAccount);");
    }
    note.append(deduction ? " кредитът се приспада ръчно." : " данъкът се начислява ръчно.");
    return note.toString();
  }

  protected void note(BgFixedAssetWriteOff writeOff, String line) {
    String existing = writeOff.getComments();
    writeOff.setComments(
        existing == null || existing.trim().isEmpty() ? line : existing + "\n" + line);
  }
}
