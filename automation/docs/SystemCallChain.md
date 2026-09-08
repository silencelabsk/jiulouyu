# 番茄免费小说 UI 自动化 —— 全量接口调用系统链路图

---

## 一、启动装配链路（FanqieRunner.main）

```
FanqieRunner.main(args)
│
├─[1] 命令行参数解析 (--doctor / --dry-run / --cycles / --pages / --config / --reset-progress)
│
├─[2] AutomationConfig(configPath)                    ← 加载 automation.properties + 命令行覆盖
│
├─[3] printDeviceChecklist()                          ← 打印手机侧 7 项设置核对清单
│
├─[4] ensureAppiumServerRunning()                     ← Appium Server 探活/启动
│   ├─ HttpClient → GET http://127.0.0.1:4723/status  ← 探活（isAppiumReady）
│   ├─ ProcessBuilder("appium --base-path /")          ← 未运行时后台启动
│   └─ 轮询 /status 直到 ready:true 或超时 30s
│
├─[5] EnvDoctor(config).checkAll()                    ← 环境自检（adb/Android_HOME/设备连接）
│
├─[6] LocatorRegistry()                               ← 加载 locators.properties 文案候选集
│   └─ dumpToConsole()                                ← 打印候选集确认无乱码
│
├─[7] createDriverFactory(config)                     ← 工厂选择（uiautomator2 / harmony_hdc）
│   └─ new AppiumUiAutomator2DriverFactory(config)
│
├─[8] driverFactory.create()                          ← 创建 Appium Session
│   ├─ new AndroidDriver(serverUrl, options)           ← UiAutomator2Options 构建
│   └─ driver.manage().timeouts().implicitlyWait(0)   ← 关闭隐式等待
│
├─[9] new RunLogger(driver, config)                   ← 日志器初始化
│   ├─ start()                                        ← 启动心跳保活线程（ScheduledExecutorService）
│   └─ addShutdownHook → logger.stop() + driverFactory.quit()  ← 优雅退出
│
├─[10] 装配 core 层组件
│   ├─ GestureSupport(driver, config)                 ← adb 路径探测 + 手势封装
│   ├─ StateDetector(driver, config, locators)        ← 状态识别器
│   │   └─ setLogger(logger)                          ← 注入 RunLogger 启用耗时上报
│   ├─ WaitSupport(driver, config, detector)          ← 统一等待原语
│   └─ ScreenLocator(driver, config, locators, gestures, logger) ← 智能定位器
│       └─ new OcrSupport(driver, config, gestures)   ← Tesseract OCR 初始化（可降级）
│
├─[11] DriverRegistry() + 注册所有 DriverAware 组件
│   ├─ register(gestures)
│   ├─ register(detector)
│   ├─ register(waitSupport)
│   ├─ register(logger)
│   └─ register(screenLocator)
│
├─[12] (dry-run 模式) → DryRunProbe(driver, config, ...).probe()
│
├─[13] 装配 page 层
│   ├─ BookshelfPage(driver, config, locators, gestures, logger, detector, waitSupport)
│   ├─ ReaderPage(driver, config, locators, gestures, logger, detector, waitSupport)
│   ├─ AdFlowPage(driver, config, locators, gestures, logger, detector, waitSupport)
│   └─ 注入 ScreenLocator → bookshelfPage/readerPage/adFlowPage.setScreenLocator()
│
├─[14] 装配 task 层
│   ├─ RecoveryHandler(driver, config, locators, detector, waitSupport, gestures, logger, driverFactory, registry)
│   │   └─ setBookshelfPage(bookshelfPage)            ← 延迟注入避免循环依赖
│   ├─ AdWatchStateMachine(driver, config, locators, detector, waitSupport, gestures, logger, adFlowPage, readerPage, bookshelfPage, recoveryHandler)
│   │   ├─ loadProgress()                             ← 从 progress.properties 恢复进度
│   │   └─ buildTransitionTable()                     ← 构建状态转移表
│   └─ FanqieAdWatchTask(driver, config, locators, detector, waitSupport, gestures, logger, bookshelfPage, readerPage, adFlowPage, stateMachine, recoveryHandler)
│
├─[15] 注册剩余 DriverAware 组件 → registry.register(page/task/recovery)
│
└─[16] task.run()                                     ← 进入主任务执行
```

