import type {
  ProgressStreamEvent,
  ProjectViewModel,
  SceneDetailViewModel,
  WrappedProgressStreamEvent
} from "../../api/types";

export const phaseLabels = [
  "项目创建",
  "文本处理",
  "实体抽取",
  "场景规划",
  "Scene 生成",
  "结构校验",
  "YAML 导出"
];

export const phaseKeyToLabel: Record<string, string> = {
  created: "项目创建",
  source_submitted: "文本处理",
  chaptered: "文本处理",
  summarizing: "文本处理",
  entity_extracting: "实体抽取",
  entity_ready: "实体抽取",
  outline_generating: "场景规划",
  outlined: "场景规划",
  scene_generating: "Scene 生成",
  validating: "结构校验",
  validated: "结构校验",
  exporting: "YAML 导出",
  completed: "YAML 导出",
  failed: "Scene 生成"
};

export const analysisModeLabels: Record<string, string> = {
  AI: "AI 抽取",
  FALLBACK: "规则兜底",
  INCREMENTAL_AI: "增量 AI",
  INCREMENTAL_FALLBACK: "增量兜底",
  INCREMENTAL_NONE: "无新增章节"
};

export const projectStatusLabels: Record<string, string> = {
  CREATED: "已创建",
  SOURCE_SUBMITTED: "已提交正文",
  CHAPTERED: "已切章",
  ENTITY_READY: "资产已就绪",
  OUTLINED: "已生成大纲",
  SCENE_GENERATING: "正在生成 Scene",
  COMPLETED: "已完成",
  FAILED: "处理失败"
};

export function resolveProjectId() {
  const searchParams = new URLSearchParams(window.location.search);
  return searchParams.get("projectId") || "";
}

export function isContractProjectId(value: string) {
  return value.startsWith("proj_");
}

export function buildYamlPreview(scene: SceneDetailViewModel | null, project: ProjectViewModel) {
  if (!scene) {
    return `schema_version: "1.0.0"
meta:
  project_id: "${project.projectId}"
  title: "${project.title}"
  workflow: "reader-outline-writer-validator"
scenes: []`;
  }

  const actionLines = scene.action.map((line) => `    - "${line}"`).join("\n");
  const dialogueLines = scene.dialogue
    .map((item) => `    - character_id: "${item.characterId}"\n      line: "${item.line}"`)
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

export function sceneUsesFallback(scene: SceneDetailViewModel | null) {
  return scene?.warnings.some((message) => message.includes("规则兜底")) ?? false;
}

export function downloadTextFile(filename: string, content: string) {
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

export function parseProgressStreamMessage(rawMessage: MessageEvent<string>) {
  const parsed = JSON.parse(rawMessage.data) as WrappedProgressStreamEvent | ProgressStreamEvent["data"];

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
