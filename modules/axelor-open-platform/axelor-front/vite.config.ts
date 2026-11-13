import legacy from "@vitejs/plugin-legacy";
import react from "@vitejs/plugin-react";
import path from "path";
import { defineConfig } from "vite";
import svgr from "vite-plugin-svgr";

// https://vitejs.dev/config/
export default defineConfig({
  base: "./",
  plugins: [
    react(),
    svgr({
      svgrOptions: {
        icon: true,
      },
    }),
    legacy({
      modernPolyfills: true,
      renderLegacyChunks: false,
    }),
  ],
  optimizeDeps: {
    entries: ["src/**/*.{ts,js,tsx,jsx,css,scss,html}"],
  },
  resolve: {
    alias: [
      {
        find: /^~(.*)/,
        replacement: "$1",
      },
      {
        find: /^@\/(.*)/,
        replacement: path.join(__dirname, "src", "$1"),
      },
    ],
  },
  build: {
    target: ["es2022"],
    rollupOptions: {
      output: {
        manualChunks(id) {
          // Group heavy vendor libs into stable, shared chunks so they aren't
          // duplicated across lazy-loaded route chunks.
          if (id.includes("node_modules/react") || id.includes("node_modules/react-dom")) {
            return "vendor-react";
          }
          if (id.includes("node_modules/echarts") || id.includes("node_modules/zrender")) {
            return "vendor-echarts";
          }
          if (id.includes("node_modules/@fullcalendar")) {
            return "vendor-calendar";
          }
          if (id.includes("node_modules/@axelor/ui")) {
            return "vendor-axelor-ui";
          }
          if (id.includes("node_modules/lodash")) {
            return "vendor-lodash";
          }
          if (id.includes("node_modules/dayjs") || id.includes("node_modules/date-fns")) {
            return "vendor-dates";
          }
        },
      },
    },
  },
});
