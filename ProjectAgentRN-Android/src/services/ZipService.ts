import { unzip, zip } from 'react-native-zip-archive';
import RNFS from 'react-native-fs';
import { v4 as uuidv4 } from 'uuid';

export class ZipService {
  static async extractProject(zipPath: string): Promise<{ sandboxPath: string; prompt: string; fileCount: number; projectId: string }> {
    const projectId = uuidv4();
    const sandboxPath = `${RNFS.DocumentDirectoryPath}/sandboxes/${projectId}`;
    await RNFS.mkdir(`${RNFS.DocumentDirectoryPath}/sandboxes`); await RNFS.mkdir(sandboxPath);
    await unzip(zipPath, sandboxPath);
    const prompt = await this.findPrompt(sandboxPath); const fileCount = await this.countFiles(sandboxPath);
    return { sandboxPath, prompt, fileCount, projectId };
  }
  static async packProject(sandboxPath: string, outputName?: string): Promise<string> {
    const resultsDir = `${RNFS.DocumentDirectoryPath}/results`; await RNFS.mkdir(resultsDir);
    const outputPath = `${resultsDir}/${outputName || `result_${Date.now()}`}.zip`;
    if (await RNFS.exists(outputPath)) await RNFS.unlink(outputPath); await zip(sandboxPath, outputPath); return outputPath;
  }
  private static async findPrompt(root: string): Promise<string> {
    for (const file of ['prompt.md','instruction.txt','task.md','TASK.md','README.md','prompt.txt']) {
      const fullPath = `${root}/${file}`; if (await RNFS.exists(fullPath)) { try { const content = await RNFS.readFile(fullPath,'utf8'); if (content.trim().length > 10) return content.trim(); } catch {} }
    }
    return 'Проанализируй структуру проекта и выполни необходимые улучшения кода. Будь аккуратен и не ломай существующую логику.';
  }
  private static async countFiles(dir: string): Promise<number> {
    let count = 0; try { for (const item of await RNFS.readDir(dir)) { if (item.isFile()) count += 1; else if (item.isDirectory()) count += await this.countFiles(item.path); } } catch {} return count;
  }
  static async cleanupSandbox(sandboxPath: string): Promise<void> { if (await RNFS.exists(sandboxPath)) await RNFS.unlink(sandboxPath); }
}
