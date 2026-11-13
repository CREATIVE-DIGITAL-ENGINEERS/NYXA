package com.axelor.apps.bglocalization.module;

import com.axelor.db.JpaSupport;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Base for DB-backed (in-memory HSQLDB) tests in axelor-bg-localization -- same convention as
 * axelor-ai-ocr's {@code AiOcrTest}: expensive per class (fresh injector + generated schema), so
 * only extend it for behaviour that genuinely lives in persistence. {@code @Tag("integration")} is
 * inherited, so this is excluded from {@code gradlew test} and picked up by {@code gradlew
 * integrationTest} only.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationTestModule.class)
@Tag("integration")
public abstract class BgLocalizationTest extends JpaSupport {}
