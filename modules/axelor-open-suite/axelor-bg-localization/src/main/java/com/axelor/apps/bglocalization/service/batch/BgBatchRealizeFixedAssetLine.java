package com.axelor.apps.bglocalization.service.batch;

import com.axelor.apps.account.db.FixedAssetLine;
import com.axelor.apps.account.db.repo.FixedAssetDerogatoryLineRepository;
import com.axelor.apps.account.db.repo.FixedAssetLineRepository;
import com.axelor.apps.account.service.FindFixedAssetService;
import com.axelor.apps.account.service.batch.BatchRealizeFixedAssetLine;
import com.axelor.apps.account.service.fixedasset.FixedAssetDerogatoryLineMoveService;
import com.axelor.apps.account.service.fixedasset.FixedAssetLineMoveService;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.google.inject.Inject;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Месечното начисляване, допълнено с ДАНЪЧНИЯ план.
 *
 * <p><b>Дефектът, който това поправя.</b> Базовият батч избира редове с {@code
 * self.fixedAsset.company.id = :companyId}, а {@code fixedAsset} е m2o, което {@code
 * AbstractFixedAssetLineServiceImpl.setFixedAsset} пълни САМО за икономическите редове — данъчните
 * ползват {@code fiscalFixedAsset}, IFRS — {@code ifrsFixedAsset}. Данъчните редове са структурно
 * недостижими за него; те се реализират единствено като СТРАНИЧЕН ЕФЕКТ от икономически ред със
 * СЪЩАТА дата, през {@code FixedAssetLineMoveServiceImpl.realizeOthersLines}.
 *
 * <p>Докато двата плана съвпадат, това е незабележимо. Разминат ли се — а точно това е смисълът на
 * двойния план — ДАП-ът спира да се начислява в мига, в който счетоводният план се изчерпи. Пример:
 * машина с ДАС 10 000 €, счетоводен срок 24 месеца и данъчна норма 30% (40 месеца). След 12.2027
 * счетоводният борд е изчерпан, а данъчният има още 16 месеца — и нито един от тях не се начислява
 * повече, защото няма икономически ред, който да ги влачи.
 *
 * <p><b>Защо това е нарушение, а не пропуск в удобството.</b> Чл. 60 ал. 3 т. 1 ЗКПО изрично казва,
 * че напълно амортизиран за СЧЕТОВОДНИ цели актив НЕ се отписва от данъчния амортизационен план по
 * ал. 2 — той остава там и излиза по реда на ал. 1, тоест при пълно ДАНЪЧНО амортизиране (ал. 4).
 * Тоест законът иска ДАП-ът да продължи точно в случая, в който базата го спира. Последствията са
 * две и двете тихи: годишната данъчна амортизация по чл. 58 ал. 3 изчезва от декларацията, а
 * данъчната стойност по чл. 53 ал. 4 остава завишена и намалението по чл. 66 ал. 2 излиза грешно.
 *
 * <p><b>Как.</b> Добавя се втора извадка по {@code self.fiscalFixedAsset.company.id} със същите
 * условия за дата и статус. Реализирането е идемпотентно ({@code realize} излиза веднага за ред,
 * който не е {@code STATUS_PLANNED}), тъй че редовете, вече довлечени от икономически близнак, не
 * се броят втори път. И не поражда статии: пазачът {@code typeSelect != TYPE_SELECT_FISCAL} в
 * {@code realize} остава непокътнат — ДАП-ът е извънсчетоводен.
 */
public class BgBatchRealizeFixedAssetLine extends BatchRealizeFixedAssetLine {

  @Inject
  public BgBatchRealizeFixedAssetLine(
      FixedAssetLineMoveService fixedAssetLineMoveService,
      AppBaseService appBaseService,
      FixedAssetLineRepository fixedAssetLineRepo,
      FixedAssetDerogatoryLineRepository fixedAssetDerogatoryLineRepo,
      FixedAssetDerogatoryLineMoveService fixedAssetDerogatoryLineMoveService,
      FindFixedAssetService findFixedAssetService) {
    super(
        fixedAssetLineMoveService,
        appBaseService,
        fixedAssetLineRepo,
        fixedAssetDerogatoryLineRepo,
        fixedAssetDerogatoryLineMoveService,
        findFixedAssetService);
  }