---

## 二、主任务执行链路（FanqieAdWatchTask.run）

```
FanqieAdWatchTask.run()
│
├─[阶段1] launchAndNavigateToShelf()                  ← 启动 App 并导航到书架
│   ├─ gestures.restartApp(config.appPackage())
│   │   ├─ gestures.terminateApp(packageName)         → adb shell am force-stop
│   │   └─ gestures.activateApp(packageName)          → adb shell monkey -p ... LAUNCHER
│   ├─ 循环检测 activity（gestures.getCurrentActivity()）
│   │   └─ gestures.getCurrentActivity()              → adb shell dumpsys activity activities | grep mResumedActivity
│   ├─ bookshelfPage.switchToShelfTab()               ← 确保在书架 Tab
│   │   ├─ gestures.getScreenSize()                   → driver.manage().window().getSize()
│   │   ├─ gestures.tapAtPixel(x, y)                  → adb shell input tap
│   │   └─ gestures.getCurrentActivity()              → 验证到达书架
│   └─ (中间状态处理)
│       ├─ 开屏广告 → gestures.tapAtPixel(右上角)     → 坐标关闭
│       └─ 通用弹窗 → driver.navigate().back()        → 系统返回
│
├─[阶段2] 打开书籍进入阅读页
│   ├─ bookshelfPage.openAnyBook() / openNextBook(triedIds)
│   │   ├─ closeFilterPageIfNeeded()                  → gestures.getCurrentActivity() + driver.navigate().back()
│   │   ├─ gestures.getCurrentActivity()              → 确认在书架
│   │   └─ clickBookAtGridIndex(gridIndex)            → gestures.tapAtPixel(计算坐标)
│   ├─ bookshelfPage.swipeLeftToRead()                ← 从详情页左滑进入阅读器
│   │   ├─ gestures.swipeLeft()                       → adb shell input swipe
│   │   └─ gestures.getCurrentActivity()              → 验证进入 Reader
│   └─ (短剧/视频页过滤) → backHomeAndReopenShelf()   → 循环 driver.navigate().back()
│
├─[阶段3] 外层循环 (outerCycles 次)
│   ├─ [熔断检查]
│   │   ├─ stateMachine.getEarnedMinutes() >= targetFreeMinutes → 退出
│   │   ├─ stateMachine.getTotalAdRounds() >= maxTotalAdRounds → 退出
│   │   ├─ 墙钟时间预算耗尽 → 退出
│   │   └─ stateMachine.isDailyQuotaExhausted() → 退出
│   │
│   ├─[内层] 翻页循环 (pagesPerCycle 次)
│   │   ├─ gestures.turnPageNext()                    ← 翻页手势
│   │   │   ├─ (swipe 策略) → gestures.swipeLeft()    → adb shell input swipe
│   │   │   └─ (tap 策略) → gestures.tapAtRatio()     → adb shell input tap
│   │   ├─ logger.incrementPageCount()
│   │   ├─ gestures.getCurrentActivity()              → 检查是否仍在阅读器
│   │   │
│   │   ├─ (仍在 Reader) → continue
│   │   ├─ (广告 Activity) → driver.navigate().back() / backHomeAndReopenShelf()
│   │   ├─ (视频/短剧页) → backHomeAndReopenShelf()
│   │   └─ (其他异常) → 三级恢复
│   │       ├─ L1: gestures.tapAtPixel(左上角返回箭头)
│   │       ├─ L2: driver.navigate().back()
│   │       └─ L3: backHomeAndReopenShelf() + reopenFirstBook()
│   │
│   └─[翻页结束后] 点击底部广告入口
│       ├─ gestures.tapAtRatio(0.50, 0.95)            → 点击底部中央
│       ├─ gestures.getCurrentActivity()              → 检查是否进入广告
│       ├─ stateMachine.runAdSubLoop()                ← ★ 进入广告子循环（见第三节）
│       ├─ ensureBackToReader()                       → driver.navigate().back()
│       └─ rotateBookIfNeeded()                       ← D5 多本书轮换
│           ├─ stateMachine.getCurrentEntryRound() >= maxAdRoundsPerEntry → 触发轮换
│           └─ rotateToNextBook()
│               ├─ stateMachine.markBookUsed(bookId)
│               ├─ navigateBackToShelf()              → 循环 driver.navigate().back() + switchToShelfTab()
│               ├─ bookshelfPage.openNextBook(usedBooks) → 打开下一本未使用的书
│               └─ (失败兜底) → gestures.swipeDown() + bookshelfPage.openAnyBook()
│
└─[收尾] 输出统计汇总
    ├─ stateMachine.getEarnedMinutes()
    └─ stateMachine.getTotalAdRounds()
```

