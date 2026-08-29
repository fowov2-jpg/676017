import RNFS from 'react-native-fs';

export class SandboxService {
  private rootPath: string;
  constructor(rootPath: string) { this.rootPath = rootPath.replace(/\/+$/, ''); }
  private resolve(relativePath: string = ''): string {
    const clean = relativePath.replace(/^\/+/, '').replace(/\.\./g, '');
    const full = `${this.rootPath}/${clean}`.replace(/\/+/g, '/');
    if (!full.startsWith(this.rootPath)) throw new Error(`Path traversal detected: ${relativePath}`);
    return full;
  }
  async exists(relativePath: string): Promise<boolean> { try { return await RNFS.exists(this.resolve(relativePath)); } catch { return false; } }
  async listDir(relativePath: string = ''): Promise<string[]> {
    const items = await RNFS.readDir(this.resolve(relativePath));
    return items.map((item) => item.isDirectory() ? `${item.name}/` : item.name).sort((a,b) => { if (a.endsWith('/') && !b.endsWith('/')) return -1; if (!a.endsWith('/') && b.endsWith('/')) return 1; return a.localeCompare(b); });
  }
  async readFile(relativePath: string, maxSizeKB = 120): Promise<string> {
    const full = this.resolve(relativePath); const stat = await RNFS.stat(full);
    if (stat.isDirectory()) throw new Error(`Cannot read directory as file: ${relativePath}`);
    if (stat.size > maxSizeKB * 1024) throw new Error(`File too large (${Math.round(stat.size / 1024)}KB > ${maxSizeKB}KB): ${relativePath}`);
    return RNFS.readFile(full, 'utf8');
  }
  async writeFile(relativePath: string, content: string): Promise<void> {
    const full = this.resolve(relativePath); const dir = full.substring(0, full.lastIndexOf('/'));
    if (!(await RNFS.exists(dir))) await RNFS.mkdir(dir); await RNFS.writeFile(full, content, 'utf8');
  }
  async deleteFile(relativePath: string): Promise<void> { const full = this.resolve(relativePath); if (await RNFS.exists(full)) await RNFS.unlink(full); }
  async getProjectTree(maxDepth = 5): Promise<string> {
    const walk = async (currentRel: string, depth: number): Promise<string> => {
      if (depth > maxDepth) return ''; let result = '';
      try { const items = await this.listDir(currentRel); for (const name of items) { const isDir = name.endsWith('/'); const cleanName = isDir ? name.slice(0,-1) : name; const prefix = '  '.repeat(depth); const relPath = currentRel ? `${currentRel}/${cleanName}` : cleanName; result += `${prefix}${cleanName}${isDir ? '/\n' : '\n'}`; if (isDir) result += await walk(relPath, depth + 1); } } catch {}
      return result;
    }; return walk('', 0);
  }
  async searchCode(query: string, maxResults = 20): Promise<string[]> {
    const results: string[] = []; const lowerQuery = query.toLowerCase();
    const walk = async (currentRel: string) => {
      if (results.length >= maxResults) return;
      try { const items = await this.listDir(currentRel); for (const name of items) { if (results.length >= maxResults) break; const isDir = name.endsWith('/'); const cleanName = isDir ? name.slice(0,-1) : name; const relPath = currentRel ? `${currentRel}/${cleanName}` : cleanName; if (isDir) await walk(relPath); else if (/\.(ts|tsx|js|jsx|py|java|kt|json|md|txt|css|html|xml|gradle|swift)$/i.test(cleanName)) { try { const content = await this.readFile(relPath,80); if (content.toLowerCase().includes(lowerQuery)) results.push(relPath); } catch {} } } } catch {}
    }; await walk(''); return results;
  }
}
