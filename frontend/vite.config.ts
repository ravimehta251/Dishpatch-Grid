import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwind from "@tailwindcss/vite";
export default defineConfig({
  plugins: [react(), tailwind()],
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes("node_modules")) return;
          if (/recharts|d3-|victory|react-smooth|decimal.js/.test(id))
            return "charts";
          if (/react|@tanstack/.test(id)) return "react-vendor";
        },
      },
    },
  },
  server: {
    port: 3000,
    proxy: { "/api": { target: "http://localhost:8080", changeOrigin: false } },
  },
});
