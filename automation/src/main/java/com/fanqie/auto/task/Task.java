package com.fanqie.auto.task;

/**
 * 任务接口：便于未来扩展到其他 App / 其他任务而核心引擎零改动。
 * <p>
 * 设计为接口的理由：
 * - FanqieAdWatchTask 是当前唯一的实现，但未来可能需要扩展到其他 App（如七猫、起点）
 *   或其他任务类型（如签到、领券）。
 * - 核心引擎（core 层）不依赖具体任务实现，只通过此接口交互。
 * - FanqieRunner 装配时只需 Task task = new XXX(...); task.run();
 */
public interface Task {

    /**
     * 执行任务。
     *
     * @return true=任务正常完成, false=任务异常终止
     */
    boolean run();

    /**
     * 获取任务名称（用于日志输出）。
     */
    String getName();
}
