import { Canvas, useFrame } from "@react-three/fiber";
import { Html, Line, OrbitControls, Sparkles } from "@react-three/drei";
import { useMemo, useRef } from "react";
import { Color, Group, Vector3 } from "three";
import type {
  ChapterViewModel,
  OutlineSceneViewModel,
  StoryEntityViewModel,
  StoryEventViewModel
} from "../../api/types";

type ProductionMapProps = {
  chapters: ChapterViewModel[];
  entities: StoryEntityViewModel[];
  events: StoryEventViewModel[];
  scenes: OutlineSceneViewModel[];
  selectedSceneId: string;
  setSelectedSceneId: (sceneId: string) => void;
};

type MapNode = {
  id: string;
  label: string;
  kind: "chapter" | "entity" | "event" | "scene";
  position: [number, number, number];
  title?: string;
  status?: string;
  entityType?: StoryEntityViewModel["entityType"];
};

type MapLink = {
  id: string;
  fromId: string;
  toId: string;
  kind: "storyline" | "chapter" | "source" | "character" | "location";
};

type StorylineGraph = {
  nodes: MapNode[];
  links: MapLink[];
  sceneLinks: MapLink[];
  selectedContextIds: Set<string>;
};

const nodeColors: Record<MapNode["kind"], string> = {
  chapter: "#8aa4b8",
  entity: "#43d9c4",
  event: "#f5b84b",
  scene: "#e7f2ff"
};

function statusColor(node: MapNode, selected: boolean) {
  if (selected) return "#23f0ff";
  if (node.kind !== "scene") return nodeColors[node.kind];
  if (node.status?.includes("WARN")) return "#ffb650";
  if (node.status?.includes("FAILED")) return "#ff5d5d";
  if (node.status?.includes("DONE") || node.status?.includes("READY")) return "#5df2a3";
  return nodeColors.scene;
}

function extractChapterNo(sourceRefs: string[]) {
  for (const ref of sourceRefs) {
    const matched = ref.match(/ch(?:apter)?\s*(\d+)/i);
    if (matched?.[1]) return Number(matched[1]);
  }
  return null;
}

function refsOverlap(left: string[], right: string[]) {
  const rightSet = new Set(right.map((ref) => ref.toLowerCase()));
  return left.some((ref) => rightSet.has(ref.toLowerCase()));
}

function getNodePosition(nodesById: Map<string, MapNode>, nodeId: string) {
  const node = nodesById.get(nodeId);
  return node ? new Vector3(...node.position) : new Vector3(0, 0, 0);
}

function averageSceneX(sceneIds: string[], nodesById: Map<string, MapNode>, fallbackX: number) {
  const points = sceneIds
    .map((sceneId) => nodesById.get(sceneId)?.position[0])
    .filter((value): value is number => typeof value === "number");

  if (points.length === 0) return fallbackX;
  return points.reduce((total, value) => total + value, 0) / points.length;
}

