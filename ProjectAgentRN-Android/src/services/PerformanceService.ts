import { PerformanceMode, PerformanceSettings, DEFAULT_PERFORMANCE } from '../types';

export class PerformanceService {
  private current: PerformanceSettings;
  constructor(initialMode: PerformanceMode = 'balanced') { this.current = { ...DEFAULT_PERFORMANCE[initialMode] }; }
  getSettings(): PerformanceSettings { return { ...this.current }; }
  setMode(mode: PerformanceMode) { this.current = { ...DEFAULT_PERFORMANCE[mode] }; }
  static getRecommendedForDevice(): PerformanceSettings {
    return { mode: 'balanced', nGpuLayers: 32, nThreads: 6, contextSize: 24576, maxAgentSteps: 22, temperature: 0.35, topP: 0.9 };
  }
  getThermalThrottledSettings(): PerformanceSettings {
    return { ...this.current, nGpuLayers: Math.max(8, Math.floor(this.current.nGpuLayers * 0.5)), nThreads: Math.max(2, Math.floor(this.current.nThreads * 0.6)), contextSize: Math.min(this.current.contextSize, 12288) };
  }
}
