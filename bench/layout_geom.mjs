// 生产地图布局几何基准 —— 真跑真测,不靠猜。
// 直接复刻 ProductionMap.tsx 里的 averageSceneX 与 resolveLaneLayout,
// 在"短篇里几乎每个实体都关联到全部场景"的塌缩工况下,量化:
//   去碰撞前(朴素 averageSceneX) vs 去碰撞后(resolveLaneLayout) 的
//   节点最小间距、重叠节点对数、占用横向跨度。
//
// 复刻自 frontend/src/components/production-map/ProductionMap.tsx:82-122

// ---- 复刻：averageSceneX ----
function averageSceneX(sceneIds, nodesById, fallbackX) {
  const points = sceneIds
    .map((id) => nodesById.get(id)?.x)
    .filter((v) => typeof v === "number");
  if (points.length === 0) return fallbackX;
  return points.reduce((t, v) => t + v, 0) / points.length;
}

// ---- 复刻：resolveLaneLayout（分道去碰撞）----
function resolveLaneLayout(anchors, minGap, baseY, staggerY) {
  const count = anchors.length;
  const x = new Array(count).fill(0);
  const y = new Array(count).fill(baseY);
  const ordered = anchors
    .map((anchorX, index) => ({ index, anchorX }))
    .sort((l, r) => l.anchorX - r.anchorX);
  let lastX = Number.NEGATIVE_INFINITY;
  ordered.forEach((item, rank) => {
    const nextX = Math.max(item.anchorX, lastX + minGap);
    x[item.index] = nextX;
    y[item.index] = baseY + (rank % 2 === 0 ? 0 : staggerY);
    lastX = nextX;
  });
  if (count > 0) {
    const anchorMean = anchors.reduce((t, v) => t + v, 0) / count;
    const resolvedMean = x.reduce((t, v) => t + v, 0) / count;
    const shift = anchorMean - resolvedMean;
    for (let i = 0; i < count; i++) x[i] += shift;
  }
  return { x, y };
}

// ---- 几何度量 ----
const COLLISION = 0.3; // 节点视觉直径量级（半径~0.15）。二维距离 < 此值视为重叠

function metrics(nodes) {
  let minDist = Infinity;
  let overlapPairs = 0;
  let minX = Infinity, maxX = -Infinity;
  for (let i = 0; i < nodes.length; i++) {
    minX = Math.min(minX, nodes[i].x);
    maxX = Math.max(maxX, nodes[i].x);
    for (let j = i + 1; j < nodes.length; j++) {
      const dx = nodes[i].x - nodes[j].x;
      const dy = nodes[i].y - nodes[j].y;
      const d = Math.sqrt(dx * dx + dy * dy);
      minDist = Math.min(minDist, d);
      if (d < COLLISION) overlapPairs++;
    }
  }
  return { minDist, overlapPairs, span: maxX - minX, n: nodes.length, totalPairs: (nodes.length * (nodes.length - 1)) / 2 };
}

// ---- 构造塌缩工况：短篇,N 场景,实体几乎关联全部场景 ----
function buildScenes(sceneCount) {
  const spacing = Math.max(0.42, Math.min(0.98, 14 / Math.max(sceneCount - 1, 1)));
  const nodesById = new Map();
  const sceneIds = [];
  for (let i = 0; i < sceneCount; i++) {
    const id = `S${i}`;
    nodesById.set(id, { x: (i - (sceneCount - 1) / 2) * spacing });
    sceneIds.push(id);
  }
  return { nodesById, sceneIds };
}

// 朴素 v0：实体 X = 关联场景平均位置，无去碰撞，全部同一 baseY
function naiveLane(group, sceneIds, nodesById, baseY) {
  return group.map((g) => ({
    x: averageSceneX(g.relatedSceneIds ?? sceneIds, nodesById, 0),
    y: baseY
  }));
}
// 当前：resolveLaneLayout 去碰撞 + 奇偶 Y 错位
function resolvedLane(group, sceneIds, nodesById, minGap, baseY, staggerY) {
  const anchors = group.map((g) => averageSceneX(g.relatedSceneIds ?? sceneIds, nodesById, 0));
  const { x, y } = resolveLaneLayout(anchors, minGap, baseY, staggerY);
  return group.map((_, i) => ({ x: x[i], y: y[i] }));
}

