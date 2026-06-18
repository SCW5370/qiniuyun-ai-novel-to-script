import {
  AlertTriangle,
  Boxes,
  CheckCircle2,
  Clapperboard,
  Copy,
  FileInput,
  FileUp,
  FolderPlus,
  GitBranch,
  Play,
  Radio,
  RefreshCcw,
  Search,
  Sparkles,
  Upload,
  Wand2
} from "lucide-react";
import { motion } from "motion/react";
import { useMemo } from "react";
import type { SceneDetailViewModel } from "../../api/types";
import { Badge } from "../../components/ui/Badge";
import { Button } from "../../components/ui/Button";
import { Panel } from "../../components/ui/Panel";
import { ProductionMap } from "../../components/production-map/ProductionMap";
import { phaseLabels } from "./domain";
import { useWorkbench } from "./useWorkbench";

function sourceTone(source: string) {
  if (source === "real" || source === "connected") return "green";
  if (source === "mock" || source === "mock-only") return "amber";
  return "neutral";
}

function sceneStatusTone(status: string | undefined) {
  if (status === "PASSED") return "green";
  if (status === "FAILED") return "red";
  if (status === "WARNING") return "amber";
  return "neutral";
}

function formatElapsed(elapsedMs: number) {
  const totalSeconds = Math.floor(elapsedMs / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, "0")}`;
}

function SceneContent({ scene }: { scene: SceneDetailViewModel | null }) {
  return (
    <div className="scene-detail-grid">
      <div>
        <strong>动作</strong>
        <ul>
          {(scene?.action ?? []).slice(0, 5).map((line, index) =>
            line ? <li key={`${scene?.sceneId}-action-${index}`}>{line}</li> : null
          )}
        </ul>
      </div>
      <div>
        <strong>对白</strong>
        <ul>
          {(scene?.dialogue ?? []).slice(0, 4).map((item, index) =>
            item.characterId || item.line ? (
              <li key={`${scene?.sceneId}-dialogue-${index}`}>
                {item.characterId ? <span>{item.characterId}</span> : null}
                {item.line}
              </li>
            ) : null
          )}
        </ul>
      </div>
    </div>
  );
}

export function DirectorCommandRoom() {
  const workbench = useWorkbench();
  const { actions, derived, messages, sources, state } = workbench;
  const selectedScene = state.sceneDetail;
  const selectedSceneTitle =
    selectedScene?.title ??
    state.outlineScenes.find((scene) => scene.sceneId === state.selectedSceneId)?.title ??
    "未选中 Scene";
  const analyzedChapterIds = useMemo(
    () => new Set(state.storyEvents.map((event) => event.chapterId)),
    [state.storyEvents]
  );
  const visibleMapChapters = useMemo(
    () => state.chapters.filter((chapter) => analyzedChapterIds.has(chapter.id)),
    [analyzedChapterIds, state.chapters]
  );
  const firstPendingScene = useMemo(
    () => state.outlineScenes
      .slice()
      .sort((left, right) => left.seqNo - right.seqNo)
      .find((scene) => !state.readySceneIds.has(scene.sceneId)),
    [state.outlineScenes, state.readySceneIds]
  );
  const liveProductionDetail =
    state.isSceneBuildActive && state.outlineScenes.length > 0
      ? `已完成 ${state.readySceneIds.size} / ${state.outlineScenes.length} 个 Scene${
          firstPendingScene ? `，${firstPendingScene.sceneId} 正在生成` : ""
        }`
      : "";

  return (
    <main className="director-room">
      <div className="cinematic-backdrop" />
      <motion.header
        className="command-bar"
        initial={{ opacity: 0, y: -12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.22 }}
      >
        <div className="brand-lockup">
          <div className="brand-sigil">
            <Clapperboard size={22} />
          </div>
          <div>
            <strong>Director Command Room</strong>
            <span>小说转剧本制作中控台</span>
          </div>
        </div>

        <div className="command-status">
          <Badge tone={sourceTone(state.connectionMode)}>{derived.connectionLabel}</Badge>
          <Badge tone={sourceTone(sources.progressSourceMode)}>{derived.activePhaseLabel}</Badge>
          <Badge tone={derived.deliveryStatusLabel === "Ready" ? "green" : "amber"}>
            {derived.deliveryStatusLabel}
          </Badge>
        </div>

        <div className="command-actions">
          <Button
            icon={<Wand2 size={16} />}
            loading={state.isAnalyzing}
            onClick={() => void actions.handleAnalyzeStoryAssets()}
            disabled={state.connectionMode !== "connected" || state.isProjectOperationBusy}
            variant="primary"
          >
            全量分析
          </Button>
          <Button
            icon={<GitBranch size={16} />}
            onClick={() => void actions.handleAnalyzeStoryAssetsIncremental()}
            disabled={state.connectionMode !== "connected" || state.isProjectOperationBusy}
          >
            增量分析
          </Button>
          <Button
            icon={<RefreshCcw size={16} />}
            onClick={() => void actions.refreshProjectList(state.projectKeyword)}
            disabled={state.isProjectOperationBusy}
          >
            刷新
          </Button>
        </div>
      </motion.header>

      <section className="stage-header">
        <div>
          <span className="stage-kicker">Project</span>
          <h1>{state.project.title}</h1>
          <p>{state.project.projectId}</p>
        </div>
        <div className="stage-scoreboard">
          <div>
            <span>进度</span>
            <strong>{derived.displayProgress}%</strong>
          </div>
          <div>
            <span>章节</span>
            <strong>{state.chapters.length}</strong>
          </div>
          <div>
            <span>资产</span>
            <strong>{state.storyEntities.length + state.storyEvents.length}</strong>
          </div>
          <div>
            <span>告警</span>
            <strong>{derived.totalValidationCount}</strong>
          </div>
        </div>
      </section>

      {state.workflowNotice || state.isProjectOperationBusy ? (
        <div
          className={
            state.workflowNotice
              ? `live-operation-strip live-operation-strip-${state.workflowNotice.tone}`
              : "live-operation-strip"
          }
        >
          <Radio size={16} />
          <div className="live-operation-copy">
            <strong>{state.workflowNotice?.title ?? "制作任务正在执行"}</strong>
            <span>
              {liveProductionDetail ||
                state.workflowNotice?.detail ||
                messages.progressStreamMessage ||
                "制作任务正在执行，写操作已暂时锁定。"}
            </span>
            {state.isWorkflowRunning ? (
              <>
                <div className="live-operation-track" role="progressbar" aria-label="任务进行中">
                  <span className="live-operation-track-fill" />
                </div>
                <small>
                  已用时 {formatElapsed(state.operationElapsedMs)} · AI 处理中，通常需 1–3 分钟，请稍候
                </small>
              </>
            ) : null}
            {state.workflowNotice?.jobId ? <small>Job {state.workflowNotice.jobId}</small> : null}
          </div>
          {state.workflowNotice?.retryAction ? (
            <Button
              icon={<RefreshCcw size={16} />}
              onClick={() => void actions.retryWorkflowNotice()}
              disabled={state.connectionMode !== "connected" || state.isProjectOperationBusy}
            >
              {derived.workflowNoticeRetryLabel}
            </Button>
          ) : null}
        </div>
      ) : null}

      <section className="command-grid">
        <aside className="left-console">
          <Panel
            title="Intake Console"
            meta={<Badge tone={sourceTone(state.connectionMode)}>{derived.projectStatusLabel}</Badge>}
          >
            <div className="field-row">
              <input
                value={state.projectTitleInput}
                onChange={(event) => actions.setProjectTitleInput(event.target.value)}
                placeholder="新项目标题"
              />
              <Button
                icon={<FolderPlus size={16} />}
                loading={state.isCreatingProject}
                onClick={() => void actions.handleCreateProject()}
                disabled={!state.projectTitleInput.trim() || state.isProjectOperationBusy}
              >
                创建
              </Button>
            </div>

            <div className="field-row">
              <input
                value={state.projectKeyword}
                onChange={(event) => actions.setProjectKeyword(event.target.value)}
                placeholder="搜索项目或 projectId"
              />
              <Button icon={<Search size={16} />} onClick={() => void actions.handleSearchProjects()}>
                搜索
              </Button>
            </div>

            {messages.projectActionMessage ? <p className="console-message">{messages.projectActionMessage}</p> : null}
            {messages.errorMessage ? <p className="console-message is-warning">{messages.errorMessage}</p> : null}

            <div className="project-switcher">
              {state.projectList.slice(0, 6).map((project) => (
                <button
                  key={project.projectId}
                  className={
                    project.projectId === state.project.projectId
                      ? "project-switch project-switch-active"
                      : "project-switch"
                  }
                  type="button"
                  disabled={state.isProjectOperationBusy}
                  onClick={() => actions.switchProject(project.projectId)}
                >
                  <strong>{project.title}</strong>
                  <span>{project.projectId}</span>
                </button>
              ))}
            </div>
          </Panel>

          <Panel title="Source Feed" meta={<Badge tone="cyan">{state.sourceTextInput.trim().length} chars</Badge>}>
            <textarea
              value={state.sourceTextInput}
              onChange={(event) => actions.setSourceTextInput(event.target.value)}
              disabled={state.connectionMode !== "connected" || state.isProjectOperationBusy}
              placeholder="粘贴小说正文，或选择 txt / md 文件导入制作流水线"
            />
            <div className="source-action-grid">
              <Button
                icon={<FileInput size={16} />}
                loading={state.isSubmittingSource && state.isAnalyzing}
                onClick={() => void actions.handleSubmitSourceText()}
                disabled={
                  state.connectionMode !== "connected" ||
                  state.isProjectOperationBusy ||
                  !state.sourceTextInput.trim()
                }
                variant="primary"
              >
                替换正文
              </Button>
              <Button
                icon={<GitBranch size={16} />}
                onClick={() => void actions.handleAppendSourceText()}
                disabled={
                  state.connectionMode !== "connected" ||
                  state.isProjectOperationBusy ||
                  !state.sourceTextInput.trim()
                }
              >
                追加文本
              </Button>
              <label className="file-pick-control">
                <Upload size={16} />
                <span>{state.sourceFileInput ? state.sourceFileInput.name : "选择文件"}</span>
                <input
                  type="file"
                  accept=".txt,.md,text/plain,text/markdown"
                  disabled={state.connectionMode !== "connected" || state.isProjectOperationBusy}
                  onChange={(event) => actions.setSourceFileInput(event.currentTarget.files?.[0] ?? null)}
                />
              </label>
              <Button
                icon={<FileUp size={16} />}
                onClick={() => void actions.handleUploadSourceFile()}
                disabled={state.connectionMode !== "connected" || state.isProjectOperationBusy || !state.sourceFileInput}
              >
                上传替换
              </Button>
              <Button
                icon={<GitBranch size={16} />}
                onClick={() => void actions.handleAppendSourceFile()}
                disabled={state.connectionMode !== "connected" || state.isProjectOperationBusy || !state.sourceFileInput}
              >
                追加文件
              </Button>
            </div>
            {messages.sourceSubmitMessage ? <p className="console-message">{messages.sourceSubmitMessage}</p> : null}
          </Panel>
        </aside>

        <section className="map-console">
          <Panel
            className="production-map-panel"
            title="Story Production Map"
            meta={
              <div className="panel-meta-row">
                <Badge tone={sourceTone(sources.outlineSourceMode)}>
                  {sources.outlineSourceMode === "real" ? "真实大纲" : "等待大纲"}
                </Badge>
                <Badge tone="cyan">{derived.sceneSelectionLabel}</Badge>
              </div>
            }
          >
            <ProductionMap
              chapters={visibleMapChapters}
              entities={state.storyEntities}
              events={state.storyEvents}
              isSceneBuildActive={state.isSceneBuildActive}
              revealedSceneIds={state.revealedSceneIds}
              scenes={state.outlineScenes}
              selectedSceneId={state.selectedSceneId}
              setSelectedSceneId={actions.setSelectedSceneId}
            />
            <div className="map-control-rail">
              <Button
                icon={<Sparkles size={16} />}
                onClick={() => void actions.handleGenerateIncrementalOutline()}
                disabled={state.connectionMode !== "connected" || state.isProjectOperationBusy}
                variant="primary"
              >
                生成增量场景
              </Button>
              <Button
                icon={<Play size={16} />}
                loading={state.isRegeneratingScene}
                onClick={() => void actions.handleRegenerateScene()}
                disabled={
                  state.connectionMode !== "connected" ||
                  sources.outlineSourceMode !== "real" ||
                  !state.selectedSceneId ||
                  state.isProjectOperationBusy
                }
              >
                重生成 Scene
              </Button>
            </div>
            {messages.outlineMessage ? <p className="console-message">{messages.outlineMessage}</p> : null}
          </Panel>
        </section>

        <aside className="right-console">
          <Panel
            title="Scene Inspector"
            meta={
              <div className="panel-meta-row">
                <Badge tone={sourceTone(sources.sceneDetailSourceMode)}>{sources.sceneDetailSourceMode}</Badge>
                <Badge tone={sceneStatusTone(selectedScene?.validationStatus)}>
                  {selectedScene?.validationStatus ?? "PENDING"}
                </Badge>
              </div>
            }
          >
            <div className="scene-title-block">
              <span>{state.selectedSceneId || "scene_pending"}</span>
              <h2>{selectedSceneTitle}</h2>
            </div>

            {messages.sceneDetailMessage ? <p className="console-message">{messages.sceneDetailMessage}</p> : null}
            {messages.sceneStreamMessage ? <p className="console-message">{messages.sceneStreamMessage}</p> : null}

            {state.sceneStreamContent ? (
              <div className="stream-box">
                <strong>AI 流式预览</strong>
                <pre>{state.sceneStreamContent}</pre>
              </div>
            ) : null}

            <SceneContent scene={selectedScene} />

            <div className="source-ref-strip">
              {(selectedScene?.sourceRefs ?? []).map((ref) => (
                <Badge key={ref} tone="neutral">
                  {ref}
                </Badge>
              ))}
            </div>
          </Panel>

          <Panel title="Assets Monitor" meta={<Badge tone="cyan">{derived.analysisStateLabel}</Badge>}>
            <div className="asset-metrics">
              <div>
                <Boxes size={18} />
                <span>角色</span>
                <strong>{derived.characterCount}</strong>
              </div>
              <div>
                <Boxes size={18} />
                <span>地点</span>
                <strong>{derived.locationCount}</strong>
              </div>
              <div>
                <GitBranch size={18} />
                <span>事件</span>
                <strong>{state.storyEvents.length}</strong>
              </div>
            </div>
            {messages.analysisMessage ? (
              <p className={messages.analysisStatus === "error" ? "console-message is-danger" : "console-message"}>
                {messages.analysisMessage}
              </p>
            ) : null}
            {messages.storyAssetsMessage ? <p className="console-message is-warning">{messages.storyAssetsMessage}</p> : null}
          </Panel>
        </aside>
      </section>

      <section id="delivery" className="delivery-rail">
        <div className="phase-track">
          {phaseLabels.map((label, index) => (
            <span
              key={label}
              className={
                index < derived.activePhaseIndex
                  ? "phase-node phase-node-done"
                  : index === derived.activePhaseIndex
                    ? "phase-node phase-node-active"
                    : "phase-node"
              }
            >
              {label}
            </span>
          ))}
        </div>

        <Panel
          className="delivery-panel"
          title="Delivery Rail"
          meta={<Badge tone={derived.deliveryStatusLabel === "Ready" ? "green" : "amber"}>{derived.deliveryStatusCaption}</Badge>}
        >
          <div className="delivery-grid">
            <div className="validation-stack">
              <div className="delivery-status-line">
                {derived.currentValidationStatus === "PASSED" ? (
                  <CheckCircle2 size={18} />
                ) : (
                  <AlertTriangle size={18} />
                )}
                <strong>{derived.currentValidationStatus}</strong>
                <span>{derived.totalValidationCount} 条风险</span>
              </div>
              <Button
                icon={<CheckCircle2 size={16} />}
                loading={state.isValidatingProject}
                onClick={() => void actions.handleValidateProject()}
                disabled={
                  state.connectionMode !== "connected" ||
                  sources.outlineSourceMode !== "real" ||
                  state.isProjectOperationBusy
                }
              >
                执行校验
              </Button>
              <div className="warning-list">
                {derived.selectedWarnings.length === 0 ? (
                  <span>当前 Scene 无告警。</span>
                ) : (
                  derived.selectedWarnings.slice(0, 3).map((item) => (
                    <span key={`${item.sceneId}-${item.field}`}>{item.message}</span>
                  ))
                )}
              </div>
            </div>

            <div className="yaml-console">
              <div className="yaml-toolbar">
                <Badge tone={sourceTone(sources.yamlSourceMode)}>
                  {sources.yamlSourceMode === "real" ? "真实 YAML" : "预览 YAML"}
                </Badge>
                <Button icon={<Copy size={16} />} onClick={actions.handleCopyYaml}>
                  复制 YAML
                </Button>
                <Button
                  icon={<FileUp size={16} />}
                  loading={state.isExportingYaml}
                  onClick={() => void actions.handleExportYaml()}
                  disabled={state.connectionMode !== "connected" || state.isProjectOperationBusy}
                  variant="primary"
                >
                  导出 YAML
                </Button>
              </div>
              {messages.yamlPreviewMessage ? <p className="console-message">{messages.yamlPreviewMessage}</p> : null}
              {messages.validationMessage ? <p className="console-message">{messages.validationMessage}</p> : null}
              <pre>{state.yamlPreviewContent}</pre>
            </div>
          </div>
        </Panel>
      </section>
    </main>
  );
}
