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

package com.tencent.devops.store.api.common

import com.tencent.devops.common.api.auth.AUTH_HEADER_USER_ID
import com.tencent.devops.common.api.auth.AUTH_HEADER_USER_ID_DEFAULT_VALUE
import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.common.log.pojo.QueryLogs
import com.tencent.devops.store.pojo.common.enums.StoreTypeEnum
import io.swagger.annotations.Api
import io.swagger.annotations.ApiOperation
import io.swagger.annotations.ApiParam
import javax.ws.rs.Consumes
import javax.ws.rs.GET
import javax.ws.rs.HeaderParam
import javax.ws.rs.Path
import javax.ws.rs.PathParam
import javax.ws.rs.Produces
import javax.ws.rs.QueryParam
import javax.ws.rs.core.MediaType

@Api(tags = ["USER_STORE_LOG"], description = "研发商店-日志")
@Path("/user/store/logs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Suppress("ALL")
interface UserStoreLogResource {

    @ApiOperation("根据构建ID获取初始化所有日志")
    @GET
    @Path("/types/{storeType}/projects/{projectCode}/pipelines/{pipelineId}/builds/{buildId}/")
    fun getInitLogs(
        @ApiParam("用户ID", required = true, defaultValue = AUTH_HEADER_USER_ID_DEFAULT_VALUE)
        @HeaderParam(AUTH_HEADER_USER_ID)
        userId: String,
        @ApiParam("研发商店组件类型", required = true)
        @PathParam("storeType")
        storeType: StoreTypeEnum,
        @ApiParam("项目代码", required = true)
        @PathParam("projectCode")
        projectCode: String,
        @ApiParam("流水线ID", required = true)
        @PathParam("pipelineId")
        pipelineId: String,
        @ApiParam("构建ID", required = true)
        @PathParam("buildId")
        buildId: String,
        @ApiParam("是否请求分析日志", required = false)
        @QueryParam("isAnalysis")
        isAnalysis: Boolean? = false,
        @ApiParam("搜索关键字", required = false)
        @QueryParam("queryKeywords")
        queryKeywords: String?,
        @ApiParam("对应elementId", required = false)
        @QueryParam("tag")
        tag: String?,
        @ApiParam("执行次数", required = false)
        @QueryParam("executeCount")
        executeCount: Int?
    ): Result<QueryLogs?>

    @ApiOperation("获取更多日志")
    @GET
    @Path("/types/{storeType}/projects/{projectCode}/pipelines/{pipelineId}/builds/{buildId}/more")
    fun getMoreLogs(
        @ApiParam("用户ID", required = true, defaultValue = AUTH_HEADER_USER_ID_DEFAULT_VALUE)
        @HeaderParam(AUTH_HEADER_USER_ID)
        userId: String,
        @ApiParam("研发商店组件类型", required = true)
        @PathParam("storeType")
        storeType: StoreTypeEnum,
        @ApiParam("项目代码", required = true)
        @PathParam("projectCode")
        projectCode: String,
        @ApiParam("流水线ID", required = true)
        @PathParam("pipelineId")
        pipelineId: String,
        @ApiParam("构建ID", required = true)
        @PathParam("buildId")
        buildId: String,
        @ApiParam("日志行数", required = false)
        @QueryParam("num")
        num: Int? = 100,
        @ApiParam("是否正序输出", required = false)
        @QueryParam("fromStart")
        fromStart: Boolean? = true,
        @ApiParam("起始行号", required = true)
        @QueryParam("start")
        start: Long,
        @ApiParam("结尾行号", required = true)
        @QueryParam("end")
        end: Long,
        @ApiParam("对应elementId", required = false)
        @QueryParam("tag")
        tag: String?,
        @ApiParam("执行次数", required = false)
        @QueryParam("executeCount")
        executeCount: Int?
    ): Result<QueryLogs?>

    @ApiOperation("获取某行后的日志")
    @GET
    @Path("/types/{storeType}/projects/{projectCode}/pipelines/{pipelineId}/builds/{buildId}/after")
    fun getAfterLogs(
        @ApiParam("用户ID", required = true, defaultValue = AUTH_HEADER_USER_ID_DEFAULT_VALUE)
        @HeaderParam(AUTH_HEADER_USER_ID)
        userId: String,
        @ApiParam("研发商店组件类型", required = true)
        @PathParam("storeType")
        storeType: StoreTypeEnum,
        @ApiParam("项目代码", required = true)
        @PathParam("projectCode")
        projectCode: String,
        @ApiParam("流水线ID", required = true)
        @PathParam("pipelineId")
        pipelineId: String,
        @ApiParam("构建ID", required = true)
        @PathParam("buildId")
        buildId: String,
        @ApiParam("起始行号", required = true)
        @QueryParam("start")
        start: Long,
        @ApiParam("是否请求分析日志", required = false)
        @QueryParam("isAnalysis")
        isAnalysis: Boolean? = false,
        @ApiParam("搜索关键字", required = false)
        @QueryParam("queryKeywords")
        queryKeywords: String?,
        @ApiParam("对应elementId", required = false)
        @QueryParam("tag")
        tag: String?,
        @ApiParam("执行次数", required = false)
        @QueryParam("executeCount")
        executeCount: Int?
    ): Result<QueryLogs?>
}
