package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.db.repo.BgApp9DeclarationRepository;
import com.axelor.apps.bglocalization.db.repo.BgSickLeaveRepository;
import com.axelor.apps.bglocalization.service.noi.BgApp9DeclarationService;
import com.axelor.apps.bglocalization.service.noi.BgApp9Emission;
import com.axelor.apps.bglocalization.service.noi.BgApp9ExportService;
import com.axelor.apps.bglocalization.service.noi.BgApp9Gap;
import com.axelor.apps.bglocalization.service.noi.BgApp9Resolution;
import com.axelor.apps.bglocalization.service.noi.BgApp9SubmissionService;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.auth.AuthUtils;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.time.LocalDate;

/**
 * БУТОНЪТ „ИЗВЕДИ/ОПРЕСНИ ОБСТОЯТЕЛСТВАТА“ — по образеца на {@code BgD1DeclarationController}.
 *
 * <p>Стои на формата на БОЛНИЧНИЯ, не на удостоверението: обстоятелствата се въвеждат и поправят
 * там, където операторът вече е дошъл за листа. Вързан е като {@code save,action-…,save} — записът
 * има идентификатор преди извеждането и произведените стойности оцеляват след него.
 *
 * <p><b>⚠️ СЪОБЩЕНИЕТО КАЗВА КОЛКО ОТКАЗА ИМА, А НЕ САМО „ГОТОВО“.</b> Присъдите се записват в
 * {@code resolverNotes} от службата, но полето е в свит панел — а отказ, който трябва да се
 * прочете, не бива да чака някой да разгъне панел. Затова броят излиза в самото съобщение и сочи
 * къде да се чете.
 *
 * <p><b>⚠️ НУЛА ПОДАВАНЕ, И ТОВА НЕ Е СЪЩОТО КАТО НУЛА ИЗНОС.</b> Ф2 добави {@link #generate} —
 * файлът се произвежда, проверява офлайн срещу схемата на НОИ и се закача като неизменяем артефакт.
 * Бутон „подай“ НЯМА и няма да има от код: подаването към портала иска КЕП и човек пред устройство.
 * Има само ОТБЕЛЯЗВАНЕ на вече направеното подаване ({@link #markSubmitted}).
 */
@Singleton
public class BgApp9DeclarationController {