---

## 三、广告子循环链路（AdWatchStateMachine.runAdSubLoop）

```
AdWatchStateMachine.runAdSubLoop()
│
├─[循环条件] shouldExitSubLoop() == false
│   ├─ earnedMinutes >= targetFreeMinutes             → 退出（目标达成）
│   ├─ currentEntryRound >= maxAdRoundsPerEntry       → 退出（单入口上限）
│   ├─ totalAdRounds >= maxTotalAdRounds              → 退出（全局上限）
│   └─ 墙钟时间预算耗尽                               → 退出
│
├─[每轮循环]
│   ├─ detector.tick()                                ← 获取 UI 快照
│   │   ├─ driver.getPageSource()                     → Appium RPC（20s 超时 + 重试）
│   │   ├─ driver.manage().window().getSize()         → 屏幕尺寸（缓存 + 自愈）
│   │   └─ new UiSnapshot(xml, width, height)         → 解析 XML 为内存节点树
│   │
│   ├─ detector.detect(snapshot)                      ← 纯内存状态识别（6 级优先级）
│   │   ├─ P1: detectByResourceId()                   → 书架/阅读页/阅读菜单锚点
│   │   ├─ P2: detectByText()                         → 广告/弹窗/章节/书架文案
│   │   ├─ P3: detectByContentDesc()                  → 关闭按钮 desc
│   │   ├─ P4: detectAppLaunching()                   → 节点极少 + 无大面积
│   │   ├─ P5: detectByGeometry()                     → 右上角关闭按钮 / 大面积视频
│   │   └─ P6: UNKNOWN                                → 兜底
│   │
│   ├─ [每日配额检查] matchesDailyQuotaExhausted(snapshot)
│   │   ├─ snapshot.findByTextContains(dailyQuotaExhaustedTexts)
│   │   └─ + 弹窗上下文约束（commonDismissTexts / adCloseTexts）
│   │
│   ├─ [滞留熔断] dwellMs > maxStateDwellMs
│   │   └─ recoveryHandler.recover(currentState)      ← 见第五节
│   │
│   └─[转移表执行] transitionTable.get(detectedState).handle()
│       │
│       ├─ APP_LAUNCHING →
│       │   └─ waitSupport.untilState(BOOKSHELF, SPLASH_AD, COMMON_POPUP)
│       │       └─ 循环: detector.tick() + detector.detect()
│       │
│       ├─ SPLASH_AD →
│       │   ├─ adFlowPage.clickByTextCandidates(splashSkipTexts)
│       │   │   └─ snapshot.findByTextContains() → clickNode() → gestures.tapAtPixel()
│       │   └─ (未命中) → adFlowPage.clickClose()     ← 见 clickClose 链
│       │
│       ├─ BOOKSHELF →
│       │   ├─ bookshelfPage.openNextBook(usedBooks)
│       │   ├─ (兜底) bookshelfPage.openAnyBook()
│       │   └─ markBookUsed(bookId)
│       │
│       ├─ AD_ENTRY_PROMPT →                           ← 点击底部广告入口
│       │   ├─ readerPage.clickBottomRewardEntry()
│       │   │   ├─ resolve(adEntrySpec, snapshot)      ← BasePage L1 降级链
│       │   │   │   ├─ L1-T: snapshot.findByTextContains() → arbitrate() → clickNode()
│       │   │   │   ├─ L1-D: snapshot.findByContentDescContains() → arbitrate() → clickNode()
│       │   │   │   └─ (未命中) → Optional.empty()
│       │   │   └─ (补充) snapshot.findByTextContains(adEntryTexts) → 取 y 最大者
│       │   └─ waitSupport.untilState(AD_CONFIRM_DIALOG, AD_VIDEO_PLAYING, ...)
│       │
│       ├─ AD_CONFIRM_DIALOG →                          ← 点击「观看广告」
│       │   ├─ adFlowPage.clickWatchAd()               ← 仅 L1（防误点）
│       │   │   ├─ resolve(adWatchSpec, snapshot)
│       │   │   ├─ (补充) clickByTextCandidates(adWatchTexts)
│       │   │   └─ ★ 不执行 L2 几何兜底
│       │   ├─ roundId.incrementAndGet()               ← M3 奖励计数幂等
│       │   └─ waitSupport.untilState(AD_VIDEO_PLAYING, AD_CLOSE_READY, ...)
│       │
│       ├─ AD_VIDEO_PLAYING →                           ← 等待倒计时（不做任何点击！）
│       │   ├─ roundId.incrementAndGet()
│       │   └─ adFlowPage.waitCountdownFinished()
│       │       └─ 循环: detector.tick() + detector.detect()
│       │           ├─ (AD_CLOSE_READY) → return
│       │           ├─ (非 AD_VIDEO_PLAYING) → return
│       │           └─ Thread.sleep(sparseInterval)    ← 稀疏轮询
│       │
│       ├─ AD_CLOSE_READY →                             ← 点击「关闭」（全链路降级）
│       │   ├─ adFlowPage.clickClose()                 ← ★ L1→L2→L3→ScreenLocator→L4
│       │   │   ├─ L1: resolve(adCloseSpec, snapshot)
│       │   │   │   ├─ L1-T: findByTextContains(adCloseTexts)
│       │   │   │   └─ L1-D: findByContentDescContains(adCloseDescs)
│       │   │   ├─ L1 补充: clickByTextCandidates + clickByContentDescCandidates
│       │   │   ├─ L2: resolveByGeometry(adCloseSpec)  ← 右上角区域 clickable 节点
│       │   │   │   └─ snapshot.findClickableInRegion(0.8, 1.0, 0.0, 0.2, 0.15)
│       │   │   ├─ L3: clickByTimeoutFallback()        ← 超时后强制执行 L2
│       │   │   ├─ L3.5: ScreenLocator 补充
│       │   │   │   ├─ findAndClickByCandidates(关闭候选文案)
│       │   │   │   │   └─ locateByTextInUiTree() → gestureSupport.tapAtPixel()
│       │   │   │   │   └─ (降级) ocrSupport.findAndClick() → captureScreen() → Tesseract
│       │   │   │   └─ findAndClickInRegion(0.85, 1.0, 0.0, 0.15) ← 右上角区域点击
│       │   │   └─ L4: systemBack() → driver.navigate().back()
│       │   │       └─ (无效) → systemRestartApp() → gestures.restartApp()
│       │   ├─ currentEntryRound.incrementAndGet()
│       │   ├─ totalAdRounds.incrementAndGet()
│       │   └─ waitSupport.untilState(AD_CONTINUE_PROMPT, AD_REWARD_GRANTED, ...)
│       │
│       ├─ AD_CONTINUE_PROMPT →                         ← 继续/关闭弹窗
│       │   ├─ (继续) adFlowPage.clickContinueGetFreeTime()
│       │   │   ├─ L1: resolve(adContinueSpec, snapshot)
│       │   │   ├─ L1 补充: clickByTextCandidates(adContinueTexts)
│       │   │   ├─ ScreenLocator 补充
│       │   │   │   └─ findAndClickByCandidates(扩展候选集)
│       │   │   └─ ★ 不执行 L2 几何兜底
│       │   └─ (不继续) adFlowPage.clickDismissContinuePrompt()
│       │       ├─ clickByTextCandidates(adCloseTexts)
│       │       ├─ clickByTextCandidates(commonDismissTexts)
│       │       └─ resolveByGeometry(adCloseSpec)
│       │
│       ├─ AD_REWARD_GRANTED →                          ← 读取奖励 + 关闭弹窗
│       │   ├─ adFlowPage.readGainedMinutes(snapshot)  ← M4 奖励语义提取
│       │   │   ├─ 逐节点扫描：跳过非奖励语义（剩余/仅剩）
│       │   │   ├─ 仅命中奖励关键词（获得/领取/奖励）的节点提取数字
│       │   │   └─ hasRewardSemanticSibling()          ← 兼容标题与数值分离
│       │   ├─ earnedMinutes.addAndGet(gained)
│       │   ├─ (兜底) config.rewardFallbackMinutes()
│       │   ├─ adFlowPage.clickClose()                 ← 关闭奖励弹窗
│       │   └─ waitSupport.untilState(READER, AD_CONTINUE_PROMPT, ...)
│       │
│       ├─ CHAPTER_END →
│       │   └─ readerPage.goNextChapter()
│       │       ├─ clickNextChapterButton(snapshot)
│       │       │   ├─ resolve(chapterSpec, snapshot)   ← BasePage L1
│       │       │   └─ (补充) clickByTextCandidates(chapterNextTexts)
│       │       └─ (翻页直到 CHAPTER_END) → turnPage() → waitSupport.untilState(...)
│       │
│       ├─ COMMON_POPUP →
│       │   └─ recoveryHandler.dismissPopup(snapshot)  ← 见第五节
│       │
│       ├─ READER_MENU →
│       │   ├─ gestures.tapAtRatio(0.85, 0.50)        ← 点击右侧空白区收起菜单
│       │   └─ (未收起) → driver.navigate().back()
│       │
│       └─ UNKNOWN / RECOVERY_NEEDED →
│           └─ recoveryHandler.recover(state)          ← 五级自愈
│
└─ saveProgress()                                      ← 持久化到 progress.properties
```

