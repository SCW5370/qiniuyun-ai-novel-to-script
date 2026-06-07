import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  build: {
    chunkSizeWarningLimit: 1300,
    rollupOptions: {
      output: {
        manualChunks: {
          motion: ["motion"],
          three: ["three", "@react-three/fiber", "@react-three/drei"]
        }
      }
    }
  },
  server: {
    port: 5173,
    host: "0.0.0.0"
  }
});
