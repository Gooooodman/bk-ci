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

package com.tencent.devops.dispatch.service

import com.tencent.devops.common.api.enums.AgentStatus
import com.tencent.devops.common.api.exception.OperationException
import com.tencent.devops.common.api.exception.RemoteServiceException
import com.tencent.devops.common.api.pojo.AgentResult
import com.tencent.devops.common.api.pojo.Page
import com.tencent.devops.common.api.pojo.SimpleResult
import com.tencent.devops.common.api.util.PageUtil
import com.tencent.devops.common.api.util.timestamp
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.dispatch.dao.ThirdPartyAgentBuildDao
import com.tencent.devops.dispatch.pojo.ThirdPartyAgentPreBuildAgents
import com.tencent.devops.dispatch.pojo.enums.PipelineTaskStatus
import com.tencent.devops.dispatch.pojo.thirdPartyAgent.AgentBuildInfo
import com.tencent.devops.dispatch.pojo.thirdPartyAgent.ThirdPartyBuildInfo
import com.tencent.devops.dispatch.pojo.thirdPartyAgent.ThirdPartyBuildWithStatus
import com.tencent.devops.dispatch.utils.ThirdPartyAgentLock
import com.tencent.devops.dispatch.utils.redis.RedisUtils
import com.tencent.devops.environment.api.thirdPartyAgent.ServiceThirdPartyAgentResource
import com.tencent.devops.model.dispatch.tables.records.TDispatchThirdpartyAgentBuildRecord
import com.tencent.devops.process.api.service.ServiceBuildResource
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import javax.ws.rs.NotFoundException

