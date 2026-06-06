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

export type BackendStoryEntityType = "CHARACTER" | "LOCATION";

export type BackendStoryEntityResponse = {
  entityId: string;
  entityType: BackendStoryEntityType;
  canonicalName: string;
  aliases: string[];
  profile: string;
  sourceRefs: string[];
  createdAt: string;
  updatedAt: string;
};

export type BackendStoryEventResponse = {
  eventId: string;
  chapterId: number;
  eventOrder: number;
  title: string;
  summary: string;
  sourceRefs: string[];
  createdAt: string;
  updatedAt: string;
};

export type BackendStoryAnalysisResponse = {
  projectId: string;
  status: string;
  entityCount: number;
  eventCount: number;
  entities: BackendStoryEntityResponse[];
  events: BackendStoryEventResponse[];
};

export type BackendOutlineSluglineResponse = {
  intExt: string;
  locationId: string;
  timeOfDay: string;
};

export type BackendOutlinePurposeResponse = {
  plot: string;
  character: string;
};

export type BackendOutlineSceneResponse = {
  sceneId: string;
  seqNo: number;
  title: string;
  slugline: BackendOutlineSluglineResponse;
  purpose: BackendOutlinePurposeResponse;
  characters: string[];
  sourceRefs: string[];
  status: string;
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

export type StoryEntityViewModel = {
  entityId: string;
  entityType: BackendStoryEntityType;
  canonicalName: string;
  aliases: string[];
  profile: string;
  sourceRefs: string[];
};

export type StoryEventViewModel = {
  eventId: string;
  chapterId: number;
  eventOrder: number;
  title: string;
  summary: string;
  sourceRefs: string[];
};

export type StoryAnalysisViewModel = {
  projectId: string;
  status: string;
  entityCount: number;
  eventCount: number;
  entities: StoryEntityViewModel[];
  events: StoryEventViewModel[];
};

export type OutlineSceneViewModel = {
  sceneId: string;
  seqNo: number;
  title: string;
  slugline: {
    intExt: string;
    locationId: string;
    timeOfDay: string;
  };
  purpose: {
    plot: string;
    character: string;
  };
  characters: string[];
  sourceRefs: string[];
  status: string;
};