  /**
   * Извежда обстоятелствата за болничния в контекста, като СЪЗДАВА удостоверението, ако го няма.
   *
   * <p>⚠️ Създаването е тук, а не при въвеждането на листа: удостоверение се подава за листовете,
   * за които се иска обезщетение, а не за всеки въведен лист. Създадено предварително за всички, то
   * би напълнило списъка с чернови, които никой не е поискал.
   */
  public void resolve(
      com.axelor.rpc.ActionRequest request, com.axelor.rpc.ActionResponse response) {
    try {
      BgSickLeave context = request.getContext().asType(BgSickLeave.class);
      CapabilityGate.requireRole("prepare an App. 9 certificate", Capabilities.NOI_WRITE);
      CapabilityGate.requireWrite(BgSickLeave.class, context.getId());
      if (context.getId() == null) {
        response.setInfo(I18n.get("Запишете болничния лист, преди да извеждате обстоятелствата."));
        return;
      }
      CapabilityGate.requireActiveCompany(
          payCompanyOf(Beans.get(BgSickLeaveRepository.class).find(context.getId())));

      report(response, resolveFor(context.getId()));

    } catch (IllegalStateException e) {
      response.setError(I18n.get(e.getMessage()));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * Същото извеждане, но повикано от формата на САМОТО удостоверение.
   *
   * <p>⚠️ Отделен метод, а не един с два прочита на контекста: {@code request.getContext()} тук
   * носи {@code BgApp9Declaration}, а там — {@code BgSickLeave}. Един метод, който гадае типа по
   * наличието на поле, би сработил грешно точно когато двата записа станат по-подобни.
   */
  public void resolveFromDeclaration(
      com.axelor.rpc.ActionRequest request, com.axelor.rpc.ActionResponse response) {
    try {
      BgApp9Declaration context = request.getContext().asType(BgApp9Declaration.class);
      CapabilityGate.requireRole("prepare an App. 9 certificate", Capabilities.NOI_WRITE);
      CapabilityGate.requireWrite(BgApp9Declaration.class, context.getId());
      if (context.getId() == null) {
        response.setInfo(I18n.get("Запишете удостоверението, преди да извеждате обстоятелствата."));
        return;
      }
      BgApp9Declaration stored = Beans.get(BgApp9DeclarationRepository.class).find(context.getId());
      CapabilityGate.requireActiveCompany(companyOf(stored));
      if (stored == null || stored.getSickLeave() == null) {
        response.setError(
            "Удостоверението няма болничен лист — обстоятелствата се извеждат ОТ листа.");
        return;
      }
      report(response, resolveFor(stored.getSickLeave().getId()));
    } catch (IllegalStateException e) {
      response.setError(I18n.get(e.getMessage()));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * БУТОНЪТ „ГЕНЕРИРАЙ ПРИЛОЖЕНИЕ 9 (НОИ)“ — емитер, валидатор, и файл САМО ако и двете минат.
   *
   * <p><b>⚠️ ПРИ ОТКАЗ СЕ ПОКАЗВА СПИСЪКЪТ „КАКВО ЛИПСВА“, А НЕ „готово“.</b> Това е същият урок
   * като при резолвера, направен една крачка по-строг: там присъдите се ЗАПИСВАТ и се броят в
   * съобщението; тук отказът СПИРА действието и изброява поправките. Причината е в цената: непълен
   * резолвер оставя празно поле на екран, а непълен файл заминава при НОИ и се връща като отказано
   * обезщетение на човек.
   *
   * <p><b>⚠️ И ЗАТОВА СЪОБЩЕНИЕТО Е {@code setError}, А НЕ {@code setInfo}.</b> Информационното
   * съобщение се затваря с Enter и се забравя; тук нищо не е произведено и това трябва да се види.
   */
  public void generate(
      com.axelor.rpc.ActionRequest request, com.axelor.rpc.ActionResponse response) {
    try {
      BgApp9Declaration context = request.getContext().asType(BgApp9Declaration.class);
      CapabilityGate.requireRole("generate an App. 9 certificate", Capabilities.NOI_WRITE);
      CapabilityGate.requireWrite(BgApp9Declaration.class, context.getId());
      if (context.getId() == null) {
        response.setInfo(I18n.get("Запишете удостоверението, преди да генерирате файла."));
        return;
      }
      CapabilityGate.requireActiveCompany(
          companyOf(Beans.get(BgApp9DeclarationRepository.class).find(context.getId())));

      BgApp9Emission emission = generateFor(context.getId());
      response.setReload(true);

      if (emission.isRefused()) {
        StringBuilder message = new StringBuilder();
        message
            .append("Файлът НЕ Е генериран. ")
            .append(emission.gapCount())
            .append(" неразрешени задължителни обстоятелства:\n\n");
        int shown = 0;
        for (BgApp9Gap gap : emission.getGaps()) {
          if (shown++ >= 8) {
            message.append("\n… и още ").append(emission.gapCount() - 8).append(".");
            break;
          }
          message.append("• ").append(gap.clause).append(" — ").append(gap.reason).append('\n');
        }
        message.append("\nЦелият списък е в поле „Какво липсва, за да е валидна“.");
        // Презареждането — през действието на грешката: клиентът при „error“ не стига до
        // „reload“ и формата би показвала отчета от предишния опит.
        response.setError(message.toString(), null, null, BgApp9ExportController.RELOAD_ACTION);
        return;
      }

      response.setInfo(
          "Файлът е генериран и закачен като НЕИЗМЕНЯЕМ артефакт ("
              + emission.getFileName()
              + "). Проверен е ОФЛАЙН срещу схемата на НОИ — виж „Проверки“."
              + " ⚠️ НЕ Е ПОДАДЕН: подаването иска КЕП и се прави от човек през портала.");

    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * ОТБЕЛЯЗВА, ЧЕ ФАЙЛЪТ НА ТОВА УДОСТОВЕРЕНИЕ Е ПОДАДЕН В НОИ — през същата услуга като износа.
   *
   * <p>⚠️ Отметката е на ФАЙЛА: ако файлът е месечният износ, отбелязват се всички удостоверения в
   * него — порталът дава един входящ номер на файл.
   */
  public void markSubmitted(
      com.axelor.rpc.ActionRequest request, com.axelor.rpc.ActionResponse response) {
    try {
      BgApp9Declaration context = request.getContext().asType(BgApp9Declaration.class);
      if (context.getId() == null) {
        response.setError(
            I18n.get("Запишете удостоверението, преди да го отбележите като подадено."));
        return;
      }
      // Прил. 9 — Capabilities.NOI_WRITE; същото като отметката на износа
      // (BgApp9ExportController).
      CapabilityGate.requireRole("mark an App. 9 certificate as submitted", Capabilities.NOI_WRITE);
      CapabilityGate.requireWrite(BgApp9Declaration.class, context.getId());
      BgApp9Declaration declaration =
          Beans.get(BgApp9DeclarationRepository.class).find(context.getId());
      CapabilityGate.requireActiveCompany(companyOf(declaration));
      NapSubmission submission =
          Beans.get(BgApp9SubmissionService.class)
              .markSubmitted(
                  declaration.getBgNoiArtifact(),
                  context.getBgPortalIncomingNumber(),
                  context.getBgPortalSubmittedDate(),
                  AuthUtils.getUser(),
                  LocalDate.now());
      response.setInfo(
          "Отбелязано като подадено в НОИ с вх. № "
              + submission.getNapIncomingNumber()
              + ". ⚠️ Това е ОТБЕЛЯЗВАНЕ, а не подаване: приложението не изпраща към НОИ.");
      response.setReload(true);
    } catch (IllegalStateException e) {
      response.setError(I18n.get(e.getMessage()));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * Чие е удостоверението: на дружеството-платец по договора на листа — същият път, по който е и
   * условието на правата ({@code sickLeave.employmentContract.payCompany}).
   */
  private static com.axelor.apps.base.db.Company companyOf(BgApp9Declaration declaration) {
    return declaration == null ? null : payCompanyOf(declaration.getSickLeave());
  }

  private static com.axelor.apps.base.db.Company payCompanyOf(BgSickLeave sheet) {
    return sheet == null || sheet.getEmploymentContract() == null
        ? null
        : sheet.getEmploymentContract().getPayCompany();
  }

  /** Транзакцията на генерирането — отделена, за да е ясно какво точно се записва. */
  @Transactional(rollbackOn = Exception.class)
  protected BgApp9Emission generateFor(Long declarationId) throws java.io.IOException {
    BgApp9DeclarationRepository declarations = Beans.get(BgApp9DeclarationRepository.class);
    BgApp9Declaration declaration = declarations.find(declarationId);
    BgApp9Emission emission =
        Beans.get(BgApp9ExportService.class).generateForDeclaration(declaration);
    declarations.save(declaration);
    return emission;
  }

  /**
   * СЪОБЩЕНИЕТО КЪМ ОПЕРАТОРА — тук върнатата резолюция се ПОЛЗВА, а не се изхвърля.
   *
   * <p>Общо за двата входа, за да не се разминат текстовете: два различни текста за един и същ
   * изход биха накарали оператора да търси разлика, каквато няма.
   */
  protected void report(com.axelor.rpc.ActionResponse response, BgApp9Resolution resolution) {
    // ⚠️ САМО ЗА АДМИНИСТРАТОР: „Изведени точки: … присъди“ е техническият
    // изход на резолвера; операторът вижда само презаредената форма. Панелът „Присъди на
    // резолвера“ е скрит по същото условие.
    if (!showsResolverVerdicts()) {
      response.setReload(true);
      return;
    }
    StringBuilder message = new StringBuilder();
    message
        .append("Изведени точки: ")
        .append(resolution.derivedCount())
        .append(" от ")
        .append(resolution.outcomes.size())
        .append(" присъди.");
    if (resolution.refusalCount() > 0) {
      message
          .append(" ⚠️ ОТКАЗИ: ")
          .append(resolution.refusalCount())
          .append(". Причината за всеки е в „Присъди на резолвера“ — точка, останала празна заради")
          .append(" отказ, НЕ значи, че обстоятелството не е налице.");
    }
    response.setInfo(message.toString());
    response.setReload(true);
  }

  /** Присъдите на резолвера се показват само на администратор — шев за unit теста. */
  protected boolean showsResolverVerdicts() {
    return AuthUtils.isAdmin(AuthUtils.getUser());
  }

  /**
   * Намира или създава удостоверението и извежда в него — през {@link
   * BgApp9DeclarationService#createOrResolve}, който отказва ново удостоверение за невалидиран
   * лист.
   *
   * <p>⚠️ БЕЗ {@code @Transactional} ТУК: транзакцията е на услугата, а отказът ѝ е ПРЕДИ нея.
   * Обвит отвън, отказът щеше да е вътре в транзакция.
   *
   * <p>⚠️ Върнатата {@link BgApp9Resolution} НЕ СЕ ИЗХВЪРЛЯ — тя пълни съобщението към оператора.
   * Точно това не се случваше в четирите генератора на фиша, докато {@code BgPaySlipService}
   * пренебрегваше върнатите списъци с откази; днес и той предупреждава с {@code
   * BgPaySlipResolver#generatorRefusalWarning}.
   */
  protected BgApp9Resolution resolveFor(Long sickLeaveId) {
    BgSickLeave sickLeave = Beans.get(BgSickLeaveRepository.class).find(sickLeaveId);
    // Създаването и отказът за невалидиран лист са в услугата — виж
    // BgApp9DeclarationService#createOrResolve.
    return Beans.get(BgApp9DeclarationService.class).createOrResolve(sickLeave);
  }
}
