import {defineConfig} from 'vite';
import vue from '@vitejs/plugin-vue';
import Markdown from 'vite-plugin-md';
import monkey from "vite-plugin-monkey";
import AutoImport from 'unplugin-auto-import/vite';
import Components from 'unplugin-vue-components/vite';
import {ElementPlusResolver} from 'unplugin-vue-components/resolvers';
import {readFileSync} from 'node:fs';

const pkgVersion: string = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf-8')).version;

let matchUrlList: string[] = [
    'https://www.zhipin.com/web/geek/*',
    'https://www.zhipin.com/overseas/*'
];

export default defineConfig(() => {
    const plugins = [
        vue({
            include: [/\.vue$/, /\.md$/], // Support .vue and .md files
        }),
        Markdown(),
        AutoImport({
            resolvers: [ElementPlusResolver()],
        }),
        Components({
            resolvers: [ElementPlusResolver()],
        }),
        monkey({
            entry: 'src/main.ts',
            userscript: {
                name: "AI工作猎手-让ai帮您找工作！",
                author: "maple.",
                version: pkgVersion,
                description: "AI工作猎手：辅助岗位筛选、职位沟通与求职流程管理。",
                namespace: 'https://github.com/youyu-zr/ai-job-hunting',
                connect: ["docdownload.zhipin.com", "aijob.zxpnb.top"],
                updateURL: "https://aijob.zxpnb.top/ai-job-hunting.user.js",
                downloadURL: "https://aijob.zxpnb.top/ai-job-hunting.user.js",
                match: matchUrlList,
            },
            build: {
                // Keep the published userscript usable when CDN access is unavailable.
                systemjs: 'inline',
            },
        })
    ];

    return {
        plugins,
        define: {
            __APP_VERSION__: JSON.stringify(pkgVersion),
        },
        resolve: {
            extensions: ['.js', '.ts', '.vue', '.json', '.css'],
            alias: {
                'vue': 'vue/dist/vue.esm-bundler.js'
            }
        },
    };
});