---

## 四、BasePage 四级定位降级链（核心定位引擎）

```
BasePage.clickBySpec(spec, snapshot)
│
├─ resolve(spec, snapshot)                             ← L1 语义属性匹配
│   ├─ L1-T: extractTextCandidatesFromSpec(spec)
│   │   └─ extractTextFromXPath(primary + fallbackBys) → 解析 contains(@text,'...')
│   │   └─ snapshot.findByTextContains(candidates)
│   │       └─ 遍历 UiNode 列表，text.contains(candidate)
│   ├─ L1-D: extractDescCandidatesFromSpec(spec)
│   │   └─ extractDescFromXPath(primary + fallbackBys) → 解析 contains(@content-desc,'...')
│   │   └─ snapshot.findByContentDescContains(candidates)
│   └─ arbitrate(hits, boundsHint, snapshot)           ← 多命中裁决
│       ├─ HINT_BOTTOM (y>0.8) → 取 y 最大者
│       ├─ HINT_TOP_RIGHT (x>0.8,y<0.2) → 取右上角区域节点
│       ├─ HINT_CENTER → 取最接近屏幕中心者
│       └─ (无 hint) → 取面积最小的 clickable 节点
│
├─ clickNode(node, snapshot)                           ← 执行点击
│   ├─ (节点不可点击) → snapshot.nearestClickableAncestor(node)
│   └─ gestures.tapAtPixel(centerX, centerY)           → adb shell input tap
│
├─ resolveByGeometry(spec, snapshot)                   ← L2 结构化几何推断
│   ├─ ★ 前置检查: spec.isAllowGeometryFallback()
│   ├─ parseBoundsHintRegion(hint) → 比例区域
│   ├─ snapshot.findClickableInRegion(xMin, xMax, yMin, yMax, 0.15)
│   └─ 取面积最小的 clickable 节点
│
├─ clickByTimeoutFallback(spec, snapshot, elapsedMs)   ← L3 时序兜底
│   ├─ 检查 elapsedMs >= adVideoTimeoutMs
│   └─ 强制执行 resolveByGeometry()                    ← 本质是超时后触发 L2
│
└─ systemBack() / systemRestartApp()                   ← L4 系统兜底
    ├─ driver.navigate().back()                        ← Appium RPC
    └─ gestures.restartApp(packageName)                ← adb force-stop + monkey
```

