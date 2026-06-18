import { useEffect, useMemo, useRef, useState } from "react";
import {
  adaptSceneDetail,
  analyzeStoryAssets,
  analyzeStoryAssetsIncremental,
  appendProjectSource,
  appendProjectSourceFile,
  createProject,
  exportProjectYaml,
  generateProjectOutline,
  generateProjectOutlineIncremental,
  generateProjectSceneScripts,
  getProject,
  getProjectChapters,
  getProjectOutline,
  getProjectScene,
  getStoryEntities,
  getStoryEvents,
  listProjects,
  listProjectScenes,
  summarizeProjectChapters,
  submitProjectSource,
  uploadProjectSourceFile,
  validateProjectScenes
} from "../../api/client";
import type {
  BackendSceneDetailResponse,
  ChapterViewModel,
  OutlineSceneViewModel,
  ProgressStreamEvent,
  ProjectViewModel,
  SceneDetailViewModel,
  StoryAnalysisViewModel,
  StoryEntityViewModel,
  StoryEventViewModel,
  ValidationItemViewModel,
  ValidationReportViewModel,
  WorkflowJobViewModel,
  WorkbenchConnectionMode
} from "../../api/types";
import { appConfig } from "../../config";
import { useWorkbenchStore } from "../../store/workbenchStore";
import projectData from "../../../../samples/mock-project.json";
import {
  analysisModeLabels,
  buildYamlPreview,
  downloadTextFile,
  isContractProjectId,
  parseProgressStreamMessage,
  phaseKeyToLabel,
  phaseLabels,
  projectStatusLabels,
  resolveProjectId,
  sceneUsesFallback
} from "./domain";

const emptyValidationReport: ValidationReportViewModel = {
  projectId: "",
  status: "PASSED",
  items: []
};

const mockProject = {
  projectId: projectData.projectId,
  title: projectData.title,
  status: projectData.status,
  currentPhase: projectData.currentPhase,
  progress: projectData.progress,
  createdAt: projectData.createdAt,
  updatedAt: projectData.updatedAt
} as ProjectViewModel;

type WorkflowNoticeTone = "info" | "running" | "success" | "danger";

type WorkflowNotice = {
  jobId: string;
  jobType: string;
  step: "submitted" | "analyzing" | "generating" | "refreshing" | "completed" | "failed";
  title: string;
  detail: string;
  tone: WorkflowNoticeTone;
  retryAction: "analyze" | "outline" | null;
};

type SceneStreamCacheEntry = {
  content: string;
  status: "streaming" | "completed" | "failed";
};

type ActiveSceneStream = {
  cacheKey: string;
  eventSource: EventSource;
  projectId: string;
  requestId: number;
  sceneId: string;
};

function workflowJobTypeLabel(jobType: string) {
  if (jobType.includes("OUTLINE")) return "场景生成";
  if (jobType.includes("ANALYZE")) return "故事分析";
  return "制作任务";
}

function createSubmittedWorkflowNotice(job: WorkflowJobViewModel, retryAction: WorkflowNotice["retryAction"]): WorkflowNotice {
  const label = workflowJobTypeLabel(job.jobType);
  return {
    jobId: job.jobId,
    jobType: job.jobType,
    step: "submitted",
    title: "任务已提交",
    detail: `${label}已进入 MQ 队列，完成后自动刷新工作台。`,
    tone: "info",
    retryAction
  };
}

