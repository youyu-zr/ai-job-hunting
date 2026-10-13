import {existsSync, readFileSync} from 'node:fs';
import {resolve} from 'node:path';

const root = resolve(import.meta.dirname, '..');
const sourceFiles = [
    'vite.config.ts',
    'src/axios.ts',
    'src/App.vue',
    'src/components/ui/AiJob.vue',
    'src/assets/UseDocument.md',
];

const forbidden = [
    'externalGlobals',
    'cdn.jsdelivr.net',
    'unpkg.com',
    '@latest',
    'gitee.com/yangfeng20',
    'github.com/yangfeng20',
    'bilibili.com',
];

const forbiddenPatterns = [
    ['IPv4-literal service URL', /https?:\/\/(?:\d{1,3}\.){3}\d{1,3}(?::\d+)?/i],
];

const required = [
    ['vite.config.ts', 'downloadURL: "https://aijob.zxpnb.top/ai-job-hunting.user.js"'],
    ['vite.config.ts', 'updateURL: "https://aijob.zxpnb.top/ai-job-hunting.user.js"'],
];

function canonicalizeBuildPaths(script) {
    const scopeIds = new Map();
    return script
        .replace(/\r\n/g, '\n')
        .replace(/\\r\\n/g, '\\n')
        .replace(/data-v-([0-9a-f]{8})/g, (_, id) => {
            if (!scopeIds.has(id)) {
                scopeIds.set(id, scopeIds.size);
            }
            return `data-v-<scope-${scopeIds.get(id)}>`;
        })
        .replace(
            /(["'])\.\/([^"'\/]+?)-(?:[A-Za-z0-9_-]{8}-)?[A-Za-z0-9_-]{8}(\.js\1)/g,
            '$1./<chunk>-<hash>$2',
        );
}

const failures = [];
const packageJson = JSON.parse(readFileSync(resolve(root, 'package.json'), 'utf8'));
const expectedVersion = packageJson.version;
const viteConfig = readFileSync(resolve(root, 'vite.config.ts'), 'utf8');
if (!viteConfig.includes("namespace: 'https://github.com/youyu-zr/ai-job-hunting'")) {
    failures.push('vite.config.ts has an unexpected userscript namespace');
}

const source = sourceFiles
    .map(file => [file, readFileSync(resolve(root, file), 'utf8')]);

for (const [file, content] of source) {
    for (const value of forbidden) {
        if (content.includes(value)) {
            failures.push(`${file} still contains ${value}`);
        }
    }
    for (const [label, pattern] of forbiddenPatterns) {
        if (pattern.test(content)) {
            failures.push(`${file} still contains an ${label}`);
        }
    }
}

for (const [file, value] of required) {
    const content = readFileSync(resolve(root, file), 'utf8');
    if (!content.includes(value)) {
        failures.push(`${file} is missing ${value}`);
    }
}

const distFile = resolve(root, 'dist/ai-job-hunting.user.js');
const publicFile = resolve(root, '..', 'ai-job-hunting.user.js');
if (!existsSync(distFile)) {
    failures.push('frontend/dist/ai-job-hunting.user.js is missing; build before verification');
} else {
    const dist = readFileSync(distFile, 'utf8');
    for (const value of forbidden) {
        if (dist.includes(value)) {
            failures.push(`dist/ai-job-hunting.user.js still contains ${value}`);
        }
    }
    for (const [label, pattern] of forbiddenPatterns) {
        if (pattern.test(dist)) {
            failures.push(`dist/ai-job-hunting.user.js still contains an ${label}`);
        }
    }
    if (!new RegExp(`@version\\s+${expectedVersion.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`).test(dist)) {
        failures.push('dist/ai-job-hunting.user.js has an unexpected version');
    }
    if (!dist.includes('这不是简历未导入导致的')
        || dist.includes('开始自动注册')
        || dist.includes('handlerImport({ value: false })')) {
        failures.push('dist/ai-job-hunting.user.js does not preserve automatic account creation with explicit resume import');
    }
    if (existsSync(publicFile)) {
        const publicScript = readFileSync(publicFile, 'utf8');
        if (canonicalizeBuildPaths(dist) !== canonicalizeBuildPaths(publicScript)) {
            failures.push('root ai-job-hunting.user.js differs from the verified build beyond path-derived identifiers');
        }
    } else {
        failures.push('root ai-job-hunting.user.js is missing');
    }
}

if (failures.length > 0) {
    console.error(failures.map(item => `FAIL: ${item}`).join('\n'));
    process.exit(1);
}

console.log('PASS: public userscript is self-contained and free of legacy promotion links');