function buildStorylineGraph({
  chapters,
  entities,
  events,
  scenes
}: Pick<ProductionMapProps, "chapters" | "entities" | "events" | "scenes">): StorylineGraph {
  const orderedScenes = scenes.slice(0, 12).sort((left, right) => left.seqNo - right.seqNo);
  const sceneCount = Math.max(orderedScenes.length, 1);
  const sceneChapterNoById = new Map(
    orderedScenes.map((scene) => [scene.sceneId, extractChapterNo(scene.sourceRefs)])
  );
  const chapterNoById = new Map(chapters.map((chapter) => [chapter.id, chapter.chapterNo]));
  const nodesById = new Map<string, MapNode>();
  const links: MapLink[] = [];

  const sceneNodes = orderedScenes.map((scene, index) => {
    const x = (index - (sceneCount - 1) / 2) * 0.98;
    const node = {
      id: scene.sceneId,
      label: String(scene.seqNo).padStart(2, "0"),
      kind: "scene" as const,
      position: [x, -0.05 + Math.sin(index * 0.7) * 0.12, 0] as [number, number, number],
      title: scene.title,
      status: scene.status
    };
    nodesById.set(node.id, node);
    return node;
  });

  const sceneLinks = sceneNodes.slice(1).map((node, index) => ({
    id: `storyline-${sceneNodes[index].id}-${node.id}`,
    fromId: sceneNodes[index].id,
    toId: node.id,
    kind: "storyline" as const
  }));
  links.push(...sceneLinks);

  const chapterNodes = chapters.slice(0, 8).map((chapter, index) => {
    const chapterSceneIds = orderedScenes
      .filter((scene) => sceneChapterNoById.get(scene.sceneId) === chapter.chapterNo)
      .map((scene) => scene.sceneId);
    const fallbackX = (index - (Math.min(chapters.length, 8) - 1) / 2) * 1.3;
    const x = averageSceneX(chapterSceneIds, nodesById, fallbackX);
    return {
      id: `chapter-${chapter.id}`,
      label: `C${chapter.chapterNo}`,
      kind: "chapter" as const,
      position: [x, 1.35, -1.55] as [number, number, number],
      title: chapter.title
    };
  });
  chapterNodes.forEach((node) => nodesById.set(node.id, node));

  const eventNodes = events.slice(0, 12).map((event, index) => {
    const chapterNo = chapterNoById.get(event.chapterId);
    const relatedSceneIds = orderedScenes
      .filter((scene) => {
        const sceneChapterNo = sceneChapterNoById.get(scene.sceneId);
        return refsOverlap(scene.sourceRefs, event.sourceRefs) || (chapterNo !== undefined && sceneChapterNo === chapterNo);
      })
      .map((scene) => scene.sceneId);
    const fallbackX = (index - (Math.min(events.length, 12) - 1) / 2) * 0.8;
    const x = averageSceneX(relatedSceneIds, nodesById, fallbackX) + ((index % 3) - 1) * 0.12;
    return {
      id: event.eventId,
      label: `E${event.eventOrder}`,
      kind: "event" as const,
      position: [x, 0.68, -0.75] as [number, number, number],
      title: event.title
    };
  });
  eventNodes.forEach((node) => nodesById.set(node.id, node));

  const entityNodes = entities.slice(0, 12).map((entity, index) => {
    const relatedSceneIds = orderedScenes
      .filter((scene) => scene.characters.includes(entity.entityId) || scene.slugline.locationId === entity.entityId)
      .map((scene) => scene.sceneId);
    const fallbackX = (index - (Math.min(entities.length, 12) - 1) / 2) * 0.74;
    const x = averageSceneX(relatedSceneIds, nodesById, fallbackX) + ((index % 2) - 0.5) * 0.18;
    const isLocation = entity.entityType === "LOCATION";
    return {
      id: entity.entityId,
      label: entity.canonicalName.slice(0, 8),
      kind: "entity" as const,
      position: [x, isLocation ? -1.08 : -0.82, isLocation ? 1.42 : 0.92] as [number, number, number],
      title: entity.canonicalName,
      entityType: entity.entityType
    };
  });
  entityNodes.forEach((node) => nodesById.set(node.id, node));

  for (const chapter of chapters.slice(0, 8)) {
    const chapterNodeId = `chapter-${chapter.id}`;
    for (const scene of orderedScenes) {
      if (sceneChapterNoById.get(scene.sceneId) === chapter.chapterNo) {
        links.push({
          id: `chapter-${chapter.id}-${scene.sceneId}`,
          fromId: chapterNodeId,
          toId: scene.sceneId,
          kind: "chapter"
        });
      }
    }
  }

  for (const event of events.slice(0, 12)) {
    const chapterNo = chapterNoById.get(event.chapterId);
    for (const scene of orderedScenes) {
      const sceneChapterNo = sceneChapterNoById.get(scene.sceneId);
      if (refsOverlap(scene.sourceRefs, event.sourceRefs) || (chapterNo !== undefined && sceneChapterNo === chapterNo)) {
        links.push({
          id: `source-${event.eventId}-${scene.sceneId}`,
          fromId: event.eventId,
          toId: scene.sceneId,
          kind: "source"
        });
      }
    }
  }

  for (const scene of orderedScenes) {
    for (const characterId of scene.characters) {
      if (nodesById.has(characterId)) {
        links.push({
          id: `character-${characterId}-${scene.sceneId}`,
          fromId: characterId,
          toId: scene.sceneId,
          kind: "character"
        });
      }
    }

    if (nodesById.has(scene.slugline.locationId)) {
      links.push({
        id: `location-${scene.slugline.locationId}-${scene.sceneId}`,
        fromId: scene.slugline.locationId,
        toId: scene.sceneId,
        kind: "location"
      });
    }
  }

  return {
    nodes: [...chapterNodes, ...eventNodes, ...sceneNodes, ...entityNodes],
    links,
    sceneLinks,
    selectedContextIds: new Set()
  };
}

