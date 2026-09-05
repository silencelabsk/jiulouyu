package com.fanqie.auto.core;

/**
 * 页面状态枚举：状态机的核心驱动。
 * <p>
 * 设计原则：用「当前处于哪个页面状态」驱动决策，而非线性脚本假设步骤顺序。
 * 任意状态跳变都能被转移表吸收，任意失败都能自愈回到书架锚点。
 * <p>
 * 每个枚举值带中文描述，便于日志可读。
 */
public enum PageState {

    /** 无法识别的页面（兜底状态，可能是新弹窗/未知界面） */
    UNKNOWN("未知页面"),

    /** App 正在启动（冷启动 + Splash 加载期间） */
    APP_LAUNCHING("App启动中"),

    /** 开屏广告（冷启动后的全屏广告，需等待或关闭） */
    SPLASH_AD("开屏广告"),

    /** 书架页面（底部 Tab 可见、书籍列表可见） */
    BOOKSHELF("书架页"),

    /** 阅读页面（正文显示中，无菜单/弹窗覆盖） */
    READER("阅读页"),

    /** 阅读页菜单展开状态（点击屏幕中间区域后弹出的工具栏） */
    READER_MENU("阅读页菜单"),

    /** 底部广告入口提示（「观看视频获取免广告时长」浮层可见） */
    AD_ENTRY_PROMPT("广告入口提示"),

    /** 广告确认弹窗（「观看广告」按钮可见） */
    AD_CONFIRM_DIALOG("广告确认弹窗"),

    /** 广告视频播放中（激励视频正在播放，倒计时进行中） */
    AD_VIDEO_PLAYING("广告视频播放中"),

    /** 广告视频播放完毕，关闭按钮已可点击 */
    AD_CLOSE_READY("广告可关闭"),

    /** 「继续获取免费时长」提示弹窗（关闭广告后的二次确认） */
    AD_CONTINUE_PROMPT("继续获取时长提示"),

    /** 奖励已发放（显示「已获得 N 分钟免广告时长」） */
    AD_REWARD_GRANTED("奖励已发放"),

    /** 章节末尾（「下一章」按钮可见） */
    CHAPTER_END("章节末尾"),

    /** 通用弹窗（青少年模式/权限/登录/更新提示/网络异常等非广告弹窗） */
    COMMON_POPUP("通用弹窗"),

    /** 需要恢复（连续错误超限、session 断开等异常情况） */
    RECOVERY_NEEDED("需要恢复");

    private final String description;

    PageState(String description) {
        this.description = description;
    }

    /** 中文描述，用于日志输出 */
    public String getDescription() {
        return description;
    }

    /** 是否属于广告流程中的状态 */
    public boolean isAdFlowState() {
        return this == AD_ENTRY_PROMPT || this == AD_CONFIRM_DIALOG ||
                this == AD_VIDEO_PLAYING || this == AD_CLOSE_READY ||
                this == AD_CONTINUE_PROMPT || this == AD_REWARD_GRANTED;
    }

    /** 是否属于需要恢复/干预的异常状态 */
    public boolean isAbnormal() {
        return this == UNKNOWN || this == RECOVERY_NEEDED || this == COMMON_POPUP;
    }

    @Override
    public String toString() {
        return name() + "(" + description + ")";
    }
}
