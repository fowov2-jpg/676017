import { SandboxService } from '../services/SandboxService';
import { toolHandlers, TOOL_DEFINITIONS } from './tools';
import { buildSystemPrompt } from './prompts/system';
import { AgentLogEntry, AgentMessage, AgentToolCall, PerformanceSettings } from '../types';
import { v4 as uuidv4 } from 'uuid';

export type GenerateFn = (messages: AgentMessage[], settings: PerformanceSettings) => Promise<string>;

export interface AgentLoopOptions {
  sandbox: SandboxService;
  userPrompt: string;
  settings: PerformanceSettings;
  generate: GenerateFn; // функция, которая вызывает llama.rn
  onLog?: (entry: AgentLogEntry) => void;
  onStep?: (step: number, maxSteps: number) => void;
}

/**
 * Основной цикл агента.
 * Модель сама решает, какие инструменты вызывать.
 */
export class AgentLoop {
  private stopped = false;
  private logs: AgentLogEntry[] = [];

  constructor(private options: AgentLoopOptions) {}

  stop() {
    this.stopped = true;
  }

  private log(type: AgentLogEntry['type'], message: string, data?: any) {
    const entry: AgentLogEntry = {
      id: uuidv4(),
      timestamp: Date.now(),
      type,
      message,
      data,
    };
    this.logs.push(entry);
    this.options.onLog?.(entry);
  }

  /**
   * Пытается вытащить JSON tool call из ответа модели
   */
  private parseToolCall(text: string): AgentToolCall | null {
    const jsonBlock = text.match(/```json\s*([\s\S]*?)```/i);
    const raw = jsonBlock ? jsonBlock[1] : text;
    const match = raw.match(/\{[\s\S]*"name"\s*:\s*"[^"]+"[\s\S]*\}/);
    if (!match) return null;

    try {
      const parsed = JSON.parse(match[0]);
      if (parsed.name && typeof parsed.name === 'string') {
        return { name: parsed.name, arguments: parsed.arguments || {} };
      }
    } catch {}
    return null;
  }

  async run(): Promise<{ success: boolean; summary: string; logs: AgentLogEntry[] }> {
    const { sandbox, userPrompt, settings, generate } = this.options;
    this.log('info', 'Агент запущен');
    this.log('info', `Максимум шагов: ${settings.maxAgentSteps}`);

    let tree = '';
    try { tree = await sandbox.getProjectTree(5); } catch { tree = '(не удалось получить дерево проекта)'; }

    const systemPrompt = buildSystemPrompt(userPrompt, tree);
    const messages: AgentMessage[] = [
      { role: 'system', content: systemPrompt },
      { role: 'user', content: 'Начни выполнение. Сначала изучи структуру проекта.' },
    ];

    let step = 0;
    let finalSummary = 'Работа завершена';

    while (step < settings.maxAgentSteps && !this.stopped) {
      step += 1;
      this.options.onStep?.(step, settings.maxAgentSteps);
      this.log('info', `Шаг ${step}/${settings.maxAgentSteps}`);

      let responseText = '';
      try { responseText = await generate(messages, settings); }
      catch (e: any) { this.log('error', `Ошибка генерации: ${e.message}`); break; }

      this.log('thought', responseText.slice(0, 500) + (responseText.length > 500 ? '...' : ''));
      const toolCall = this.parseToolCall(responseText);

      if (!toolCall) {
        messages.push({ role: 'assistant', content: responseText });
        messages.push({ role: 'user', content: 'Ты должен вызывать инструменты в формате JSON. Продолжай работу или вызови finish.' });
        continue;
      }

      this.log('tool_call', `Вызов: ${toolCall.name}`, toolCall);
      const handler = toolHandlers[toolCall.name];
      if (!handler) {
        const errorMsg = `Неизвестный инструмент: ${toolCall.name}`;
        this.log('error', errorMsg);
        messages.push({ role: 'assistant', content: responseText });
        messages.push({ role: 'user', content: errorMsg });
        continue;
      }

      const result = await handler(toolCall.arguments, sandbox);
      this.log('tool_result', result.content.slice(0, 400), { success: result.success });
      messages.push({ role: 'assistant', content: responseText });
      messages.push({ role: 'tool', name: toolCall.name, content: result.content });

      if (toolCall.name === 'finish') {
        finalSummary = toolCall.arguments?.summary || result.content;
        this.log('info', 'Агент завершил работу через finish');
        break;
      }
    }

    if (this.stopped) {
      this.log('info', 'Агент остановлен пользователем');
      return { success: false, summary: 'Остановлено пользователем', logs: this.logs };
    }
    if (step >= settings.maxAgentSteps) this.log('info', 'Достигнут лимит шагов');

    return { success: true, summary: finalSummary, logs: this.logs };
  }
}