function resolveSelectedContext(graph: StorylineGraph, selectedSceneId: string) {
  const selectedContextIds = new Set<string>(selectedSceneId ? [selectedSceneId] : []);

  for (const link of graph.links) {
    if (link.fromId === selectedSceneId) selectedContextIds.add(link.toId);
    if (link.toId === selectedSceneId) selectedContextIds.add(link.fromId);
  }

  for (const link of graph.sceneLinks) {
    if (link.fromId === selectedSceneId) selectedContextIds.add(link.toId);
    if (link.toId === selectedSceneId) selectedContextIds.add(link.fromId);
  }

  return {
    ...graph,
    selectedContextIds
  };
}

function linkColor(kind: MapLink["kind"], focused: boolean) {
  if (focused) return "#58f4ff";
  const colors: Record<MapLink["kind"], string> = {
    storyline: "#2de8ff",
    chapter: "#8298ad",
    source: "#f7b84e",
    character: "#49e8c9",
    location: "#75a7ff"
  };
  return colors[kind];
}

function linkOpacity(kind: MapLink["kind"], focused: boolean) {
  if (focused) return 0.95;
  return kind === "storyline" ? 0.58 : 0.26;
}

function linkWidth(kind: MapLink["kind"], focused: boolean) {
  if (focused) return 1.8;
  return kind === "storyline" ? 1.35 : 0.55;
}

function nodeScale(node: MapNode, selected: boolean, inContext: boolean) {
  if (selected) return 1.42;
  if (inContext) return 1.14;
  if (node.kind === "scene") return 1;
  return 0.94;
}

function nodeRadius(node: MapNode) {
  if (node.kind === "scene") return 0.18;
  if (node.kind === "event") return 0.14;
  return 0.12;
}

function labelClassName(node: MapNode, selected: boolean, inContext: boolean) {
  const classes = ["map-node-label", `map-node-label-${node.kind}`];
  if (node.entityType === "LOCATION") classes.push("map-node-label-location");
  if (selected) classes.push("map-node-label-active");
  if (inContext && !selected) classes.push("map-node-label-context");
  return classes.join(" ");
}

function SelectedSceneHalo() {
  const groupRef = useRef<Group>(null);

  useFrame((_, delta) => {
    if (groupRef.current) {
      groupRef.current.rotation.z += delta * 0.75;
    }
  });

  return (
    <group ref={groupRef}>
      <mesh rotation={[Math.PI / 2, 0, 0]}>
        <torusGeometry args={[0.34, 0.012, 10, 80]} />
        <meshBasicMaterial color="#58f4ff" transparent opacity={0.72} />
      </mesh>
      <mesh rotation={[Math.PI / 2, 0, 0]}>
        <torusGeometry args={[0.47, 0.006, 10, 80]} />
        <meshBasicMaterial color="#f8c365" transparent opacity={0.36} />
      </mesh>
    </group>
  );
}

