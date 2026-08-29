import { initLlama, LlamaContext, CompletionParams } from 'llama.rn';
import RNFS from 'react-native-fs';
import { PerformanceSettings, AgentMessage } from '../types';

export interface ModelInfo { id: string; name: string; path: string; sizeMB: number; }

export class ModelService {
  private context: LlamaContext | null = null;
  private currentModelPath: string | null = null;
  private isLoading = false;

  async listLocalModels(): Promise<ModelInfo[]> {
    const modelsDir = `${RNFS.DocumentDirectoryPath}/models`;
    await RNFS.mkdir(modelsDir);
    const files = await RNFS.readDir(modelsDir);
    const models: ModelInfo[] = [];
    for (const file of files) {
      if (file.isFile() && file.name.toLowerCase().endsWith('.gguf')) {
        models.push({ id: file.name, name: file.name.replace(/\.gguf$/i, ''), path: file.path, sizeMB: Math.round((file.size || 0) / (1024 * 1024)) });
      }
    }
    return models.sort((a, b) => a.name.localeCompare(b.name));
  }

  async loadModel(modelPath: string, settings: PerformanceSettings): Promise<void> {
    if (this.isLoading) throw new Error('Модель уже загружается');
    if (this.context && this.currentModelPath === modelPath) return;
    await this.unloadModel();
    this.isLoading = true;
    try {
      this.context = await initLlama({ model: modelPath, n_ctx: settings.contextSize, n_gpu_layers: settings.nGpuLayers, n_threads: settings.nThreads, use_mlock: true });
      this.currentModelPath = modelPath;
    } finally { this.isLoading = false; }
  }

  async unloadModel(): Promise<void> {
    if (this.context) {
      try { await this.context.release(); } catch {}
      this.context = null; this.currentModelPath = null;
    }
  }
  isModelLoaded(): boolean { return this.context !== null; }
  getCurrentModelPath(): string | null { return this.currentModelPath; }

  async generate(messages: AgentMessage[], settings: PerformanceSettings, onToken?: (token: string) => void): Promise<string> {
    if (!this.context) throw new Error('Модель не загружена');
    const prompt = this.messagesToPrompt(messages);
    const params: CompletionParams = { prompt, n_predict: 1024, temperature: settings.temperature, top_p: settings.topP, stop: ['```json', '<|im_end|>', '</s>', '<|end|>', '<|eot_id|>'] };
    let fullText = '';
    const result = await this.context.completion(params, (data) => { if (data.token) { fullText += data.token; onToken?.(data.token); } });
    return result.text || fullText;
  }

  private messagesToPrompt(messages: AgentMessage[]): string {
    let prompt = '';
    for (const msg of messages) {
      if (msg.role === 'system') prompt += `System: ${msg.content}\n\n`;
      else if (msg.role === 'user') prompt += `User: ${msg.content}\n\n`;
      else if (msg.role === 'assistant') prompt += `Assistant: ${msg.content}\n\n`;
      else if (msg.role === 'tool') prompt += `Tool (${msg.name}): ${msg.content}\n\n`;
    }
    return prompt + 'Assistant:';
  }
}

export const modelService = new ModelService();
