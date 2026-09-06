---
kind: external_dependency
name: 代码托管远程仓库（GitHub）
slug: github
category: external_dependency
category_hints:
    - vendor_identity
    - client_constraint
scope:
    - '**'
---

### 项目中的角色
工程的 Git 远端仓库为 GitHub 上的 `silencelabsk/jiulouyu`，默认分支为 `main`，通过 HTTPS 协议推送。

### 集成方式
- `origin` remote 地址：`https://github.com/silencelabsk/jiulouyu.git`
- 提交范围由 `automation/.gitignore` 排除 `logs/` 与 `target/`，仅提交源码与配置文件。

### 客户端约束
- Windows PowerShell 环境下多命令需用 `;` 分隔而非 `&&`。
- 推送失败常见原因为代理未启动或端口 7890 未监听。