function run(title, sceneCount, lanes) {
  const { nodesById, sceneIds } = buildScenes(sceneCount);
  console.log(`\n=== ${title}（${sceneCount} 场景）===`);
  console.log(
    `${"条带".padEnd(10)}${"节点".padStart(4)}  ${"v0 最小间距".padStart(11)}  ${"当前 最小间距".padStart(13)}  ${"v0 重叠对".padStart(9)}  ${"当前 重叠对".padStart(11)}`
  );
  const agg = { v0Overlap: 0, curOverlap: 0, v0Min: Infinity, curMin: Infinity, pairs: 0 };
  for (const lane of lanes) {
    const group = Array.from({ length: lane.count }, () => ({ relatedSceneIds: sceneIds })); // 每个实体关联全部场景（塌缩）
    const v0 = metrics(naiveLane(group, sceneIds, nodesById, lane.baseY));
    const cur = metrics(resolvedLane(group, sceneIds, nodesById, lane.minGap, lane.baseY, lane.staggerY));
    agg.v0Overlap += v0.overlapPairs; agg.curOverlap += cur.overlapPairs;
    agg.v0Min = Math.min(agg.v0Min, v0.minDist); agg.curMin = Math.min(agg.curMin, cur.minDist);
    agg.pairs += v0.totalPairs;
    console.log(
      `${lane.name.padEnd(10)}${String(lane.count).padStart(4)}  ${v0.minDist.toFixed(3).padStart(11)}  ${cur.minDist.toFixed(3).padStart(13)}  ${String(v0.overlapPairs).padStart(9)}  ${String(cur.overlapPairs).padStart(11)}`
    );
  }
  console.log("-".repeat(70));
  console.log(
    `${"合计/最差".padEnd(10)}${"".padStart(4)}  ${agg.v0Min.toFixed(3).padStart(11)}  ${agg.curMin.toFixed(3).padStart(13)}  ${String(agg.v0Overlap).padStart(9)}  ${String(agg.curOverlap).padStart(11)}`
  );
  console.log(
    `结论：去碰撞前最小间距 ${agg.v0Min.toFixed(3)}（节点塌缩重叠），去碰撞后 ${agg.curMin.toFixed(3)}（≥minGap）；` +
    `重叠节点对 ${agg.v0Overlap} → ${agg.curOverlap}（共 ${agg.pairs} 对，重叠率 ${(agg.v0Overlap / agg.pairs * 100).toFixed(0)}% → ${(agg.curOverlap / agg.pairs * 100).toFixed(0)}%）。`
  );
  return agg;
}

console.log("生产地图布局几何基准（复刻真实算法，塌缩工况）");
// 三条带：角色(minGap .5)、地点(minGap .5)、事件(minGap .36)，参数取自源码
const a = run("代表性短篇", 10, [
  { name: "角色", count: 8, minGap: 0.5, baseY: -0.78, staggerY: -0.2 },
  { name: "地点", count: 4, minGap: 0.5, baseY: -1.26, staggerY: -0.2 },
  { name: "事件", count: 12, minGap: 0.36, baseY: 0.68, staggerY: 0.22 }
]);
// 写 CSV
import { writeFileSync } from "node:fs";
writeFileSync(
  "bench/results/layout_geom.csv",
  "metric,v0,current\nmin_distance," + a.v0Min.toFixed(3) + "," + a.curMin.toFixed(3) +
  "\noverlap_pairs," + a.v0Overlap + "," + a.curOverlap +
  "\noverlap_rate_pct," + (a.v0Overlap / a.pairs * 100).toFixed(0) + "," + (a.curOverlap / a.pairs * 100).toFixed(0) + "\n"
);
console.log("\n[完成] 已写入 bench/results/layout_geom.csv");
