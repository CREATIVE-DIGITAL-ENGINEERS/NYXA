package com.axelor.apps.bglocalization.db.repo;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Localization;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.apps.base.db.repo.LocalizationRepository;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.bglocalization.service.BgCompanyReportLocale;
import com.axelor.apps.bglocalization.service.BgDeclarantResolver;
import com.axelor.apps.bglocalization.service.partner.BgJustInserted;
import com.axelor.apps.bglocalization.service.partner.BgPartnerCompany;
import com.axelor.apps.bglocalization.service.partner.BgPartnerSequenceSetupService;
import com.axelor.apps.bglocalization.service.stock.BgStockCostingPolicyService;
import com.axelor.apps.bglocalization.service.stock.BgStockCostingRules;
import com.axelor.apps.bglocalization.service.stock.BgStockCostingSetupService;
import com.axelor.apps.bglocalization.service.stock.BgStockSetupService;
import com.axelor.inject.Beans;
import java.time.LocalDate;
import javax.persistence.PersistenceException;

/**
 * ИНВАРИАНТЪТ „ТОЧНО ЕДИН ДЕКЛАРАТОР ПО ПОДРАЗБИРАНЕ“ — половината, която {@code
 * BgDeclarantManagementRepository} не може да види.
 *
 * <p><b>⚠️ ЗАЩО ИЗОБЩО ТРЯБВА ВТОРА ВРАТА.</b> Декларантите висят на дружеството като {@code
 * one-to-many … mappedBy="company"}, а генераторът слага на такова поле {@code cascade =
 * CascadeType.ALL}. При запис на формата Дружество {@code Resource.save} вика репозиторията САМО на
 * корена ({@code JPA.edit} → {@code JPA.manage} → {@code repository.save}); децата ги записва
 * Hibernate по cascade и {@code BgDeclarantRepository.save} НЕ се докосва. Оттам и защо счупеното
 * се виждаше само през решетката на Дружеството, а през самия декларант работеше: там коренът е
 * декларантът.
 *
 * <p><b>⚠️ И ЗАЩО ТУК СЕ ОТКАЗВА, А ОТТАТЪК СЕ СВАЛЯ.</b> Не са две мнения за едно правило, а един
 * отговор на два различни въпроса — разграничението е изписано в {@link BgDeclarantResolver}.
 * Накратко: оттатък записваният е един и е недвусмислено щракнатият; тук пристига цялата колекция и
 * подадените данни не казват кой от вдигнатите е бил щракнат.
 *
 * <p><b>⚠️ И ТОЗИ ОТКАЗ Е БЕКСТОП, А НЕ ВСЕКИДНЕВНИЯТ ПЪТ.</b> На екрана до него стои {@code
 * BgCompanyController.clearOtherDeclarantDefaults}: при onChange на решетката още нищо не е
 * записано, тъй че четенето от базата дава ПРЕДИШНОТО състояние и новоповдигнатият се познава —
 * другите се свалят видимо и дотук стига само един вдигнат. Отказът пази онова, което екранът не
 * покрива: REST, внос, партиден запис и случаите, в които и там не може да се реши.
 *
 * <p><b>⚠️ ЗАВАРЕНАТА БАЗА С ДВА ВДИГНАТИ СЕ ОТКАЗВА СЪЩО.</b> Дружество, чиито декларатори вече са
 * два default-а, няма да се запише, докато не се оправят — включително при промяна, която няма нищо
 * общо с декларатори. Това е цената на инварианта и е нарочна: тихо преминаване би оставило
 * декларация, която взима „по подразбиране“ от два реда наведнъж.
 *
 * <p>Отказът е {@code PersistenceException} по същия идиом като {@code
 * BgPaySlipManagementRepository.remove}: {@code AxelorException} не се обявява в подписа на {@code
 * save} и не може да се хвърли оттук, а съобщението стига до екрана и по двата пътя.
 */
public class BgCompanyManagementRepository extends CompanyRepository {

  @Override
  public Company save(Company company) {
    String refusal =
        company == null
            ? null
            : BgDeclarantResolver.refuseMultipleDefaults(company.getBgDeclarantList());
    if (refusal == null) {
      refusal = stockCostingRefusal(company);
    }
    if (refusal != null) {
      throw new PersistenceException(refusal);
    }
    // „Ново“ — по белега на Hibernate, не по id: по пътя на екрана (Resource.save) дружеството е
    // вмъкнато ПРЕДИ тази верига и вече има id. Проверката по id не пускаше afterCreate от екрана.
    boolean isNew = BgJustInserted.consume(company);
    String partnerRefusal = companyPartnerRefusal(company, isNew);
    if (partnerRefusal != null) {
      throw new PersistenceException(partnerRefusal);
    }
    // Нов партньор, закачен на дружеството в кода (не от екрана): дружеството се записва ПЪРВО, а
    // партньорът — след него и в него. Иначе Hibernate вмъква партньора преди дружеството.
    Partner pending = detachNewPartner(company);
    Company saved = saveRecord(company);
    if (saved == null || saved.getId() == null) {
      return saved;
    }
    if (isNew) {
      // След записа: дружеството е управлявано и попълненото се записва при commit.
      fillReportLocale(saved);
      afterCreate(saved);
    }
    if (pending != null || (isNew && saved.getPartner() == null)) {
      saved.setPartner(createOwnPartner(saved, pending));
    }
    return saved;
  }

