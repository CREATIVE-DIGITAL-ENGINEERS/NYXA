package com.axelor.apps.bglocalization.service.noi;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ЗАГЛАВНАТА ЧАСТ НА ФАЙЛА — всичко, което е ЕДНО за целия износ, а не за отделния лист.
 *
 * <p>Схемата държи тези полета на три места: преди {@code DocList} (осигурителят, ТП на НОИ, флагът
 * за упълномощаване), вътре във всеки документ ({@code Representative}, {@code DateCertificate},
 * {@code DateSaved}/{@code TimeSaved}) и след списъка ({@code Exported}, {@code DateExport}, {@code
 * Source}). Тук са събрани заедно, защото ИЗТОЧНИКЪТ им е един — износът, — а не защото схемата ги
 * държи заедно.
 *
 * <p><b>⚠️ ДЛЪЖНОСТНОТО ЛИЦЕ Е ПО ФАЙЛ, ВЪПРЕКИ ЧЕ СХЕМАТА ГО ИСКА ПО ДОКУМЕНТ.</b> Един износ се
 * подписва от едно лице; повторено на всеки документ, то би било едно и също копирано число.
 * Разминаване тук няма: емитерът го изписва във ВСЕКИ {@code NumberPril9}, както схемата иска.
 *
 * <p><b>⚠️ ТП НА НОИ (1–28) НЯМА НОСИТЕЛ НИКЪДЕ В МОДЕЛА и затова е ВХОДНО поле на износа.</b>
 * Контролът е „задължително попълнена стойност от 1 до 28“ — тоест празното спира приема на целия
 * файл, а не един документ. Изведено „по адреса на дружеството“, то щеше да е догадка за
 * съответствие между области и териториални поделения, каквото номенклатурата на НОИ определя, а
 * репото не носи.
 *
 * <p>Обектът е НЕИЗМЕНЯЕМ и не пази нищо от базата.
 */
public final class BgApp9EmitterContext {

  /**
   * {@code Exported} = 1 — подаване ЧРЕЗ ФАЙЛ. Единствената стойност, която тази фаза произвежда.
   */
  public static final String EXPORTED_BY_FILE = "1";

  /** {@code TypeOfDocument} = 0 — „удостоверения към болничен лист“, единствената стойност. */
  public static final String TYPE_OF_DOCUMENT_SICK_LEAVE = "0";

  /** {@code FlagDelegated}: 0 — от осигурителя; 1 — чрез упълномощено лице. */
  public final String flagDelegated;

  /** {@code FlagKasa}: 0 — осигурител/СОЛ; 1 — осигурителна каса. */
  public final String flagKasa;

  /** {@code BULSTATKasa} — ЕИК на подателя. 9, 11 или 13 цифри. */
  public final String bulstatKasa;

  /** {@code BulName} — наименованието на подателя, до 200 знака. */
  public final String bulName;

  /** {@code BULSTAT} — ЕИК на члена на касата; празно, когато не се подава чрез каса. */
  public final String bulstatMember;

  /** {@code NSSI_RO} — кодът на териториалното поделение, 1–28. */
  public final Integer nssiRoCode;

  /** {@code NSSI_RO_Name} — текстът към кода, до 15 знака. */
  public final String nssiRoName;

  /** {@code BulRegion} — област. Контролът иска поне област и населено място. */
  public final String bulRegion;

  /** {@code BulSubRegion} — община. */
  public final String bulSubRegion;

  /** {@code BulCity} — населено място. */
  public final String bulCity;

  /**
   * Защо адресът на осигурителя не става — назованата причина от {@link BgApp9Place}, или {@code
   * null}. Отделно от трите полета, за да може отказът да каже КАКВО да се поправи, а не само че
   * областта е празна.
   */
  public final String bulAddressProblem;

  /** {@code BulPostalCode} — пощенски код, до 4 знака. */
  public final String bulPostalCode;

  /** {@code BulAddress} — адрес, до 100 знака. */
  public final String bulAddress;

  /** {@code BulPhone}. */
  public final String bulPhone;

  /** {@code BulCellPhoneNumber}. */
  public final String bulCellPhone;

  /** {@code BulEMail}. */
  public final String bulEmail;

  /** {@code Representative/FirstName}. */
  public final String representativeFirstName;

  /** {@code Representative/SurName}. */
  public final String representativeSurName;

  /** {@code Representative/FamilyName} — контролът иска поне фамилия. */
  public final String representativeFamilyName;

  /** {@code Representative/Position} — длъжност, 2–100 знака, задължителна. */
  public final String representativePosition;

  /** {@code Representative/City}. */
  public final String representativeCity;

  /** {@code DateCertificate} — датата на удостоверението, задължителна. */
  public final LocalDate certificateDate;

  /** {@code DateExport} — датата на износа. */
  public final LocalDate exportDate;

  /** {@code DateSaved}/{@code TimeSaved} — кога данните са записани в локалната база. */
  public final LocalDateTime savedAt;

