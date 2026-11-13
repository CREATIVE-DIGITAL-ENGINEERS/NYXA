package com.axelor.apps.aiocr.module;

import com.axelor.db.JpaSupport;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Base for DB-backed (HSQLDB) integration tests in axelor-ai-ocr.
 *
 * <p><b>Extending this class is expensive, and the tag says so.</b> {@code GuiceExtension} builds a
 * fresh injector per test class over the 15 Axelor modules {@link AiOcrTestModule} installs, and
 * Hibernate then generates an HSQLDB schema for the entire Open Suite entity model. That is roughly
 * 25-30 seconds before the first assertion runs, and it is paid again for every subclass — which is
 * why the whole suite took over seven minutes while the assertions themselves accounted for about
 * seventeen seconds of it.
 *
 * <p>{@code @Tag("integration")} is inherited, so every subclass is automatically excluded from
 * {@code gradlew test} and picked up by {@code gradlew integrationTest} (see the root
 * build.gradle). Nothing else needs to be registered.
 *
 * <p>So: only extend this when the behaviour under test genuinely lives in persistence — cascades,
 * orphan removal, real queries, transaction boundaries, repository chains. Validation rules,
 * parsing, calculations and guard logic belong in a plain unit test.
 *
 * <p>The EntityManagerFactory is closed by {@code GuiceExtension.afterAll}; this class used to
 * close it again in its own {@code @AfterAll}, which was redundant and made it look as though
 * teardown were being handled here.
 */
// UploadDirIsolationExtension is listed first so its beforeAll (which points data.upload.dir at a
// per-worker temp root) runs before GuiceExtension builds the injector and AppSettings is read.
@ExtendWith(UploadDirIsolationExtension.class)
@ExtendWith(GuiceExtension.class)
@GuiceModules(AiOcrTestModule.class)
@TestMethodOrder(MethodOrderer.MethodName.class)
@Tag("integration")
public abstract class AiOcrTest extends JpaSupport {}