---

## 五、五级自愈链路（RecoveryHandler.recover）

```
RecoveryHandler.recover(currentState)
│
├─[第1级] COMMON_POPUP 弹窗关闭
│   └─ dismissPopup(snapshot)
│       ├─ 遍历 locators.commonDismissTexts()（由弱到强）
│       │   ├─ snapshot.findByTextContains([text])
│       │   ├─ (不可点击) → snapshot.nearestClickableAncestor(node)
│       │   └─ gestures.tapAtPixel(centerX, centerY)
│       └─ (文案未命中) → snapshot.findByContentDescContains([desc])
│           └─ gestures.tapAtPixel(centerX, centerY)
│
├─[第2级] 逐级 back()
│   └─ 循环 maxRecoveryRetry 次
│       ├─ driver.navigate().back()                    ← Appium RPC
│       ├─ detector.tick() + detector.detect()         ← 确认状态
│       ├─ (READER/BOOKSHELF) → 恢复成功
│       └─ (COMMON_POPUP) → dismissPopup(snapshot)
│
├─[第3级] 重启 App
│   ├─ gestures.restartApp(packageName)                ← adb force-stop + monkey
│   └─ waitSupport.untilState(BOOKSHELF, SPLASH_AD, COMMON_POPUP)
│       ├─ (SPLASH_AD) → clickByTexts(adCloseTexts)
│       └─ (COMMON_POPUP) → dismissPopup(snapshot)
│
├─[第4级] 主动导航到书架
│   └─ bookshelfPage.switchToShelfTab()
│       └─ gestures.tapAtPixel(书架Tab坐标)
│
├─[第5级] 重建 Session
│   ├─ driverFactory.recreate()                        ← quit() + new AndroidDriver()
│   ├─ registry.refreshAll(newDriver)                  ← 刷新所有 DriverAware 组件
│   │   └─ 遍历 awares → aware.refreshDriver(newDriver)
│   ├─ gestures.activateApp(packageName)
│   └─ waitSupport.untilState(BOOKSHELF, COMMON_POPUP)
│
└─[第6级] 落盘诊断
    ├─ logger.captureSnapshot("recovery-failed")       ← 截图 + XML 快照落盘
    └─ logger.markSessionDead()
```

