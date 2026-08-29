import { SandboxService } from '../../services/SandboxService';
import { AgentToolName } from '../../types';

export interface ToolResult {
  success: boolean;
  content: string;
}

export type ToolHandler = (args: Record<string, any>, sandbox: SandboxService) => Promise<ToolResult>;

export const toolHandlers: Record<AgentToolName, ToolHandler> = {
  list_dir: async (args, sandbox) => {
    try {
      const path = args.path || '';
      const items = await sandbox.listDir(path);
      return { success: true, content: items.length > 0 ? items.join('\n') : '(empty directory)' };
    } catch (e: any) { return { success: false, content: `Error: ${e.message}` }; }
  },
  read_file: async (args, sandbox) => {
    try { return { success: true, content: await sandbox.readFile(args.path) }; }
    catch (e: any) { return { success: false, content: `Error: ${e.message}` }; }
  },
  write_file: async (args, sandbox) => {
    try {
      if (!args.path || typeof args.content !== 'string') return { success: false, content: 'Error: path and content are required' };
      await sandbox.writeFile(args.path, args.content);
      return { success: true, content: `Successfully wrote ${args.path}` };
    } catch (e: any) { return { success: false, content: `Error: ${e.message}` }; }
  },
  delete_file: async (args, sandbox) => {
    try { await sandbox.deleteFile(args.path); return { success: true, content: `Deleted ${args.path}` }; }
    catch (e: any) { return { success: false, content: `Error: ${e.message}` }; }
  },
  search_code: async (args, sandbox) => {
    try {
      const results = await sandbox.searchCode(args.query || '', 15);
      return { success: true, content: results.length > 0 ? results.join('\n') : 'No matches found' };
    } catch (e: any) { return { success: false, content: `Error: ${e.message}` }; }
  },
  get_project_tree: async (_args, sandbox) => {
    try { return { success: true, content: (await sandbox.getProjectTree(6)) || '(empty project)' }; }
    catch (e: any) { return { success: false, content: `Error: ${e.message}` }; }
  },
  finish: async (args) => ({ success: true, content: args.summary || 'Task completed' }),
};

export const TOOL_DEFINITIONS = `
Доступные инструменты (вызывай строго в формате JSON):

1. list_dir
   {"name": "list_dir", "arguments": {"path": "src/components"}}

2. read_file
   {"name": "read_file", "arguments": {"path": "src/App.tsx"}}

3. write_file
   {"name": "write_file", "arguments": {"path": "src/utils/helper.ts", "content": "полный текст файла"}}

4. delete_file
   {"name": "delete_file", "arguments": {"path": "old_file.js"}}

5. search_code
   {"name": "search_code", "arguments": {"query": "useState"}}

6. get_project_tree
   {"name": "get_project_tree", "arguments": {}}

7. finish
   {"name": "finish", "arguments": {"summary": "Краткое описание того, что сделано"}}
`;
