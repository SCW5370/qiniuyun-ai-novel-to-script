export type ApiEnvelope<T> = {
  success: boolean;
  message: string;
  data: T;
};

export type BackendProjectStatus =
  | "CREATED"
  | "SOURCE_SUBMITTED"
  | "CHAPTERED"
  | "ENTITY_READY"
  | "OUTLINED"
  | "SCENE_GENERATING"
  | "COMPLETED"
  | "FAILED";

export type BackendProjectResponse = {
  projectId: string;
  title: string;
  status: BackendProjectStatus;
  createdAt: string;
  updatedAt: string;
};

export type BackendChapterResponse = {
  id: number;
  chapterNo: number;
  title: string;
  cleanText: string;
  summary: string | null;
  createdAt: string;
};

export type WorkbenchConnectionMode = "connected" | "mock-only" | "error";

export type ProjectViewModel = {
  projectId: string;
  title: string;
  status: BackendProjectStatus;
  currentPhase: string;
  progress: number;
  createdAt: string;
  updatedAt: string;
};

export type ChapterViewModel = {
  id: number;
  chapterNo: number;
  title: string;
  cleanText: string;
  summary: string | null;
  previewText: string;
  createdAt: string;
};
