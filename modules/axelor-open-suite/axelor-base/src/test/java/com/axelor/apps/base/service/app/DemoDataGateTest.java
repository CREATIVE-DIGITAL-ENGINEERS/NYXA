package com.axelor.apps.base.service.app;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.axelor.apps.base.exceptions.BaseExceptionMessage;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.repo.MetaFileRepository;
import com.axelor.meta.db.repo.MetaModuleRepository;
import com.axelor.meta.loader.AppVersionService;
import com.axelor.studio.db.App;
import com.axelor.studio.db.repo.AppRepository;
import com.axelor.studio.service.AppSettingsStudioService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * "Import demo data" on an app, and "Bulk install" with demo data, only in an environment marked
 * {@code nyxa.environment = dev}; a plain install goes on everywhere.
 */
class DemoDataGateTest {

  @Test
  void onlyTheDevMarkAllowsDemoData() {
    Assertions.assertFalse(DemoDataGate.allowed(null), "no mark");
    Assertions.assertFalse(DemoDataGate.allowed(""));
    Assertions.assertFalse(DemoDataGate.allowed("prod"));
    Assertions.assertFalse(DemoDataGate.allowed("test"));
    Assertions.assertTrue(DemoDataGate.allowed("dev"));
    Assertions.assertTrue(DemoDataGate.allowed(" DEV "));
  }

  @Test
  void importDemoDataIsRefusedWithoutTheMark() {
    RecordingAppService service = new RecordingAppService(null);
    App app = app(1L, "sale");

    IllegalStateException refusal =
        Assertions.assertThrows(IllegalStateException.class, () -> service.importDataDemo(app));

    Assertions.assertEquals(BaseExceptionMessage.DEMO_DATA_ONLY_IN_DEV, refusal.getMessage());
    Assertions.assertTrue(service.imported.isEmpty(), "demo data imported: " + service.imported);
    Assertions.assertFalse(app.getDemoDataLoaded());
  }

  @Test
  void importDemoDataIsRefusedOnProd() {
    RecordingAppService service = new RecordingAppService("prod");

    Assertions.assertThrows(
        IllegalStateException.class, () -> service.importDataDemo(app(1L, "sale")));
    Assertions.assertTrue(service.imported.isEmpty());
  }

  @Test
  void importDemoDataRunsWithTheDevMark() throws IOException {
    RecordingAppService service = new RecordingAppService("dev");
    App app = app(1L, "sale");

    App loaded = service.importDataDemo(app);

    Assertions.assertEquals(List.of("sale:demo"), service.imported);
    Assertions.assertTrue(loaded.getDemoDataLoaded());
  }

  @Test
  void bulkInstallWithDemoDataIsRefusedBeforeAnyAppIsInstalled() {
    RecordingAppService service = new RecordingAppService(null);

    Assertions.assertThrows(
        IllegalStateException.class,
        () -> service.bulkInstall(List.of(app(1L, "base"), app(2L, "sale")), true, "en"));

    Assertions.assertTrue(service.installed.isEmpty(), "installed: " + service.installed);
    Assertions.assertTrue(service.imported.isEmpty());
  }

  @Test
  void plainBulkInstallGoesOnWithoutTheMark() throws IOException {
    RecordingAppService service = new RecordingAppService(null);

    service.bulkInstall(List.of(app(1L, "base"), app(2L, "sale")), false, "en");

    Assertions.assertEquals(List.of("base", "sale"), service.installed);
    Assertions.assertTrue(service.imported.isEmpty(), "demo data imported: " + service.imported);
  }

  @Test
  void bulkInstallWithDemoDataRunsWithTheDevMark() throws IOException {
    RecordingAppService service = new RecordingAppService("dev");

    service.bulkInstall(List.of(app(1L, "base"), app(2L, "sale")), true, "en");

    Assertions.assertEquals(List.of("base", "sale"), service.installed);
    Assertions.assertEquals(List.of("base:demo", "sale:demo"), service.imported);
  }

  @Test
  void theRefusalHasBulgarian() throws IOException {
    String bulgarian = null;
    try (InputStream in = getClass().getResourceAsStream("/i18n/messages_bg.csv")) {
      Assertions.assertNotNull(in, "messages_bg.csv of axelor-base");
      BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      String prefix = "\"" + BaseExceptionMessage.DEMO_DATA_ONLY_IN_DEV + "\",\"";
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        if (line.startsWith(prefix)) {
          bulgarian = line.substring(prefix.length(), line.indexOf('"', prefix.length()));
        }
      }
    }
    Assertions.assertNotNull(bulgarian, "no Bulgarian row for the refusal");
    Assertions.assertTrue(bulgarian.matches(".*[\\u0400-\\u04FF].*"), bulgarian);
  }

  private static App app(Long id, String code) {
    App app = new App();
    app.setId(id);
    app.setCode(code);
    app.setDemoDataLoaded(false);
    return app;
  }

  /** The stock install and import, recorded instead of run; the mark is passed in. */
  private static final class RecordingAppService extends AppBaseServiceImpl {

    private final String mark;
    final List<String> installed = new ArrayList<>();
    final List<String> imported = new ArrayList<>();

    RecordingAppService(String mark) {
      super(
          repository(),
          mock(MetaFiles.class),
          mock(AppVersionService.class),
          mock(AppSettingsStudioService.class),
          mock(MetaModuleRepository.class),
          mock(MetaFileRepository.class));
      this.mark = mark;
    }

    /** find(id) gives back the app itself: the stock import reloads it by id. */
    private static AppRepository repository() {
      AppRepository repository = mock(AppRepository.class);
      when(repository.find(anyLong()))
          .thenAnswer(
              call -> {
                App app = new App();
                app.setId(call.getArgument(0));
                return app;
              });
      return repository;
    }

    @Override
    protected String environmentMark() {
      return mark;
    }

    @Override
    public App installApp(App app, String language) {
      installed.add(app.getCode());
      return app;
    }

    @Override
    protected void importParentData(App app) {}

    @Override
    protected String getLanguage(App app) {
      return "en";
    }

    @Override
    protected void importData(App app, String dir, boolean useDemo) {
      imported.add(app.getCode() + (useDemo ? ":demo" : ":init"));
    }

    @Override
    public App saveApp(App app) {
      return app;
    }
  }
}
