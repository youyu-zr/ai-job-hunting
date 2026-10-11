export function normalizeMessageFingerprint(text: unknown): string {
    return String(text || '').replace(/\s+/g, ' ').trim();
}

export function messageFingerprint(bossId: number, text: unknown): string {
    const normalized = normalizeMessageFingerprint(text);
    let hash = 2166136261;
    for (let i = 0; i < normalized.length; i++) {
        hash ^= normalized.charCodeAt(i);
        hash = Math.imul(hash, 16777619);
    }
    return `${bossId}:${normalized.length}:${hash >>> 0}`;
}
