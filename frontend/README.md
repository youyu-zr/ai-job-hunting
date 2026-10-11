# 前端开发

Vue 3 + Vite，构建产物为浏览器用户脚本，不是独立网站。请使用 Node.js 24 与 pnpm。

```powershell
pnpm install --frozen-lockfile
pnpm test
pnpm build
pnpm verify:public
```

`dist/ai-job-hunting.user.js` 由源码构建，不要直接修改根目录分发脚本。合成测试不连接 BOSS，也不替代真实账号验收。

默认后端是托管服务，不是纯本地模式。自部署必须先运行自己的后端，再在面板修改服务地址；必要时同步修改 `vite.config.ts` 的 `connect`、更新/下载地址，以及后端 CORS。发布自己维护的分发版时，不应继续指向维护者的自动更新地址。

本地准备版本 `0.0.113-beta-auto-grant` 使用新的 namespace。脚本管理器可能把旧 namespace 的版本视为另一份脚本；升级时先关闭旧脚本，确认只启用一份并重新核对服务地址与偏好，防止双份投递。
