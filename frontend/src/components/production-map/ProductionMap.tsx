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
  status?: string;
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

function buildNodes({
  chapters,
  entities,
  events,
  scenes
}: Pick<ProductionMapProps, "chapters" | "entities" | "events" | "scenes">) {
  const chapterNodes = chapters.slice(0, 8).map((chapter, index) => {
    const angle = (index / Math.max(chapters.length, 1)) * Math.PI * 2;
    return {
      id: `chapter-${chapter.id}`,
      label: `C${chapter.chapterNo}`,
      kind: "chapter" as const,
      position: [Math.cos(angle) * 4.8, -1.25, Math.sin(angle) * 4.8] as [number, number, number]
    };
  });

  const entityNodes = entities.slice(0, 10).map((entity, index) => {
    const angle = (index / Math.max(entities.length, 1)) * Math.PI * 2;
    return {
      id: entity.entityId,
      label: entity.canonicalName.slice(0, 8),
      kind: "entity" as const,
      position: [Math.cos(angle) * 3.1, 1.15, Math.sin(angle) * 3.1] as [number, number, number]
    };
  });

  const eventNodes = events.slice(0, 10).map((event, index) => {
    const angle = (index / Math.max(events.length, 1)) * Math.PI * 2 + 0.3;
    return {
      id: event.eventId,
      label: `E${event.eventOrder}`,
      kind: "event" as const,
      position: [Math.cos(angle) * 2.05, 0, Math.sin(angle) * 2.05] as [number, number, number]
    };
  });

  const sceneNodes = scenes.slice(0, 12).map((scene, index) => {
    const x = (index - (Math.min(scenes.length, 12) - 1) / 2) * 0.88;
    return {
      id: scene.sceneId,
      label: String(scene.seqNo).padStart(2, "0"),
      kind: "scene" as const,
      position: [x, -0.1 + Math.sin(index * 0.7) * 0.24, 0] as [number, number, number],
      status: scene.status
    };
  });

  return [...chapterNodes, ...entityNodes, ...eventNodes, ...sceneNodes];
}

function MapScene({
  nodes,
  selectedSceneId,
  setSelectedSceneId
}: {
  nodes: MapNode[];
  selectedSceneId: string;
  setSelectedSceneId: (sceneId: string) => void;
}) {
  const groupRef = useRef<Group>(null);
  const core = new Vector3(0, 0, 0);

  useFrame((_, delta) => {
    if (groupRef.current) {
      groupRef.current.rotation.y += delta * 0.08;
    }
  });

  return (
    <group ref={groupRef}>
      <Sparkles count={90} scale={[8, 3, 8]} size={1.5} speed={0.18} color="#72f6ff" />
      {nodes
        .filter((node) => node.kind !== "scene")
        .map((node) => (
          <Line
            key={`line-${node.id}`}
            points={[new Vector3(...node.position), core]}
            color="#244653"
            lineWidth={0.6}
            transparent
            opacity={0.42}
          />
        ))}

      {nodes.map((node) => {
        const selected = node.id === selectedSceneId;
        const color = statusColor(node, selected);
        return (
          <group key={node.id} position={node.position}>
            <mesh
              onClick={(event) => {
                event.stopPropagation();
                if (node.kind === "scene") setSelectedSceneId(node.id);
              }}
            >
              <sphereGeometry args={[node.kind === "scene" ? 0.18 : 0.13, 32, 32]} />
              <meshStandardMaterial
                color={new Color(color)}
                emissive={new Color(color)}
                emissiveIntensity={selected ? 1.4 : 0.46}
                metalness={0.35}
                roughness={0.22}
              />
            </mesh>
            <Html center distanceFactor={8}>
              <button
                className={selected ? "map-node-label map-node-label-active" : "map-node-label"}
                disabled={node.kind !== "scene"}
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
  const nodes = useMemo(() => buildNodes(props), [props.chapters, props.entities, props.events, props.scenes]);
  const sceneNodes = props.scenes.slice(0, 12);

  return (
    <div className="production-map-shell">
      <div className="production-map-desktop" data-testid="production-map-canvas">
        <Canvas camera={{ position: [0, 4.2, 7.2], fov: 42 }} dpr={[1, 1.75]}>
          <color attach="background" args={["#05080d"]} />
          <ambientLight intensity={0.35} />
          <pointLight position={[3, 5, 4]} intensity={18} color="#60f5ff" />
          <pointLight position={[-4, 2, -3]} intensity={8} color="#ffb84d" />
          <MapScene
            nodes={nodes}
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
          </button>
        ))}
      </div>
    </div>
  );
}