---

## 六、DriverRegistry 刷新链路（Session 重建后）

```
DriverRegistry.refreshAll(newDriver)
│
├─ GestureSupport.refreshDriver(newDriver)
│   └─ invalidateScreenSizeCache()
│
├─ StateDetector.refreshDriver(newDriver)
│   ├─ invalidateScreenSizeCache()
│   └─ cachedSnapshot = null
│
├─ WaitSupport.refreshDriver(newDriver)
│
├─ RunLogger.refreshDriver(newDriver)
│
├─ ScreenLocator.refreshDriver(newDriver)
│   └─ OcrSupport.refreshDriver(newDriver)
│       └─ GestureSupport.refreshDriver(newDriver)
│
├─ BookshelfPage.refreshDriver(newDriver)              ← 继承 BasePage
├─ ReaderPage.refreshDriver(newDriver)
├─ AdFlowPage.refreshDriver(newDriver)
│
├─ AdWatchStateMachine.refreshDriver(newDriver)
├─ FanqieAdWatchTask.refreshDriver(newDriver)
└─ RecoveryHandler.refreshDriver(newDriver)
```

---

## 七、ScreenLocator 定位链路（UI 层级 + OCR 双降级）

```
ScreenLocator.findAndClickByText(targetText)
│
├─[策略1] locateByTextInUiTree(targetText)             ← UI 层级分析
│   ├─ driver.getPageSource()                          ← Appium RPC
│   ├─ new UiSnapshot(xml, w, h)
│   ├─ snapshot.findByTextContains([targetText])
│   ├─ snapshot.findByContentDescContains([targetText])
│   ├─ (节点 clickable) → 取中心坐标
│   └─ (节点不可点击) → snapshot.nearestClickableAncestor(node)
│
├─[策略2] OcrSupport.findAndClick(targetText)          ← OCR 识别（降级）
│   ├─ captureScreen()                                 → adb exec-out screencap -p
│   ├─ Tesseract.getWords(screenshot, Word level)      → OCR 逐词识别
│   ├─ 遍历 Word → 匹配 targetText
│   └─ gestureSupport.tapAtPixel(centerX, centerY)     → adb shell input tap
│
└─ (未找到) → return false

ScreenLocator.findAndClickInRegion(xRatioMin, xRatioMax, yRatioMin, yRatioMax)
│
├─ findClickableInRegion()
│   ├─ driver.getPageSource()
│   └─ snapshot.findClickableInRegion(...)
├─ (有可点击节点) → 取面积最小者 → gestureSupport.tapAtPixel()
└─ (无可点击节点) → 点击区域中心 → gestureSupport.tapAtPixel()
```

