---
kind: dependency_management
name: Maven 依赖管理（Appium 2 + Selenium BOM 版本锁定）
category: dependency_management
scope:
    - '**'
source_files:
    - automation/pom.xml
    - automation/dependency-reduced-pom.xml
---

## 1. 使用的系统/方法

本仓库采用 **Maven** 作为唯一的依赖管理与构建工具，工程位于 `automation/` 子模块下，使用标准 Maven 目录结构（`src/main/java`、`src/test/java`、`src/main/resources`），并通过 `pom.xml` 声明所有第三方库。

## 2. 关键文件与包

- `automation/pom.xml`：唯一依赖声明入口，集中定义 groupId/artifactId/version、properties 版本矩阵、dependencyManagement、repositories、plugins。
- `automation/dependency-reduced-pom.xml`：maven-shade-plugin 打包后生成的精简 POM（由插件自动生成，非手工维护）。
- 根目录 `Student.iml` 为 IntelliJ IDEA 项目元数据，不参与依赖解析。

核心依赖（按作用域分类）：
- **运行时依赖**：`io.appium:java-client`（Appium Java Client）、`org.slf4j:slf4j-simple`（日志实现，因 java-client 内部通过 SLF4J 输出日志）。
- **测试依赖**：`org.junit.jupiter:junit-jupiter`（JUnit 5 Jupiter 聚合依赖，含 api/params/engine）。
- **传递依赖版本锁定**：通过 `org.seleniumhq.selenium:selenium-bom`（import scope）统一控制 Selenium 及其子模块版本。

## 3. 架构与设计约定

### 3.1 版本矩阵集中化
所有外部库版本号集中在 `<properties>` 中定义（如 `appium.java-client.version=9.5.0`、`selenium.bom.version=4.34.0`、`slf4j.version=2.0.16`、`junit.jupiter.version=5.10.2`、`maven.surefire.version=3.2.5`），引用处一律通过 `${...}` 占位符，避免硬编码版本号散落各处。

### 3.2 强制版本锁定（BOM + 注释约束）
- 通过 `dependencyManagement` 引入 `selenium-bom` 并以 `import` scope 覆盖传递依赖版本，确保 Selenium 系列 jar 始终一致。
- 注释明确说明：`java-client` 的 POM 用开放区间 `[x, 5.0)` 声明 selenium 依赖，官方警告不遵循语义化版本，因此必须 pin 具体版本，否则某天重建工程可能拉到不兼容 patch 版导致 `NoSuchMethodError`。
- 同时显式声明 `appium.java-client.version` 与 `selenium.bom.version` 的对应关系，并标注为“官方兼容矩阵唯一一对一映射”。

### 3.3 私有镜像源
在 `<repositories>` 中配置阿里云 Maven 公共镜像 `https://maven.aliyun.com/repository/public`，关闭 snapshot 下载，仅启用 releases。该镜像用于加速国内拉取速度，替代默认 Maven Central。

### 3.4 可执行产物策略
使用 `maven-shade-plugin` 将依赖打入 fat-jar，并通过 `ManifestResourceTransformer` 指定主类 `com.fanqie.auto.FanqieRunner`，同时使用 `ServicesResourceTransformer` 合并 `META-INF/services` SPI 文件（Selenium 依赖此机制）。打包时排除签名文件（`.SF`、`.DSA`、`.RSA`）以避免校验失败。这使得长耗时任务（1-3 小时）可通过 `mvn exec:java` 或独立运行 fat-jar 脱离 IDE。

### 3.5 编译与测试环境约束
- 编译器 release 固定为 11，与 `.idea/misc.xml` 中的 `languageLevel="JDK_11"` 保持一致。
- 源码与测试均强制 UTF-8 编码（`project.build.sourceEncoding`、`maven.compiler.encoding`、Surefire 的 `-Dfile.encoding=UTF-8`），原因是代码包含大量中文字符串，Windows 默认 GBK 会导致乱码并使 text 匹配失效。
- JUnit 5 通过 Surefire 3.x 原生支持 JUnit Platform 运行；测试均为离线回放（fixture XML + 手写替身），无需 Mockito 等重型 mock 框架。

## 4. 约定与约束

- **版本变更必须修改 properties**：新增或升级依赖时，应在 `<properties>` 中更新版本号，禁止在 `<dependencies>` 中直接写死版本号。
- **Selenium 版本必须通过 BOM 锁定**：不得单独声明 selenium 子模块的版本号，统一由 `selenium-bom` 管理。
- **运行时依赖不得放在 test scope**：注释强调入口是 `main()` 而非 JUnit，若将 Appium/SLF4J 放入 test scope，`mvn exec:java` 与 fat-jar 将无法访问这些类。
- **单元测试依赖严格限定为 test scope**：JUnit 5 仅在测试期可见，不进入 fat-jar 与 exec 运行时 classpath。
- **无 vendoring / 无本地仓库快照**：未使用 `lib/` 目录存放 jar，也未配置私有 Nexus/Artifactory；依赖全部通过 Maven 远程仓库（阿里云镜像）拉取。
- **无 lockfile**：仓库未提交 `*.lock`、`*.lock.json`、`*.lock.yaml` 等锁定文件；依赖解析结果由 Maven 本地仓库缓存保证，但未被纳入版本控制。
- **无多模块聚合**：当前仓库仅有单一 Maven 模块（`automation/`），根目录的 `src/` 仅为教学 demo，不参与 Maven 构建。