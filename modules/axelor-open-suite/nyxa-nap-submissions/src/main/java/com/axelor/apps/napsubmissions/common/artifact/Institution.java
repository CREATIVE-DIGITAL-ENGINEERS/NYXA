package com.axelor.apps.napsubmissions.common.artifact;

/**
 * WHICH AUTHORITY a regulatory file is addressed to — the discriminator of the shared core.
 *
 * <p><b>Why a discriminator and not two parallel stacks.</b> НАП (Д1/Д6, ДДС, SAF-T) and НОИ
 * (Приложение № 9) differ in everything a human cares about — the norm, the format, the transport,
 * the deadline. They do not differ at all in what the machine must guarantee about the produced
 * file: it is hashed once, stored once, and never edited afterwards. That guarantee had exactly one
 * implementation for НАП; НОИ becomes a second client of it rather than a second copy of it.
 *
 * <p><b>⚠️ The entity is still called {@code NapArtifact}, and that is deliberate, not an
 * oversight.</b> Its table is populated on live installations; renaming it is a migration with a
 * downtime window, not a refactor, and the branch that emits the first НОИ file is the wrong place
 * to take that risk. The discriminator makes the row honest today; the rename is written up as an
 * open item.
 *
 * <p>The codes are lower-case ASCII because they are stored in a selection column, next to {@code
 * nap.artifact.type.select}, which is lower-case too.
 */
public enum Institution {

  /** Национална агенция за приходите — Д1/Д6, ДДС, SAF-T. */
  NAP("nap", "НАП"),

  /** Национален осигурителен институт — удостоверения по НПОПДОО (Приложение № 9). */
  NOI("noi", "НОИ");

  private final String code;
  private final String label;

  Institution(String code, String label) {
    this.code = code;
    this.label = label;
  }

  /** The value stored in {@code NapArtifact.institutionSelect}. Never {@code null}. */
  public String getCode() {
    return code;
  }

  /** The short Bulgarian name, for messages a human reads. */
  public String getLabel() {
    return label;
  }

  /**
   * The institution for a stored code, or {@code null} for an unknown or absent one.
   *
   * <p>⚠️ Returns {@code null} rather than defaulting to {@link #NAP}: rows written before the
   * column existed carry no code, and reading them as "НАП" would turn a blank into a claim.
   */
  public static Institution of(String code) {
    if (code == null) {
      return null;
    }
    for (Institution value : values()) {
      if (value.code.equals(code)) {
        return value;
      }
    }
    return null;
  }
}
