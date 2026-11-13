package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgD1Declaration;
import com.axelor.apps.bglocalization.db.BgD6Declaration;
import com.axelor.apps.bglocalization.db.repo.BgD1DeclarationRepository;
import com.axelor.apps.bglocalization.db.repo.BgD6DeclarationRepository;
import com.axelor.apps.bglocalization.report.IReport;
import com.axelor.apps.bglocalization.service.d1.BgD1PeriodLockService;
import com.axelor.apps.bglocalization.service.d1.BgD1SubmissionService;
import com.axelor.apps.bglocalization.service.d1.BgD6GenerationService;
import com.axelor.apps.bglocalization.service.d1.BgD6ReportPrintService;
import com.axelor.apps.bglocalization.service.d1.dto.BgD1FindingDto;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.apps.report.engine.ReportSettings;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.studio.db.AppBgLocalization;
import com.axelor.studio.db.repo.AppBgLocalizationRepository;
import com.google.inject.Singleton;
import java.util.List;

/**
 * Бутоните на Декларация обр. № 6 — по образеца на {@link BgD1DeclarationController}.
 *
 * <p><b>⚠️ ДО ТОЗИ КЛОН ЕМИТЕРЪТ НА Д6 БЕШЕ НЕДОСТИЖИМ ОТ ИНТЕРФЕЙСА.</b> {@code BgD6Emitter} и
 * {@code BgD6Aggregator} съществуват от WS-7 и са покрити със свидетели, но нямаше нито един бутон,
 * който да ги извика — тоест декларацията се пълнеше само от тест. Това не е дребно: услуга без
 * читател е мнение, което остарява мълчаливо (същият довод, с който шестте флага на речника
 * получиха читател в WS-7).
 *
 * <p><b>⚠️ РАВЕНСТВОТО ПО ЧЛ. 2 АЛ. 4 СЕ ПРОВЕРЯВА СРЕЩУ ЗАПИСИТЕ НА Д1, тоест иска ги.</b> Затова
 * генерирането ТЪРСИ декларацията обр. 1 за същото дружество и месец. Липсва ли тя, проверката НЕ
 * се прави и това се ОБЯВЯВА — мълчаливо пропусната, тя би оставила Д6 да изглежда сверена.
 */
@Singleton
public class BgD6DeclarationController {

  /** Отказът на ново генериране след подаване. */
  static final String REGENERATION_LOCKED =
      "За периода има подадена декларация обр. 1/6 (подадена, приета, за корекция или с неясен"
          + " изход). Ново генериране е заключено: Д6 би се разминала с подадения файл. Поправка на"
          + " подадено се прави с нова, коригираща декларация, а не с подмяна.";

