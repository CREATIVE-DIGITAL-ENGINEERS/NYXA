package com.axelor.apps.bglocalization.service.fixedasset;

import com.axelor.apps.account.db.repo.FixedAssetRepository;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * ИНВЕНТАРНИЯТ НОМЕР И КАКВО ТОЙ ОЗНАЧАВА — правилата върху старото и новото състояние на актива
 * (SAF-T A1). Чисти функции; по всеки път ги вика {@link BgFixedAssetListener} (Hibernate), а
 * масовото обновяване — {@link BgFixedAssetRequestGuard}.
 *
 * <p><b>Защо.</b> Инвентарният номер ({@code fixedAssetSeq}) е AssetID в годишния SAF-T (MF.A.2):
 * уникален в дружеството, не се мени и не се преизползва. Той е и {@code Move.origin} на всички
 * статии на актива, тоест смяната му къса връзката с главната книга. Днес в AOS е readonly само в
 * изгледа — по REST се пише свободно, а валидиран актив се връща в чернова и се трие, тоест номерът
 * се освобождава.
 *
 * <ul>
 *   <li><b>След валидиране</b> (статус над чернова): инвентарният номер не се мени (празен може да
 *       се попълни — заварените от Microinvest); сметката и категорията на актива не се сменят
 *       (тиха смяна без статия чупи сверката с главната книга — вътрешният трансфер идва с A6c);
 *       статусът не се връща в чернова; активът не се трие.
 *   <li><b>„Отделен от“</b> ({@code bgSplitFrom}) се пише веднъж, само на актив, отделен със split
 *       (произход 4/5/6 — {@link #isSplit}), и после не се мени.
 *   <li><b>Видът</b> — държан за продажба и право на ползване само при рамка МСФО ({@link
 *       BgFixedAssetKinds#refusal}).
 * </ul>
 */
public final class BgFixedAssetGuard {

  static final String SEQ = "fixedAssetSeq";
  static final String STATUS = "statusSelect";
  static final String ACCOUNT = "purchaseAccount";
  static final String CATEGORY = "fixedAssetCategory";
  static final String SPLIT_FROM = "bgSplitFrom";
  static final String KIND = "bgAssetKindSelect";
  static final String ORIGIN = "originSelect";

  /** Не се пипат с масово обновяване — то минава покрай Hibernate и покрай правилата по-горе. */
  public static final List<String> NOT_MASS_UPDATABLE =
      Collections.unmodifiableList(Arrays.asList(SEQ, STATUS, ACCOUNT, CATEGORY, SPLIT_FROM, KIND));

  private BgFixedAssetGuard() {}

  /** Състоянието на актива, което правилата гледат; връзките — по id. */
  public static final class State {
    final Integer status;
    final String seq;
    final Long accountId;
    final Long categoryId;
    final Long splitFromId;
    final String kind;
    final Integer origin;

    public State(
        Integer status,
        String seq,
        Long accountId,
        Long categoryId,
        Long splitFromId,
        String kind,
        Integer origin) {
      this.status = status;
      this.seq = seq;
      this.accountId = accountId;
      this.categoryId = categoryId;
      this.splitFromId = splitFromId;
      this.kind = kind;
      this.origin = origin;
    }
  }

  /** Нов актив: „отделен от“ само при split; видът — по рамката. {@code null} — позволено. */
  public static String insertRefusal(State state, String framework) {
    if (state.splitFromId != null && !isSplit(state)) {
      return "„Отделен от“ се пише само на актив, отделен със split.";
    }
    return BgFixedAssetKinds.refusal(state.kind, framework);
  }

  /** Промяна: правилата по старото и новото състояние. {@code null} — позволено. */
  public static String updateRefusal(Long id, State old, State now, String framework) {
    String asset = "Актив #" + id;
    if (validated(old)) {
      if (!blank(old.seq) && !Objects.equals(trim(old.seq), trim(now.seq))) {
        return asset
            + ": инвентарният номер „"
            + old.seq.trim()
            + "“ не се сменя след валидиране — той е AssetID в SAF-T и Move.origin на статиите.";
      }
      if (!Objects.equals(old.accountId, now.accountId)) {
        return asset
            + ": сметката на валидиран актив не се сменя — смяната е вътрешен трансфер и иска"
            + " статия.";
      }
      if (!Objects.equals(old.categoryId, now.categoryId)) {
        return asset
            + ": категорията на валидиран актив не се сменя — смяната е вътрешен трансфер и иска"
            + " статия.";
      }
      if (now.status != null && now.status == FixedAssetRepository.STATUS_DRAFT) {
        return asset
            + ": валидиран актив не се връща в чернова — номерът му би се освободил за триене.";
      }
    }
    if (old.splitFromId != null && !Objects.equals(old.splitFromId, now.splitFromId)) {
      return asset + ": „Отделен от“ не се сменя.";
    }
    if (old.splitFromId == null && now.splitFromId != null && !isSplit(now)) {
      return asset + ": „Отделен от“ се пише само на актив, отделен със split.";
    }
    if (!Objects.equals(old.kind, now.kind)) {
      return BgFixedAssetKinds.refusal(now.kind, framework);
    }
    return null;
  }

  /** Триене: само чернова. {@code null} — позволено. */
  public static String deleteRefusal(Long id, State old) {
    return validated(old)
        ? "Актив #" + id + ": валидиран актив не се трие — инвентарният номер не се преизползва."
        : null;
  }

  static boolean validated(State state) {
    return state.status != null && state.status > FixedAssetRepository.STATUS_DRAFT;
  }

  /** Произход „разделяне“: split, частично бракуване, частична продажба (AOS пише 4). */
  static boolean isSplit(State state) {
    return state.origin != null
        && (state.origin == FixedAssetRepository.ORIGINAL_SELECT_SCISSION
            || state.origin == FixedAssetRepository.ORIGINAL_SELECT_PARTIAL_SCRAPPING
            || state.origin == FixedAssetRepository.ORIGINAL_SELECT_PARTIAL_SALE);
  }

  private static boolean blank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private static String trim(String value) {
    return value == null ? null : value.trim();
  }
}