export function useWorkbench() {
  const activeView = useWorkbenchStore((state) => state.activeView);
  const selectedSceneId = useWorkbenchStore((state) => state.selectedSceneId);
  const setActiveView = useWorkbenchStore((state) => state.setActiveView);
  const setSelectedSceneId = useWorkbenchStore((state) => state.setSelectedSceneId);
  const [projectId, setProjectId] = useState(resolveProjectId() || appConfig.defaultProjectId);
  const [projectList, setProjectList] = useState<ProjectViewModel[]>([]);
  const [projectKeyword, setProjectKeyword] = useState("");
  const [projectTitleInput, setProjectTitleInput] = useState("");
  const [sourceTextInput, setSourceTextInput] = useState("");
  const [sourceFileInput, setSourceFileInput] = useState<File | null>(null);
  const [project, setProject] = useState<ProjectViewModel>(mockProject);
  const [chapters, setChapters] = useState<ChapterViewModel[]>([]);
  const [outlineScenes, setOutlineScenes] = useState<OutlineSceneViewModel[]>([]);
  const [sceneDetail, setSceneDetail] = useState<SceneDetailViewModel | null>(null);
  const [storyEntities, setStoryEntities] = useState<StoryEntityViewModel[]>([]);
  const [storyEvents, setStoryEvents] = useState<StoryEventViewModel[]>([]);
  const [connectionMode, setConnectionMode] = useState<WorkbenchConnectionMode>("mock-only");
  const [errorMessage, setErrorMessage] = useState("");
  const [projectActionMessage, setProjectActionMessage] = useState("");
  const [sourceSubmitMessage, setSourceSubmitMessage] = useState("");
  const [chapterSummaryMessage, setChapterSummaryMessage] = useState("");
  const [outlineMessage, setOutlineMessage] = useState("");
  const [outlineSourceMode, setOutlineSourceMode] = useState<"real" | "empty">("empty");
  const [sceneDetailMessage, setSceneDetailMessage] = useState("");
  const [sceneDetailSourceMode, setSceneDetailSourceMode] = useState<"real" | "mock" | "empty">(
    "empty"
  );
  const [storyAssetsMessage, setStoryAssetsMessage] = useState("");
  const [storyEventsMessage, setStoryEventsMessage] = useState("");
  const [validationReportData, setValidationReportData] =
    useState<ValidationReportViewModel>(emptyValidationReport);
  const [validationMessage, setValidationMessage] = useState("");
  const [validationSourceMode, setValidationSourceMode] = useState<"real" | "empty">("empty");
  const [yamlPreviewContent, setYamlPreviewContent] = useState(
    buildYamlPreview(null, mockProject)
  );
  const [yamlPreviewMessage, setYamlPreviewMessage] = useState("");
  const [yamlSourceMode, setYamlSourceMode] = useState<"real" | "empty">("empty");
  const [progressStreamMessage, setProgressStreamMessage] = useState("");
  const [progressStreamPhase, setProgressStreamPhase] = useState("");
  const [progressStreamValue, setProgressStreamValue] = useState<number | null>(null);
  const [progressSourceMode, setProgressSourceMode] = useState<"real" | "static">("static");
  const [workflowNotice, setWorkflowNotice] = useState<WorkflowNotice | null>(null);
  const [operationElapsedMs, setOperationElapsedMs] = useState(0);
  const [readySceneIds, setReadySceneIds] = useState<Set<string>>(() => new Set());
  const [revealedSceneIds, setRevealedSceneIds] = useState<Set<string>>(() => new Set());
  const [isSceneBuildActive, setIsSceneBuildActive] = useState(false);
  const [analysisResult, setAnalysisResult] = useState<StoryAnalysisViewModel | null>(null);
  const [analysisMessage, setAnalysisMessage] = useState("");
  const [analysisStatus, setAnalysisStatus] = useState<"success" | "warning" | "error" | "">("");
  const [isCreatingProject, setIsCreatingProject] = useState(false);
  const [isSubmittingSource, setIsSubmittingSource] = useState(false);
  const [isSummarizingChapters, setIsSummarizingChapters] = useState(false);
  const [isAnalyzing, setIsAnalyzing] = useState(false);
  const [isRegeneratingScene, setIsRegeneratingScene] = useState(false);
  const [isStreamingScene, setIsStreamingScene] = useState(false);
  const [sceneStreamContent, setSceneStreamContent] = useState("");
  const [sceneStreamMessage, setSceneStreamMessage] = useState("");
  const [isValidatingProject, setIsValidatingProject] = useState(false);
  const [isExportingYaml, setIsExportingYaml] = useState(false);
  const sceneStreamCacheRef = useRef<Record<string, SceneStreamCacheEntry>>({});
  const activeSceneStreamRef = useRef<ActiveSceneStream | null>(null);
  const sceneStreamRequestSequenceRef = useRef(0);
  const readinessHydratedProjectRef = useRef("");

  const canLoadGeneratedScenes =
    project.status === "ENTITY_READY" ||
    project.status === "OUTLINED" ||
    project.status === "SCENE_GENERATING" ||
    project.status === "COMPLETED";
  const isWorkflowRunning =
    workflowNotice?.step === "submitted" ||
    workflowNotice?.step === "analyzing" ||
    workflowNotice?.step === "generating";
  const isProjectOperationBusy =
    isSubmittingSource ||
    isSummarizingChapters ||
    isAnalyzing ||
    isRegeneratingScene ||
    isStreamingScene ||
    isValidatingProject ||
    isExportingYaml ||
    isWorkflowRunning;
  // 正式流式生成由 activeSceneStreamRef 兜底，避免 React state 异步更新造成重复连接。
  const isNonStreamOperationBusy =
    isSubmittingSource ||
    isSummarizingChapters ||
    isAnalyzing ||
    isRegeneratingScene ||
    isValidatingProject ||
    isExportingYaml ||
    isWorkflowRunning;

  useEffect(() => {
    if (!isWorkflowRunning) {
      setOperationElapsedMs(0);
      return;
    }
    const startedAt = Date.now();
    setOperationElapsedMs(0);
    const handle = setInterval(() => {
      setOperationElapsedMs(Date.now() - startedAt);
    }, 1000);
    return () => clearInterval(handle);
  }, [isWorkflowRunning]);

  function invalidateSceneStreamCache(targetProjectId: string, sceneId?: string) {
    const activeStream = activeSceneStreamRef.current;
    if (
      activeStream?.projectId === targetProjectId &&
      (sceneId == null || activeStream.sceneId === sceneId)
    ) {
      cancelActiveSceneStream("项目资产已更新，旧的正式流式生成已停止，可重新生成。");
    }

    if (sceneId) {
      delete sceneStreamCacheRef.current[`${targetProjectId}:${sceneId}`];
      return;
    }

    const prefix = `${targetProjectId}:`;
    for (const cacheKey of Object.keys(sceneStreamCacheRef.current)) {
      if (cacheKey.startsWith(prefix)) {
        delete sceneStreamCacheRef.current[cacheKey];
      }
    }
  }

  function cancelActiveSceneStream(message?: string) {
    const activeStream = activeSceneStreamRef.current;
    if (!activeStream) return;

    activeStream.eventSource.close();
    activeSceneStreamRef.current = null;
    const cached = sceneStreamCacheRef.current[activeStream.cacheKey];
    if (cached?.status === "streaming") {
      sceneStreamCacheRef.current[activeStream.cacheKey] = {
        content: cached.content,
        status: "failed"
      };
    }
    setIsStreamingScene(false);
    setIsRegeneratingScene(false);
    if (message) setSceneStreamMessage(message);
  }

  function switchProject(nextProjectId: string) {
    setWorkflowNotice(null);
    setProjectId(nextProjectId);
    window.history.replaceState(null, "", `?projectId=${encodeURIComponent(nextProjectId)}`);
  }

  function markWorkflowSubmitted(job: WorkflowJobViewModel, retryAction: WorkflowNotice["retryAction"]) {
    setWorkflowNotice(createSubmittedWorkflowNotice(job, retryAction));
  }

  function markWorkflowFailed(detail: string, retryAction: WorkflowNotice["retryAction"]) {
    setWorkflowNotice((current) => ({
      jobId: current?.jobId ?? "",
      jobType: current?.jobType ?? "",
      step: "failed",
      title: "任务失败，可重试",
      detail,
      tone: "danger",
      retryAction
    }));
  }

  async function retryWorkflowNotice() {
    if (!workflowNotice?.retryAction || connectionMode !== "connected" || isProjectOperationBusy) return;

    if (workflowNotice.retryAction === "outline") {
      await handleGenerateIncrementalOutline();
      return;
    }

    await handleAnalyzeStoryAssets();
  }

  function applyProgressEvent(progressEvent: ProgressStreamEvent) {
    const { event, data } = progressEvent;

    if (typeof data.progress === "number") {
      setProgressStreamValue(data.progress);
    }

    if (typeof data.message === "string") {
      setProgressStreamMessage(data.message);
    }

    if (typeof data.phase === "string") {
      setProgressStreamPhase(data.phase);
      if (data.phase.includes("outline") || data.phase.includes("scene_generating")) {
        setIsSceneBuildActive(true);
      }
    } else if (event === "outline.ready") {
      setProgressStreamPhase("outlined");
    } else if (event === "scene.done") {
      setProgressStreamPhase("scene_generating");
    } else if (event === "job.completed") {
      setProgressStreamPhase("completed");
    }

    if (event === "job.started") {
      const nextPhase = data.phase ?? progressStreamPhase;
      const isSceneWork =
        nextPhase.includes("outline") ||
        nextPhase.includes("scene") ||
        Boolean(data.jobType?.includes("OUTLINE")) ||
        Boolean(data.jobType?.includes("story_production"));
      if (isSceneWork) setIsSceneBuildActive(true);
      setWorkflowNotice((current) => ({
        jobId: current?.jobId ?? data.sceneId ?? "",
        jobType: data.jobType ?? current?.jobType ?? "",
        step: isSceneWork ? "generating" : "analyzing",
        title: isSceneWork ? "正在生成场景" : "正在分析",
        detail: data.message ?? "MQ 消费端正在处理任务，完成后自动刷新工作台。",
        tone: "running",
        retryAction: isSceneWork ? "outline" : "analyze"
      }));
    } else if (event === "outline.ready") {
      setIsSceneBuildActive(true);
      setWorkflowNotice((current) => ({
        jobId: current?.jobId ?? data.sceneId ?? "",
        jobType: data.jobType ?? current?.jobType ?? "",
        step: "generating",
        title: "正在边分析边生成",
        detail: data.message ?? "新场景大纲已落库，正在生成内容并继续分析后续场景。",
        tone: "running",
        retryAction: null
      }));
    } else if (event === "scene.done") {
      setIsSceneBuildActive(true);
      setReadySceneIds((current) => {
        if (!data.sceneId || current.has(data.sceneId)) return current;
        const next = new Set(current);
        next.add(data.sceneId);
        return next;
      });
      setWorkflowNotice((current) => ({
        jobId: current?.jobId ?? data.sceneId ?? "",
        jobType: data.jobType ?? current?.jobType ?? "scene_scripts_generation",
        step: "generating",
        title: "正在逐场生成",
        detail: data.message ?? `Scene 已就绪：${data.sceneId ?? ""}`,
        tone: "running",
        retryAction: "outline"
      }));
    } else if (event === "job.completed") {
      if (data.exportReady || data.phase === "completed") setIsSceneBuildActive(false);
      setWorkflowNotice((current) => ({
        jobId: current?.jobId ?? "",
        jobType: data.jobType ?? current?.jobType ?? "",
        step: "completed",
        title: "完成后自动刷新",
        detail: data.message ?? "任务已完成，工作台数据已自动同步。",
        tone: "success",
        retryAction: null
      }));
    } else if (event === "job.failed") {
      setIsSceneBuildActive(false);
      setWorkflowNotice((current) => ({
        jobId: current?.jobId ?? "",
        jobType: data.jobType ?? current?.jobType ?? "",
        step: "failed",
        title: "任务失败，可重试",
        detail: data.message ?? "MQ 任务处理失败，请检查输入后重试。",
        tone: "danger",
        retryAction:
          data.phase?.includes("outline") || data.phase?.includes("scene") || data.jobType?.includes("OUTLINE")
            ? "outline"
            : "analyze"
      }));
    }

    setProgressSourceMode("real");
  }

  async function loadProjectDetail(nextProjectId: string) {
    const [nextProject, nextChapters] = await Promise.all([
      getProject(nextProjectId),
      getProjectChapters(nextProjectId)
    ]);

    setProject(nextProject);
    setChapters(nextChapters);
    setConnectionMode("connected");
    setErrorMessage("");
  }

  async function refreshProjectList(keyword = projectKeyword) {
    const projects = await listProjects(keyword);
    setProjectList(projects);
    return projects;
  }

  async function refreshStoryAssets(targetProjectId: string) {
    const [entities, events] = await Promise.all([
      getStoryEntities(targetProjectId),
      getStoryEvents(targetProjectId)
    ]);
    setStoryEntities(entities);
    setStoryEvents(events);
    setStoryAssetsMessage("");
    setStoryEventsMessage("");
    setAnalysisStatus("success");
    setAnalysisMessage(`已同步 ${entities.length} 个实体和 ${events.length} 个事件，后续章节仍在分析。`);
  }

  async function handleCreateProject() {
    const title = projectTitleInput.trim();
    if (!title || isCreatingProject) return;

    setIsCreatingProject(true);
    setProjectActionMessage("");

    try {
      const createdProject = await createProject(title);
      setProjectTitleInput("");
      setProjectActionMessage(`项目已创建：${createdProject.projectId}`);
      await refreshProjectList("");
      switchProject(createdProject.projectId);
    } catch (error) {
      setProjectActionMessage(error instanceof Error ? error.message : "无法创建项目");
    } finally {
      setIsCreatingProject(false);
    }
  }

  async function handleSearchProjects() {
    try {
      await refreshProjectList(projectKeyword);
      setProjectActionMessage("");
    } catch (error) {
      setProjectActionMessage(error instanceof Error ? error.message : "无法搜索项目");
    }
  }

  async function refreshGeneratedAssets(targetProjectId: string) {
    invalidateSceneStreamCache(targetProjectId);
    setWorkflowNotice((current) =>
      current && current.step !== "failed"
        ? {
            ...current,
            step: isSceneBuildActive ? "generating" : "refreshing",
            title: isSceneBuildActive ? "正在边分析边生成" : "完成后自动刷新",
            detail: isSceneBuildActive
              ? "正在同步新场景；后台继续分析并生成后续内容。"
              : "后台结果已返回，正在同步故事资产、场景大纲和项目状态。",
            tone: isSceneBuildActive ? "running" : "success",
            retryAction: null
          }
        : current
    );

    const [entities, events, scenes, scripts] = await Promise.all([
      getStoryEntities(targetProjectId),
      getStoryEvents(targetProjectId),
      getProjectOutline(targetProjectId),
      getProjectSceneScriptsSafe(targetProjectId)
    ]);

    setReadySceneIds((current) => {
      const next = new Set(current);
      scripts.forEach((scene) => next.add(scene.sceneId));
      return next.size === current.size ? current : next;
    });

    if (entities.length > 0 || events.length > 0) {
      setStoryEntities(entities);
      setStoryEvents(events);
      setStoryAssetsMessage("");
      setStoryEventsMessage("");
      setAnalysisStatus("success");
      setAnalysisMessage(`已同步 ${entities.length} 个实体和 ${events.length} 个事件。`);
    }

    if (scenes.length > 0) {
      setOutlineScenes((current) => {
        const merged = new Map(current.map((scene) => [scene.sceneId, scene]));
        scenes.forEach((scene) => merged.set(scene.sceneId, scene));
        return Array.from(merged.values()).sort((left, right) => left.seqNo - right.seqNo);
      });
      setOutlineSourceMode("real");
      setOutlineMessage("已读取真实场景大纲。");
    }

    await loadProjectDetail(targetProjectId);
    await refreshProjectList();
    return { entities, events, scenes };
  }

  async function refreshGeneratedAssetsAndContinue(targetProjectId: string) {
    const { events, scenes } = await refreshGeneratedAssets(targetProjectId);
    if (events.length > 0 && scenes.length === 0) {
      const job = await generateProjectOutline(targetProjectId);
      markWorkflowSubmitted(job, "outline");
      setOutlineMessage(`场景大纲任务已提交到 MQ：${job.jobId}`);
    } else if (scenes.length > 0) {
      const existingScripts = await getProjectSceneScriptsSafe(targetProjectId);
      if (existingScripts.length < scenes.length) {
        const job = await generateProjectSceneScripts(targetProjectId);
        setSceneDetailMessage(`Scene 剧本生成任务已提交到 MQ：${job.jobId}`);
      }
    }
  }

  async function getProjectSceneScriptsSafe(targetProjectId: string) {
    try {
      return await listProjectScenes(targetProjectId);
    } catch {
      return [];
    }
  }

  async function runStoryAnalysis(targetProjectId: string) {
    invalidateSceneStreamCache(targetProjectId);
    setIsSceneBuildActive(true);
    setAnalysisStatus("");
    setWorkflowNotice({
      jobId: "",
      jobType: "ANALYZE_STORY",
      step: "submitted",
      title: "任务已提交",
      detail: "正在向 MQ 投递故事分析任务。",
      tone: "info",
      retryAction: "analyze"
    });
    setAnalysisMessage("任务已提交，正在分析。完成后自动刷新工作台。");
    const job = await analyzeStoryAssets(targetProjectId);
    markWorkflowSubmitted(job, "analyze");
    setAnalysisStatus("success");
    setAnalysisMessage(`任务已提交：${job.jobId}。正在分析，完成后自动刷新。`);
    await loadProjectDetail(targetProjectId);
    await refreshProjectList();
    return job;
  }

  async function completeSourceSubmission(nextChapters: ChapterViewModel[]) {
    invalidateSceneStreamCache(project.projectId);
    setChapters(nextChapters);
    setOutlineScenes([]);
    setReadySceneIds(new Set());
    setRevealedSceneIds(new Set());
    setIsSceneBuildActive(false);
    readinessHydratedProjectRef.current = project.projectId;
    setOutlineSourceMode("empty");
    setSceneDetail(null);
    setSceneDetailSourceMode("empty");
    setValidationReportData(emptyValidationReport);
    setValidationSourceMode("empty");
    setYamlPreviewContent(buildYamlPreview(null, project));
    setYamlSourceMode("empty");
    setProgressStreamMessage("");
    setProgressStreamPhase("");
    setProgressStreamValue(null);
    setProgressSourceMode("static");
    setStoryEntities([]);
    setStoryEvents([]);
    setChapterSummaryMessage("");
    setSourceSubmitMessage(`已切分 ${nextChapters.length} 章，正在提交 MQ 分析任务。`);

    setIsAnalyzing(true);
    try {
      await runStoryAnalysis(project.projectId);
      setSourceSubmitMessage(`已切分 ${nextChapters.length} 章，故事资产分析任务已提交。`);
    } catch (analysisError) {
      setAnalysisStatus("error");
      setAnalysisMessage(analysisError instanceof Error ? analysisError.message : "自动故事分析失败");
      markWorkflowFailed("正文已提交，但 MQ 分析任务提交失败，可重试。", "analyze");
      setSourceSubmitMessage("正文已提交，但 MQ 分析任务提交失败，可执行全量分析重试。");
      await loadProjectDetail(project.projectId);
      await refreshProjectList();
    } finally {
      setIsAnalyzing(false);
    }
  }

  async function completeSourceAppend(nextChapters: ChapterViewModel[], appendedLabel: string) {
    invalidateSceneStreamCache(project.projectId);
    setChapters(nextChapters);
    setSourceSubmitMessage(
      `${appendedLabel}已追加，当前共 ${nextChapters.length} 章。可执行增量分析处理新增内容。`
    );
    await loadProjectDetail(project.projectId);
    await refreshProjectList();
  }

  async function handleSubmitSourceText() {
    const content = sourceTextInput.trim();
    if (connectionMode !== "connected" || !content || isSubmittingSource || isAnalyzing) return;

    setIsSubmittingSource(true);
    setSourceSubmitMessage("");
    setAnalysisResult(null);
    setAnalysisMessage("");
    setAnalysisStatus("");

    try {
      const nextChapters = await submitProjectSource(project.projectId, content);
      setSourceTextInput("");
      await completeSourceSubmission(nextChapters);
    } catch (error) {
      setSourceSubmitMessage(error instanceof Error ? error.message : "无法提交小说正文");
    } finally {
      setIsSubmittingSource(false);
    }
  }

  async function handleAppendSourceText() {
    const content = sourceTextInput.trim();
    if (connectionMode !== "connected" || !content || isSubmittingSource || isAnalyzing) return;

    setIsSubmittingSource(true);
    setSourceSubmitMessage("");

    try {
      const nextChapters = await appendProjectSource(project.projectId, content);
      setSourceTextInput("");
      await completeSourceAppend(nextChapters, "文本章节");
    } catch (error) {
      setSourceSubmitMessage(error instanceof Error ? error.message : "无法追加小说章节");
    } finally {
      setIsSubmittingSource(false);
    }
  }

  async function handleUploadSourceFile() {
    if (connectionMode !== "connected" || !sourceFileInput || isSubmittingSource || isAnalyzing) return;

    setIsSubmittingSource(true);
    setSourceSubmitMessage("");
    setAnalysisResult(null);
    setAnalysisMessage("");
    setAnalysisStatus("");

    try {
      const nextChapters = await uploadProjectSourceFile(project.projectId, sourceFileInput);
      setSourceFileInput(null);
      await completeSourceSubmission(nextChapters);
    } catch (error) {
      setSourceSubmitMessage(error instanceof Error ? error.message : "无法上传小说文件");
    } finally {
      setIsSubmittingSource(false);
    }
  }

  async function handleAppendSourceFile() {
    if (connectionMode !== "connected" || !sourceFileInput || isSubmittingSource || isAnalyzing) return;

    setIsSubmittingSource(true);
    setSourceSubmitMessage("");

    try {
      const nextChapters = await appendProjectSourceFile(project.projectId, sourceFileInput);
      setSourceFileInput(null);
      await completeSourceAppend(nextChapters, "文件章节");
    } catch (error) {
      setSourceSubmitMessage(error instanceof Error ? error.message : "无法追加小说文件");
    } finally {
      setIsSubmittingSource(false);
    }
  }

  async function handleSummarizeChapters() {
    if (connectionMode !== "connected" || chapters.length === 0 || isSummarizingChapters) return;

    setIsSummarizingChapters(true);
    setChapterSummaryMessage("");

    try {
      const summarizedChapters = await summarizeProjectChapters(project.projectId);
      setChapters(summarizedChapters);
      const summarizedCount = summarizedChapters.filter((chapter) => chapter.summary?.trim()).length;
      setChapterSummaryMessage(`已生成 ${summarizedCount} 个章节摘要。`);
      await loadProjectDetail(project.projectId);
      await refreshProjectList();
    } catch (error) {
      setChapterSummaryMessage(error instanceof Error ? error.message : "无法生成章节摘要");
    } finally {
      setIsSummarizingChapters(false);
    }
  }

  async function handleAnalyzeStoryAssets() {
    if (connectionMode !== "connected" || isAnalyzing) return;

    setIsAnalyzing(true);
    setAnalysisResult(null);
    setAnalysisMessage("");
    setAnalysisStatus("");

    try {
      await runStoryAnalysis(project.projectId);
    } catch (error) {
      setIsSceneBuildActive(false);
      setAnalysisStatus("error");
      const message = error instanceof Error ? error.message : "无法执行故事资产分析";
      setAnalysisMessage(`${message}。可重试。`);
      markWorkflowFailed(`${message}。可重试。`, "analyze");
    } finally {
      setIsAnalyzing(false);
    }
  }

  async function handleAnalyzeStoryAssetsIncremental() {
    if (connectionMode !== "connected" || isAnalyzing) return;

    setIsAnalyzing(true);
    setAnalysisResult(null);
    setAnalysisMessage("");
    setAnalysisStatus("");
    setIsSceneBuildActive(true);

    try {
      const job = await analyzeStoryAssetsIncremental(project.projectId);
      invalidateSceneStreamCache(project.projectId);
      markWorkflowSubmitted(job, "analyze");
      setAnalysisStatus("success");
      setAnalysisMessage(`增量任务已提交：${job.jobId}。正在分析，完成后自动刷新。`);
      await loadProjectDetail(project.projectId);
      await refreshProjectList();
    } catch (error) {
      setIsSceneBuildActive(false);
      setAnalysisStatus("error");
      const message = error instanceof Error ? error.message : "无法执行增量故事资产分析";
      setAnalysisMessage(`${message}。可重试。`);
      markWorkflowFailed(`${message}。可重试。`, "analyze");
    } finally {
      setIsAnalyzing(false);
    }
  }

  async function handleGenerateIncrementalOutline() {
    if (connectionMode !== "connected" || isProjectOperationBusy) return;

    setIsSceneBuildActive(true);
    setOutlineMessage("正在从第一个场景开始构建故事线...");

    try {
      const job = await generateProjectOutlineIncremental(project.projectId);
      invalidateSceneStreamCache(project.projectId);
      markWorkflowSubmitted(job, "outline");
      setOutlineMessage(`任务已提交：${job.jobId}。正在生成场景，完成后自动刷新。`);
      await loadProjectDetail(project.projectId);
      await refreshProjectList();
    } catch (error) {
      setIsSceneBuildActive(false);
      const message = error instanceof Error ? error.message : "无法生成增量场景大纲";
      setOutlineMessage(`${message}。可重试。`);
      markWorkflowFailed(`${message}。可重试。`, "outline");
    }
  }

  async function handleRegenerateScene() {
    if (
      connectionMode !== "connected" ||
      outlineSourceMode !== "real" ||
      !selectedSceneId ||
      isRegeneratingScene ||
      isStreamingScene
    ) {
      return;
    }

    startSceneStream(selectedSceneId);
  }

  function startSceneStream(sceneId: string) {
    if (!sceneId) return;
    const cacheKey = `${project.projectId}:${sceneId}`;

    if (
      connectionMode !== "connected" ||
      outlineSourceMode !== "real" ||
      isNonStreamOperationBusy ||
      activeSceneStreamRef.current !== null
    ) {
      return;
    }

    setIsRegeneratingScene(true);
    setIsStreamingScene(true);
    setSceneStreamContent("");
    setSceneDetailMessage("正在流式生成并保存当前 Scene...");
    setSceneStreamMessage("正在连接 AI 正式流式生成...");
    sceneStreamCacheRef.current[cacheKey] = { content: "", status: "streaming" };

    const streamUrl = `${appConfig.apiBaseUrl}/projects/${encodeURIComponent(
      project.projectId
    )}/scenes/${encodeURIComponent(sceneId)}/stream`;
    const eventSource = new EventSource(streamUrl);
    const requestId = ++sceneStreamRequestSequenceRef.current;
    activeSceneStreamRef.current = {
      cacheKey,
      eventSource,
      projectId: project.projectId,
      requestId,
      sceneId
    };
    let closed = false;
    // 把高频 chunk 合并成 ~50ms 一次的刷新，避免每个 token 都触发整树重渲染导致流式卡顿。
    let accumulated = "";
    let pending = "";
    let flushHandle: ReturnType<typeof setTimeout> | null = null;

    function isCurrentStream() {
      return activeSceneStreamRef.current?.requestId === requestId;
    }

    function flushStreamBuffer() {
      flushHandle = null;
      if (!pending || !isCurrentStream()) return;
      accumulated += pending;
      pending = "";
      setSceneStreamContent(accumulated);
      sceneStreamCacheRef.current[cacheKey] = { content: accumulated, status: "streaming" };
    }

    function closeStream(message: string | undefined, status: "completed" | "failed") {
      if (closed) return;
      closed = true;
      if (flushHandle != null) {
        clearTimeout(flushHandle);
        flushHandle = null;
      }
      eventSource.close();
      if (!isCurrentStream()) return;
      if (pending) {
        accumulated += pending;
        pending = "";
        setSceneStreamContent(accumulated);
      }
      activeSceneStreamRef.current = null;
      sceneStreamCacheRef.current[cacheKey] = {
        content: accumulated,
        status
      };
      setIsStreamingScene(false);
      setIsRegeneratingScene(false);
      if (message) setSceneStreamMessage(message);
    }

    function readPayload(event: MessageEvent<string>) {
      try {
        return JSON.parse(event.data) as {
          content?: string;
          message?: string;
          scene?: BackendSceneDetailResponse;
        };
      } catch {
        closeStream("AI 正式流式生成返回数据格式异常，可重新生成。", "failed");
        return null;
      }
    }

    eventSource.addEventListener("started", (event) => {
      if (!isCurrentStream()) return;
      const payload = readPayload(event as MessageEvent<string>);
      if (payload) setSceneStreamMessage(payload.message ?? "AI 正式流式生成已开始。");
    });

    eventSource.addEventListener("chunk", (event) => {
      if (!isCurrentStream()) return;
      const payload = readPayload(event as MessageEvent<string>);
      if (payload?.content) {
        pending += payload.content;
        if (flushHandle == null) {
          flushHandle = setTimeout(flushStreamBuffer, 50);
        }
      }
    });

    eventSource.addEventListener("done", (event) => {
      if (!isCurrentStream()) return;
      const payload = readPayload(event as MessageEvent<string>);
      if (payload?.scene) {
        const detail = adaptSceneDetail(payload.scene);
        setSceneDetail(detail);
        setSceneDetailSourceMode("real");
        setSceneDetailMessage("正式 Scene 已保存。");
        setReadySceneIds((current) => {
          const next = new Set(current);
          next.add(detail.sceneId);
          return next;
        });
        void loadProjectDetail(project.projectId);
        void refreshProjectList();
      }
      if (payload) closeStream(payload.message ?? "正式 Scene 已流式生成并落库。", "completed");
    });

    eventSource.addEventListener("failed", (event) => {
      if (!isCurrentStream()) return;
      const payload = readPayload(event as MessageEvent<string>);
      if (payload) {
        setSceneDetailMessage(payload.message ?? "AI 正式流式生成失败，可重新生成。");
        closeStream(payload.message ?? "AI 正式流式生成失败，可重新生成。", "failed");
      }
    });

    eventSource.onerror = () => closeStream("AI 正式流式生成连接已断开，请稍后刷新 Scene 状态。", "failed");
  }

  async function handleValidateProject() {
    if (connectionMode !== "connected" || outlineSourceMode !== "real" || isValidatingProject) return;

    setIsValidatingProject(true);
    setValidationMessage("正在执行结构校验...");

    try {
      const report = await validateProjectScenes(project.projectId);
      setValidationReportData(report);
      setValidationSourceMode("real");
      setValidationMessage("已读取真实校验结果。");
      await loadProjectDetail(project.projectId);
      await refreshProjectList();
    } catch (error) {
      setValidationReportData(emptyValidationReport);
      setValidationSourceMode("empty");
      setValidationMessage(error instanceof Error ? error.message : "无法执行项目校验");
    } finally {
      setIsValidatingProject(false);
    }
  }

  async function handleExportYaml() {
    if (connectionMode !== "connected" || isExportingYaml) return;

    setIsExportingYaml(true);
    setYamlPreviewMessage("正在导出 YAML...");

    try {
      const yamlContent = await exportProjectYaml(project.projectId);
      setYamlPreviewContent(yamlContent);
      setYamlSourceMode("real");
      setYamlPreviewMessage("真实 YAML 已刷新。");
      downloadTextFile(`${project.projectId}.yaml`, yamlContent);
      await loadProjectDetail(project.projectId);
      await refreshProjectList();
    } catch (error) {
      setYamlPreviewContent(buildYamlPreview(sceneDetail, project));
      setYamlSourceMode("empty");
      setYamlPreviewMessage(error instanceof Error ? error.message : "无法导出项目 YAML");
    } finally {
      setIsExportingYaml(false);
    }
  }

  function handleCopyYaml() {
    void navigator.clipboard?.writeText(yamlPreviewContent);
    setYamlPreviewMessage("YAML 已复制到剪贴板。");
  }

  useEffect(() => {
    let cancelled = false;

    readinessHydratedProjectRef.current = "";
    setReadySceneIds(new Set());
    setRevealedSceneIds(new Set());
    setIsSceneBuildActive(false);

    async function bootstrapProject() {
      setAnalysisResult(null);
      setAnalysisMessage("");
      setAnalysisStatus("");

      try {
        const projects = await listProjects();
        if (cancelled) return;

        setProjectList(projects);
        const targetProjectId = isContractProjectId(projectId) ? projectId : projects[0]?.projectId ?? "";

        if (!targetProjectId) {
          setProject(mockProject);
          setChapters([]);
          setOutlineScenes([]);
          setSceneDetail(null);
          setStoryEntities([]);
          setStoryEvents([]);
          setValidationReportData(emptyValidationReport);
          setValidationSourceMode("empty");
          setYamlPreviewContent(buildYamlPreview(null, mockProject));
          setYamlSourceMode("empty");
          setConnectionMode("mock-only");
          setErrorMessage("暂无真实项目，请先新建项目并提交小说。");
          return;
        }

        if (targetProjectId !== projectId) {
          switchProject(targetProjectId);
          return;
        }

        await loadProjectDetail(targetProjectId);
      } catch (error) {
        if (cancelled) return;
        setErrorMessage(error instanceof Error ? error.message : "无法连接项目接口");
        setProject(mockProject);
        setChapters([]);
        setOutlineScenes([]);
        setSceneDetail(null);
        setStoryEntities([]);
        setStoryEvents([]);
        setValidationReportData(emptyValidationReport);
        setValidationSourceMode("empty");
        setYamlPreviewContent(buildYamlPreview(null, mockProject));
        setYamlSourceMode("empty");
        setProgressStreamMessage("");
        setProgressStreamPhase("");
        setProgressStreamValue(null);
        setProgressSourceMode("static");
        setConnectionMode(appConfig.enableMockFallback ? "mock-only" : "error");
      }
    }

    void bootstrapProject();

    return () => {
      cancelled = true;
    };
  }, [projectId]);

  useEffect(() => {
    if (connectionMode !== "connected") {
      setStoryEntities([]);
      setStoryAssetsMessage("");
      return;
    }

    let cancelled = false;

    async function loadStoryEntities() {
      try {
        const entities = await getStoryEntities(project.projectId);
        if (!cancelled) {
          setStoryEntities(entities);
          setStoryAssetsMessage("");
        }
      } catch (error) {
        if (!cancelled) {
          setStoryEntities([]);
          setStoryAssetsMessage(error instanceof Error ? error.message : "无法加载故事实体");
        }
      }
    }

    void loadStoryEntities();
    return () => {
      cancelled = true;
    };
  }, [connectionMode, project.projectId]);

  useEffect(() => {
    if (connectionMode !== "connected") {
      setStoryEvents([]);
      setStoryEventsMessage("");
      return;
    }

    let cancelled = false;

    async function loadStoryEvents() {
      try {
        const events = await getStoryEvents(project.projectId);
        if (!cancelled) {
          setStoryEvents(events);
          setStoryEventsMessage("");
        }
      } catch (error) {
        if (!cancelled) {
          setStoryEvents([]);
          setStoryEventsMessage(error instanceof Error ? error.message : "无法加载故事事件");
        }
      }
    }

    void loadStoryEvents();
    return () => {
      cancelled = true;
    };
  }, [connectionMode, project.projectId]);

  useEffect(() => {
    if (connectionMode !== "connected" || !canLoadGeneratedScenes) {
      setOutlineScenes([]);
      setOutlineSourceMode("empty");
      setOutlineMessage(connectionMode === "connected" ? "执行故事分析后将加载真实场景大纲。" : "");
      return;
    }

    let cancelled = false;
    setOutlineMessage("正在加载真实场景大纲...");

    async function loadProjectOutline() {
      try {
        const [scenes, scripts] = await Promise.all([
          getProjectOutline(project.projectId),
          getProjectSceneScriptsSafe(project.projectId)
        ]);
        if (cancelled) return;
        const generatedIds = new Set(scripts.map((scene) => scene.sceneId));
        setReadySceneIds((current) => {
          const next = new Set(current);
          generatedIds.forEach((sceneId) => next.add(sceneId));
          return next;
        });

        if (readinessHydratedProjectRef.current !== project.projectId) {
          const initiallyRevealed = new Set<string>();
          if (!isSceneBuildActive) {
            for (const scene of scenes.slice().sort((left, right) => left.seqNo - right.seqNo)) {
              if (!generatedIds.has(scene.sceneId)) break;
              initiallyRevealed.add(scene.sceneId);
            }
          }
          setRevealedSceneIds(initiallyRevealed);
          readinessHydratedProjectRef.current = project.projectId;
        }
        if (scenes.length === 0) {
          setOutlineScenes([]);
          setOutlineSourceMode("empty");
          setOutlineMessage("真实大纲暂为空，请先提交文本并等待场景生成完成。");
          return;
        }
        setOutlineScenes(scenes);
        setOutlineSourceMode("real");
        setOutlineMessage("已读取真实场景大纲。");
      } catch (error) {
        if (!cancelled) {
          setOutlineScenes([]);
          setOutlineSourceMode("empty");
          setOutlineMessage(error instanceof Error ? error.message : "无法加载真实场景大纲。");
        }
      }
    }

    void loadProjectOutline();
    return () => {
      cancelled = true;
    };
  }, [connectionMode, project.projectId, canLoadGeneratedScenes]);

  useEffect(() => {
    if (outlineScenes.length === 0) {
      setSelectedSceneId("");
      return;
    }

    const selectedSceneStillExists = outlineScenes.some((scene) => scene.sceneId === selectedSceneId);
    if (selectedSceneStillExists) {
      return;
    }

    const revealedScenes = outlineScenes
      .slice()
      .sort((left, right) => left.seqNo - right.seqNo)
      .filter((scene) => revealedSceneIds.has(scene.sceneId));

    setSelectedSceneId(revealedScenes[0]?.sceneId ?? "");
  }, [outlineScenes, revealedSceneIds, selectedSceneId, setSelectedSceneId]);

  useEffect(() => {
    const orderedScenes = outlineScenes.slice().sort((left, right) => left.seqNo - right.seqNo);
    const nextScene = orderedScenes.find(
      (scene) => readySceneIds.has(scene.sceneId) && !revealedSceneIds.has(scene.sceneId)
    );
    if (!nextScene) return;
    const handle = window.setTimeout(() => {
      setRevealedSceneIds((current) => {
        if (current.has(nextScene.sceneId)) return current;
        const next = new Set(current);
        next.add(nextScene.sceneId);
        return next;
      });
      if (!selectedSceneId) {
        setSelectedSceneId(nextScene.sceneId);
      }
    }, 180);

    return () => window.clearTimeout(handle);
  }, [outlineScenes, readySceneIds, revealedSceneIds, selectedSceneId, setSelectedSceneId]);

  useEffect(() => {
    if (connectionMode !== "connected" || !isSceneBuildActive) return;
    let cancelled = false;
    let syncing = false;

    async function syncGeneratedSceneIds() {
      if (syncing) return;
      syncing = true;
      try {
        const scripts = await listProjectScenes(project.projectId);
        if (cancelled) return;
        const generatedIds = new Set(scripts.map((scene) => scene.sceneId));
        setReadySceneIds((current) => {
          const next = new Set(current);
          generatedIds.forEach((sceneId) => next.add(sceneId));
          return next.size === current.size ? current : next;
        });
        if (
          selectedSceneId &&
          generatedIds.has(selectedSceneId)
        ) {
          const detail = await getProjectScene(project.projectId, selectedSceneId);
          if (!cancelled) {
            setSceneDetail(detail);
            setSceneDetailSourceMode("real");
            setSceneDetailMessage("已读取真实 Scene。");
          }
        }
      } catch {
        // SSE 仍是主通道；轮询只负责断线或漏事件后的状态自愈。
      } finally {
        syncing = false;
      }
    }

    void syncGeneratedSceneIds();
    const handle = window.setInterval(() => void syncGeneratedSceneIds(), 2500);
    return () => {
      cancelled = true;
      window.clearInterval(handle);
    };
  }, [connectionMode, isSceneBuildActive, project.projectId, selectedSceneId]);

  useEffect(() => {
    return () => {
      activeSceneStreamRef.current?.eventSource.close();
      activeSceneStreamRef.current = null;
    };
  }, []);

  useEffect(() => {
    cancelActiveSceneStream();
    setSceneStreamContent("");
    setSceneStreamMessage("");

    if (!selectedSceneId) {
      setSceneDetail(null);
      setSceneDetailSourceMode("empty");
      setSceneDetailMessage("");
      return;
    }

    if (connectionMode !== "connected" || !canLoadGeneratedScenes) {
      setSceneDetail(null);
      setSceneDetailSourceMode("empty");
      setSceneDetailMessage(connectionMode === "connected" ? "执行故事分析后将加载真实 Scene。" : "");
      return;
    }

    let cancelled = false;
    setSceneDetail(null);
    setSceneDetailSourceMode("empty");
    setSceneDetailMessage("正在加载真实 Scene...");

    async function loadSceneDetail() {
      try {
        const detail = await getProjectScene(project.projectId, selectedSceneId);
        if (!cancelled) {
          setSceneDetail(detail);
          setSceneDetailSourceMode("real");
          setSceneDetailMessage("已读取真实 Scene。");
        }
      } catch (error) {
        if (cancelled) return;
        setSceneDetail(null);
        setSceneDetailSourceMode("empty");
        setSceneDetailMessage(
          error instanceof Error
            ? `${error.message}。不会自动重新提交任务，请等待后台完成或手动触发生成。`
            : "真实 Scene 尚未就绪。不会自动重新提交任务，请等待后台完成或手动触发生成。"
        );
      }
    }

    void loadSceneDetail();
    return () => {
      cancelled = true;
    };
  }, [connectionMode, project.projectId, selectedSceneId, canLoadGeneratedScenes, outlineSourceMode]);

  useEffect(() => {
    if (yamlSourceMode !== "real") {
      setYamlPreviewContent(buildYamlPreview(sceneDetail, project));
    }
  }, [sceneDetail, project, yamlSourceMode]);

  useEffect(() => {
    if (connectionMode !== "connected") {
      setProgressStreamMessage("");
      setProgressStreamPhase("");
      setProgressStreamValue(null);
      setProgressSourceMode("static");
      return;
    }

    const eventsUrl = `${appConfig.apiBaseUrl}/projects/${project.projectId}/events`;
    const eventSource = new EventSource(eventsUrl);
    const eventNames = [
      "job.started",
      "phase.changed",
      "assets.batch.ready",
      "outline.ready",
      "scene.done",
      "validation.warn",
      "job.completed",
      "job.failed"
    ];

    function handleStreamMessage(rawMessage: MessageEvent<string>) {
      try {
        const parsed = parseProgressStreamMessage(rawMessage);
        if (parsed && parsed.data.projectId === project.projectId) {
          applyProgressEvent(parsed);
          if (parsed.event === "assets.batch.ready") {
            void refreshStoryAssets(project.projectId).catch(() => {
              setProgressSourceMode("static");
            });
          } else if (parsed.event === "outline.ready") {
            void refreshGeneratedAssets(project.projectId).catch(() => {
              setProgressSourceMode("static");
            });
          } else if (parsed.event === "job.completed") {
            void refreshGeneratedAssetsAndContinue(project.projectId).catch(() => {
              setProgressSourceMode("static");
            });
          }
        }
      } catch {
        setProgressSourceMode("static");
      }
    }

    eventSource.onmessage = handleStreamMessage;
    eventSource.onerror = () => setProgressSourceMode("static");
    eventNames.forEach((eventName) => {
      eventSource.addEventListener(eventName, handleStreamMessage as EventListener);
    });

    return () => {
      eventNames.forEach((eventName) => {
        eventSource.removeEventListener(eventName, handleStreamMessage as EventListener);
      });
      eventSource.close();
    };
  }, [connectionMode, project.projectId]);

  const derived = useMemo(() => {
    const displayProgress =
      progressSourceMode === "real" && progressStreamValue != null ? progressStreamValue : project.progress;
    const activePhaseLabel =
      phaseKeyToLabel[
        progressSourceMode === "real" && progressStreamPhase ? progressStreamPhase : project.currentPhase
      ] ?? "Scene 生成";
    const analysisModeLabel =
      analysisResult?.generationMode == null
        ? ""
        : analysisModeLabels[analysisResult.generationMode] ?? analysisResult.generationMode;
    const analysisReady =
      storyEntities.length > 0 ||
      storyEvents.length > 0 ||
      project.status === "ENTITY_READY" ||
      project.status === "OUTLINED" ||
      project.status === "SCENE_GENERATING" ||
      project.status === "COMPLETED";
    const selectedSceneIndex = outlineScenes.findIndex((scene) => scene.sceneId === selectedSceneId);
    const projectCompleted = project.status === "COMPLETED";
    const selectedSceneUsesFallback = sceneUsesFallback(sceneDetail);
    const selectedWarnings: ValidationItemViewModel[] =
      validationSourceMode === "real"
        ? validationReportData.items.filter((item) => item.sceneId === selectedSceneId)
        : sceneDetailSourceMode === "real" && sceneDetail
          ? sceneDetail.warnings.map((message, index) => ({
              sceneId: sceneDetail.sceneId,
              level: "warning",
              field: `warning_${index + 1}`,
              message
            }))
          : [];
    const currentValidationStatus =
      validationSourceMode === "real"
        ? validationReportData.status
        : sceneDetail?.validationStatus ?? "PENDING";

    return {
      activePhaseIndex: Math.max(phaseLabels.indexOf(activePhaseLabel), 0),
      activePhaseLabel,
      analysisStateLabel: isSceneBuildActive
        ? "逐章分析生成中"
        : analysisModeLabel || (analysisReady ? "分析已完成" : "待执行分析"),
      characterCount: storyEntities.filter((entity) => entity.entityType === "CHARACTER").length,
      chapterSummaryCount: chapters.filter((chapter) => chapter.summary?.trim()).length,
      connectionLabel:
        connectionMode === "connected" ? "真实项目" : connectionMode === "mock-only" ? "Mock 回退" : "连接失败",
      currentValidationStatus,
      deliveryStatusLabel:
        yamlSourceMode === "real" ? "Ready" : projectCompleted && connectionMode === "connected" ? "可导出" : "Pending",
      deliveryStatusCaption:
        yamlSourceMode === "real"
          ? "真实 YAML 已加载"
          : projectCompleted && connectionMode === "connected"
            ? "执行导出刷新最终稿"
            : "等待导出链路",
      displayProgress,
      locationCount: storyEntities.filter((entity) => entity.entityType === "LOCATION").length,
      projectCompleted,
      projectStatusLabel: projectStatusLabels[project.status] ?? project.status,
      sceneSelectionLabel:
        selectedSceneIndex >= 0 ? `${selectedSceneIndex + 1} / ${outlineScenes.length}` : "未选中",
      selectedSceneIndex,
      selectedSceneUsesFallback,
      selectedWarnings,
      totalValidationCount:
        validationSourceMode === "real"
          ? validationReportData.items.length
          : sceneDetail?.warnings.length ?? 0,
      workflowNoticeRetryLabel:
        workflowNotice?.retryAction === "outline"
          ? "重试生成场景"
          : workflowNotice?.retryAction === "analyze"
            ? "重试分析"
            : ""
    };
  }, [
    analysisResult,
    chapters,
    connectionMode,
    isSceneBuildActive,
    outlineScenes,
    progressSourceMode,
    progressStreamPhase,
    progressStreamValue,
    project,
    sceneDetail,
    sceneDetailSourceMode,
    selectedSceneId,
    storyEntities,
    storyEvents,
    validationReportData,
    validationSourceMode,
    workflowNotice,
    yamlSourceMode
  ]);

  return {
    activeView,
    actions: {
      handleAnalyzeStoryAssets,
      handleAnalyzeStoryAssetsIncremental,
      handleAppendSourceFile,
      handleAppendSourceText,
      handleCopyYaml,
      handleCreateProject,
      handleExportYaml,
      handleGenerateIncrementalOutline,
      handleRegenerateScene,
      retryWorkflowNotice,
      handleSearchProjects,
      handleSubmitSourceText,
      handleSummarizeChapters,
      handleUploadSourceFile,
      handleValidateProject,
      refreshProjectList,
      setActiveView,
      setProjectKeyword,
      setProjectTitleInput,
      setSelectedSceneId,
      setSourceFileInput,
      setSourceTextInput,
      switchProject
    },
    derived,
    messages: {
      analysisMessage,
      analysisStatus,
      chapterSummaryMessage,
      errorMessage,
      outlineMessage,
      projectActionMessage,
      progressStreamMessage,
      sceneDetailMessage,
      sceneStreamMessage,
      sourceSubmitMessage,
      storyAssetsMessage,
      storyEventsMessage,
      validationMessage,
      yamlPreviewMessage
    },
    sources: {
      outlineSourceMode,
      progressSourceMode,
      sceneDetailSourceMode,
      validationSourceMode,
      yamlSourceMode
    },
    state: {
      analysisResult,
      chapters,
      connectionMode,
      isAnalyzing,
      isCreatingProject,
      isExportingYaml,
      isProjectOperationBusy,
      isRegeneratingScene,
      isSceneBuildActive,
      isStreamingScene,
      isSubmittingSource,
      isSummarizingChapters,
      isValidatingProject,
      isWorkflowRunning,
      operationElapsedMs,
      outlineScenes,
      readySceneIds,
      revealedSceneIds,
      project,
      projectKeyword,
      projectList,
      projectTitleInput,
      sceneDetail,
      sceneStreamContent,
      selectedSceneId,
      sourceFileInput,
      sourceTextInput,
      storyEntities,
      storyEvents,
      validationReportData,
      workflowNotice,
      yamlPreviewContent
    }
  };
}

export type WorkbenchModel = ReturnType<typeof useWorkbench>;
