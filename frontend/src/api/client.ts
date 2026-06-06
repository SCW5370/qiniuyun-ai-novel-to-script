import { appConfig } from "../config";
import type {
  ApiEnvelope,
  BackendChapterResponse,
  BackendOutlineSceneResponse,
  BackendProjectResponse,
  BackendSceneDetailResponse,
  BackendProjectStatus,
  BackendStoryAnalysisResponse,
  BackendStoryEntityResponse,
  BackendStoryEventResponse,
  BackendValidationReportResponse,
  ChapterViewModel,
  OutlineSceneViewModel,
  ProjectViewModel,
  SceneDetailViewModel,
  StoryAnalysisViewModel,
  StoryEntityViewModel,
  StoryEventViewModel,
  ValidationReportViewModel
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

async function requestJson<T>(path: string, options: RequestInit = {}) {
  const response = await fetch(`${appConfig.apiBaseUrl}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...options.headers
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

async function requestText(path: string, options: RequestInit = {}) {
  const response = await fetch(`${appConfig.apiBaseUrl}${path}`, {
    ...options,
    headers: {
      ...options.headers
    }
  });

  const text = await response.text();

  if (response.ok) {
    return text;
  }

  let parsedMessage = "";

  try {
    const payload = JSON.parse(text) as ApiEnvelope<unknown>;
    parsedMessage = payload.message || "";
  } catch {
    parsedMessage = "";
  }

  throw new Error(parsedMessage || text || `Request failed for ${path}`);
}

function buildJsonPostOptions(body?: unknown): RequestInit {
  return {
    method: "POST",
    body: body == null ? undefined : JSON.stringify(body)
  };
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

export function adaptStoryEntity(entity: BackendStoryEntityResponse): StoryEntityViewModel {
  return {
    entityId: entity.entityId,
    entityType: entity.entityType,
    canonicalName: entity.canonicalName,
    aliases: entity.aliases ?? [],
    profile: entity.profile,
    sourceRefs: entity.sourceRefs ?? []
  };
}

export function adaptStoryEvent(event: BackendStoryEventResponse): StoryEventViewModel {
  return {
    eventId: event.eventId,
    chapterId: event.chapterId,
    eventOrder: event.eventOrder,
    title: event.title,
    summary: event.summary,
    sourceRefs: event.sourceRefs ?? []
  };
}

export function adaptStoryAnalysis(
  result: BackendStoryAnalysisResponse
): StoryAnalysisViewModel {
  return {
    projectId: result.projectId,
    status: result.status,
    entityCount: result.entityCount,
    eventCount: result.eventCount,
    entities: result.entities.map(adaptStoryEntity),
    events: result.events.map(adaptStoryEvent)
  };
}

export function adaptOutlineScene(scene: BackendOutlineSceneResponse): OutlineSceneViewModel {
  return {
    sceneId: scene.sceneId,
    seqNo: scene.seqNo,
    title: scene.title,
    slugline: {
      intExt: scene.slugline.intExt,
      locationId: scene.slugline.locationId,
      timeOfDay: scene.slugline.timeOfDay
    },
    purpose: {
      plot: scene.purpose.plot,
      character: scene.purpose.character
    },
    characters: scene.characters ?? [],
    sourceRefs: scene.sourceRefs ?? [],
    status: scene.status
  };
}

export function adaptSceneDetail(scene: BackendSceneDetailResponse): SceneDetailViewModel {
  return {
    sceneId: scene.sceneId,
    seqNo: scene.seqNo,
    title: scene.title,
    action: scene.action ?? [],
    dialogue: (scene.dialogue ?? []).map((item) => ({
      characterId: item.characterId,
      line: item.line
    })),
    sourceRefs: scene.sourceRefs ?? [],
    validationStatus: scene.validationStatus,
    warnings: scene.warnings ?? []
  };
}

export function adaptValidationReport(
  report: BackendValidationReportResponse
): ValidationReportViewModel {
  return {
    projectId: report.projectId,
    status: report.status,
    items: (report.items ?? []).map((item) => ({
      sceneId: item.sceneId,
      level: item.level,
      field: item.field,
      message: item.message
    }))
  };
}

export async function getProject(projectId: string) {
  const data = await requestJson<BackendProjectResponse>(`/projects/${projectId}`);
  return adaptProject(data);
}

export async function listProjects(keyword?: string) {
  const query = keyword?.trim() ? `?keyword=${encodeURIComponent(keyword.trim())}` : "";
  const data = await requestJson<BackendProjectResponse[]>(`/projects${query}`);
  return data.map(adaptProject);
}

export async function createProject(title: string) {
  const data = await requestJson<BackendProjectResponse>(
    "/projects",
    buildJsonPostOptions({ title })
  );
  return adaptProject(data);
}

export async function getProjectChapters(projectId: string) {
  const data = await requestJson<BackendChapterResponse[]>(`/projects/${projectId}/chapters`);
  return data.map(adaptChapter);
}

export async function submitProjectSource(projectId: string, content: string) {
  const data = await requestJson<BackendChapterResponse[]>(
    `/projects/${projectId}/source`,
    buildJsonPostOptions({ content })
  );
  return data.map(adaptChapter);
}

export async function analyzeStoryAssets(projectId: string) {
  // 对齐开发契约：分析会写入实体和事件资产，因此必须使用 POST /analyze。
  const data = await requestJson<BackendStoryAnalysisResponse>(
    `/projects/${projectId}/analyze`,
    buildJsonPostOptions()
  );
  return adaptStoryAnalysis(data);
}

export async function getStoryEntities(projectId: string) {
  const data = await requestJson<BackendStoryEntityResponse[]>(`/projects/${projectId}/entities`);
  return data.map(adaptStoryEntity);
}

export async function getStoryEvents(projectId: string) {
  const data = await requestJson<BackendStoryEventResponse[]>(
    `/projects/${projectId}/story-events`
  );
  return data.map(adaptStoryEvent);
}

export async function getProjectOutline(projectId: string) {
  const data = await requestJson<BackendOutlineSceneResponse[]>(`/projects/${projectId}/outline`);
  return data.map(adaptOutlineScene);
}

export async function getProjectScene(projectId: string, sceneId: string) {
  const data = await requestJson<BackendSceneDetailResponse>(
    `/projects/${projectId}/scenes/${sceneId}`
  );
  return adaptSceneDetail(data);
}

export async function regenerateProjectScene(projectId: string, sceneId: string) {
  const data = await requestJson<BackendSceneDetailResponse>(
    `/projects/${projectId}/scenes/${sceneId}/regenerate`,
    buildJsonPostOptions()
  );
  return adaptSceneDetail(data);
}

export async function validateProjectScenes(projectId: string) {
  const data = await requestJson<BackendValidationReportResponse>(
    `/projects/${projectId}/validate`,
    buildJsonPostOptions()
  );
  return adaptValidationReport(data);
}

export async function exportProjectYaml(projectId: string) {
  return requestText(`/projects/${projectId}/export?format=yaml`);
}
