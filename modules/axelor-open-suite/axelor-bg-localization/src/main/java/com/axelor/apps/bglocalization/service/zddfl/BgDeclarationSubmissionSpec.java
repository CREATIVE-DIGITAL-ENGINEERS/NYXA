package com.axelor.apps.bglocalization.service.zddfl;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.meta.db.MetaFile;
import java.time.LocalDate;

/**
 * ВСИЧКО, КОЕТО ЕДНО ПОДАВАНЕ ПО ЗДДФЛ ИСКА — попълнено от образеца, който го праща.
 *
 * <p><b>⚠️ ПАРАМЕТЪР-ОБЕКТ, А НЕ ОБЩ РОДИТЕЛ НА ДВАТА ОБРАЗЕЦА.</b> {@code BgArt55Declaration} и
 * {@code BgArt73Para1Report} нямат общ предшественик и не бива да получават такъв заради
 * транспорта: единият е тримесечен и има три реда с числа, другият е годишен и има списък от лица.
 * Общ интерфейс върху тях би съществувал само за да бъде подаден тук.
 *
 * <p>⚠️ {@code metaFile} е ВЕЧЕ КАЧЕНИЯТ файл, а не байтове: той се ражда при ГЕНЕРИРАНЕ, за да
 * може човек да го свали и провери ПРЕДИ да го закачи. Това е разликата срещу {@code
 * BgD1SubmissionService#persist}, който качва в момента на закачането.
 */
public final class BgDeclarationSubmissionSpec {

  private Company company;
  private String name;
  private LocalDate referenceDate;
  private String taxpayerPin;
  private String relatedModel;
  private Long relatedRecordId;
  private NapSubmission previousSubmission;
  private MetaFile metaFile;
  private NapArtifact artifact;
  private String fileName;
  private Integer recordCount;
  private String incomingNumber;
  private LocalDate incomingDate;
  private String serviceCode;

  /**
   * Видът на подаването в регистъра ({@code nap.service.code.select}); празно — {@code
   * declarations}, както за образците по ЗДДФЛ. FATCA носи свой вид, за да не се чете като
   * декларация по Declarations API.
   */
  public String getServiceCode() {
    return serviceCode;
  }

  public BgDeclarationSubmissionSpec setServiceCode(String serviceCode) {
    this.serviceCode = serviceCode;
    return this;
  }

  public Company getCompany() {
    return company;
  }

  public BgDeclarationSubmissionSpec setCompany(Company company) {
    this.company = company;
    return this;
  }

  /** Името за човек, напр. „Справка чл. 73 ал. 1 / 2026“. */
  public String getName() {
    return name;
  }

  public BgDeclarationSubmissionSpec setName(String name) {
    this.name = name;
    return this;
  }

  /** ⚠️ СРОКЪТ, а не днешната дата: срещу него одитната следа се чете „подадено ли е навреме“. */
  public LocalDate getReferenceDate() {
    return referenceDate;
  }

  public BgDeclarationSubmissionSpec setReferenceDate(LocalDate referenceDate) {
    this.referenceDate = referenceDate;
    return this;
  }

  public String getTaxpayerPin() {
    return taxpayerPin;
  }

  public BgDeclarationSubmissionSpec setTaxpayerPin(String taxpayerPin) {
    this.taxpayerPin = taxpayerPin;
    return this;
  }

  public String getRelatedModel() {
    return relatedModel;
  }

  public BgDeclarationSubmissionSpec setRelatedModel(String relatedModel) {
    this.relatedModel = relatedModel;
    return this;
  }

  public Long getRelatedRecordId() {
    return relatedRecordId;
  }

  public BgDeclarationSubmissionSpec setRelatedRecordId(Long relatedRecordId) {
    this.relatedRecordId = relatedRecordId;
    return this;
  }

  /**
   * ПРЕДИШНОТО подаване, когато това го замества.
   *
   * <p>⚠️ Веригата е НА {@code NapSubmission} ({@code previousSubmission}, „Supersedes“), а не на
   * отчета: отчетът носи ЕДНА връзка към последното подаване, а одитната следа иска всички. Старото
   * подаване и неговите файлове остават непокътнати — корекцията по образеца е НОВА декларация с
   * всички данни за периода, а не редакция на подадената.
   */
  public NapSubmission getPreviousSubmission() {
    return previousSubmission;
  }

  public BgDeclarationSubmissionSpec setPreviousSubmission(NapSubmission previousSubmission) {
    this.previousSubmission = previousSubmission;
    return this;
  }

  /**
   * ВЕЧЕ СЪЗДАДЕНИЯТ артефакт за същите байтове, ако генерирането е минало през {@code
   * RegulatoryFile}.
   *
   * <p><b>⚠️ ПОДАДЕН, ТОЙ СЕ ПРЕИЗПОЛЗВА — НЕ СЕ ПРАВИ ВТОРИ.</b> {@code createFromMetaFile} би
   * създал нов ред със свой отпечатък за същото съдържание; тогава на въпроса „кой артефакт е
   * подаден“ има два еднакво правдоподобни отговора.
   */
  public NapArtifact getArtifact() {
    return artifact;
  }

  public BgDeclarationSubmissionSpec setArtifact(NapArtifact artifact) {
    this.artifact = artifact;
    return this;
  }

  /** Вече каченият файл — ражда се при ГЕНЕРИРАНЕ. */
  public MetaFile getMetaFile() {
    return metaFile;
  }

  public BgDeclarationSubmissionSpec setMetaFile(MetaFile metaFile) {
    this.metaFile = metaFile;
    return this;
  }

  public String getFileName() {
    return fileName;
  }

  public BgDeclarationSubmissionSpec setFileName(String fileName) {
    this.fileName = fileName;
    return this;
  }

  /** Броят ЗАПИСИ във файла — НАП сверява точно него. */
  public Integer getRecordCount() {
    return recordCount;
  }

  public BgDeclarationSubmissionSpec setRecordCount(Integer recordCount) {
    this.recordCount = recordCount;
    return this;
  }

  /**
   * Входящият номер от портала. <b>⚠️ ЗАДЪЛЖИТЕЛЕН</b> — той е свидетелят, че подаване е станало;
   * файлът е незадължителен, номерът не е.
   */
  public String getIncomingNumber() {
    return incomingNumber;
  }

  public BgDeclarationSubmissionSpec setIncomingNumber(String incomingNumber) {
    this.incomingNumber = incomingNumber;
    return this;
  }

  /** Датата, на която порталът е върнал входящия номер — ⚠️ не днешната. */
  public LocalDate getIncomingDate() {
    return incomingDate;
  }

  public BgDeclarationSubmissionSpec setIncomingDate(LocalDate incomingDate) {
    this.incomingDate = incomingDate;
    return this;
  }
}