@Service
@Suppress("ALL")
class ThirdPartyAgentService @Autowired constructor(
    private val dslContext: DSLContext,
    private val redisUtils: RedisUtils,
    private val client: Client,
    private val redisOperation: RedisOperation,
    private val thirdPartyAgentBuildDao: ThirdPartyAgentBuildDao
) {

    fun queueBuild(
        projectId: String,
        agentId: String,
        pipelineId: String,
        buildId: String,
        vmSeqId: String,
        thirdPartyAgentWorkspace: String,
        pipelineName: String,
        buildNo: Int,
        taskName: String
    ) {
        val count = thirdPartyAgentBuildDao.add(
            dslContext = dslContext,
            projectId = projectId,
            agentId = agentId,
            pipelineId = pipelineId,
            buildId = buildId,
            vmSeqId = vmSeqId,
            thirdPartyAgentWorkspace = thirdPartyAgentWorkspace,
            pipelineName = pipelineName,
            buildNum = buildNo,
            taskName = taskName
        )
        if (count != 1) {
            logger.warn("Fail to add the third party agent build of ($buildId|$vmSeqId|$agentId|$count)")
            throw OperationException("Fail to add the third party agent build")
        }
    }

    fun getPreBuildAgents(projectId: String, pipelineId: String, vmSeqId: String): List<ThirdPartyAgentPreBuildAgents> {
        val records = thirdPartyAgentBuildDao.getPreBuildAgent(
            dslContext, projectId, pipelineId, vmSeqId
        )
        return records.map {
            ThirdPartyAgentPreBuildAgents(
                id = it.id,
                projectId = it.projectId,
                agentId = it.agentId,
                buildId = it.buildId,
                status = it.status,
                createdTime = it.createdTime.timestamp()
            )
        }
    }

    fun getRunningBuilds(agentId: String): Int {
        return thirdPartyAgentBuildDao.getRunningAndQueueBuilds(dslContext, agentId).size
    }

    fun startBuild(
        projectId: String,
        agentId: String,
        secretKey: String
    ): AgentResult<ThirdPartyBuildInfo?> {
        // Get the queue status build by buildId and agentId
        logger.debug("Start the third party agent($agentId) of project($projectId)")
        try {
            val agentResult = try {
                client.get(ServiceThirdPartyAgentResource::class).getAgentById(projectId, agentId)
            } catch (e: RemoteServiceException) {
                logger.warn("Fail to get the agent($agentId) of project($projectId) because of ${e.message}")
                return AgentResult(1, e.message ?: "Fail to get the agent")
            }

            if (agentResult.agentStatus == AgentStatus.DELETE) {
                return AgentResult(AgentStatus.DELETE, null)
            }

            if (agentResult.isNotOk()) {
                logger.warn("Fail to get the third party agent($agentId) because of ${agentResult.message}")
                throw NotFoundException("Fail to get the agent")
            }

            if (agentResult.data == null) {
                logger.warn("Get the null third party agent($agentId)")
                throw NotFoundException("Fail to get the agent")
            }

            if (agentResult.data!!.secretKey != secretKey) {
                logger.warn(
                    "The secretKey($secretKey) is not match the expect one(${agentResult.data!!.secretKey} " +
                        "of project($projectId) and agent($agentId)"
                )
                throw NotFoundException("Fail to get the agent")
            }

            if (agentResult.data!!.status != AgentStatus.IMPORT_OK) {
                logger.warn("The agent($agentId) is not import(${agentResult.data!!.status})")
                throw NotFoundException("Fail to get the agent")
            }

            logger.debug("Third party agent($agentId) start up")

            val redisLock = ThirdPartyAgentLock(redisOperation, projectId, agentId)
            try {
                redisLock.lock()
                val buildRecords =
                    thirdPartyAgentBuildDao.getQueueBuilds(dslContext, agentId)
                if (buildRecords.isEmpty()) {
                    logger.debug("There is not build by agent($agentId) in queue")
                    return AgentResult(AgentStatus.IMPORT_OK, null)
                }
                val build = buildRecords[0]
                logger.info("Start the build(${build.buildId}) of agent($agentId) and seq(${build.vmSeqId})")
                thirdPartyAgentBuildDao.updateStatus(dslContext, build.id, PipelineTaskStatus.RUNNING)
                return AgentResult(
                    AgentStatus.IMPORT_OK,
                    ThirdPartyBuildInfo(projectId, build.buildId, build.vmSeqId, build.workspace)
                )
            } finally {
                redisLock.unlock()
            }
        } catch (ignored: Throwable) {
            logger.warn("Fail to start build for agent($agentId)", ignored)
            throw ignored
        }
    }

    fun checkIfCanUpgradeByVersion(
        projectId: String,
        agentId: String,
        secretKey: String,
        version: String?,
        masterVersion: String?
    ): AgentResult<Boolean> {
        // logger.info("Start to check if the agent($agentId) of version $version of project($projectId) can upgrade")
        return try {
            val agentUpgradeResult = client.get(ServiceThirdPartyAgentResource::class)
                .upgradeByVersion(projectId, agentId, secretKey, version, masterVersion)
            return if (agentUpgradeResult.data != null && !agentUpgradeResult.data!!) {
                agentUpgradeResult
            } else {
                redisUtils.setThirdPartyAgentUpgrading(projectId, agentId)
                AgentResult(AgentStatus.IMPORT_OK, true)
            }
        } catch (t: Throwable) {
            logger.warn("Fail to check if agent can upgrade", t)
            AgentResult(AgentStatus.IMPORT_EXCEPTION, false)
        }
    }

    fun finishUpgrade(
        projectId: String,
        agentId: String,
        secretKey: String,
        success: Boolean
    ): AgentResult<Boolean> {
        logger.info("The agent($agentId) of project($projectId) finish upgrading with result $success")
        try {
            val agentResult = try {
                client.get(ServiceThirdPartyAgentResource::class).getAgentById(projectId, agentId)
            } catch (e: RemoteServiceException) {
                logger.warn("Fail to get the agent($agentId) of project($projectId) because of ${e.message}")
                return AgentResult(1, e.message ?: "Fail to get the agent")
            }

            if (agentResult.agentStatus == AgentStatus.DELETE) {
                return AgentResult(AgentStatus.DELETE, false)
            }

            if (agentResult.isNotOk()) {
                logger.warn("Fail to get the third party agent($agentId) because of ${agentResult.message}")
                throw NotFoundException("Fail to get the agent")
            }

            if (agentResult.data == null) {
                logger.warn("Get the null third party agent($agentId)")
                throw NotFoundException("Fail to get the agent")
            }

            if (agentResult.data!!.secretKey != secretKey) {
                logger.warn(
                    "The secretKey($secretKey) is not match the expect one(${agentResult.data!!.secretKey} " +
                        "of project($projectId) and agent($agentId)"
                )
                throw NotFoundException("Fail to get the agent")
            }
            redisUtils.thirdPartyAgentUpgradingDone(projectId, agentId)
            return AgentResult(agentResult.agentStatus!!, true)
        } catch (ignored: Throwable) {
            logger.warn("Fail to finish upgrading", ignored)
            return AgentResult(AgentStatus.IMPORT_EXCEPTION, false)
        }
    }

    fun finishBuild(
        buildId: String,
        vmSeqId: String?,
        success: Boolean
    ) {
        if (vmSeqId.isNullOrBlank()) {
            val records = thirdPartyAgentBuildDao.list(dslContext, buildId)
            if (records.isEmpty()) {
                return
            }
            records.forEach {
                finishBuild(it, success)
            }
        } else {
            val record =
                thirdPartyAgentBuildDao.get(dslContext, buildId, vmSeqId!!) ?: return
            finishBuild(record, success)
        }
    }

    fun listAgentBuilds(agentId: String, page: Int?, pageSize: Int?): Page<AgentBuildInfo> {
        val pageNotNull = page ?: 0
        val pageSizeNotNull = pageSize ?: 100
        val sqlLimit =
            if (pageSizeNotNull != -1) PageUtil.convertPageSizeToSQLLimit(pageNotNull, pageSizeNotNull) else null
        val offset = sqlLimit?.offset ?: 0
        val limit = sqlLimit?.limit ?: 100

        val agentBuildCount = thirdPartyAgentBuildDao.countAgentBuilds(dslContext, agentId)
        val agentBuilds = thirdPartyAgentBuildDao.listAgentBuilds(dslContext, agentId, offset, limit).map {
            AgentBuildInfo(
                projectId = it.projectId,
                agentId = it.agentId,
                pipelineId = it.pipelineId,
                pipelineName = it.pipelineName,
                buildId = it.buildId,
                buildNum = it.buildNum,
                vmSeqId = it.vmSeqId,
                taskName = it.taskName,
                status = PipelineTaskStatus.toStatus(it.status).name,
                createdTime = it.createdTime.timestamp(),
                updatedTime = it.updatedTime.timestamp(),
                workspace = it.workspace
            )
        }
        return Page(pageNotNull, pageSizeNotNull, agentBuildCount, agentBuilds)
    }

    private fun finishBuild(
        record: TDispatchThirdpartyAgentBuildRecord,
        success: Boolean
    ) {
        logger.info(
            "Finish the third party agent(${record.agentId}) build(${record.buildId}) " +
                "of seq(${record.vmSeqId}) and status(${record.status})"
        )
        val agentResult = client.get(ServiceThirdPartyAgentResource::class)
            .getAgentById(record.projectId, record.agentId)
        if (agentResult.isNotOk()) {
            logger.warn("Fail to get the third party agent(${record.agentId}) because of ${agentResult.message}")
            throw RemoteServiceException("Fail to get the third party agent")
        }

        if (agentResult.data == null) {
            logger.warn("Get the null third party agent(${record.agentId})")
            throw RemoteServiceException("Fail to get the third party agent")
        }
        redisUtils.deleteThirdPartyBuild(agentResult.data!!.secretKey, record.agentId, record.buildId, record.vmSeqId)
        thirdPartyAgentBuildDao.updateStatus(
            dslContext, record.id,
            if (success) PipelineTaskStatus.DONE else PipelineTaskStatus.FAILURE
        )
    }

    fun workerBuildFinish(projectId: String, agentId: String, secretKey: String, buildInfo: ThirdPartyBuildWithStatus) {
        val agentResult = client.get(ServiceThirdPartyAgentResource::class).getAgentById(projectId, agentId)
        if (agentResult.isNotOk()) {
            logger.warn("Fail to get the third party agent($agentId) because of ${agentResult.message}")
            throw NotFoundException("Fail to get the agent")
        }
        if (agentResult.data == null) {
            logger.warn("Get the null third party agent($agentId)")
            throw NotFoundException("Fail to get the agent")
        }

        if (agentResult.data!!.secretKey != secretKey) {
            throw NotFoundException("Fail to get the agent")
        }

        // FIXME 构建机停止问题因goAgent传pipelineId为空串问题导致接口报错404，
        // pipelineId是无意义的字段，先解决报错问题。后续需要新增一个没有pipelineId参数的接口
        client.get(ServiceBuildResource::class).workerBuildFinish(
            projectId = projectId,
            pipelineId = if (buildInfo.pipelineId.isNullOrBlank()) "dummyPipelineId" else buildInfo.pipelineId!!,
            buildId = buildInfo.buildId,
            vmSeqId = buildInfo.vmSeqId,
            simpleResult = SimpleResult(success = buildInfo.success, message = buildInfo.message)
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ThirdPartyAgentService::class.java)
    }
}
