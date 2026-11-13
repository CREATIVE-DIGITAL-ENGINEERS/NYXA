package com.axelor.apps.bglocalization.service.art55;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgArt55Declaration;
import com.axelor.apps.bglocalization.db.BgDeclarant;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЧАСТ ІІ, Т. 7 НА ОБРАЗЕЦ 4001 — кой се предлага за представляващ и кога изобщо се предлага.
 *
 * <p>⚠️ Правилата седят в ЧИСТИ функции именно за да имат свидетел: модулът няма mock-подпора, а
 * изборът на декларатор не бива да се проверява само структурно. Самото попълване ({@code
 * freezeRepresentative}) е три присвоявания отгоре и няма собствено решение.
 */
class BgArt55RepresentativeTest {

  // ═══ ВРАТАТА: КОДЪТ ПО Т. 2 ════════════════════════════════════════════════════════

  @Test
  @DisplayName("СВИДЕТЕЛ · т. 7 се предлага САМО при код „1“ (ЕИК) — юридическо лице")
  void representativeOnlyForLegalEntity() {
    assertTrue(BgArt55DeclarationService.representativeApplies("1"), "ЕИК е юридическото лице");
    assertTrue(BgArt55DeclarationService.representativeApplies(" 1 "), "изчистен от интервали");

    assertFalse(BgArt55DeclarationService.representativeApplies("2"), "ЕГН — физическо лице");
    assertFalse(BgArt55DeclarationService.representativeApplies("3"), "ЛН");
    assertFalse(BgArt55DeclarationService.representativeApplies("4"), "ЛНЧ");
    assertFalse(BgArt55DeclarationService.representativeApplies("5"), "служебен № от НАП");
    assertFalse(BgArt55DeclarationService.representativeApplies(null), "неопределен код");
    assertFalse(BgArt55DeclarationService.representativeApplies(""), "празен код");
  }

  /**
   * ⚠️ ЗАЩО ВРАТАТА НЕ Е УКРАСА. Предложен представляващ при код, различен от „1“, пали блокиращо
   * нарушение — тоест удобството би произвело грешката, която иначе не съществува. Двата края на
   * правилото се сверяват тук заедно, за да не се разминат при следваща редакция.
   */
  @Test
  @DisplayName("СВИДЕТЕЛ · при код „2“ попълненият представляващ Е блокиращо нарушение")
  void fillingRepresentativeOutsideTheGateIsBlocking() {
    BgArt55Declaration declaration = new BgArt55Declaration();
    declaration.setTaxYear(2026);
    declaration.setQuarter(1);
    declaration.setPayerCodeType("2");
    declaration.setPayerIdentifier("8001011234");
    declaration.setPayerName("Самоосигуряващо се лице");
    declaration.setRepresentativeName("Иван Иванов Иванов");

    assertFalse(
        BgArt55DeclarationService.representativeApplies(declaration.getPayerCodeType()),
        "вратата е затворена за код „2“");

    List<BgDeclarationFileFinding> findings = BgArt55DeclarationValidator.validate(declaration);
    assertTrue(
        codes(findings).contains(BgArt55DeclarationValidator.REPRESENTATIVE_NOT_ALLOWED),
        "точно това нарушение пази вратата да не се отваря: " + codes(findings));
  }

  // ═══ ИЗБОРЪТ НА ДЕКЛАРАТОР ═════════════════════════════════════════════════════════

  @Test
  @DisplayName("СВИДЕТЕЛ · вдигнатият „По подразбиране“ печели пред останалите")
  void raisedDefaultWins() {
    BgDeclarant other = declarant("Петър Петров", "7001011234", false);
    BgDeclarant chosen = declarant("Мария Георгиева", "6505054321", true);

    assertSame(
        chosen,
        BgArt55DeclarationService.chooseRepresentative(Arrays.asList(other, chosen)),
        "флагът решава, а не редът в списъка");
  }

  @Test
  @DisplayName("без вдигнат флаг ЕДИНСТВЕНИЯТ декларатор пак се предлага")
  void theOnlyDeclarantIsProposedWithoutTheFlag() {
    BgDeclarant only = declarant("Мария Георгиева", "6505054321", false);

    assertSame(
        only, BgArt55DeclarationService.chooseRepresentative(Collections.singletonList(only)));
  }

  /**
   * ⚠️ НЕЯСНОТАТА СЕ ПРЕДАВА НА ЧОВЕКА, А НЕ СЕ РЕШАВА С ПОДРЕЖДАНЕ. Под т. 7 се подписва име, а
   * образецът носи наказателна отговорност за неверни данни — първият поред е догадка, празното
   * поле е въпрос, който валидаторът вече задава.
   */
  @Test
  @DisplayName("СВИДЕТЕЛ · двама без флаг НЕ дават предложение — първият поред е догадка")
  void twoWithoutFlagProposeNothing() {
    BgDeclarant first = declarant("Петър Петров", "7001011234", false);
    BgDeclarant second = declarant("Мария Георгиева", "6505054321", false);

    assertNull(BgArt55DeclarationService.chooseRepresentative(Arrays.asList(first, second)));
  }

  @Test
  @DisplayName("празен, липсващ и само-null списък не дават предложение")
  void emptyInputsProposeNothing() {
    assertNull(BgArt55DeclarationService.chooseRepresentative(null));
    assertNull(BgArt55DeclarationService.chooseRepresentative(new ArrayList<BgDeclarant>()));
    assertNull(
        BgArt55DeclarationService.chooseRepresentative(
            Arrays.asList((BgDeclarant) null, (BgDeclarant) null)));
  }

  // ═══ ПОМОЩНИ ═══════════════════════════════════════════════════════════════════════

  private static BgDeclarant declarant(String names, String egn, boolean isDefault) {
    BgDeclarant declarant = new BgDeclarant();
    declarant.setDeclarantNames(names);
    declarant.setDeclarantEgn(egn);
    declarant.setIsDefault(isDefault);
    return declarant;
  }

  private static List<String> codes(List<BgDeclarationFileFinding> findings) {
    List<String> out = new ArrayList<>();
    for (BgDeclarationFileFinding finding : findings) {
      out.add(finding.getCode());
    }
    return out;
  }
}
