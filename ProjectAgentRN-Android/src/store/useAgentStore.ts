import { create } from 'zustand';
import { ProjectInfo, AgentLogEntry, PerformanceMode, PerformanceSettings, DEFAULT_PERFORMANCE } from '../types';
import { PerformanceService } from '../services/PerformanceService';

interface AgentState {
  currentProject: ProjectInfo | null; logs: AgentLogEntry[]; isRunning: boolean; performance: PerformanceSettings; resultZipPath: string | null;
  setProject: (project: ProjectInfo | null) => void; addLog: (entry: AgentLogEntry) => void; clearLogs: () => void; setRunning: (value: boolean) => void;
  setPerformanceMode: (mode: PerformanceMode) => void; setResultZip: (path: string | null) => void; reset: () => void;
}

export const useAgentStore = create<AgentState>((set) => ({
  currentProject: null, logs: [], isRunning: false, performance: PerformanceService.getRecommendedForDevice(), resultZipPath: null,
  setProject: (project) => set({ currentProject: project }), addLog: (entry) => set((state) => ({ logs: [...state.logs, entry] })), clearLogs: () => set({ logs: [] }), setRunning: (value) => set({ isRunning: value }),
  setPerformanceMode: (mode) => set({ performance: { ...DEFAULT_PERFORMANCE[mode] } }), setResultZip: (path) => set({ resultZipPath: path }),
  reset: () => set({ currentProject: null, logs: [], isRunning: false, resultZipPath: null }),
}));
