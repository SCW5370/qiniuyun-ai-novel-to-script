import { useEffect, useState } from "react";
import { getProject, getProjectChapters } from "./api/client";
import type {
  ChapterViewModel,
  ProjectViewModel,
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

const mockProject = {
  projectId: projectData.projectId,
  backendProjectId: Number(projectData.projectId.replace(/\D/g, "")) || 1,
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

function buildYamlPreview(sceneId: string, project: ProjectViewModel) {
  const scene = sceneMap[sceneId];

  if (!scene) {
    return "scenes: []";
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
  const [selectedSceneId, setSelectedSceneId] = useState(outlineData[0]?.sceneId ?? "");
  const [projectId] = useState(resolveProjectId);
  const [project, setProject] = useState<ProjectViewModel>(mockProject);
  const [chapters, setChapters] = useState<ChapterViewModel[]>([]);
  const [connectionMode, setConnectionMode] = useState<WorkbenchConnectionMode>("mock-only");
  const [errorMessage, setErrorMessage] = useState("");
  const selectedScene = sceneMap[selectedSceneId];
  const selectedWarnings = validationReport.items.filter(
    (item) => item.sceneId === selectedSceneId
  );

  useEffect(() => {
    let cancelled = false;

    async function bootstrapProject() {
      try {
        const [nextProject, nextChapters] = await Promise.all([
          getProject(projectId),
          getProjectChapters(projectId)
        ]);

        if (cancelled) {
          return;
        }

        setProject(nextProject);
        setChapters(nextChapters);
        setConnectionMode("connected");
        setErrorMessage("");
      } catch (error) {
        if (cancelled) {
          return;
        }

        const message = error instanceof Error ? error.message : "无法连接项目接口";

        setErrorMessage(message);

        if (appConfig.enableMockFallback) {
          setProject(mockProject);
          setChapters([]);
          setConnectionMode("mock-only");
          return;
        }

        setChapters([]);
        setConnectionMode("error");
      }
    }

    void bootstrapProject();

    return () => {
      cancelled = true;
    };
  }, [projectId]);

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
          onClick={() => setSelectedSceneId(outlineData[0]?.sceneId ?? "")}
        >
          载入示例项目
        </button>
      </header>

      <main className="workspace-grid">
        <section className="panel project-panel">
          <div className="panel-header">
            <h2>项目概览</h2>
            <span className={connectionMode === "connected" ? "status-pill" : "status-pill status-pill-warn"}>
              {connectionLabel}
            </span>
          </div>
          {errorMessage ? <div className="notice-banner">{errorMessage}</div> : null}
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
              <strong>{outlineData.length}</strong>
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

        <section className="panel outline-panel">
          <div className="panel-header">
            <h2>场景大纲</h2>
            <span>{outlineData.length} scenes</span>
          </div>
          <div className="scene-list">
            {outlineData.map((scene) => (
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
            <div className="empty-state">未找到当前场景详情。</div>
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