---

## 八、底层系统调用汇总

| 系统调用 | 调用路径 | 用途 |
|----------|----------|------|
| `adb shell input tap x y` | GestureSupport.tapAtPixel → adbShell | 坐标点击 |
| `adb shell input swipe x1 y1 x2 y2 ms` | GestureSupport.executeSwipeGesture → adbShell | 滑动手势 |
| `adb shell am force-stop pkg` | GestureSupport.terminateApp → adbShell | 终止 App |
| `adb shell monkey -p pkg ... 1` | GestureSupport.activateApp → adbShell | 启动 App |
| `adb shell dumpsys activity activities` | GestureSupport.getCurrentActivity → adbShell | Activity 检测 |
| `adb exec-out screencap -p` | OcrSupport.captureScreen | OCR 截图 |
| `driver.getPageSource()` | StateDetector.tick / ScreenLocator | 获取 UI 树 XML |
| `driver.manage().window().getSize()` | GestureSupport / StateDetector | 获取屏幕尺寸 |
| `driver.navigate().back()` | BasePage.systemBack / RecoveryHandler | 系统返回 |
| `new AndroidDriver(url, options)` | DriverFactory.create | 创建 Appium Session |
| `HttpClient → GET /status` | FanqieRunner.isAppiumReady | Appium 探活 |
| `Tesseract.getWords(image)` | OcrSupport.recognizeScreen | OCR 文字识别 |
| `Properties.load / store` | AdWatchStateMachine.loadProgress / saveProgress | 进度持久化 |
