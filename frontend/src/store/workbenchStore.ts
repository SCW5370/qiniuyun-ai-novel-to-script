import { create } from "zustand";

export type WorkbenchView = "map" | "assets" | "delivery";

type WorkbenchStore = {
  activeView: WorkbenchView;
  selectedSceneId: string;
  setActiveView: (view: WorkbenchView) => void;
  setSelectedSceneId: (sceneId: string) => void;
};

export const useWorkbenchStore = create<WorkbenchStore>((set) => ({
  activeView: "map",
  selectedSceneId: "",
  setActiveView: (activeView) => set({ activeView }),
  setSelectedSceneId: (selectedSceneId) => set({ selectedSceneId })
}));
