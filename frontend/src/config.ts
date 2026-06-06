function parseBooleanFlag(value: string | undefined, defaultValue: boolean) {
  if (value == null || value.trim() === "") {
    return defaultValue;
  }

  return value === "true";
}

const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api").replace(
  /\/$/,
  ""
);

const defaultProjectId = import.meta.env.VITE_DEFAULT_PROJECT_ID ?? "";

export const appConfig = {
  apiBaseUrl,
  defaultProjectId,
  enableMockFallback: parseBooleanFlag(import.meta.env.VITE_ENABLE_MOCK_FALLBACK, true)
};
