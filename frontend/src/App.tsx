const phaseLabels = [
  "项目创建",
  "文本处理",
  "实体抽取",
  "场景规划",
  "Scene 生成",
  "结构校验",
  "YAML 导出"
];

function App() {
  return (
    <div className="app-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">Novel2Script</p>
          <h1>AI 小说转剧本工作台</h1>
        </div>
        <button className="ghost-button" type="button">
          载入示例项目
        </button>
      </header>

      <main className="workspace-grid">
        <section className="panel project-panel">
          <div className="panel-header">
            <h2>项目概览</h2>
            <span className="status-pill">等待联调</span>
          </div>
          <div className="project-meta">
            <div>
              <span>项目 ID</span>
              <strong>proj_20260606_001</strong>
            </div>
            <div>
              <span>当前阶段</span>
              <strong>前端骨架已就绪</strong>
            </div>
            <div>
              <span>目标交付</span>
              <strong>Scene 级 YAML</strong>
            </div>
          </div>
        </section>

        <section className="panel progress-panel">
          <div className="panel-header">
            <h2>流程阶段</h2>
            <span>0%</span>
          </div>
          <ol className="phase-list">
            {phaseLabels.map((label, index) => (
              <li key={label} className={index === 0 ? "phase phase-active" : "phase"}>
                <span className="phase-index">{String(index + 1).padStart(2, "0")}</span>
                <span>{label}</span>
              </li>
            ))}
          </ol>
        </section>

        <section className="panel outline-panel">
          <div className="panel-header">
            <h2>场景大纲</h2>
            <span>待接入 mock</span>
          </div>
          <div className="empty-state">
            当前分支仅初始化布局，下一步接入场景列表与详情视图。
          </div>
        </section>

        <section className="panel scene-panel">
          <div className="panel-header">
            <h2>Scene 详情</h2>
            <span>未选中</span>
          </div>
          <div className="empty-state">
            这里将展示 action、dialogue、source refs、校验状态和重生成入口。
          </div>
        </section>

        <section className="panel yaml-panel">
          <div className="panel-header">
            <h2>YAML 预览</h2>
            <button className="ghost-button" type="button">
              导出 YAML
            </button>
          </div>
          <pre className="code-block">
{`schema_version: "1.0.0"
meta:
  project_id: "proj_20260606_001"
  workflow: "reader-outline-writer-validator"
scenes: []`}
          </pre>
        </section>
      </main>
    </div>
  );
}

export default App;
