package io.github.melin.flink.jobserver.rest;

import com.gitee.melin.bee.core.support.Result;
import io.github.melin.flink.jobserver.rest.dto.InstanceInfo;
import io.github.melin.flink.jobserver.rest.dto.JobSubmitRequet;
import io.github.melin.flink.jobserver.service.JobServerServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

@Controller
public class JobServerRestApi {

    private static final Logger LOG = LoggerFactory.getLogger(JobServerRestApi.class);

    @Autowired
    private JobServerServiceImpl jobServerService;

    @RequestMapping("v1/jobserver/submitJobInstance")
    @ResponseBody
    public Result<String> submitJobInstance(String accessKey, String accessSecret, JobSubmitRequet requet) {
        try {
            String instanceCode = jobServerService.submitJobInstance(requet);
            return Result.successDataResult(instanceCode);
        } catch (Throwable e) {
            LOG.error(e.getMessage(), e);
            return Result.failureResult(e.getMessage());
        }
    }

    @RequestMapping("v1/jobserver/queryInstanceStatus")
    @ResponseBody
    public Result<InstanceInfo> queryInstanceStatus(String accessKey, String accessSecret, String instanceCode) {
        try {
            InstanceInfo info = jobServerService.queryInstanceStatus(instanceCode);
            return Result.successDataResult(info);
        } catch (Throwable e) {
            LOG.error(e.getMessage(), e);
            return Result.failureResult(e.getMessage());
        }
    }

    @RequestMapping("v1/jobserver/batchQueryInstanceStatus")
    @ResponseBody
    public Result<List<InstanceInfo>> batchQueryInstanceStatus(String accessKey, String accessSecret, String[] instanceCode) {
        try {
            List<InstanceInfo> infos = jobServerService.batchQueryInstanceStatus(instanceCode);
            return Result.successDataResult(infos);
        } catch (Throwable e) {
            LOG.error(e.getMessage(), e);
            return Result.failureResult(e.getMessage());
        }
    }

    @RequestMapping("v1/jobserver/queryInstanceLog")
    @ResponseBody
    public Result<String> queryInstanceLog(String accessKey, String accessSecret, String instanceCode) {
        try {
            String log = jobServerService.queryInstanceLog(instanceCode);
            return Result.successDataResult(log);
        } catch (Throwable e) {
            LOG.error(e.getMessage(), e);
            return Result.failureResult(e.getMessage());
        }
    }

    @RequestMapping("v1/jobserver/stopInstance")
    @ResponseBody
    public Result<String> stopInstance(String accessKey, String accessSecret, String instanceCode) {
        try {
            String error = jobServerService.stopInstance(instanceCode);
            if (error == null) {
                return Result.successResult();
            } else {
                return Result.failureResult(error);
            }
        } catch (Throwable e) {
            LOG.error(e.getMessage(), e);
            return Result.failureResult(e.getMessage());
        }
    }
}