  /**
   * Празните език и часова зона на справките → bg_BG и Europe/Sofia ({@link
   * BgCompanyReportLocale}). Само за НОВО дружество: заварените ги попълва стартът ({@code
   * ensureCompanyReportLocale}), а изтритата от администратора стойност не се връща при всеки
   * запис. Редът bg_BG се търси само при празнота.
   */
  protected void fillReportLocale(Company company) {
    if (!BgCompanyReportLocale.hasGaps(company)) {
      return;
    }
    Localization bulgarian =
        company.getLocalization() != null
            ? null
            : Beans.get(LocalizationRepository.class)
                .all()
                .filter("self.code = :code")
                .bind("code", BgCompanyReportLocale.LOCALIZATION_CODE)
                .fetchOne();
    BgCompanyReportLocale.fillGaps(company, bulgarian);
  }

  /**
   * ПАРТНЬОРЪТ НА ДРУЖЕСТВОТО Е В САМОТО ДРУЖЕСТВО. Отказва се партньор на друго дружество и
   * системният партньор на потребител. Заварено дружество със заварен партньор (без дружество)
   * минава непроменено — него го разпределя отделна стъпка; ново дружество не може да вземе заварен
   * партньор: неговият се създава сам.
   */
  static String companyPartnerRefusal(Company company, boolean isNew) {
    Partner partner = company == null ? null : company.getPartner();
    if (partner == null) {
      return null;
    }
    if (partner.getNxSystem()) {
      return BgPartnerCompany.COMPANY_PARTNER_IS_SYSTEM;
    }
    if (partner.getId() == null) {
      return partner.getCompany() == null || BgPartnerCompany.same(partner.getCompany(), company)
          ? null
          : BgPartnerCompany.companyPartnerElsewhere(company);
    }
    if (partner.getCompany() == null) {
      return isNew ? BgPartnerCompany.companyPartnerElsewhere(company) : null;
    }
    return company.getId() != null && BgPartnerCompany.same(partner.getCompany(), company)
        ? null
        : BgPartnerCompany.companyPartnerElsewhere(company);
  }

  /** Нов (незаписан) партньор на дружеството — откача се, за да се запише след него. */
  static Partner detachNewPartner(Company company) {
    Partner partner = company == null ? null : company.getPartner();
    if (partner == null || partner.getId() != null) {
      return null;
    }
    company.setPartner(null);
    return partner;
  }

  /**
   * Партньорът на дружеството, записан СЛЕД него и в него: подаденият нов или — за ново дружество
   * без партньор — създаден от името му. Номерът идва от поредицата на дружеството (afterCreate я
   * вече е създал), без потребител и без активно дружество — тоест и на чиста инсталация.
   */
  protected Partner createOwnPartner(Company company, Partner pending) {
    Partner partner = pending;
    if (partner == null) {
      partner = new Partner();
      partner.setName(company.getName());
      partner.setPartnerTypeSelect(PartnerRepository.PARTNER_TYPE_COMPANY);
      partner.setIsContact(false);
    }
    partner.setCompany(company);
    // companySet остава празен — както в AOS за партньора на дружеството: дружество в набора кара
    // PartnerAccountRepository да създаде счетоводна ситуация, а ново дружество още няма счетоводна
    // конфигурация (записът му би паднал). Видимостта за ролите се определя от
    // условието в каталога, не оттук.
    return Beans.get(PartnerRepository.class).save(partner);
  }

  /**
   * Новото дружество получава шаблона на склада веднага, а не при следващото стартиране: без
   * StockConfig Axelor отказва всяко складово движение. Заварените го получават при стартиране —
   * виж {@code BgLocalizationServerStartListener.ensureStockSetup}.
   *
   * <p>Същото за поредицата на партньорите: без нея първият партньор на дружеството получава отказ
   * (обща поредица няма). Заварените — при стартиране, {@code ensurePartnerSequences}. Поредицата е
   * преди партньора на дружеството ({@link #createOwnPartner}) — той взема първия ѝ номер.
   *
   * <p>ТРЗ редиците на новото дружество НЕ са тук: хранилището не хваща вноса (data-init, App
   * Install) и {@code persist}/{@code merge} от код. Създава ги {@code
   * BgTrzCompanySequenceListener} на ниво Hibernate — по всеки път, включително този.
   */
  protected void afterCreate(Company company) {
    Beans.get(BgStockSetupService.class).ensureTemplate(company);
    Beans.get(BgStockCostingSetupService.class).ensureIssueJournal(company);
    Beans.get(BgPartnerSequenceSetupService.class).ensureFor(company);
  }

  /**
   * ИЗП: дружеството не минава на МСФО, докато политика за незавършила година ползва LIFO (МСС 2 не
   * го допуска). Политиката сама отказва LIFO при МСФО; тук е обратната врата — смяната на базата
   * след избора на метода.
   */
  protected String stockCostingRefusal(Company company) {
    if (company == null
        || company.getId() == null
        || !BgStockCostingRules.IFRS.equals(company.getBgAccountingFramework())) {
      return null;
    }
    LocalDate today = Beans.get(AppBaseService.class).getTodayDate(company);
    return BgStockCostingRules.frameworkChangeRefusal(
        company.getBgAccountingFramework(),
        Beans.get(BgStockCostingPolicyService.class).lifoYearNames(company, today));
  }

  /** Самият запис — свой шев, за да може тестът да провери пазача без JPA. */
  protected Company saveRecord(Company company) {
    return super.save(company);
  }
}