  /**
   * Редът тук е задължителен и крехък: конфигурацията се СНИМА ПРЕДИ {@link #runBaseProcess()}.
   *
   * <p>Базовият {@code realizeFixedAssetLineList} вика {@code JPA.clear()} след ВСЕКИ ред. Това
   * откача {@code batch} от сесията и оставя {@code batch.getAccountingBatch()} неинициализирано
   * Hibernate прокси. Четенето му след това хвърляше {@code LazyInitializationException: could not
   * initialize proxy [AccountingBatch#N] - no Session}, което изхвърчаше чак до {@code
   * AbstractBatch.run} и спираше батча ПРЕДИ {@code stop()} — без крайна дата, без коментар, без
   * броячи. Отвън изглеждаше, че „нищо не се случва".
   *
   * <p>Коварното е, че се появява само когато базовата обработка е реализирала поне един ред: без
   * редове няма {@code JPA.clear()} и няма грешка. Тоест чупи се точно в случая, заради който
   * функцията съществува.
   *
   * <p>Снимката пази само примитивни стойности и идентификатори, затова е безразлична към това
   * колко пъти сесията е чистена след нея.
   */
  @Override
  protected void process() {
    TaxPlanSelection selection = takeSelection();
    runBaseProcess();
    realizeRemainingTaxLines(selection);
  }

  /** Отделен шев, за да може тест да провери реда на стъпките без JPA/Guice. */
  protected void runBaseProcess() {
    super.process();
  }

  /**
   * Прочита конфигурацията на батча, докато сесията още е жива. Викa се веднъж, преди базовата
   * обработка — виж {@link #process()}.
   */
  protected TaxPlanSelection takeSelection() {
    LocalDate startDate = batch.getAccountingBatch().getStartDate();
    LocalDate endDate = batch.getAccountingBatch().getEndDate();
    boolean updateAll =
        Boolean.TRUE.equals(batch.getAccountingBatch().getUpdateAllRealizedFixedAssetLines());
    Company company = batch.getAccountingBatch().getCompany();
    Long companyId = company != null ? company.getId() : Long.valueOf(0);
    LocalDate dateNow =
        appBaseService.getTodayDate(
            company != null
                ? company
                : Optional.ofNullable(AuthUtils.getUser())
                    .map(User::getActiveCompany)
                    .orElse(null));
    return new TaxPlanSelection(startDate, endDate, updateAll, companyId, dateNow);
  }

  /**
   * Догонва данъчния план за онези активи, чийто счетоводен борд вече не влачи нищо.
   *
   * <p>Пуска се СЛЕД базовата обработка нарочно: така редовете със съвпадащи дати вече са
   * реализирани по обичайния път и тук остава само остатъкът. Обратният ред би дал същия резултат,
   * но би приписал на данъчния план редове, които икономическият е щял да довлече — а броячите на
   * батча се четат.
   */
  protected void realizeRemainingTaxLines(TaxPlanSelection selection) {
    List<FixedAssetLine> taxLines =
        fixedAssetLineRepo
            .all()
            .filter(buildTaxLineQuery(selection))
            .bind(buildTaxLineParams(selection))
            .fetch();
    realizeFixedAssetLineList(taxLines);
  }

  /**
   * Статично нарочно: конструкторът на {@code AbstractBatch} вика {@code JPA.runInTransaction},
   * тоест наследник на батч изобщо не се инстанцира без жив JPA контекст. Подборът обаче е чиста
   * логика и така се проверява от тест без база.
   */
  protected static String buildTaxLineQuery(TaxPlanSelection selection) {
    String query =
        "self.statusSelect = :statusSelect AND self.fiscalFixedAsset.company.id = :companyId";
    if (!selection.updateAll
        && selection.startDate != null
        && selection.endDate != null
        && selection.startDate.compareTo(selection.endDate) <= 0) {
      return query
          + " AND self.depreciationDate <= :endDate AND self.depreciationDate >= :startDate";
    }
    return query + " AND self.depreciationDate < :dateNow";
  }

  /** Виж {@link #buildTaxLineQuery} за това защо е статично. */
  protected static Map<String, Object> buildTaxLineParams(TaxPlanSelection selection) {
    Map<String, Object> params = new HashMap<>();
    params.put("statusSelect", FixedAssetLineRepository.STATUS_PLANNED);
    params.put("companyId", selection.companyId);
    params.put("startDate", selection.startDate);
    params.put("endDate", selection.endDate);
    params.put("dateNow", selection.dateNow);
    return params;
  }

  /**
   * Конфигурацията на батча, откъсната от сесията: само стойности и идентификатор на фирма, никакви
   * закачени entity-та. Точно това я прави имунизирана срещу {@code JPA.clear()}.
   */
  protected static class TaxPlanSelection {
    protected final LocalDate startDate;
    protected final LocalDate endDate;
    protected final boolean updateAll;
    protected final Long companyId;
    protected final LocalDate dateNow;

    protected TaxPlanSelection(
        LocalDate startDate,
        LocalDate endDate,
        boolean updateAll,
        Long companyId,
        LocalDate dateNow) {
      this.startDate = startDate;
      this.endDate = endDate;
      this.updateAll = updateAll;
      this.companyId = companyId;
      this.dateNow = dateNow;
    }
  }
}
