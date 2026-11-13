package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Находките на един код, слети за четене в тестовете.
 *
 * <p>От G6a находката е ПО ЕДНА НА ЗАПИС ({@link BgSaftRef}); тестове, които питат „има ли запис X
 * сред находките с код Y“, четат всички наведнъж — съобщенията, свързани с „ | “. Нивото е на
 * първата (всички с един код са едно ниво). Указателят към записа не се слива — за него тестът чете
 * самите находки.
 */
final class BgSaftTestFindings {

  private BgSaftTestFindings() {}

  static Optional<BgDeclarationFileFinding> merged(
      List<BgDeclarationFileFinding> findings, String code) {
    List<BgDeclarationFileFinding> same =
        findings.stream().filter(f -> f.getCode().equals(code)).collect(Collectors.toList());
    if (same.isEmpty()) {
      return Optional.empty();
    }
    String message =
        same.stream().map(BgDeclarationFileFinding::getMessage).collect(Collectors.joining(" | "));
    return Optional.of(
        same.get(0).isBlocking()
            ? BgDeclarationFileFinding.error(code, message)
            : BgDeclarationFileFinding.warning(code, message));
  }
}
