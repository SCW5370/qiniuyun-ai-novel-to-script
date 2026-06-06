import { appConfig } from "../config";
import type {
  ApiEnvelope,
  BackendChapterResponse,
  BackendProjectResponse,
  BackendProjectStatus,
  ChapterViewModel,
  ProjectViewModel
} from "./types";

const PHASE_LABELS: Record<BackendProjectStatus, { phase: string; progress: number }> = {
  CREATED: { phase: "created", progress: 5 },
  SOURCE_SUBMITTED: { phase: "source_submitted", progress: 18 },
  CHAPTERED: { phase: "chaptered", progress: 30 },
  ENTITY_READY: { phase: "entity_ready", progress: 45 },
  OUTLINED: { phase: "outlined", progress: 60 },
  SCENE_GENERATING: { phase: "scene_generating", progress: 78 },
  COMPLETED: { phase: "completed", progress: 100 },
  FAILED: { phase: "failed", progress: 100 }
};

async function requestJson<T>(path: string) {
  const response = await fetch(`${appConfig.apiBaseUrl}${path}`, {
    headers: {
      "Content-Type": "application/json"
    }
  });

  let payload: ApiEnvelope<T>;

  try {
    payload = (await response.json()) as ApiEnvelope<T>;
  } catch {
    throw new Error(`Invalid JSON response for ${path}`);
  }

  if (!response.ok || !payload.success) {
    throw new Error(payload.message || `Request failed for ${path}`);
  }

  return payload.data;
}

function createPreviewText(cleanText: string) {
  const flattened = cleanText.replace(/\s+/g, " ").trim();
  return flattened.length > 120 ? `${flattened.slice(0, 120)}...` : flattened;
}

export function adaptProject(project: BackendProjectResponse): ProjectViewModel {
  const mapping = PHASE_LABELS[project.status];

  return {
    projectId: project.projectId,
    title: project.title,
    status: project.status,
    currentPhase: mapping.phase,
    progress: mapping.progress,
    createdAt: project.createdAt,
    updatedAt: project.updatedAt
  };
}

export function adaptChapter(chapter: BackendChapterResponse): ChapterViewModel {
  return {
    id: chapter.id,
    chapterNo: chapter.chapterNo,
    title: chapter.title,
    cleanText: chapter.cleanText,
    summary: chapter.summary,
    previewText: createPreviewText(chapter.cleanText),
    createdAt: chapter.createdAt
  };
}

export async function getProject(projectId: string) {
  const data = await requestJson<BackendProjectResponse>(`/projects/${projectId}`);
  return adaptProject(data);
}

export async function getProjectChapters(projectId: string) {
  const data = await requestJson<BackendChapterResponse[]>(`/projects/${projectId}/chapters`);
  return data.map(adaptChapter);
}
