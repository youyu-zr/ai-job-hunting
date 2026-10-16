# 贡献指南

感谢参与 AI 工作猎手。这个项目最需要的是可靠性、可解释性和真实边界，而不是单纯增加自动化程度。

## 先选一种参与方式

- **反馈问题**：在 GitHub 提交 Issue，适合报错、功能建议、页面兼容和文档问题。
- **修改项目**：Fork 仓库后创建分支，提交代码、测试或文档，再发 Pull Request（PR）。
- **没有开发环境**：先提交脱敏的复现步骤或页面兼容记录，不必直接改代码。

普通用户使用维护者托管服务时不需要自备模型 Key；只有自部署或使用自定义模型接口时，才需要在自己的部署环境中配置 Key。任何人都不应把 Key、Cookie 或真实简历发到 Issue/PR。

## 提交前检查

前端：

```text
cd frontend
pnpm install --frozen-lockfile
pnpm test
pnpm build
pnpm verify:public
```

后端：

```text
cd backend
mvn test "-Dtest=!EmailTest,!JobAgentTest"
mvn -DskipTests package
```

如果本机没有 MySQL、模型服务或 BOSS 登录态，请在 PR 中明确写出未验证的部分。

前端使用 Node.js 24。后端 `-o` 只适用于已缓存依赖的机器；历史 Spring AI 快照在干净机器上的可获取性待验证。`EmailTest` 和 `JobAgentTest` 属于需要外部服务的集成测试，上述命令不代表它们通过。

## 不要提交

- 真实简历、手机号、邮箱、BOSS uid、Cookie、聊天记录或截图；
- API Key、数据库密码、SMTP 凭据、支付宝密钥和 SSH 材料；
- 生产服务器路径、运行日志、浏览器 profile 或回滚包；
- 未经验证的“已发送”“已读”“已录用”等结果；
- 让系统自动承诺薪资、面试时间、联系方式或录用条件的逻辑。

## Pull Request 要写什么

- 变更目的和复现步骤；
- 修改的模块与新增测试；
- 本地验证结果；
- 是否需要真实 BOSS 登录态验收；
- 兼容性、隐私或平台规则影响。

## 推荐的首批贡献

- 脱敏的 BOSS 页面兼容记录；
- 消息解析和发送状态的回归测试；
- 前端错误状态和安装文档；
- 干净机器的启动验证；
- Docker、数据库迁移和依赖许可说明。

涉及生产服务器、真实账号、自动发送、激活/权限、支付、Cookie 或真实用户数据的改动，请先开 Issue 讨论，不要直接提交未经维护者确认的 PR。