  public void generate(ActionRequest request, ActionResponse response) {
    try {
      BgD6Declaration context = request.getContext().asType(BgD6Declaration.class);
      CapabilityGate.requireRole("generate a D6 declaration", Capabilities.PAYROLL_WRITE);
      CapabilityGate.requireWrite(BgD6Declaration.class, context.getId());
      // Дружеството — от ЗАПИСАНОТО: клиентът изпраща каквото дружество поиска.
      BgD6Declaration stored =
          context.getId() == null
              ? context
              : Beans.get(BgD6DeclarationRepository.class).find(context.getId());
      CapabilityGate.requireActiveCompany(stored == null ? null : stored.getCompany());
      if (context.getId() == null) {
        response.setInfo(I18n.get("Запишете декларацията, преди да я генерирате."));
        return;
      }
      if (context.getBgPeriod() == null) {
        response.setError(
            "Периодът е задължителен — той пълни т. 9 (месецът и годината, ЗА КОИТО се отнасят"
                + " вноските).");
        return;
      }
      if (Boolean.TRUE.equals(context.getBgIsHistoricalImport())) {
        response.setError(
            "Исторически внесена декларация (миграция) — генерирането е ЗАМЯНА и би изтрило записите,"
                + " преписани от вече подадена декларация в Microinvest.");
        return;
      }
      // Един документ — едно подаване: при подадена Д1/Д6 за периода ново генериране би подменило
      // Д6, която вече е при НАП (решение 17–18.10.2026).
      if (Beans.get(BgD1PeriodLockService.class).isDeclaredToNap(stored.getBgPeriod())) {
        response.setError(I18n.get(REGENERATION_LOCKED));
        return;
      }
      // ⚠️ Сверката с Д1 отказва с изключение ПРЕДИ емитера — тук то става грешка на екрана,
      // с текста си, а не следа на стека. Същият път носи и отказа за вида плащане 5/6 (ръчен
      // избор, решение 19.10.2026): дали изобщо има блок на заплатите, знае само услугата —
      // Д6 само с обезщетение по вид 27 има вид 6 по норма (BgD6Blocks).
      List<BgD1FindingDto> findings;
      try {
        findings = generateFor(context.getId());
      } catch (IllegalStateException refused) {
        response.setError(I18n.get(refused.getMessage()));
        return;
      }
      StringBuilder message = new StringBuilder("Декларацията е генерирана.");
      if (!findings.isEmpty()) {
        message.append(" Находки: ").append(findings.size()).append(" - виж панела \"Проверки\".");
      }
      response.setInfo(message.toString());
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * Самото генериране — ДЕЛЕГИРА на услугата ({@link BgD6GenerationService}), по образеца на Д1.
   *
   * <p>⚠️ Тялото е в услугата, защото изборът на фишове вече е ОБЩ с редовната Д1 ({@code
   * BgEffectivePaySlips#effectiveFor}) и сверката с Д1 иска свидетел по реалния път.
   */
  protected List<BgD1FindingDto> generateFor(Long declarationId) {
    return Beans.get(BgD6GenerationService.class).generate(declarationId);
  }

  /**
   * Декларацията обр. 1 за същото дружество и същия месец, или {@code null}.
   *
   * <p>⚠️ ТЪРСИ СЕ ПО ПЕРИОДА НА ДАННИТЕ, не по периода на подаване: Д6 носи месеца, "за които се
   * отнасят вноските“ (указание т. 9), а на Д1 това е {@code bgDataPeriod}. Съпоставена по {@code
   * bgSubmissionPeriod}, заявката би пропуснала точно случая на чл. 4 ал. 1 т. 1 б. "б“ —
   * допълнителни доходи, начислени след 25-о число, където двата периода се различават.
   *
   * <p>⚠️ Взима се НАЙ-НОВАТА: при коригираща Д1 старата остава в базата и сравнението трябва да е
   * срещу последното състояние.
   */
  protected BgD1Declaration d1For(BgD6Declaration declaration) {
    try {
      return Beans.get(BgD1DeclarationRepository.class)
          .all()
          .filter("self.company = :company AND self.bgDataPeriod = :period")
          .bind("company", declaration.getCompany())
          .bind("period", declaration.getBgPeriod())
          .order("-id")
          .fetchOne();
    } catch (RuntimeException e) {
      // Липсваща база е СЪСТОЯНИЕ НА СРЕДАТА, не отговор "няма Д1“ — но и двете водят до обявена
      // непроверена проверка, тоест тук разликата не мени поведението.
      return null;
    }
  }

  public void print(ActionRequest request, ActionResponse response) {
    try {
      BgD6Declaration declaration = request.getContext().asType(BgD6Declaration.class);
      CapabilityGate.requireRead(BgD6Declaration.class, declaration.getId());
      if (declaration.getId() == null || "draft".equals(declaration.getStatusSelect())) {
        response.setInfo(I18n.get("Първо генерирайте декларацията, за да има какво да се печата."));
        return;
      }
      String fileLink =
          Beans.get(BgD6ReportPrintService.class)
              .print(
                  Beans.get(BgD6DeclarationRepository.class).find(declaration.getId()),
                  IReport.BG_D6_DECLARATION,
                  ReportSettings.FORMAT_PDF,
                  "Декларация-обр-6");
      response.setView(
          ActionView.define("Декларация обр. 6")
              .add("html", fileLink)
              .param("download", "false")
              .map());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * ЗАКАЧА ДВАТА ФАЙЛА КЪМ ТРАНСПОРТА и отваря подаването. <b>НЕ подава.</b>
   *
   * <p><b>⚠️ БУТОНЪТ СЕ НАРИЧА "Закачи файловете“, А НЕ "Подай“, и това не е предпазливост в
   * текста.</b> Подаването иска детачен PKCS#7 подпис на точните байтове и публичния сертификат на
   * подаващия — частният ключ на КЕП никога не влиза в ERP-а. Тоест бутон "Подай“ би обещавал нещо,
   * което този слой не може да направи, а следващата стъпка (тестова среда на НАП) е човешка.
   */
  public void attachToSubmission(ActionRequest request, ActionResponse response) {
    try {
      BgD6Declaration context = request.getContext().asType(BgD6Declaration.class);
      CapabilityGate.requireRole(
          "attach a D6 declaration to an НАП submission", Capabilities.PAYROLL_WRITE);
      CapabilityGate.requireWrite(BgD6Declaration.class, context.getId());
      if (context.getId() == null || "draft".equals(context.getStatusSelect())) {
        response.setInfo(I18n.get("Първо генерирайте декларацията."));
        return;
      }
      BgD6Declaration declaration =
          Beans.get(BgD6DeclarationRepository.class).find(context.getId());
      CapabilityGate.requireActiveCompany(declaration == null ? null : declaration.getCompany());
      if (Boolean.TRUE.equals(declaration.getBgIsHistoricalImport())) {
        response.setError(
            "Исторически внесена декларация (миграция) — вече е била подадена в Microinvest и не"
                + " минава през това подаване.");
        return;
      }
      BgD1Declaration d1 = d1For(declaration);
      if (d1 == null) {
        response.setError(
            "За това дружество и месец няма Декларация обр. 1. ⚠️ Транспортът иска ЕДНО подаване с"
                + " ДВА файла (serviceDocumentType = DEC_1_6): байтовете на EMPL2021.TXT идват от"
                + " записите на Д1. Генерирай я и повтори.");
        return;
      }
      if (Boolean.TRUE.equals(d1.getBgIsHistoricalImport())) {
        response.setError(
            "Декларация обр. 1 за същото дружество и месец е исторически внесена (миграция) — не"
                + " минава през това подаване.");
        return;
      }
      NapSubmission submission =
          Beans.get(BgD1SubmissionService.class).attach(d1, declaration, sourceFlagOf(d1));

      response.setView(
          ActionView.define("Подаване към НАП (чернова)")
              .model(NapSubmission.class.getName())
              .add("form", "nap-submission-form")
              .context("_showRecord", submission.getId())
              .map());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * Поле 53 на Д1 — "флаг за източник“: ЕИК на РАЗРАБОТЧИКА на софтуера, от настройката на
   * инсталацията ({@code AppBgLocalization.bgSoftwareDeveloperEik}).
   *
   * <p>⚠️ ДОТУК ТУК СТОЕШЕ ЕИК-ЪТ НА ОСИГУРИТЕЛЯ — избор "докато няма тестово подаване“. Приетите
   * от НАП еталони (10.2026) носят в поле 53 ЕИК, различен от т. 4, а заповедта казва изрично
   * "разработчика на софтуер“. Празната настройка НЕ се подменя: отказът е в {@code
   * BgD1SubmissionPlan#sourceFlagRefusal}, преди да се пипне базата.
   */
  protected String sourceFlagOf(BgD1Declaration d1) {
    AppBgLocalization app = Beans.get(AppBgLocalizationRepository.class).all().fetchOne();
    return app == null ? null : app.getBgSoftwareDeveloperEik();
  }
}
