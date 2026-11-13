import { useAtomValue } from "jotai";
import { useCallback } from "react";

import { Box, Button, Link } from "@axelor/ui";

import { DefaultAppLogo } from "@/components/app-logo";
import { dialogs } from "@/components/dialogs";
import { COPYRIGHT, useAppSettings } from "@/hooks/use-app-settings";
import { useRoute } from "@/hooks/use-route";
import { i18n } from "@/services/client/i18n";
import { l10n } from "@/services/client/l10n";
import { session } from "@/services/client/session";
import { usePopupHandlerAtom } from "@/view-containers/view-popup/handler";
import { System } from "../system";

import styles from "./about.module.scss";

// The EU emblem exists in Bulgarian and English (img/eu/eu-emblem-bg.png, -en.png); English for
// any other language.
function euEmblemImage() {
  const lang = l10n.getLocale()?.toLowerCase() ?? "";
  return `img/eu/eu-emblem-${lang.startsWith("bg") ? "bg" : "en"}.png`;
}

// Logo of the Programme "Competitiveness and Innovation in Enterprises" 2021-2027, as published
// on eufunds.bg. Only a Bulgarian version is published, so it is shown for every language.
const PROGRAMME_LOGO = "img/eu/pkip-logo-bg.png";

export function About() {
  const { info } = session;
  const { application: app, user } = info || { application: {}, user: {} };

  const technical = user?.technical;

  const { navigate } = useRoute();
  const { name, description, copyright } = useAppSettings();

  const popupHandlerAtom = usePopupHandlerAtom();
  const { close: closePopup } = useAtomValue(popupHandlerAtom);

  const showSwagger = useCallback(() => {
    closePopup?.();
    navigate({ pathname: "/api-documentation" });
  }, [navigate, closePopup]);

  const showSystemInfo = useCallback(() => {
    dialogs.info({
      title: i18n.get("System Information"),
      content: <System />,
    });
  }, []);

  return (
    <Box flex={1} textAlign="center">
      <Box>
        <DefaultAppLogo className={styles.logo} />
        <h5>
          {name} - {description}
        </h5>
      </Box>
      <div>
        <p>
          {i18n.get("Version")}: {app.version}
        </p>
        {app.imageVersion && (
          <p>
            {i18n.get("Build")}: {app.imageVersion}
          </p>
        )}
        <p>{copyright}</p>
        <Box d="flex" g={1} flexDirection="column">
          {app.home && <Link href={app.home}>{i18n.get("Home page")}</Link>}
          {app.help && <Link href={app.help}>{i18n.get("Documentation")}</Link>}
          <Link href="https://www.gnu.org/licenses/agpl.html">
            {i18n.get("License")}
          </Link>
        </Box>
      </div>
      <Box mt={4}>
        <h5>{i18n.get("Open source components")}</h5>
        <p className={styles.notice}>
          {i18n.get(
            "This application is a modified version of Axelor Open Suite and Axelor Open Platform, distributed under the GNU Affero General Public License, version 3.",
          )}
        </p>
        {app.sourceUrl && (
          <Link href={app.sourceUrl}>{i18n.get("Source code")}</Link>
        )}
      </Box>
      <Box mt={3}>
        <h6>Axelor Open Platform</h6>
        <p>
          {i18n.get("Version")}: {app.aopVersion}
          <br />
          {technical && (
            <span style={{ fontSize: "small" }}>
              (build {app.aopBuildDate} - rev {app.aopGitHash?.slice(0, 12)})
            </span>
          )}
        </p>
        <p className={styles.notice}>Copyright (C) 2005-2025 Axelor</p>
        <Box d="flex" g={1} flexDirection="column">
          <Link href="https://github.com/axelor/axelor-open-platform">
            {i18n.get("Project page")}
          </Link>
          <Link href="https://www.gnu.org/licenses/agpl-3.0.html">
            {i18n.get("License")} (AGPLv3)
          </Link>
        </Box>
      </Box>
      <Box mt={3}>
        <h6>Axelor Open Suite</h6>
        {app.aosVersion && (
          <p>
            {i18n.get("Version")}: {app.aosVersion}
          </p>
        )}
        <p className={styles.notice}>Copyright (C) 2005-2025 Axelor</p>
        <Box d="flex" g={1} flexDirection="column">
          <Link href="https://github.com/axelor/axelor-open-suite">
            {i18n.get("Project page")}
          </Link>
          <Link href="https://www.gnu.org/licenses/agpl-3.0.html">
            {i18n.get("License")} (AGPLv3)
          </Link>
        </Box>
      </Box>
      {/* EU visibility: the emblem carries the funding statement in the user's language, and its
          flag is not smaller than the programme logo's mark. */}
      <Box mt={4} className={styles.funding}>
        <div className={styles.fundingLogos}>
          <img
            className={styles.euEmblem}
            src={euEmblemImage()}
            alt={i18n.get("Co-funded by the European Union")}
          />
          <img
            className={styles.programmeLogo}
            src={PROGRAMME_LOGO}
            alt={i18n.get(
              'Programme "Competitiveness and Innovation in Enterprises" 2021-2027',
            )}
          />
        </div>
        <p className={styles.fundingProject}>
          {i18n.get(
            'Project BG16RFPR001-1.001-0075 "We Account" is implemented under procedure BG16RFPR001-1.001 "Development of innovations in enterprises" of the Programme "Competitiveness and Innovation in Enterprises" 2021-2027 and is co-funded by the European Union.',
          )}
        </p>
        <p className={styles.fundingProject}>
          {i18n.get(
            'The "We Account" project will develop innovative "software as a service" for the purposes and growth of micro, small and medium-sized enterprises. The "software as a service" will provide an innovative method of reporting and decision-making in accordance with the International Financial Reporting Standards (IFRS). The final product will follow a "mobile first" approach with no special technical requirements.',
          )}
        </p>
        <p className={styles.fundingProject}>
          {i18n.get(
            "Result: a cloud-based web system that runs in the browser, including on mobile devices, with no installation. It supports choosing the accounting basis (NAS or IFRS) for each company, a statement of financial position and a statement of profit or loss under IAS, invoice recognition (OCR), an AI assistant, and files and reports for the NRA, NSI and BNB.",
          )}
        </p>
      </Box>
    </Box>
  );
}
