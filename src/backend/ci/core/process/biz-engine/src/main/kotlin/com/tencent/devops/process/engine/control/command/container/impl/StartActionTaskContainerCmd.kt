/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.process.engine.control.command.container.impl

import com.tencent.devops.common.event.dispatcher.pipeline.PipelineEventDispatcher
import com.tencent.devops.common.event.enums.ActionType
import com.tencent.devops.common.log.utils.BuildLogPrinter
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.common.pipeline.pojo.element.ElementPostInfo
import com.tencent.devops.common.pipeline.utils.BuildStatusSwitcher
import com.tencent.devops.common.service.utils.MessageCodeUtil
import com.tencent.devops.process.engine.common.VMUtils
import com.tencent.devops.process.engine.control.ControlUtils
import com.tencent.devops.process.engine.control.FastKillUtils
import com.tencent.devops.process.engine.control.command.CmdFlowState
import com.tencent.devops.process.engine.control.command.container.ContainerCmd
import com.tencent.devops.process.engine.control.command.container.ContainerContext
import com.tencent.devops.process.engine.pojo.PipelineBuildTask
import com.tencent.devops.process.engine.pojo.event.PipelineBuildAtomTaskEvent
import com.tencent.devops.process.engine.service.PipelineBuildDetailService
import com.tencent.devops.process.engine.service.PipelineRuntimeService
import com.tencent.devops.process.pojo.mq.PipelineBuildContainerEvent
import com.tencent.devops.process.service.PipelineTaskService
import com.tencent.devops.process.util.TaskUtils
import com.tencent.devops.store.pojo.common.ATOM_POST_EXECUTE_TIP
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class StartActionTaskContainerCmd(
    private val pipelineRuntimeService: PipelineRuntimeService,
    private val pipelineBuildDetailService: PipelineBuildDetailService,
    private val pipelineEventDispatcher: PipelineEventDispatcher,
    private val buildLogPrinter: BuildLogPrinter,
    private val pipelineTaskService: PipelineTaskService
) : ContainerCmd {

    companion object {
        private val LOG = LoggerFactory.getLogger(StartActionTaskContainerCmd::class.java)
    }

    override fun canExecute(commandContext: ContainerContext): Boolean {
        return commandContext.cmdFlowState == CmdFlowState.CONTINUE && !commandContext.buildStatus.isFinish()
    }

    override fun execute(commandContext: ContainerContext) {
        val actionType = commandContext.event.actionType
        commandContext.cmdFlowState = CmdFlowState.FINALLY
        when {
            ActionType.isStart(actionType) || ActionType.REFRESH == actionType || ActionType.isEnd(actionType) -> {
                if (!ActionType.isTerminate(actionType)) {
                    commandContext.buildStatus = BuildStatus.SUCCEED
                }
                val waitToDoTask = findTask(commandContext)
                if (waitToDoTask == null) { // 非fast kill的强制终止时到最后无任务，最终状态必定是FAILED
                    val fastKill = FastKillUtils.isFastKillCode(commandContext.event.errorCode)
                    if (!fastKill && ActionType.isTerminate(actionType) && !commandContext.buildStatus.isFailure()) {
                        commandContext.buildStatus = BuildStatus.FAILED
                    }
                    commandContext.latestSummary = "status=${commandContext.buildStatus}"
                } else {
                    sendTask(event = commandContext.event, task = waitToDoTask)
                }
            }
            else -> { // 未规定的类型，打回Stage处理
                commandContext.buildStatus = BuildStatus.UNKNOWN
                commandContext.latestSummary = "j(${commandContext.container.containerId}) unknown action: $actionType"
            }
        }
    }

    /**
     * 寻找能下发的任务:
     * 如遇到[BuildStatus.isRunning]的任务，如果没有出现错误，则本次不做任何处理，返回null, 使用[CmdFlowState.BREAK]中断后续执行
     * 如遇到[BuildStatus.isPause]需要暂停任务，则查找下一个要执行的插件任务，比如关机插件任务，暂停需要关机
     * 如遇到[BuildStatus.isFailure]失败任务，检查是否开启了「失败继续」,未开启则会把容器标识为失败，继续寻找下一个可以执行的任务
     * 如遇到[BuildStatus.isReadyToRun]待执行任务，则检查是否可以执行，
     *  包括「是否条件跳过」「当前是否构建机启动失败」「Post Action检查」等等，通过方能成为待执行的任务
     */
    private fun findTask(containerContext: ContainerContext): PipelineBuildTask? {
        var toDoTask: PipelineBuildTask? = null
        var hasFailedTaskInSuccessContainer = false
        var needTerminate = isTerminate(containerContext) // 是否终止类型
        var breakFlag = false

        for (t in containerContext.containerTasks) {
            // 此处pause状态由构建机[PipelineVMBuildService.claim]认领任务遇到需要暂停任务时更新为PAUSE。
            if (t.status.isPause()) { // 若为暂停，则要确保拿到的任务为stopVM-关机或者空任务发送next stage任务
                toDoTask = findNextTaskAfterPause(containerContext, currentTask = t)
                breakFlag = toDoTask == null
            } else if (t.status.isRunning()) {
                breakFlag = ActionType.isStart(containerContext.event.actionType)
                toDoTask = findRunningTask(containerContext, currentTask = t)
            } else if (t.status.isFailure() || t.status.isCancel()) {
                // 当前任务已经失败or取消，并且没有设置[失败继续]的， 设置给容器最终FAILED状态
                if (!ControlUtils.continueWhenFailure(t.additionalOptions)) {
                    containerContext.buildStatus = BuildStatus.FAILED
                    needTerminate = needTerminate || TaskUtils.isStartVMTask(t) // 构建机启动失败，就需要终止
                } else {
                    hasFailedTaskInSuccessContainer = true
                }
            } else if (t.status.isReadyToRun()) {
                // 拿到按序号排列的第一个必须要执行的插件
                toDoTask = t.findNeedToRunTask(
                    hasFailedTaskInSuccessContainer = hasFailedTaskInSuccessContainer,
                    containerContext = containerContext,
                    needTerminate = needTerminate
                )
            }

            if (toDoTask != null || breakFlag) {
                break
            }
        }

        LOG.info("ENGINE|${containerContext.event.buildId}|${containerContext.event.source}|CONTAINER_FIND_TASK|" +
            "${containerContext.event.stageId}|j(${containerContext.event.containerId})|" +
            "${toDoTask?.taskId}|break=$breakFlag|needTerminate=$needTerminate")

        if (!needTerminate && breakFlag) {
            // #3400 暂停场景下，Job超时引起的终止，以及FastKill 等对于要求终止的，未必有后续执行，需要结束而不是中断
            containerContext.latestSummary = "action=${containerContext.event.actionType}"
            containerContext.cmdFlowState = CmdFlowState.BREAK
        }
        return toDoTask
    }

    private fun isTerminate(containerContext: ContainerContext): Boolean {
        return ActionType.isTerminate(containerContext.event.actionType) ||
            FastKillUtils.isFastKillCode(containerContext.event.errorCode)
    }

    private fun findRunningTask(
        containerContext: ContainerContext,
        currentTask: PipelineBuildTask
    ): PipelineBuildTask? {
        var toDoTask: PipelineBuildTask? = null
        when {
            ActionType.isTerminate(containerContext.event.actionType) -> { // 终止命令，需要设置失败，并返回
                containerContext.buildStatus = BuildStatus.RUNNING
                toDoTask = currentTask // 将当前任务传给TaskControl做终止
                buildLogPrinter.addRedLine(
                    buildId = toDoTask.buildId,
                    message = "Terminate Plugin[${toDoTask.taskName}]: ${containerContext.event.reason ?: "unknown"}",
                    tag = toDoTask.taskId,
                    jobId = toDoTask.containerHashId,
                    executeCount = toDoTask.executeCount ?: 1
                )
            }
            ActionType.isEnd(containerContext.event.actionType) -> { // 将当前正在运行的任务传给TaskControl做结束
                containerContext.buildStatus = BuildStatus.RUNNING
                toDoTask = currentTask
            }
        }
        return toDoTask
    }

    private fun PipelineBuildTask.findNeedToRunTask(
        hasFailedTaskInSuccessContainer: Boolean,
        containerContext: ContainerContext,
        needTerminate: Boolean
    ): PipelineBuildTask? {
        val source = containerContext.event.source
        var toDoTask: PipelineBuildTask? = null
        when { // [post action] 包含对应的关机任务，优先开机失败startVMFail=true
            additionalOptions?.elementPostInfo != null -> { // 如果是[post task], elementPostInfo必不为空
                toDoTask = additionalOptions?.elementPostInfo?.findPostActionTask(
                    containerTasks = containerContext.containerTasks,
                    currentTask = this,
                    isTerminate = needTerminate,
                    isContainerFailed = containerContext.buildStatus.isFailure(),
                    hasFailedTaskInSuccessContainer = hasFailedTaskInSuccessContainer
                )
                LOG.info("ENGINE|$buildId|$source|CONTAINER_POST_TASK|$stageId|j($containerId)|${toDoTask?.taskId}")
            }
            needTerminate -> { // 构建环境启动失败的，
                LOG.warn("ENGINE|$buildId|$source|CONTAINER_FAIL_VM|$stageId|j($containerId)|$taskId|$status")
                // 更新任务状态为跳过
                pipelineRuntimeService.updateTaskStatus(task = this, userId = starter, buildStatus = BuildStatus.UNEXEC)
                // 打印构建日志
                buildLogPrinter.addYellowLine(executeCount = containerContext.executeCount, tag = taskId,
                    buildId = buildId, message = "Terminate Plugin [$taskName]: ${containerContext.latestSummary}!",
                    jobId = containerHashId
                )
            }
            ControlUtils.checkTaskSkip(
                buildId = buildId,
                additionalOptions = additionalOptions,
                containerFinalStatus = containerContext.buildStatus,
                variables = containerContext.variables,
                hasFailedTaskInSuccessContainer = hasFailedTaskInSuccessContainer
            ) -> { // 检查条件跳过
                val taskStatus = BuildStatusSwitcher.readyToSkipWhen(containerContext.buildStatus.isFailure())
                LOG.warn("ENGINE|$buildId|$source|CONTAINER_SKIP_TASK|$stageId|j($containerId)|$taskId|$taskStatus")
                // 更新任务状态
                pipelineRuntimeService.updateTaskStatus(task = this, userId = starter, buildStatus = taskStatus)
                // 只更新SKIP编排模型
                if (taskStatus == BuildStatus.SKIP) {
                    pipelineBuildDetailService.taskEnd(buildId, taskId = taskId, buildStatus = taskStatus)
                }
                // 打印构建日志
                buildLogPrinter.addYellowLine(executeCount = containerContext.executeCount, tag = taskId,
                    buildId = buildId, message = "Skip Plugin [$taskName]: ${containerContext.latestSummary}",
                    jobId = containerHashId
                )
            }
            else -> {
                toDoTask = this // 当前排队的任务晋级为下一个执行任务
            }
        }

        if (toDoTask != null) {
            containerContext.buildStatus = BuildStatus.RUNNING
            containerContext.event.actionType = ActionType.START // 未开始的需要开始
        }
        return toDoTask
    }

    /**
     * 根据当前处于暂停状态的[currentTask]任务之下，查找到暂停任务后的未执行过的stopVM-xxxx关机任务， 如果没找到，则返回null
     */
    private fun findNextTaskAfterPause(
        containerContext: ContainerContext,
        currentTask: PipelineBuildTask
    ): PipelineBuildTask? {
        // 终止将直接返回
        if (isTerminate(containerContext)) {
            buildLogPrinter.addRedLine(
                buildId = currentTask.buildId,
                message = "Terminate Plugin[${currentTask.taskName}]: ${containerContext.event.reason ?: "unknown"}",
                tag = currentTask.taskId,
                jobId = currentTask.containerHashId,
                executeCount = currentTask.executeCount ?: 1
            )
            return null
        }

        var toDoTask: PipelineBuildTask? = null

        val pipelineBuildTask = containerContext.containerTasks
            .filter { it.taskId.startsWith(VMUtils.getStopVmLabel()) } // 找构建环境关机任务
            .getOrNull(0) // 取第一个关机任务，如果没有返回空

        if (pipelineBuildTask?.status?.isFinish() == false) { // 如果未执行过，则取该任务作为后续执行任务
            toDoTask = pipelineBuildTask
            LOG.info("ENGINE|${currentTask.buildId}|findNextTaskAfterPause|PAUSE|${currentTask.stageId}|" +
                "j(${currentTask.containerId})|${currentTask.taskId}|NextTask=${toDoTask.taskId}")
            // 此处多余，待确认后移除
            pipelineTaskService.pauseBuild(task = currentTask)
            containerContext.event.actionType = ActionType.START
        }
        containerContext.buildStatus = BuildStatus.PAUSE

        return toDoTask
    }

    fun ElementPostInfo.findPostActionTask(
        containerTasks: List<PipelineBuildTask>,
        currentTask: PipelineBuildTask,
        isTerminate: Boolean, // 是否终止
        isContainerFailed: Boolean,
        hasFailedTaskInSuccessContainer: Boolean
    ): PipelineBuildTask? {
        // 终止情况下，[post action]只适用于stopVM-关机任务
        if (isTerminate && currentTask.taskId != VMUtils.genStopVMTaskId(currentTask.taskSeq)) {
            return null
        }
        // 添加Post action标识打印
        addPostTipLog(currentTask)

        // 查询父任务及是否允许当前的post action 任务执行标识
        val (parentTask, postExecuteFlag) = TaskUtils.getPostTaskAndExecuteFlag(
            taskList = containerTasks,
            task = currentTask,
            isContainerFailed = isContainerFailed,
            hasFailedTaskInInSuccessContainer = hasFailedTaskInSuccessContainer
        )

        var waitToDoTask: PipelineBuildTask? = null
        if (parentTask != null && !postExecuteFlag) {
            val parentTaskSkipFlag = parentTask.status == BuildStatus.SKIP
            // 如果post任务的主体任务状态是SKIP，则该post任务的状态也应该置为SKIP
            val buildStatus = if (parentTaskSkipFlag) BuildStatus.SKIP else BuildStatus.UNEXEC
            val message = if (parentTaskSkipFlag) "Plugin [${currentTask.taskName}] was skipped"
            else "Post action execution conditions (expectation: $postCondition), not executed"
            // 更新排队中的post任务的构建状态
            pipelineRuntimeService.updateTaskStatus(currentTask, currentTask.starter, buildStatus = buildStatus)
            if (buildStatus == BuildStatus.SKIP) { // 更新跳过状态
                pipelineBuildDetailService.taskSkip(buildId = currentTask.buildId, taskId = currentTask.taskId)
            }
            buildLogPrinter.addYellowLine(
                buildId = currentTask.buildId,
                message = message,
                tag = currentTask.taskId,
                jobId = currentTask.containerHashId,
                executeCount = currentTask.executeCount ?: 1
            )
        }
        if (parentTask != null && postExecuteFlag) {
            waitToDoTask = currentTask
        }

        return waitToDoTask
    }

    private fun ElementPostInfo.addPostTipLog(task: PipelineBuildTask) {
        buildLogPrinter.addLine(
            buildId = task.buildId,
            message = MessageCodeUtil.getCodeMessage(
                messageCode = ATOM_POST_EXECUTE_TIP,
                params = arrayOf((parentElementJobIndex + 1).toString(), parentElementName)
            ) ?: "",
            tag = task.taskId,
            jobId = task.containerHashId,
            executeCount = task.executeCount ?: 1
        )
    }

    private fun sendTask(event: PipelineBuildContainerEvent, task: PipelineBuildTask) {
        pipelineEventDispatcher.dispatch(
            PipelineBuildAtomTaskEvent(
                source = "CONTAINER_${event.actionType}",
                projectId = task.projectId,
                pipelineId = task.pipelineId,
                userId = event.userId,
                buildId = task.buildId,
                stageId = task.stageId,
                containerId = task.containerId,
                containerType = task.containerType,
                taskId = task.taskId,
                taskParam = task.taskParams,
                actionType = event.actionType,
                reason = event.reason,
                errorCode = event.errorCode,
                errorTypeName = event.errorTypeName
            )
        )
    }
}