function MapScene({
  graph,
  selectedSceneId,
  setSelectedSceneId
}: {
  graph: StorylineGraph;
  selectedSceneId: string;
  setSelectedSceneId: (sceneId: string) => void;
}) {
  const nodesById = useMemo(() => new Map(graph.nodes.map((node) => [node.id, node])), [graph.nodes]);

  return (
    <group>
      <Sparkles count={70} scale={[7.5, 2.4, 4.2]} size={1.25} speed={0.12} color="#72f6ff" />
      {graph.links.map((link) => {
        const focused = Boolean(selectedSceneId && (link.fromId === selectedSceneId || link.toId === selectedSceneId));
        return (
          <Line
            key={link.id}
            points={[getNodePosition(nodesById, link.fromId), getNodePosition(nodesById, link.toId)]}
            color={linkColor(link.kind, focused)}
            lineWidth={linkWidth(link.kind, focused)}
            transparent
            opacity={linkOpacity(link.kind, focused)}
          />
        );
      })}

      {graph.nodes.map((node) => {
        const selected = node.id === selectedSceneId;
        const inContext = graph.selectedContextIds.has(node.id);
        const color = statusColor(node, selected);
        return (
          <group key={node.id} position={node.position} scale={nodeScale(node, selected, inContext)}>
            {selected ? <SelectedSceneHalo /> : null}
            <mesh
              onClick={(event) => {
                event.stopPropagation();
                if (node.kind === "scene") setSelectedSceneId(node.id);
              }}
            >
              <sphereGeometry args={[nodeRadius(node), 32, 32]} />
              <meshStandardMaterial
                color={new Color(color)}
                emissive={new Color(color)}
                emissiveIntensity={selected ? 1.55 : inContext ? 0.78 : 0.36}
                metalness={0.35}
                roughness={0.22}
              />
            </mesh>
            <Html center distanceFactor={8}>
              <button
                className={labelClassName(node, selected, inContext)}
                disabled={node.kind !== "scene"}
                title={node.title ?? node.label}
                type="button"
                onClick={() => {
                  if (node.kind === "scene") setSelectedSceneId(node.id);
                }}
              >
                {node.label}
              </button>
            </Html>
          </group>
        );
      })}
    </group>
  );
}

export function ProductionMap(props: ProductionMapProps) {
  const graph = useMemo(
    () => resolveSelectedContext(buildStorylineGraph(props), props.selectedSceneId),
    [props.chapters, props.entities, props.events, props.scenes, props.selectedSceneId]
  );
  const sceneNodes = props.scenes.slice(0, 12).sort((left, right) => left.seqNo - right.seqNo);

  return (
    <div className="production-map-shell">
      <div className="production-map-legend" aria-hidden="true">
        <span className="legend-spine">Scene 主线</span>
        <span className="legend-source">来源事件</span>
        <span className="legend-character">角色/地点</span>
      </div>
      <div className="production-map-desktop" data-testid="production-map-canvas">
        <Canvas camera={{ position: [0, 3.45, 6.8], fov: 40 }} dpr={[1, 1.75]}>
          <color attach="background" args={["#05080d"]} />
          <ambientLight intensity={0.35} />
          <pointLight position={[3, 5, 4]} intensity={18} color="#60f5ff" />
          <pointLight position={[-4, 2, -3]} intensity={8} color="#ffb84d" />
          <MapScene
            graph={graph}
            selectedSceneId={props.selectedSceneId}
            setSelectedSceneId={props.setSelectedSceneId}
          />
          <OrbitControls enablePan={false} enableZoom={false} autoRotate={false} />
        </Canvas>
      </div>

      <div className="production-map-mobile">
        {sceneNodes.map((scene) => (
          <button
            key={scene.sceneId}
            className={
              scene.sceneId === props.selectedSceneId
                ? "mobile-scene-node mobile-scene-node-active"
                : "mobile-scene-node"
            }
            type="button"
            onClick={() => props.setSelectedSceneId(scene.sceneId)}
          >
            <span>{String(scene.seqNo).padStart(2, "0")}</span>
            <strong>{scene.title}</strong>
            <small>{scene.status}</small>
            <em className="mobile-scene-meta">
              {scene.sourceRefs[0] ?? "source_pending"} · {scene.characters.length} 角色 ·{" "}
              {scene.slugline.locationId}
            </em>
          </button>
        ))}
      </div>
    </div>
  );
}
