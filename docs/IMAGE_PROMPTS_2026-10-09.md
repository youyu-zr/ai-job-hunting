# 宣传海报生图提示词：待文案确认

**状态：暂不生成或发布。**线上仍为旧脚本，本地候选代码尚未推送或部署；公开许可和真实账号验收也未完成。文案确认后再决定是否生成海报。

这些提示词只描述公开产品信息，不包含任何 Key、Cookie、账号、真实简历、真实聊天内容或未公开部署信息。生成海报时，建议让模型留出文字区域，再使用本地排版脚本写入中文，避免生图模型把中文写错。

## 海报 1：产品定位

```text
Use case: ads-marketing
Asset type: Douyin vertical poster, 9:16, 1080x1920
Primary request: a clean editorial technology poster for an open-source AI job-search copilot running inside a Chinese recruitment webpage
Scene/backdrop: abstract but realistic browser workspace, generic job cards and message panels, no real platform logo, no personal data
Subject: a transparent AI assistant panel beside a job listing, with a visible human confirmation checkpoint
Style/medium: modern product design, restrained cyan, white, dark navy, and warm orange accents, crisp UI-inspired composition
Composition/framing: strong negative space in the upper third for Chinese headline, product interface visual in the lower two thirds
Lighting/mood: clear, trustworthy, practical, engineering-focused
Color palette: dark navy background, cyan highlights, white type area, orange warning accent
Text (verbatim): no generated text; leave blank typography areas
Constraints: no logos, no watermark, no fake metrics, no faces, no resumes, no phone numbers, no QR code
Avoid: sci-fi robot, exaggerated automation, money symbols, success guarantees, crowded text
```

建议后期叠字：

`我把求职工具的发布先按停了`

## 海报 2：人工确认边界

```text
Use case: ads-marketing
Asset type: Douyin vertical poster, 9:16, 1080x1920
Primary request: a product safety and human-in-the-loop poster for an AI job-search assistant
Scene/backdrop: a clean split visual between AI-generated message draft and a human approval button, generic browser UI
Subject: one draft message, one highlighted review step, one clear handoff from AI to human
Style/medium: polished editorial infographic, not a real screenshot, sharp lines and readable blank labels
Composition/framing: central review checkpoint, generous empty areas for later Chinese typesetting
Lighting/mood: calm, responsible, transparent, user-controlled
Color palette: charcoal, cyan, green for assistance, orange for human confirmation
Text (verbatim): no generated text; leave blank areas for typesetting
Constraints: no platform branding, no real chat text, no contact information, no claim of interview or offer success
Avoid: autonomous agent taking over, handshake, money, trophies, fake statistics
```

建议后期叠字：

`简历导入，应该由用户自己决定`

## 海报 3：开源协作

```text
Use case: ads-marketing
Asset type: Douyin and Xiaohongshu vertical poster, 9:16, 1080x1920
Primary request: an open-source developer collaboration poster for a Chinese AI job-search web assistant
Scene/backdrop: generic code editor, issue list, test report, and pull request review, all synthetic and anonymized
Subject: a laptop-like workspace showing code, tests, and a contribution flow
Style/medium: clean open-source developer editorial, realistic but fictional interface, no readable generated code
Composition/framing: code and test visuals in lower half, large empty headline space at top
Lighting/mood: welcoming, practical, collaborative, trustworthy
Color palette: light background with charcoal, cobalt, cyan, and orange accents
Text (verbatim): no generated text; reserve blank panels
Constraints: no GitHub logo imitation, no repository URL rendered by the model, no credentials, no personal data, no fake contributor count
Avoid: corporate stock photo, handshake, exaggerated community size, reward or income promises
```

建议后期叠字：

`开放之前，先把问题查清楚`

## 生成与验收

1. 先生成无文字底图。
2. 检查没有真实平台 Logo、个人资料和虚假数据。
3. 用本地排版脚本写入中文标题和日期。
4. 海报中若使用 AI 生成画面，发布时按平台要求添加 AI 生成内容标识。
5. 真实功能截图单独标注“演示/合成”，不与生图画面混淆。
