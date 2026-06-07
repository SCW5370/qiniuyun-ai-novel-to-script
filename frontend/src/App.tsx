import { useEffect, useState } from "react";
import {
  analyzeStoryAssets,
  createProject,
  exportProjectYaml,
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
  validateProjectScenes
} from "./api/client";
import type {
  ChapterViewModel,
  OutlineSceneViewModel,
  ProgressStreamEvent,
  ProjectViewModel,
  SceneDetailViewModel,
  StoryAnalysisViewModel,
  StoryEntityViewModel,
  StoryEventViewModel,
  ValidationReportViewModel,
  WrappedProgressStreamEvent,
  WorkbenchConnectionMode
} from "./api/types";
import { appConfig } from "./config";
import projectData from "../../samples/mock-project.json";
import outlineData from "../../samples/mock-outline.json";
import scenesData from "../../samples/mock-scenes.json";
import validationReport from "../../samples/mock-validation-report.json";

const phaseLabels = [
  "项目创建",
  "文本处理",
  "实体抽取",
  "场景规划",
  "Scene 生成",
  "结构校验",
  "YAML 导出"
];

const phaseKeyToLabel: Record<string, string> = {
  created: "项目创建",
  source_submitted: "文本处理",
  chaptered: "文本处理",
  entity_ready: "实体抽取",
  outlined: "场景规划",
  scene_generating: "Scene 生成",
  completed: "YAML 导出",
  failed: "Scene 生成"
};

const analysisModeLabels: Record<string, string> = {
  AI: "AI 抽取",
  FALLBACK: "规则兜底"
};

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

function resolveProjectId() {
  const searchParams = new URLSearchParams(window.location.search);
  return searchParams.get("projectId") || appConfig.defaultProjectId;
}

function isContractProjectId(value: string) {
  return value.startsWith("proj_");
}

function buildYamlPreview(scene: SceneDetailViewModel | null, project: ProjectViewModel) {

  if (!scene) {
    return `schema_version: "1.0.0"
meta:
  project_id: "${project.projectId}"
  title: "${project.title}"
  workflow: "reader-outline-writer-validator"
scenes: []
# 当前仍等待真实 scenes 详情接口接入`;
  }

  const actionLines = scene.action.map((line) => `    - "${line}"`).join("\n");
  const dialogueLines = scene.dialogue
    .map(
      (item) =>
        `    - character_id: "${item.characterId}"\n      line: "${item.line}"`
    )
    .join("\n");
  const sourceRefLines = scene.sourceRefs.map((ref) => `"${ref}"`).join(", ");

  return `schema_version: "1.0.0"
meta:
  project_id: "${project.projectId}"
  title: "${project.title}"
  workflow: "reader-outline-writer-validator"
scenes:
  - scene_id: "${scene.sceneId}"
    seq_no: ${scene.seqNo}
    title: "${scene.title}"
    action:
${actionLines}
    dialogue:
${dialogueLines}
    source_refs: [${sourceRefLines}]`;
}

function sceneUsesFallback(scene: SceneDetailViewModel | null) {
  return scene?.warnings.some((message) => message.includes("规则兜底")) ?? false;
}

function downloadTextFile(filename: string, content: string) {
  const blob = new Blob([content], { type: "text/yaml;charset=utf-8" });
  const objectUrl = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = objectUrl;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(objectUrl);
}

