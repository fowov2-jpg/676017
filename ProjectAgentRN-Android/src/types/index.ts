export type AgentToolName = 'list_dir' | 'read_file' | 'write_file' | 'delete_file' | 'search_code' | 'get_project_tree' | 'finish';
export interface AgentToolCall { name: AgentToolName; arguments: Record<string, any>; }
export interface AgentMessage { role: 'system' | 'user' | 'assistant' | 'tool'; content: string; tool_call_id?: string; name?: string; }
export interface ProjectInfo { id: string; name: string; sandboxPath: string; prompt: string; fileCount: number; createdAt: number; status: 'idle' | 'running' | 'finished' | 'error' | 'stopped'; }
export type PerformanceMode = 'eco' | 'balanced' | 'max';
export interface PerformanceSettings { mode: PerformanceMode; nGpuLayers: number; nThreads: number; contextSize: number; maxAgentSteps: number; temperature: number; topP: number; }
export interface AgentLogEntry { id: string; timestamp: number; type: 'thought' | 'tool_call' | 'tool_result' | 'error' | 'info'; message: string; data?: any; }
export const DEFAULT_PERFORMANCE: Record<PerformanceMode, PerformanceSettings> = {
  eco: { mode: 'eco', nGpuLayers: 12, nThreads: 4, contextSize: 8192, maxAgentSteps: 12, temperature: 0.3, topP: 0.9 },
  balanced: { mode: 'balanced', nGpuLayers: 28, nThreads: 6, contextSize: 16384, maxAgentSteps: 20, temperature: 0.4, topP: 0.9 },
  max: { mode: 'max', nGpuLayers: 99, nThreads: 8, contextSize: 32768, maxAgentSteps: 30, temperature: 0.5, topP: 0.95 },
};
