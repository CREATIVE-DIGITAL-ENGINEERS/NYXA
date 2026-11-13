package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgDeclarant;
import com.axelor.apps.bglocalization.db.repo.BgDeclarantRepository;
import com.axelor.apps.bglocalization.service.BgDeclarantResolver;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ДРУЖЕСТВОТО откъм българската локализация — засега само решетката с декларатори.
 *
 * <p><b>⚠️ ДВЕТЕ ВРАТИ НА ЕДИН ИНВАРИАНТ, И ТЕ НЕ СЕ ПРИПОКРИВАТ.</b> Тази тук е ПРЕДИ записа и
 * работи на екрана: вдигне ли човек втори декларатор, другите се свалят ВИДИМО и до записа стига
 * само един вдигнат. Отказът в {@code BgCompanyManagementRepository} остава непокътнат зад нея —
 * той пази пътищата, които нямат екран (REST, внос, партиден запис) и случая, в който тук не може
 * да се реши.
 *
 * <p><b>⚠️ И ЗАЩО onChange НА ПОЛЕТО, А НЕ НА ОТМЕТКАТА В РЕДА.</b> Действие, закачено на полето в
 * реда, вижда {@code _parent}, но отговорът му се нанася САМО върху собствения запис на реда
 * ({@code axelor-front}, {@code views/form/builder/scope.ts}: {@code updateFormAtom} е {@code
 * formState.parent} само при JSON scope) — тоест оттам чуждите редове са недостижими. onChange на
 * самото one-to-many ПОЛЕ обаче се изпълнява в контекста на Дружеството и се задейства и при
 * редакция в реда, защото решетката подава {@code changed = true} при всяко потвърдено редактиране.
 */
@Singleton
public class BgCompanyController {

  /**
   * Вдигне ли се нов декларатор по подразбиране, другите се свалят — ПРЕДИ записа, видимо в
   * решетката.
   *
   * <p>⚠️ ЧЕТЕНЕТО ОТ БАЗАТА ТУК Е ВЯРНОТО ПРЕДИШНО СЪСТОЯНИЕ, И ТОВА Е ЦЕЛИЯТ ТРИК. При onChange
   * още нищо не е записано: {@code JPA.edit} не е минавал, няма мутирани обекти и следователно няма
   * какво да се flush-не, тъй че заявката вижда базата такава, каквато е била преди щракването.
   * Оттам се знае кой е СТАРИЯТ default и следователно кой е новият — знание, което при запис вече
   * не съществува.
   *
   * <p>⚠️ Редовете се променят НА МЯСТО и се връщат същите. Пресъздадени, те биха стигнали до
   * екрана без полетата, които решетката вече показва.
   *
   * <p><b>⚠️ И ЗАТОВА СУРОВИЯТ КОНТЕКСТ, А НЕ {@code asType(Company.class)}.</b> {@code
   * Context.get} за истинско поле връща ВАЛИДИРАНИЯ бийн, а {@code ContextHandler.createOrFind}
   * дава за ред с номер и без ключ {@code version} УПРАВЛЯВАНИЯ обект ({@code JPA.em().find}).
   * Свалянето на флага върху него би било мутация на жив запис — тоест onChange, който ПИШЕ в
   * базата, без човекът да е натиснал Запис. Суровите Map-ове нямат тази страна и са същият идиом
   * като {@code SaleOrderConfirmController}.
   */
  @SuppressWarnings("unchecked")
  public void clearOtherDeclarantDefaults(ActionRequest request, ActionResponse response) {
    try {
      Map<String, Object> raw = request.getRawContext();
      List<Map<String, Object>> rows = (List<Map<String, Object>>) raw.get("bgDeclarantList");
      if (rows == null || rows.isEmpty()) {
        return;
      }
      if (BgDeclarantResolver.clearOthersAroundNewlyRaised(rows, persistedDefaultIds(raw))) {
        response.setValue("bgDeclarantList", rows);
      }
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * Номерата на вдигнатите в базата за ТОВА дружество.
   *
   * <p>⚠️ Ново, незаписано дружество няма номер — тогава множеството е празно и всеки вдигнат ред
   * се брои за нов, което е вярно: заварен default няма.
   */
  protected Set<Long> persistedDefaultIds(Map<String, Object> rawContext) {
    Set<Long> ids = new HashSet<>();
    Object companyId = rawContext.get("id");
    if (!(companyId instanceof Number) || ((Number) companyId).longValue() <= 0L) {
      return ids;
    }
    List<BgDeclarant> persisted =
        Beans.get(BgDeclarantRepository.class)
            .all()
            .filter("self.company.id = :companyId AND self.isDefault = true")
            .bind("companyId", ((Number) companyId).longValue())
            .fetch();
    for (BgDeclarant declarant : persisted) {
      if (declarant.getId() != null) {
        ids.add(declarant.getId());
      }
    }
    return ids;
  }
}
