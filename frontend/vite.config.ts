import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The Spring Boot `app` module serves the pipeline API on :8080.
// Proxy `/v1` there so the browser talks to Vite same-origin (no CORS setup).
const API_TARGET = process.env.VITE_API_TARGET ?? "http://localhost:8080";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/v1": {
        target: API_TARGET,
        changeOrigin: true,
      },
    },
  },
});
