export function joinPath(...parts: string[]): string {
  return parts.map((p, i) => { if (i === 0) return p.replace(/\/+$/, ''); return p.replace(/^\/+|\/+$/g, ''); }).filter(Boolean).join('/');
}
export function dirname(filePath: string): string { const idx = filePath.lastIndexOf('/'); if (idx <= 0) return '.'; return filePath.slice(0, idx); }
export function basename(filePath: string): string { const idx = filePath.lastIndexOf('/'); return idx >= 0 ? filePath.slice(idx + 1) : filePath; }