function App() {
  const [selectedSceneId, setSelectedSceneId] = useState(mockOutlineScenes[0]?.sceneId ?? "");
  const [projectId, setProjectId] = useState(resolveProjectId);
  const [projectList, setProjectList] = useState<ProjectViewModel[]>([]);
  const [projectKeyword, setProjectKeyword] = useState("");
  const [projectTitleInput, setProjectTitleInput] = useState("");
  const [sourceTextInput, setSourceTextInput] = useState("");
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
  const [analysisResult, setAnalysisResult] = useState<StoryAnalysisViewModel | null>(null);
  const [analysisMessage, setAnalysisMessage] = useState("");
  const [analysisStatus, setAnalysisStatus] = useState<"success" | "warning" | "error" | "">("");
  const [isCreatingProject, setIsCreatingProject] = useState(false);
  const [isSubmittingSource, setIsSubmittingSource] = useState(false);
  const [isSummarizingChapters, setIsSummarizingChapters] = useState(false);
  const [isAnalyzing, setIsAnalyzing] = useState(false);
  const [isRegeneratingScene, setIsRegeneratingScene] = useState(false);
  const [isValidatingProject, setIsValidatingProject] = useState(false);
  const [isExportingYaml, setIsExportingYaml] = useState(false);
  const isProjectOperationBusy =
    isSubmittingSource ||
    isSummarizingChapters ||
    isAnalyzing ||
    isRegeneratingScene ||
    isValidatingProject ||
    isExportingYaml;
  const canLoadGeneratedScenes =
    project.status === "ENTITY_READY" ||
    project.status === "OUTLINED" ||
    project.status === "SCENE_GENERATING" ||
    project.status === "COMPLETED";
  const mockSelectedWarnings = mockValidationReport.items.filter(
    (item) => item.sceneId === selectedSceneId
  );
  const selectedWarnings =
    validationSourceMode === "real"
      ? validationReportData.items.filter((item) => item.sceneId === selectedSceneId)
      : sceneDetailSourceMode === "real" && sceneDetail
        ? sceneDetail.warnings.map((message, index) => ({
            sceneId: sceneDetail.sceneId,
            level: "warning" as const,
            field: `warning_${index + 1}`,
            message
          }))
        : mockSelectedWarnings;

  function switchProject(nextProjectId: string) {
    setProjectId(nextProjectId);
    window.history.replaceState(null, "", `?projectId=${encodeURIComponent(nextProjectId)}`);
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

    setProgressSourceMode("real");
  }

  function parseProgressStreamMessage(rawMessage: MessageEvent<string>): ProgressStreamEvent | null {
    const parsed = JSON.parse(rawMessage.data) as
      | WrappedProgressStreamEvent
      | ProgressStreamEvent["data"];

    if (
      parsed &&
      typeof parsed === "object" &&
      "event" in parsed &&
      "data" in parsed &&
      parsed.data &&
      typeof parsed.data === "object"
    ) {
      return parsed as WrappedProgressStreamEvent;
    }

    if (parsed && typeof parsed === "object" && rawMessage.type !== "message") {
      return {
        event: rawMessage.type as ProgressStreamEvent["event"],
        data: parsed as ProgressStreamEvent["data"]
      };
    }

    return null;
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
    if (!title || isCreatingProject) {
      return;
    }

    setIsCreatingProject(true);
    setProjectActionMessage("");

    try {
      // 对齐开发契约：新建项目必须先调用 POST /api/projects，由后端返回 projectId。
      const createdProject = await createProject(title);
      setProjectTitleInput("");
      setProjectActionMessage(`项目已创建：${createdProject.projectId}`);
      await refreshProjectList("");
      switchProject(createdProject.projectId);
    } catch (error) {
      const message = error instanceof Error ? error.message : "无法创建项目";
      setProjectActionMessage(message);
    } finally {
      setIsCreatingProject(false);
    }
  }

  async function handleSearchProjects() {
    try {
      await refreshProjectList(projectKeyword);
      setProjectActionMessage("");
    } catch (error) {
      const message = error instanceof Error ? error.message : "无法搜索项目";
      setProjectActionMessage(message);
    }
  }

  async function runStoryAnalysis(targetProjectId: string) {
    const result = await analyzeStoryAssets(targetProjectId);
    const [entities, events] = await Promise.all([
      getStoryEntities(targetProjectId),
      getStoryEvents(targetProjectId)
    ]);

    setStoryEntities(entities);
    setStoryEvents(events);
    setStoryAssetsMessage("");
    setStoryEventsMessage("");
    setAnalysisResult(result);
    setAnalysisStatus(result.aiSuccess ? "success" : "warning");
    setAnalysisMessage(
      `${result.message}，已同步 ${result.entityCount} 个实体和 ${result.eventCount} 个事件。`
    );

    try {
      const scenes = await getProjectOutline(targetProjectId);
      if (scenes.length > 0) {
        setOutlineScenes(scenes);
        setOutlineSourceMode("real");
        setOutlineMessage("已读取真实场景大纲。");
      }
    } catch {
      setOutlineMessage("故事资产已生成，场景大纲稍后会自动加载。");
    }

    await loadProjectDetail(targetProjectId);
    await refreshProjectList();
    return result;
  }

  async function handleSubmitSourceText() {
    const content = sourceTextInput.trim();
    if (connectionMode !== "connected" || !content || isSubmittingSource || isAnalyzing) {
      return;
    }

    setIsSubmittingSource(true);
    setProjectActionMessage("");
    setSourceSubmitMessage("");
    setAnalysisResult(null);
    setAnalysisMessage("");
    setAnalysisStatus("");

    try {
      // 对齐开发契约：小说正文提交到 POST /api/projects/{projectId}/source。
      const nextChapters = await submitProjectSource(project.projectId, content);
      setChapters(nextChapters);
      setOutlineScenes(mockOutlineScenes);
      setOutlineSourceMode("mock");
      setOutlineMessage("正文已更新，真实场景大纲生成后会自动替换当前 mock 大纲。");
      setSceneDetail(mockSceneMap[mockOutlineScenes[0]?.sceneId] ?? null);
      setSceneDetailSourceMode("mock");
      setSceneDetailMessage("正文已更新，真实 Scene 详情生成后会自动替换当前 mock 内容。");
      setValidationReportData(mockValidationReport);
      setValidationSourceMode("mock");
      setValidationMessage("正文已更新，真实校验结果生成后会自动替换当前 mock 报告。");
      setYamlPreviewContent(
        buildYamlPreview(mockSceneMap[mockOutlineScenes[0]?.sceneId] ?? null, project)
      );
      setYamlSourceMode("mock");
      setYamlPreviewMessage("正文已更新，真实 YAML 导出就绪后会替换当前 mock 预览。");
      setProgressStreamMessage("");
      setProgressStreamPhase("");
      setProgressStreamValue(null);
      setProgressSourceMode("static");
      setStoryEntities([]);
      setStoryEvents([]);
      setSourceTextInput("");
      setChapterSummaryMessage("");
      setSourceSubmitMessage(
        `小说已提交到当前项目，并切分为 ${nextChapters.length} 个章节，正在自动执行故事分析。`
      );

      setIsAnalyzing(true);
      try {
        await runStoryAnalysis(project.projectId);
        setSourceSubmitMessage(
          `小说已提交到当前项目，并切分为 ${nextChapters.length} 个章节，故事分析已完成。`
        );
      } catch (analysisError) {
        const message = analysisError instanceof Error ? analysisError.message : "自动故事分析失败";
        setAnalysisStatus("error");
        setAnalysisMessage(message);
        setSourceSubmitMessage(
          `小说已提交到当前项目，并切分为 ${nextChapters.length} 个章节，但自动分析失败，可点击“执行分析”重试。`
        );
        await loadProjectDetail(project.projectId);
        await refreshProjectList();
      } finally {
        setIsAnalyzing(false);
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : "无法提交小说正文";
      setSourceSubmitMessage(message);
    } finally {
      setIsSubmittingSource(false);
    }
  }

  async function handleSummarizeChapters() {
    if (connectionMode !== "connected" || chapters.length === 0 || isSummarizingChapters) {
      return;
    }

    setIsSummarizingChapters(true);
    setChapterSummaryMessage("");

    try {
      const summarizedChapters = await summarizeProjectChapters(project.projectId);
      setChapters(summarizedChapters);
      const summarizedCount = summarizedChapters.filter((chapter) => chapter.summary?.trim()).length;
      setChapterSummaryMessage(`章节摘要已生成，已更新 ${summarizedCount} 个章节摘要。`);
      await loadProjectDetail(project.projectId);
      await refreshProjectList();
    } catch (error) {
      const message = error instanceof Error ? error.message : "无法生成章节摘要";
      setChapterSummaryMessage(message);
    } finally {
      setIsSummarizingChapters(false);
    }
  }

  async function handleAnalyzeStoryAssets() {
    if (connectionMode !== "connected" || isAnalyzing) {
      return;
    }

    setIsAnalyzing(true);
    setAnalysisResult(null);
    setAnalysisMessage("");
    setAnalysisStatus("");

    try {
      await runStoryAnalysis(project.projectId);
    } catch (error) {
      const message = error instanceof Error ? error.message : "无法执行故事资产分析";
      setAnalysisStatus("error");
      setAnalysisMessage(message);
    } finally {
      setIsAnalyzing(false);
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
    setSceneDetailMessage("");

    try {
      const detail = await regenerateProjectScene(project.projectId, selectedSceneId);
      setSceneDetail(detail);
      setSceneDetailSourceMode("real");
      setSceneDetailMessage("真实 Scene 已重新生成，详情和 YAML 预览已刷新。");
      await loadProjectDetail(project.projectId);
      await refreshProjectList();
    } catch (error) {
      const message = error instanceof Error ? error.message : "无法重新生成当前 Scene";
      setSceneDetailMessage(message);
    } finally {
      setIsRegeneratingScene(false);
    }
  }

  async function handleValidateProject() {
    if (connectionMode !== "connected" || outlineSourceMode !== "real" || isValidatingProject) {
      return;
    }

    setIsValidatingProject(true);
    setValidationMessage("");

    try {
      const report = await validateProjectScenes(project.projectId);
      setValidationReportData(report);
      setValidationSourceMode("real");
      setValidationMessage("已读取真实校验结果，当前场景告警已按后端报告刷新。");
      await loadProjectDetail(project.projectId);
      await refreshProjectList();
    } catch (error) {
      const message = error instanceof Error ? error.message : "无法执行项目校验";
      setValidationReportData(mockValidationReport);
      setValidationSourceMode("mock");
      setValidationMessage(message);
    } finally {
      setIsValidatingProject(false);
    }
  }

  async function handleExportYaml() {
    if (connectionMode !== "connected" || isExportingYaml) {
      return;
    }

    setIsExportingYaml(true);
    setYamlPreviewMessage("");

    try {
      const yamlContent = await exportProjectYaml(project.projectId);
      setYamlPreviewContent(yamlContent);
      setYamlSourceMode("real");
      setYamlPreviewMessage("已导出真实 YAML，并同步刷新当前预览。");
      downloadTextFile(`${project.projectId}.yaml`, yamlContent);
      await loadProjectDetail(project.projectId);
      await refreshProjectList();
    } catch (error) {
      const message = error instanceof Error ? error.message : "无法导出项目 YAML";
      setYamlPreviewContent(buildYamlPreview(sceneDetail, project));
      setYamlSourceMode("mock");
      setYamlPreviewMessage(message);
    } finally {
      setIsExportingYaml(false);
    }
  }

  useEffect(() => {
    let cancelled = false;

    async function bootstrapProject() {
      setAnalysisResult(null);
      setAnalysisMessage("");
      setAnalysisStatus("");

      try {
        const projects = await listProjects();

        if (cancelled) {
          return;
        }

        setProjectList(projects);

        const targetProjectId = isContractProjectId(projectId)
          ? projectId
          : projects[0]?.projectId ?? "";

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
        if (cancelled) {
          return;
        }

        const message = error instanceof Error ? error.message : "无法连接项目接口";

        setErrorMessage(message);

        if (appConfig.enableMockFallback) {
          setProject(mockProject);
          setChapters([]);
          setStoryEntities([]);
          setStoryEvents([]);
          setValidationReportData(mockValidationReport);
          setValidationSourceMode("mock");
          setValidationMessage("");
          setYamlPreviewContent(
            buildYamlPreview(mockSceneMap[mockOutlineScenes[0]?.sceneId] ?? null, mockProject)
          );
          setYamlSourceMode("mock");
          setYamlPreviewMessage("");
          setProgressStreamMessage("");
          setProgressStreamPhase("");
          setProgressStreamValue(null);
          setProgressSourceMode("static");
          setAnalysisResult(null);
          setAnalysisMessage("");
          setAnalysisStatus("");
          setConnectionMode("mock-only");
          return;
        }

        setChapters([]);
        setStoryEntities([]);
        setStoryEvents([]);
        setValidationReportData(mockValidationReport);
        setValidationSourceMode("mock");
        setValidationMessage("");
        setYamlPreviewContent(
          buildYamlPreview(mockSceneMap[mockOutlineScenes[0]?.sceneId] ?? null, mockProject)
        );
        setYamlSourceMode("mock");
        setYamlPreviewMessage("");
        setProgressStreamMessage("");
        setProgressStreamPhase("");
        setProgressStreamValue(null);
        setProgressSourceMode("static");
        setAnalysisResult(null);
        setAnalysisMessage("");
        setAnalysisStatus("");
        setConnectionMode("error");
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

        if (cancelled) {
          return;
        }

        setStoryEntities(entities);
        setStoryAssetsMessage("");
      } catch (error) {
        if (cancelled) {
          return;
        }

        const message = error instanceof Error ? error.message : "无法加载故事实体";
        setStoryEntities([]);
        setStoryAssetsMessage(message);
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

        if (cancelled) {
          return;
        }

        setStoryEvents(events);
        setStoryEventsMessage("");
      } catch (error) {
        if (cancelled) {
          return;
        }

        const message = error instanceof Error ? error.message : "无法加载故事事件";
        setStoryEvents([]);
        setStoryEventsMessage(message);
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
      setOutlineMessage(
        connectionMode === "connected"
          ? "请先执行故事中间资产分析，之后会自动加载真实场景大纲。"
          : ""
      );
      return;
    }

    let cancelled = false;

    async function loadProjectOutline() {
      try {
        const scenes = await getProjectOutline(project.projectId);

        if (cancelled) {
          return;
        }

        if (scenes.length === 0) {
          setOutlineScenes(mockOutlineScenes);
          setOutlineSourceMode("mock");
          setOutlineMessage("真实场景大纲暂为空，当前继续使用 mock 大纲。");
          return;
        }

        setOutlineScenes(scenes);
        setOutlineSourceMode("real");
        setOutlineMessage("已读取真实场景大纲。");
      } catch (error) {
        if (cancelled) {
          return;
        }

        const message =
          error instanceof Error ? error.message : "无法加载真实场景大纲，当前继续使用 mock。";
        setOutlineScenes(mockOutlineScenes);
        setOutlineSourceMode("mock");
        setOutlineMessage(message);
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

    const exists = outlineScenes.some((scene) => scene.sceneId === selectedSceneId);

    if (!exists) {
      setSelectedSceneId(outlineScenes[0].sceneId);
    }
  }, [outlineScenes, selectedSceneId]);

  useEffect(() => {
    const mockScene = mockSceneMap[selectedSceneId] ?? null;

    if (!selectedSceneId) {
      setSceneDetail(null);
      setSceneDetailSourceMode("empty");
      setSceneDetailMessage("");
      return;
    }

    if (connectionMode !== "connected" || !canLoadGeneratedScenes) {
      setSceneDetail(mockScene);
      setSceneDetailSourceMode(mockScene ? "mock" : "empty");
      setSceneDetailMessage(
        connectionMode === "connected"
          ? "请先执行故事中间资产分析，之后会自动加载真实 Scene 详情。"
          : ""
      );
      return;
    }

    let cancelled = false;

    async function loadSceneDetail() {
      try {
        const detail = await getProjectScene(project.projectId, selectedSceneId);

        if (cancelled) {
          return;
        }

        setSceneDetail(detail);
        setSceneDetailSourceMode("real");
        setSceneDetailMessage("已读取真实 Scene 详情，YAML 预览已切换到真实场景内容。");
      } catch (error) {
        if (cancelled) {
          return;
        }

        const message =
          error instanceof Error ? error.message : "无法加载真实 Scene 详情，当前继续使用 mock。";

        if (mockScene) {
          setSceneDetail(mockScene);
          setSceneDetailSourceMode("mock");
          setSceneDetailMessage(message);
          return;
        }

        setSceneDetail(null);
        setSceneDetailSourceMode("empty");
        setSceneDetailMessage("真实 Scene 详情尚未就绪，且当前没有可回退的 mock 场景。");
      }
    }

    void loadSceneDetail();

    return () => {
      cancelled = true;
    };
  }, [connectionMode, project.projectId, selectedSceneId, canLoadGeneratedScenes]);

  useEffect(() => {
    if (yamlSourceMode === "real") {
      return;
    }

    setYamlPreviewContent(buildYamlPreview(sceneDetail, project));
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

        if (!parsed || parsed.data.projectId !== project.projectId) {
          return;
        }

        applyProgressEvent(parsed);
      } catch {
        // Ignore malformed stream payloads and keep static fallback.
      }
    }

    function handleStreamError() {
      setProgressSourceMode("static");
    }

    eventSource.onmessage = handleStreamMessage;
    eventSource.onerror = handleStreamError;
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

  const connectionLabel =
    connectionMode === "connected"
      ? "真实项目"
      : connectionMode === "mock-only"
        ? "Mock 回退"
        : "连接失败";
  const displayProgress =
    progressSourceMode === "real" && progressStreamValue != null ? progressStreamValue : project.progress;
  const activePhaseLabel =
    phaseKeyToLabel[progressSourceMode === "real" && progressStreamPhase ? progressStreamPhase : project.currentPhase] ??
    "Scene 生成";
  const analysisModeLabel =
    analysisResult?.generationMode == null
      ? ""
      : analysisModeLabels[analysisResult.generationMode] ?? analysisResult.generationMode;
  const projectCompleted = project.status === "COMPLETED";
  const selectedSceneUsesFallback = sceneUsesFallback(sceneDetail);
  const completionMessage = projectCompleted
    ? yamlSourceMode === "real"
      ? "项目已完成，当前 YAML 为最新真实导出，可直接用于演示或评审。"
      : "项目已完成，建议执行一次 YAML 导出，刷新当前预览并确认最终交付内容。"
    : "";
  const sceneFallbackMessage = selectedSceneUsesFallback
    ? "当前 Scene 含规则兜底内容，建议在 AI 可用后重新生成，避免直接作为最终稿。"
    : "";
  const yamlFallbackMessage =
    yamlSourceMode === "real" && selectedSceneUsesFallback
      ? "当前 YAML 已走真实导出链路，但包含规则兜底生成的 Scene 内容。"
      : "";
  const validationFallbackMessage =
    validationSourceMode === "real" && selectedSceneUsesFallback
      ? "当前校验结果已来自真实接口，但部分告警由规则兜底生成的 Scene 引起。"
      : "";
  const busyProjectOperationLabel = isSubmittingSource
    ? isAnalyzing
      ? "提交正文并自动分析"
      : "提交正文"
    : isSummarizingChapters
      ? "生成章节摘要"
      : isAnalyzing
        ? "执行故事分析"
        : isRegeneratingScene
          ? "重新生成 Scene"
          : isValidatingProject
            ? "执行项目校验"
            : isExportingYaml
              ? "导出 YAML"
              : "";
  const busyProjectOperationMessage = isProjectOperationBusy
    ? `当前项目正在${busyProjectOperationLabel}，其他写操作已暂时禁用。`
    : "";

  return (
    <div className="app-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">Novel2Script</p>
          <h1>AI 小说转剧本工作台</h1>
        </div>
        <button
          className="ghost-button"
          type="button"
          disabled={isProjectOperationBusy}
          onClick={() => void refreshProjectList(projectKeyword)}
        >
          刷新项目
        </button>
      </header>

      <main className="workspace-grid">
        {/* 对齐开发契约：用户从项目列表选择真实 projectId，不再手动查询数据库或拼 URL。 */}
        <section className="panel project-entry-panel">
          <div className="panel-header">
            <h2>项目入口</h2>
            <span>{projectList.length} projects</span>
          </div>
          {busyProjectOperationMessage ? (
            <div className="notice-banner notice-banner-warning">{busyProjectOperationMessage}</div>
          ) : null}

          <div className="form-grid">
            <label className="field-block">
              <span>新建项目</span>
              <div className="inline-form">
                <input
                  value={projectTitleInput}
                  onChange={(event) => setProjectTitleInput(event.target.value)}
                  placeholder="项目标题"
                />
                <button
                  className="ghost-button"
                  type="button"
                  disabled={isCreatingProject || isProjectOperationBusy || !projectTitleInput.trim()}
                  onClick={() => void handleCreateProject()}
                >
                  {isCreatingProject ? "创建中..." : "创建"}
                </button>
              </div>
            </label>

            <label className="field-block">
              <span>搜索项目</span>
              <div className="inline-form">
                <input
                  value={projectKeyword}
                  onChange={(event) => setProjectKeyword(event.target.value)}
                  placeholder="标题或 projectId"
                />
                <button
                  className="ghost-button"
                  type="button"
                  disabled={isProjectOperationBusy}
                  onClick={() => void handleSearchProjects()}
                >
                  搜索
                </button>
              </div>
            </label>
          </div>

          {projectActionMessage ? <div className="notice-banner">{projectActionMessage}</div> : null}

          <div className="project-list">
            {projectList.length === 0 ? (
              <div className="empty-state empty-state-compact">暂无真实项目。</div>
            ) : (
              projectList.map((item) => (
                <button
                  key={item.projectId}
                  className={
                    item.projectId === project.projectId
                      ? "project-list-item project-list-item-active"
                      : "project-list-item"
                  }
                  type="button"
                  disabled={isProjectOperationBusy}
                  onClick={() => switchProject(item.projectId)}
                >
                  <strong>{item.title}</strong>
                  <span>{item.projectId}</span>
                  <small>{item.status}</small>
                </button>
              ))
            )}
          </div>
        </section>

        <section className="panel project-panel">
          <div className="panel-header">
            <h2>项目概览</h2>
            <div className="panel-header-actions">
              <span
                className={
                  connectionMode === "connected" ? "status-pill" : "status-pill status-pill-warn"
                }
              >
                {connectionLabel}
              </span>
              <button
                className="ghost-button"
                type="button"
                disabled={connectionMode !== "connected" || isProjectOperationBusy}
                onClick={() => void handleAnalyzeStoryAssets()}
              >
                {isAnalyzing ? "分析中..." : "执行分析"}
              </button>
            </div>
          </div>
          {errorMessage ? <div className="notice-banner">{errorMessage}</div> : null}
          {analysisMessage ? (
            <div
              className={
                analysisStatus === "success"
                  ? "notice-banner notice-banner-success"
                  : analysisStatus === "warning"
                    ? "notice-banner notice-banner-warning"
                    : "notice-banner"
              }
            >
              <div className="analysis-banner-content">
                <span>{analysisMessage}</span>
                {analysisResult ? (
                  <div className="analysis-pill-row">
                    <span className="inline-pill">{analysisModeLabel}</span>
                    <span
                      className={
                        analysisResult.aiSuccess
                          ? "status-pill status-pill-success-strong"
                          : "status-pill status-pill-warn"
                      }
                    >
                      {analysisResult.aiSuccess ? "AI 成功" : "AI 失败"}
                    </span>
                    {analysisResult.fallbackUsed ? (
                      <span className="status-pill status-pill-warn">已切换兜底</span>
                    ) : null}
                  </div>
                ) : null}
              </div>
            </div>
          ) : null}
          {completionMessage ? (
            <div className="notice-banner notice-banner-success">{completionMessage}</div>
          ) : null}
          <div className="project-meta">
            <div>
              <span>项目 ID</span>
              <strong>{project.projectId}</strong>
            </div>
            <div>
              <span>当前阶段</span>
              <strong>{project.currentPhase}</strong>
            </div>
            <div>
              <span>目标交付</span>
              <strong>Scene 级 YAML</strong>
            </div>
            <div>
              <span>场景数量</span>
              <strong>{outlineScenes.length}</strong>
            </div>
            <div>
              <span>项目标题</span>
              <strong>{project.title}</strong>
            </div>
          </div>
        </section>

        <section className="panel progress-panel">
          <div className="panel-header">
            <h2>流程阶段</h2>
            <div className="panel-header-actions">
              <span>{displayProgress}%</span>
              <span className={progressSourceMode === "real" ? "status-pill" : "status-pill status-pill-warn"}>
                {progressSourceMode === "real" ? "实时进度" : "静态阶段"}
              </span>
            </div>
          </div>
          <div className="progress-bar">
            <span style={{ width: `${displayProgress}%` }} />
          </div>
          {progressStreamMessage ? <div className="notice-banner">{progressStreamMessage}</div> : null}
          <ol className="phase-list">
            {phaseLabels.map((label) => {
              const isActive = label === activePhaseLabel;
              return (
                <li key={label} className={isActive ? "phase phase-active" : "phase"}>
                  <span className="phase-index">
                    {String(phaseLabels.indexOf(label) + 1).padStart(2, "0")}
                  </span>
                  <span>{label}</span>
                </li>
              );
            })}
          </ol>
        </section>

        <section className="panel chapter-panel">
          <div className="panel-header">
            <h2>章节原文</h2>
            <div className="panel-header-actions">
              <span>{connectionMode === "connected" ? `${chapters.length} chapters` : "等待真实接口"}</span>
              <button
                className="ghost-button"
                type="button"
                disabled={connectionMode !== "connected" || chapters.length === 0 || isProjectOperationBusy}
                onClick={() => void handleSummarizeChapters()}
              >
                {isSummarizingChapters ? "生成中..." : "生成摘要"}
              </button>
            </div>
          </div>
          {chapterSummaryMessage ? <div className="notice-banner">{chapterSummaryMessage}</div> : null}
          {connectionMode === "connected" ? (
            chapters.length > 0 ? (
              <div className="chapter-list">
                {chapters.map((chapter) => (
                  <article key={chapter.id} className="chapter-card">
                    <div className="chapter-card-top">
                      <strong>
                        第 {chapter.chapterNo} 章 {chapter.title}
                      </strong>
                      <span>#{chapter.id}</span>
                    </div>
                    <p>{chapter.previewText}</p>
                    {chapter.summary ? (
                      <div className="chapter-summary">
                        <span className="detail-label">章节摘要</span>
                        <p>{chapter.summary}</p>
                      </div>
                    ) : null}
                  </article>
                ))}
              </div>
            ) : (
              <div className="empty-state empty-state-compact">当前项目暂无章节数据。</div>
            )
          ) : (
            <div className="empty-state empty-state-compact">
              当前仍使用 mock 场景工作台。A 线章节接口接通后，这里会自动显示真实章节列表。
            </div>
          )}
        </section>

        {/* 对齐开发契约：小说正文提交后端 POST /source，由 A 线负责章节切分和入库。 */}
        <section className="panel source-submit-panel">
          <div className="panel-header">
            <h2>小说提交</h2>
            <span>{sourceTextInput.trim().length} chars</span>
          </div>
          {sourceSubmitMessage ? <div className="notice-banner">{sourceSubmitMessage}</div> : null}
          {connectionMode !== "connected" ? (
            <div className="notice-banner">请先在项目入口中新建或选择一个真实项目。</div>
          ) : null}
          <textarea
            className="source-textarea"
            value={sourceTextInput}
            onChange={(event) => setSourceTextInput(event.target.value)}
            disabled={connectionMode !== "connected" || isProjectOperationBusy}
            placeholder="粘贴小说正文"
          />
          <button
            className="primary-button"
            type="button"
            disabled={connectionMode !== "connected" || isProjectOperationBusy || !sourceTextInput.trim()}
            onClick={() => void handleSubmitSourceText()}
          >
            {isSubmittingSource ? (isAnalyzing ? "分析中..." : "提交中...") : "提交到当前项目"}
          </button>
        </section>

        <section className="panel asset-panel">
          <div className="panel-header">
            <h2>角色与地点</h2>
            <span>{connectionMode === "connected" ? `${storyEntities.length} assets` : "等待真实接口"}</span>
          </div>
          {connectionMode !== "connected" ? (
            <div className="empty-state empty-state-compact">
              当前仍使用 mock 场景工作台。A 线实体接口接通后，这里会显示真实角色与地点资产。
            </div>
          ) : storyAssetsMessage ? (
            <div className="empty-state empty-state-compact">{storyAssetsMessage}</div>
          ) : storyEntities.length === 0 ? (
            <div className="empty-state empty-state-compact">
              当前项目暂无实体资产，可在后端先执行故事中间资产分析。
            </div>
          ) : (
            <div className="asset-list">
              {storyEntities.map((entity) => (
                <article key={entity.entityId} className="asset-card">
                  <div className="asset-card-top">
                    <strong>{entity.canonicalName}</strong>
                    <span>{entity.entityId}</span>
                  </div>
                  <div className="pill-list">
                    <span className="inline-pill">{entity.entityType}</span>
                    {entity.aliases.map((alias) => (
                      <span key={`${entity.entityId}-${alias}`} className="inline-pill">
                        {alias}
                      </span>
                    ))}
                  </div>
                  <p>{entity.profile}</p>
                </article>
              ))}
            </div>
          )}
        </section>

        <section className="panel event-panel">
          <div className="panel-header">
            <h2>故事事件</h2>
            <span>{connectionMode === "connected" ? `${storyEvents.length} events` : "等待真实接口"}</span>
          </div>
          {connectionMode !== "connected" ? (
            <div className="empty-state empty-state-compact">
              当前仍使用 mock 场景工作台。A 线故事事件接口接通后，这里会显示真实事件时间线。
            </div>
          ) : storyEventsMessage ? (
            <div className="empty-state empty-state-compact">{storyEventsMessage}</div>
          ) : storyEvents.length === 0 ? (
            <div className="empty-state empty-state-compact">
              当前项目暂无故事事件，可在后端先执行故事中间资产分析。
            </div>
          ) : (
            <div className="event-list">
              {storyEvents.map((event) => (
                <article key={event.eventId} className="event-card">
                  <div className="event-card-top">
                    <strong>{event.title}</strong>
                    <span>{event.eventId}</span>
                  </div>
                  <div className="pill-list">
                    <span className="inline-pill">Chapter {event.chapterId}</span>
                    <span className="inline-pill">Order {event.eventOrder}</span>
                    {event.sourceRefs.map((ref) => (
                      <span key={`${event.eventId}-${ref}`} className="inline-pill">
                        {ref}
                      </span>
                    ))}
                  </div>
                  <p>{event.summary}</p>
                </article>
              ))}
            </div>
          )}
        </section>

        <section className="panel outline-panel">
          <div className="panel-header">
            <h2>场景大纲</h2>
            <span>{outlineSourceMode === "real" ? `${outlineScenes.length} scenes` : "Mock 回退"}</span>
          </div>
          {outlineMessage ? <div className="notice-banner">{outlineMessage}</div> : null}
          {outlineScenes.length === 0 ? (
            <div className="empty-state empty-state-compact">当前暂无可展示的场景大纲。</div>
          ) : (
            <div className="scene-list">
              {outlineScenes.map((scene) => (
                <button
                  key={scene.sceneId}
                  type="button"
                  className={
                    scene.sceneId === selectedSceneId ? "scene-card scene-card-active" : "scene-card"
                  }
                  onClick={() => setSelectedSceneId(scene.sceneId)}
                >
                  <div className="scene-card-top">
                    <strong>{scene.title}</strong>
                    <span>{scene.sceneId}</span>
                  </div>
                  <p>{scene.purpose.plot}</p>
                  <div className="scene-card-tags">
                    <span>{scene.slugline.intExt}</span>
                    <span>{scene.slugline.timeOfDay}</span>
                    <span>{scene.status}</span>
                  </div>
                </button>
              ))}
            </div>
          )}
        </section>

        <section className="panel scene-panel">
          <div className="panel-header">
            <h2>Scene 详情</h2>
            <div className="panel-header-actions">
              <span>{sceneDetail?.validationStatus ?? "未选中"}</span>
              <button
                className="ghost-button"
                type="button"
                disabled={
                  connectionMode !== "connected" ||
                  outlineSourceMode !== "real" ||
                  !selectedSceneId ||
                  isProjectOperationBusy
                }
                onClick={() => void handleRegenerateScene()}
              >
                {isRegeneratingScene ? "生成中..." : "重新生成"}
              </button>
            </div>
          </div>
          {sceneDetailMessage ? <div className="notice-banner">{sceneDetailMessage}</div> : null}
          {sceneFallbackMessage ? (
            <div className="notice-banner notice-banner-warning">{sceneFallbackMessage}</div>
          ) : null}
          {sceneDetail ? (
            <div className="scene-detail">
              <div className="detail-block">
                <span className="detail-label">动作</span>
                <ul className="detail-list">
                  {sceneDetail.action.map((line) => (
                    <li key={line}>{line}</li>
                  ))}
                </ul>
              </div>

              <div className="detail-block">
                <span className="detail-label">对白</span>
                <ul className="dialogue-list">
                  {sceneDetail.dialogue.map((item) => (
                    <li key={`${item.characterId}-${item.line}`}>
                      <strong>{item.characterId}</strong>
                      <span>{item.line}</span>
                    </li>
                  ))}
                </ul>
              </div>

              <div className="detail-block">
                <span className="detail-label">Source Refs</span>
                <div className="pill-list">
                  {sceneDetail.sourceRefs.map((ref) => (
                    <span key={ref} className="inline-pill">
                      {ref}
                    </span>
                  ))}
                </div>
              </div>
            </div>
          ) : (
            <div className="empty-state">
              {sceneDetailSourceMode === "empty"
                ? "真实场景大纲已接入，当前仍等待 scenes 详情接口返回该场景内容。"
                : "未找到当前场景详情。"}
            </div>
          )}
        </section>

        <section className="panel yaml-panel">
          <div className="panel-header">
            <h2>YAML 预览</h2>
            <div className="panel-header-actions">
              <span>{yamlSourceMode === "real" ? "真实导出" : "Mock 预览"}</span>
              <button
                className="ghost-button"
                type="button"
                disabled={connectionMode !== "connected" || isProjectOperationBusy}
                onClick={() => void handleExportYaml()}
              >
                {isExportingYaml ? "导出中..." : "导出 YAML"}
              </button>
            </div>
          </div>
          {yamlPreviewMessage ? <div className="notice-banner">{yamlPreviewMessage}</div> : null}
          {yamlFallbackMessage ? (
            <div className="notice-banner notice-banner-warning">{yamlFallbackMessage}</div>
          ) : null}
          <pre className="code-block">{yamlPreviewContent}</pre>
        </section>

        <section className="panel validation-panel">
          <div className="panel-header">
            <h2>校验报告</h2>
            <div className="panel-header-actions">
              <span>{validationSourceMode === "real" ? validationReportData.status : sceneDetail?.validationStatus ?? mockValidationReport.status}</span>
              <button
                className="ghost-button"
                type="button"
                disabled={connectionMode !== "connected" || outlineSourceMode !== "real" || isProjectOperationBusy}
                onClick={() => void handleValidateProject()}
              >
                {isValidatingProject ? "校验中..." : "执行校验"}
              </button>
            </div>
          </div>
          {validationMessage ? <div className="notice-banner">{validationMessage}</div> : null}
          {validationFallbackMessage ? (
            <div className="notice-banner notice-banner-warning">{validationFallbackMessage}</div>
          ) : null}
          <div className="validation-list">
            {selectedWarnings.length === 0 ? (
              <div className="validation-item validation-pass">当前场景无告警</div>
            ) : (
              selectedWarnings.map((item) => (
                <div key={`${item.sceneId}-${item.field}`} className="validation-item">
                  <strong>{item.field}</strong>
                  <p>{item.message}</p>
                </div>
              ))
            )}
          </div>
        </section>
      </main>
    </div>
  );
}

export default App;
