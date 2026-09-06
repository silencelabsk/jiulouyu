---
kind: build_system
name: Maven + Shade Fat-JAR 构建与 JUnit 5 测试体系
category: build_system
scope:
    - '**'
source_files:
    - automation/pom.xml
    - automation/dependency-reduced-pom.xml
    - Student.iml
---

## 1. 构建系统与工具

仓库采用 **Maven**（`automation/pom.xml`）作为唯一的构建、依赖管理与打包系统。根目录的 `Student.iml` 是 IntelliJ IDEA 模块描述，仅声明 `src/` 为源码根并继承 JDK，不参与实际编译流程；真正的工程位于子目录 `automation/`。

- Java 版本：通过 `maven.compiler.release=11` 与 `maven-compiler-plugin 3.13.0` 强制以 Java 11 编译，注释明确需与 `.idea/misc.xml` 的 `languageLevel="JDK_11"` 保持一致。
- 编码：全局设置 `project.build.sourceEncoding=UTF-8` 与 `maven.compiler.encoding=UTF-8`，并在 Surefire 中追加 `-Dfile.encoding=UTF-8`，原因是源码含大量中文文案，Windows 默认 GBK 会导致 text 匹配失效。
- 镜像源：配置阿里云 Maven 公共仓库（`aliyun-public`），禁用 snapshot，加速国内下载。

## 2. 关键构建文件

| 文件 | 作用 |
|---|---|
| `automation/pom.xml` | 唯一构建定义：依赖管理、插件、打包 |
| `dependency-reduced-pom.xml` | shade 插件生成的精简 POM（用于发布时保留） |
| `Student.iml` | IDEA 根模块，仅声明 `src/` 源码，无依赖 |
| `automation/.gitignore` | 忽略 `target/`、日志等构建产物 |

## 3. 架构与约定

### 3.1 依赖版本锁定策略
- 使用 `selenium-bom 4.34.0` 通过 `<scope>import</scope>` 覆盖传递依赖，确保 `java-client 9.5.0` 与 Selenium 的官方兼容矩阵严格对应。
- 注释明确警告：Selenium 客户端不遵循语义化版本，patch 升级也可能引入破坏性变更，因此必须 pin BOM。

### 3.2 依赖范围约束
- Appium Java Client 与 SLF4J Simple 使用 `compile` scope，因为入口是 `main()` 而非 JUnit，若放 `test` 则 `mvn exec:java` 与 fat-jar 无法访问。
- JUnit Jupiter 使用 `test` scope，绝不进入 fat-jar 与运行时 classpath。

### 3.3 测试体系
- 框架：**JUnit 5 (Jupiter) 5.10.2** + **Maven Surefire 3.2.5**。
- 所有单元测试均为**离线回放**：基于 `src/test/resources/fixtures/*.xml` 中的 UI 快照 XML 与手写测试替身，不连接真机、不启动 Appium session，因此无需 Mockito 等重型 mock 框架。
- 测试运行命令：`mvn test`。

### 3.4 可执行产物
- 通过 `maven-exec-plugin 3.4.1` 暴露 `mvn exec:java`，主类为 `com.fanqie.auto.FanqieRunner`。
- 通过 `maven-shade-plugin 3.6.0` 在 `package` 阶段生成 fat-jar，包含所有依赖，便于长任务（1–3 小时）脱离 IDEA 运行。
- Shade 配置合并 `META-INF/services`（Selenium SPI 必需），并排除签名文件（`.SF`、`.DSA`、`.RSA`）。

## 4. 约定与约束

- **构建入口**：所有构建、测试、打包均通过 `mvn` 生命周期完成，仓库内不存在 Makefile、Gradle、Shell 脚本或 Dockerfile。
- **Java 版本一致性**：编译器 release 11 必须与 IDE 语言级别一致，否则编译失败。
- **UTF-8 强制**：从源码到测试输出全部强制 UTF-8，避免 Windows 环境乱码导致 UI 文本匹配失败。
- **依赖不可漂移**：Selenium 与 Appium Java Client 的版本对通过 BOM 严格 pin，禁止由 Maven 解析最新 patch 版本。
- **测试隔离**：测试依赖仅限 `test` scope，fat-jar 与生产运行不包含任何测试库。
- **无 CI/CD**：仓库未包含 GitHub Actions、Jenkinsfile、Dockerfile 等持续集成配置；构建仅在本地 Maven + IDEA 环境下进行。
- **IDEA 原生工程**：根级 `Student.iml` 仅将 `src/` 标记为源码根，实际业务代码位于 `automation/src/main/java`，IDEA 通过导入 `automation/` 子模块工作。