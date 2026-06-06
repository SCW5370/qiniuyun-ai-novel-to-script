import { useEffect, useState } from "react";
import {
  analyzeStoryAssets,
  createProject,
  getProject,
  getProjectChapters,
  getProjectOutline,
  getStoryEntities,
  getStoryEvents,
  listProjects,
  submitProjectSource
} from "./api/client";
import type {
  ChapterViewModel,
  OutlineSceneViewModel,
  ProjectViewModel,
  StoryEntityViewModel,
  StoryEventViewModel,
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

const sceneMap = Object.fromEntries(scenesData.map((scene) => [scene.sceneId, scene]));
const mockOutlineScenes = outlineData as OutlineSceneViewModel[];

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

function buildYamlPreview(sceneId: string, project: ProjectViewModel) {
  const scene = sceneMap[sceneId];

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
  const [storyEntities, setStoryEntities] = useState<StoryEntityViewModel[]>([]);
  const [storyEvents, setStoryEvents] = useState<StoryEventViewModel[]>([]);
  const [connectionMode, setConnectionMode] = useState<WorkbenchConnectionMode>("mock-only");
  const [errorMessage, setErrorMessage] = useState("");
  const [projectActionMessage, setProjectActionMessage] = useState("");
  const [outlineMessage, setOutlineMessage] = useState("");
  const [outlineSourceMode, setOutlineSourceMode] = useState<"real" | "mock">("mock");
  const [storyAssetsMessage, setStoryAssetsMessage] = useState("");
  const [storyEventsMessage, setStoryEventsMessage] = useState("");
  const [analysisMessage, setAnalysisMessage] = useState("");
  const [analysisStatus, setAnalysisStatus] = useState<"success" | "error" | "">("");
  const [isCreatingProject, setIsCreatingProject] = useState(false);
  const [isSubmittingSource, setIsSubmittingSource] = useState(false);
  const [isAnalyzing, setIsAnalyzing] = useState(false);
  const selectedScene = sceneMap[selectedSceneId];
  const selectedWarnings = validationReport.items.filter(
    (item) => item.sceneId === selectedSceneId
  );

  function switchProject(nextProjectId: string) {
    setProjectId(nextProjectId);
    window.history.replaceState(null, "", `?projectId=${encodeURIComponent(nextProjectId)}`);
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

  async function handleSubmitSourceText() {
    const content = sourceTextInput.trim();
    if (connectionMode !== "connected" || !content || isSubmittingSource) {
      return;
    }

    setIsSubmittingSource(true);
    setProjectActionMessage("");

    try {
      // 对齐开发契约：小说正文提交到 POST /api/projects/{projectId}/source。
      const nextChapters = await submitProjectSource(project.projectId, content);
      setChapters(nextChapters);
      setOutlineScenes(mockOutlineScenes);
      setOutlineSourceMode("mock");
      setOutlineMessage("正文已更新，真实场景大纲生成后会自动替换当前 mock 大纲。");
      setStoryEntities([]);
      setStoryEvents([]);
      setSourceTextInput("");
      setProjectActionMessage(`小说已提交并切分为 ${nextChapters.length} 个章节。`);
      await loadProjectDetail(project.projectId);
      await refreshProjectList();
    } catch (error) {
      const message = error instanceof Error ? error.message : "无法提交小说正文";
      setProjectActionMessage(message);
    } finally {
      setIsSubmittingSource(false);
    }
  }

  async function handleAnalyzeStoryAssets() {
    if (connectionMode !== "connected" || isAnalyzing) {
      return;
    }

    setIsAnalyzing(true);
    setAnalysisMessage("");
    setAnalysisStatus("");

    try {
      const result = await analyzeStoryAssets(project.projectId);
      const [entities, events] = await Promise.all([
        getStoryEntities(project.projectId),
        getStoryEvents(project.projectId)
      ]);

      setStoryEntities(entities);
      setStoryEvents(events);
      setStoryAssetsMessage("");
      setStoryEventsMessage("");
      setAnalysisStatus("success");
      setAnalysisMessage(`分析完成，已同步 ${result.entityCount} 个实体和 ${result.eventCount} 个事件。`);
      await loadProjectDetail(project.projectId);
      await refreshProjectList();
    } catch (error) {
      const message = error instanceof Error ? error.message : "无法执行故事资产分析";
      setAnalysisStatus("error");
      setAnalysisMessage(message);
    } finally {
      setIsAnalyzing(false);
    }
  }

  useEffect(() => {
    let cancelled = false;

    async function bootstrapProject() {
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
          setConnectionMode("mock-only");
          return;
        }

        setChapters([]);
        setStoryEntities([]);
        setStoryEvents([]);
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
    if (connectionMode !== "connected") {
      setOutlineScenes(mockOutlineScenes);
      setOutlineSourceMode("mock");
      setOutlineMessage("");
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
        setOutlineMessage("已读取真实场景大纲，Scene 详情和 YAML 仍等待 scenes 接口接入。");
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
  }, [connectionMode, project.projectId]);

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

  const connectionLabel =
    connectionMode === "connected"
      ? "真实项目"
      : connectionMode === "mock-only"
        ? "Mock 回退"
        : "连接失败";

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
                  disabled={isCreatingProject || !projectTitleInput.trim()}
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
                disabled={connectionMode !== "connected" || isAnalyzing}
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
                analysisStatus === "success" ? "notice-banner notice-banner-success" : "notice-banner"
              }
            >
              {analysisMessage}
            </div>
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
            <span>{project.progress}%</span>
          </div>
          <div className="progress-bar">
            <span style={{ width: `${project.progress}%` }} />
          </div>
          <ol className="phase-list">
            {phaseLabels.map((label) => {
              const isActive = label === "Scene 生成";
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
            <span>{connectionMode === "connected" ? `${chapters.length} chapters` : "等待真实接口"}</span>
          </div>
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
          <textarea
            className="source-textarea"
            value={sourceTextInput}
            onChange={(event) => setSourceTextInput(event.target.value)}
            placeholder="粘贴小说正文"
          />
          <button
            className="primary-button"
            type="button"
            disabled={connectionMode !== "connected" || isSubmittingSource || !sourceTextInput.trim()}
            onClick={() => void handleSubmitSourceText()}
          >
            {isSubmittingSource ? "提交中..." : "提交小说"}
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
            <span>{selectedScene?.validationStatus ?? "未选中"}</span>
          </div>
          {selectedScene ? (
            <div className="scene-detail">
              <div className="detail-block">
                <span className="detail-label">动作</span>
                <ul className="detail-list">
                  {selectedScene.action.map((line) => (
                    <li key={line}>{line}</li>
                  ))}
                </ul>
              </div>

              <div className="detail-block">
                <span className="detail-label">对白</span>
                <ul className="dialogue-list">
                  {selectedScene.dialogue.map((item) => (
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
                  {selectedScene.sourceRefs.map((ref) => (
                    <span key={ref} className="inline-pill">
                      {ref}
                    </span>
                  ))}
                </div>
              </div>
            </div>
          ) : (
            <div className="empty-state">
              {outlineSourceMode === "real"
                ? "真实场景大纲已接入，当前仍等待 scenes 详情接口替换 mock 明细。"
                : "未找到当前场景详情。"}
            </div>
          )}
        </section>

        <section className="panel yaml-panel">
          <div className="panel-header">
            <h2>YAML 预览</h2>
            <button className="ghost-button" type="button">
              导出 YAML
            </button>
          </div>
          <pre className="code-block">{buildYamlPreview(selectedSceneId, project)}</pre>
        </section>

        <section className="panel validation-panel">
          <div className="panel-header">
            <h2>校验报告</h2>
            <span>{validationReport.status}</span>
          </div>
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
