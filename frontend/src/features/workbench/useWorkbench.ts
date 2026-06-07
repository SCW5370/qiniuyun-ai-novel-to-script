import { useEffect, useMemo, useState } from "react";
import {
  analyzeStoryAssets,
  analyzeStoryAssetsIncremental,
  appendProjectSource,
  appendProjectSourceFile,
  createProject,
  exportProjectYaml,
  generateProjectOutline,
  generateProjectOutlineIncremental,
  getProject,
  getProjectChapters,
  getProjectOutline,
  getProjectScene,
  getStoryEntities,
  getStoryEvents,
  listProjects,
  regenerateProjectScene,
  summarizeProjectChapters,
  submitProjectSource,
  uploadProjectSourceFile,
  validateProjectScenes
} from "../../api/client";
import type {
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
import outlineData from "../../../../samples/mock-outline.json";
import scenesData from "../../../../samples/mock-scenes.json";
import validationReport from "../../../../samples/mock-validation-report.json";
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

const mockOutlineScenes = outlineData as OutlineSceneViewModel[];
const mockSceneDetails = scenesData as SceneDetailViewModel[];
const mockSceneMap = Object.fromEntries(mockSceneDetails.map((scene) => [scene.sceneId, scene]));
const mockValidationReport = validationReport as ValidationReportViewModel;

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
  const [outlineScenes, setOutlineScenes] = useState<OutlineSceneViewModel[]>(mockOutlineScenes);
  const [sceneDetail, setSceneDetail] = useState<SceneDetailViewModel | null>(
    mockSceneMap[mockOutlineScenes[0]?.sceneId] ?? null
  );
  const [storyEntities, setStoryEntities] = useState<StoryEntityViewModel[]>([]);
  const [storyEvents, setStoryEvents] = useState<StoryEventViewModel[]>([]);
  const [connectionMode, setConnectionMode] = useState<WorkbenchConnectionMode>("mock-only");
  const [errorMessage, setErrorMessage] = useState("");
  const [projectActionMessage, setProjectActionMessage] = useState("");
  const [sourceSubmitMessage, setSourceSubmitMessage] = useState("");
  const [chapterSummaryMessage, setChapterSummaryMessage] = useState("");
  const [outlineMessage, setOutlineMessage] = useState("");
  const [outlineSourceMode, setOutlineSourceMode] = useState<"real" | "mock">("mock");
  const [sceneDetailMessage, setSceneDetailMessage] = useState("");
  const [sceneDetailSourceMode, setSceneDetailSourceMode] = useState<"real" | "mock" | "empty">(
    "mock"
  );
  const [storyAssetsMessage, setStoryAssetsMessage] = useState("");
  const [storyEventsMessage, setStoryEventsMessage] = useState("");
  const [validationReportData, setValidationReportData] =
    useState<ValidationReportViewModel>(mockValidationReport);
  const [validationMessage, setValidationMessage] = useState("");
  const [validationSourceMode, setValidationSourceMode] = useState<"real" | "mock">("mock");
  const [yamlPreviewContent, setYamlPreviewContent] = useState(
    buildYamlPreview(mockSceneMap[mockOutlineScenes[0]?.sceneId] ?? null, mockProject)
  );
  const [yamlPreviewMessage, setYamlPreviewMessage] = useState("");
  const [yamlSourceMode, setYamlSourceMode] = useState<"real" | "mock">("mock");
  const [progressStreamMessage, setProgressStreamMessage] = useState("");
  const [progressStreamPhase, setProgressStreamPhase] = useState("");
  const [progressStreamValue, setProgressStreamValue] = useState<number | null>(null);
  const [progressSourceMode, setProgressSourceMode] = useState<"real" | "static">("static");
  const [workflowNotice, setWorkflowNotice] = useState<WorkflowNotice | null>(null);
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

  const canLoadGeneratedScenes =
    project.status === "ENTITY_READY" ||
    project.status === "OUTLINED" ||
    project.status === "SCENE_GENERATING" ||
    project.status === "COMPLETED";
  const isProjectOperationBusy =
    isSubmittingSource ||
    isSummarizingChapters ||
    isAnalyzing ||
    isRegeneratingScene ||
    isStreamingScene ||
    isValidatingProject ||
    isExportingYaml;

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
    } else if (event === "outline.ready") {
      setProgressStreamPhase("outlined");
    } else if (event === "scene.done") {
      setProgressStreamPhase("scene_generating");
    } else if (event === "job.completed") {
      setProgressStreamPhase("completed");
    }

    if (event === "job.started" || event === "phase.changed") {
      const nextPhase = data.phase ?? progressStreamPhase;
      const isSceneWork =
        nextPhase.includes("outline") || nextPhase.includes("scene") || Boolean(data.jobType?.includes("OUTLINE"));
      setWorkflowNotice((current) => ({
        jobId: current?.jobId ?? data.sceneId ?? "",
        jobType: data.jobType ?? current?.jobType ?? "",
        step: isSceneWork ? "generating" : "analyzing",
        title: isSceneWork ? "正在生成场景" : "正在分析",
        detail: data.message ?? "MQ 消费端正在处理任务，完成后自动刷新工作台。",
        tone: "running",
        retryAction: isSceneWork ? "outline" : "analyze"
      }));
    } else if (event === "outline.ready" || event === "scene.done") {
      setWorkflowNotice((current) => ({
        jobId: current?.jobId ?? data.sceneId ?? "",
        jobType: data.jobType ?? current?.jobType ?? "",
        step: "refreshing",
        title: "完成后自动刷新",
        detail: data.message ?? "后台任务已产出结果，正在刷新故事资产与场景。",
        tone: "success",
        retryAction: null
      }));
    } else if (event === "job.completed") {
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
    setWorkflowNotice((current) =>
      current && current.step !== "failed"
        ? {
            ...current,
            step: "refreshing",
            title: "完成后自动刷新",
            detail: "后台结果已返回，正在同步故事资产、场景大纲和项目状态。",
            tone: "success",
            retryAction: null
          }
        : current
    );

    const [entities, events, scenes] = await Promise.all([
      getStoryEntities(targetProjectId),
      getStoryEvents(targetProjectId),
      getProjectOutline(targetProjectId)
    ]);

    if (entities.length > 0 || events.length > 0) {
      setStoryEntities(entities);
      setStoryEvents(events);
      setStoryAssetsMessage("");
      setStoryEventsMessage("");
      setAnalysisStatus("success");
      setAnalysisMessage(`已同步 ${entities.length} 个实体和 ${events.length} 个事件。`);
    }

    if (scenes.length > 0) {
      setOutlineScenes(scenes);
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
    }
  }

  async function runStoryAnalysis(targetProjectId: string) {
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
    setChapters(nextChapters);
    setOutlineScenes(mockOutlineScenes);
    setOutlineSourceMode("mock");
    setSceneDetail(mockSceneMap[mockOutlineScenes[0]?.sceneId] ?? null);
    setSceneDetailSourceMode("mock");
    setValidationReportData(mockValidationReport);
    setValidationSourceMode("mock");
    setYamlPreviewContent(buildYamlPreview(mockSceneMap[mockOutlineScenes[0]?.sceneId] ?? null, project));
    setYamlSourceMode("mock");
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

    try {
      const job = await analyzeStoryAssetsIncremental(project.projectId);
      markWorkflowSubmitted(job, "analyze");
      setAnalysisStatus("success");
      setAnalysisMessage(`增量任务已提交：${job.jobId}。正在分析，完成后自动刷新。`);
      await loadProjectDetail(project.projectId);
      await refreshProjectList();
    } catch (error) {
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

    setOutlineMessage("正在为新增事件生成追加场景...");

    try {
      const job = await generateProjectOutlineIncremental(project.projectId);
      markWorkflowSubmitted(job, "outline");
      setOutlineMessage(`任务已提交：${job.jobId}。正在生成场景，完成后自动刷新。`);
      await loadProjectDetail(project.projectId);
      await refreshProjectList();
    } catch (error) {
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
      isRegeneratingScene
    ) {
      return;
    }

    setIsRegeneratingScene(true);
    setSceneDetailMessage("正在重新生成当前 Scene...");

    try {
      const detail = await regenerateProjectScene(project.projectId, selectedSceneId);
      setSceneDetail(detail);
      setSceneDetailSourceMode("real");
      setSceneDetailMessage("真实 Scene 已重新生成。");
      await loadProjectDetail(project.projectId);
      await refreshProjectList();
    } catch (error) {
      setSceneDetailMessage(error instanceof Error ? error.message : "无法重新生成当前 Scene");
    } finally {
      setIsRegeneratingScene(false);
    }
  }

  function handleStreamScenePreview() {
    if (
      connectionMode !== "connected" ||
      outlineSourceMode !== "real" ||
      !selectedSceneId ||
      isProjectOperationBusy ||
      isStreamingScene
    ) {
      return;
    }

    setIsStreamingScene(true);
    setSceneStreamContent("");
    setSceneStreamMessage("正在连接 AI 流式预览...");

    const streamUrl = `${appConfig.apiBaseUrl}/projects/${encodeURIComponent(
      project.projectId
    )}/scenes/${encodeURIComponent(selectedSceneId)}/stream`;
    const eventSource = new EventSource(streamUrl);
    let closed = false;

    function closeStream(message?: string) {
      if (closed) return;
      closed = true;
      eventSource.close();
      setIsStreamingScene(false);
      if (message) setSceneStreamMessage(message);
    }

    function readPayload(event: MessageEvent<string>) {
      try {
        return JSON.parse(event.data) as { content?: string; message?: string };
      } catch {
        closeStream("AI 流式预览返回数据格式异常。");
        return null;
      }
    }

    eventSource.addEventListener("started", (event) => {
      const payload = readPayload(event as MessageEvent<string>);
      if (payload) setSceneStreamMessage(payload.message ?? "AI 流式预览已开始。");
    });

    eventSource.addEventListener("chunk", (event) => {
      const payload = readPayload(event as MessageEvent<string>);
      if (payload?.content) setSceneStreamContent((current) => current + payload.content);
    });

    eventSource.addEventListener("done", (event) => {
      const payload = readPayload(event as MessageEvent<string>);
      if (payload) closeStream(payload.message ?? "Scene 预览流式生成完成。");
    });

    eventSource.addEventListener("failed", (event) => {
      const payload = readPayload(event as MessageEvent<string>);
      if (payload) closeStream(payload.message ?? "AI 流式预览失败。");
    });

    eventSource.onerror = () => closeStream("AI 流式预览连接已断开。");
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
      setValidationReportData(mockValidationReport);
      setValidationSourceMode("mock");
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
      setYamlSourceMode("mock");
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
    if (!selectedSceneId && mockOutlineScenes[0]?.sceneId) {
      setSelectedSceneId(mockOutlineScenes[0].sceneId);
    }
  }, [selectedSceneId, setSelectedSceneId]);

  useEffect(() => {
    let cancelled = false;

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
          setStoryEntities([]);
          setStoryEvents([]);
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
        setStoryEntities([]);
        setStoryEvents([]);
        setValidationReportData(mockValidationReport);
        setValidationSourceMode("mock");
        setYamlPreviewContent(
          buildYamlPreview(mockSceneMap[mockOutlineScenes[0]?.sceneId] ?? null, mockProject)
        );
        setYamlSourceMode("mock");
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
      setOutlineScenes(mockOutlineScenes);
      setOutlineSourceMode("mock");
      setOutlineMessage(connectionMode === "connected" ? "执行故事分析后将加载真实场景大纲。" : "");
      return;
    }

    let cancelled = false;
    setOutlineMessage("正在加载真实场景大纲...");

    async function loadProjectOutline() {
      try {
        const scenes = await getProjectOutline(project.projectId);
        if (cancelled) return;
        if (scenes.length === 0) {
          setOutlineScenes(mockOutlineScenes);
          setOutlineSourceMode("mock");
          setOutlineMessage("真实大纲暂为空，继续使用 mock 大纲。");
          return;
        }
        setOutlineScenes(scenes);
        setOutlineSourceMode("real");
        setOutlineMessage("已读取真实场景大纲。");
      } catch (error) {
        if (!cancelled) {
          setOutlineScenes(mockOutlineScenes);
          setOutlineSourceMode("mock");
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

    if (!outlineScenes.some((scene) => scene.sceneId === selectedSceneId)) {
      setSelectedSceneId(outlineScenes[0].sceneId);
    }
  }, [outlineScenes, selectedSceneId, setSelectedSceneId]);

  useEffect(() => {
    const mockScene = mockSceneMap[selectedSceneId] ?? null;
    setSceneStreamContent("");
    setSceneStreamMessage("");

    if (!selectedSceneId) {
      setSceneDetail(null);
      setSceneDetailSourceMode("empty");
      setSceneDetailMessage("");
      return;
    }

    if (connectionMode !== "connected" || !canLoadGeneratedScenes) {
      setSceneDetail(mockScene);
      setSceneDetailSourceMode(mockScene ? "mock" : "empty");
      setSceneDetailMessage(connectionMode === "connected" ? "执行故事分析后将加载真实 Scene。" : "");
      return;
    }

    let cancelled = false;
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
        if (mockScene) {
          setSceneDetail(mockScene);
          setSceneDetailSourceMode("mock");
          setSceneDetailMessage(error instanceof Error ? error.message : "当前继续使用 mock Scene。");
          return;
        }
        setSceneDetail(null);
        setSceneDetailSourceMode("empty");
        setSceneDetailMessage("真实 Scene 尚未就绪，且当前没有 mock 回退。");
      }
    }

    void loadSceneDetail();
    return () => {
      cancelled = true;
    };
  }, [connectionMode, project.projectId, selectedSceneId, canLoadGeneratedScenes]);

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
          if (parsed.event === "job.completed" || parsed.event === "outline.ready") {
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
    const mockSelectedWarnings = mockValidationReport.items.filter((item) => item.sceneId === selectedSceneId);
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
          : mockSelectedWarnings;
    const currentValidationStatus =
      validationSourceMode === "real"
        ? validationReportData.status
        : sceneDetail?.validationStatus ?? mockValidationReport.status;

    return {
      activePhaseIndex: Math.max(phaseLabels.indexOf(activePhaseLabel), 0),
      activePhaseLabel,
      analysisStateLabel: analysisModeLabel || (analysisReady ? "分析已完成" : "待执行分析"),
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
          : sceneDetail?.warnings.length ?? mockValidationReport.items.length,
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
      handleStreamScenePreview,
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
      isStreamingScene,
      isSubmittingSource,
      isSummarizingChapters,
      isValidatingProject,
      outlineScenes,
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