  /**
   * {@code Source} — „флаг на източник“, 9/11/13 цифри.
   *
   * <p>⚠️ ЕИК-ЪТ НА ОСИГУРИТЕЛЯ, а не на разработчика, и това е СЪЩИЯТ ИЗБОР като при поле 53 на Д1
   * ({@code BgD6DeclarationController#sourceFlagOf}). Схемата описва полето като БУЛСТАТ на
   * разработчика на софтуера; ЕИК на Nyxa като разработчик не е набавен, а полето е задължително за
   * прием. Избран е ЕИК-ът на подателя — стойност, която не твърди нищо ново. Ако НОИ изисква
   * друго, това се вижда при ръчна проверка през уеб-валидатора и се сменя на едно място.
   */
  public final String source;

  private BgApp9EmitterContext(Builder b) {
    this.flagDelegated = b.flagDelegated;
    this.flagKasa = b.flagKasa;
    this.bulstatKasa = b.bulstatKasa;
    this.bulName = b.bulName;
    this.bulstatMember = b.bulstatMember;
    this.nssiRoCode = b.nssiRoCode;
    this.nssiRoName = b.nssiRoName;
    this.bulRegion = b.bulRegion;
    this.bulSubRegion = b.bulSubRegion;
    this.bulCity = b.bulCity;
    this.bulAddressProblem = b.bulAddressProblem;
    this.bulPostalCode = b.bulPostalCode;
    this.bulAddress = b.bulAddress;
    this.bulPhone = b.bulPhone;
    this.bulCellPhone = b.bulCellPhone;
    this.bulEmail = b.bulEmail;
    this.representativeFirstName = b.representativeFirstName;
    this.representativeSurName = b.representativeSurName;
    this.representativeFamilyName = b.representativeFamilyName;
    this.representativePosition = b.representativePosition;
    this.representativeCity = b.representativeCity;
    this.certificateDate = b.certificateDate;
    this.exportDate = b.exportDate;
    this.savedAt = b.savedAt;
    this.source = b.source;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Сглобяване по едно поле — по образеца на {@code BgApp9Facts.Builder}. */
  public static final class Builder {
    private String flagDelegated = "0";
    private String flagKasa = "0";
    private String bulstatKasa;
    private String bulName;
    private String bulstatMember = "";
    private Integer nssiRoCode;
    private String nssiRoName;
    private String bulRegion;
    private String bulSubRegion;
    private String bulCity;
    private String bulAddressProblem;
    private String bulPostalCode;
    private String bulAddress;
    private String bulPhone;
    private String bulCellPhone;
    private String bulEmail;
    private String representativeFirstName;
    private String representativeSurName;
    private String representativeFamilyName;
    private String representativePosition;
    private String representativeCity;
    private LocalDate certificateDate;
    private LocalDate exportDate;
    private LocalDateTime savedAt;
    private String source;

    public Builder flagDelegated(String v) {
      this.flagDelegated = v;
      return this;
    }

    public Builder flagKasa(String v) {
      this.flagKasa = v;
      return this;
    }

    public Builder bulstatKasa(String v) {
      this.bulstatKasa = v;
      return this;
    }

    public Builder bulName(String v) {
      this.bulName = v;
      return this;
    }

    public Builder bulstatMember(String v) {
      this.bulstatMember = v;
      return this;
    }

    public Builder nssiRo(Integer code, String name) {
      this.nssiRoCode = code;
      this.nssiRoName = name;
      return this;
    }

    /** Причината от {@link BgApp9Place}, когато адресът на осигурителя не става. */
    public Builder addressProblem(String problem) {
      this.bulAddressProblem = problem;
      return this;
    }

    public Builder address(
        String region,
        String subRegion,
        String city,
        String postalCode,
        String address,
        String phone,
        String cellPhone,
        String email) {
      this.bulRegion = region;
      this.bulSubRegion = subRegion;
      this.bulCity = city;
      this.bulPostalCode = postalCode;
      this.bulAddress = address;
      this.bulPhone = phone;
      this.bulCellPhone = cellPhone;
      this.bulEmail = email;
      return this;
    }

    public Builder representative(
        String firstName, String surName, String familyName, String position, String city) {
      this.representativeFirstName = firstName;
      this.representativeSurName = surName;
      this.representativeFamilyName = familyName;
      this.representativePosition = position;
      this.representativeCity = city;
      return this;
    }

    public Builder certificateDate(LocalDate v) {
      this.certificateDate = v;
      return this;
    }

    public Builder exportDate(LocalDate v) {
      this.exportDate = v;
      return this;
    }

    public Builder savedAt(LocalDateTime v) {
      this.savedAt = v;
      return this;
    }

    public Builder source(String v) {
      this.source = v;
      return this;
    }

    public BgApp9EmitterContext build() {
      return new BgApp9EmitterContext(this);
    }
  }
}
