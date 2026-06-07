import { ArrowDown, Clapperboard, FileCheck2, Network, RadioTower } from "lucide-react";
import { motion } from "motion/react";

export function ProductHome() {
  return (
    <section className="product-home" aria-label="Novel2Script 产品首页">
      <div className="product-home-bg" />
      <nav className="product-home-nav" aria-label="首页导航">
        <a className="home-brand" href="#home">
          <Clapperboard size={20} />
          <span>Novel2Script</span>
        </a>
        <div className="home-nav-links">
          <a href="#workbench">工作台</a>
          <a href="#delivery">交付</a>
        </div>
      </nav>

      <motion.div
        className="home-hero-copy"
        initial={{ opacity: 0, y: 18 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.46, ease: [0.22, 1, 0.36, 1] }}
      >
        <span className="home-signal">AI screenplay production system</span>
        <h1>Novel2Script</h1>
        <p>把中长篇小说送进制作流水线，生成可检查、可重写、可导出的 Scene 级剧本资产。</p>
        <div className="home-hero-actions">
          <a className="home-primary-action" href="#workbench">
            进入工作台
            <ArrowDown size={18} />
          </a>
          <a className="home-secondary-action" href="#delivery">查看交付能力</a>
        </div>
      </motion.div>

      <div className="home-proof-row" aria-label="核心能力">
        <div>
          <Network size={18} />
          <span>故事资产抽取</span>
        </div>
        <div>
          <RadioTower size={18} />
          <span>Scene 实时生成</span>
        </div>
        <div>
          <FileCheck2 size={18} />
          <span>YAML 级交付</span>
        </div>
      </div>

      <a className="home-scroll-cue" href="#workbench" aria-label="下滑进入工作台">
        <span>Director Command Room</span>
        <ArrowDown size={18} />
      </a>
    </section>
  );
}
