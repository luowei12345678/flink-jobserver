package io.github.melin.flink.jobserver.driver.task;

import com.gitee.melin.bee.core.support.Result;
import com.gitee.melin.bee.util.NetUtils;
import com.gitee.melin.bee.util.RestTemplateUtils;
import io.github.melin.flink.jobserver.api.FlinkJobServerException;
import io.github.melin.flink.jobserver.core.dto.InstanceDto;
import io.github.melin.flink.jobserver.core.entity.ApplicationDriver;
import io.github.melin.flink.jobserver.core.enums.InstanceStatus;
import io.github.melin.flink.jobserver.core.exception.FlinkJobException;
import io.github.melin.flink.jobserver.core.service.ApplicationDriverService;
import io.github.melin.flink.jobserver.core.service.JobInstanceContentService;
import io.github.melin.flink.jobserver.core.service.JobInstanceService;
import io.github.melin.flink.jobserver.core.util.CommonUtils;
import io.github.melin.flink.jobserver.driver.FlinkDriverContext;
import io.github.melin.flink.jobserver.driver.FlinkDriverEnv;
import io.github.melin.flink.jobserver.driver.ServerPortService;
import io.github.melin.flink.jobserver.driver.util.LogUtils;
import org.apache.calcite.rel.metadata.RelMetadataQueryBase;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestTemplate;

import java.io.StringReader;
import java.sql.SQLException;
import java.util.Properties;

import static io.github.melin.flink.jobserver.core.util.TaskStatusFlag.*;

public abstract class AbstractFlinkTask {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractFlinkTask.class);

    @Autowired
    protected JobInstanceService instanceService;

    @Autowired
    protected JobInstanceContentService instanceContentService;

    @Autowired
    protected FlinkDriverContext sparkDriverContext;

    @Autowired
    private ApplicationDriverService driverService;

    @Autowired
    private ServerPortService serverPortService;

    @Autowired
    private RestTemplate restTemplate;

    private Long startTime = 0L;

    public Result<String> runTask(InstanceDto instanceDto) {
        new Task(instanceDto).start();
        return Result.successResult();
    }

    /**
     * 启动job时的初始化工作
     */
    protected void startJob(InstanceDto instanceDto) throws Exception {
        String instanceCode = instanceDto.getInstanceCode();

        if (instanceCode == null) {
            exitFailure(instanceCode, "job instance " + instanceCode + " not exists");
            throw new FlinkJobException("作业实例不存在: " + instanceCode);
        }

        String applicationId = instanceDto.getApplicationId();
        instanceService.startJobInstance(instanceDto.getInstanceCode(), applicationId);

        startTime = System.currentTimeMillis();

        ApplicationDriver driver = driverService.queryDriverByAppId(applicationId);
        if (driver == null) {
            String msg = "driver 不存在, yarn applicationId: " + applicationId;
            exitFailure(instanceCode, msg);
            throw new FlinkJobException(msg);
        }

        driver.setInstanceCode(instanceCode);
        driverService.updateServerRunning(driver);
        LOG.info("Update Driver: {} Status running:", driver.getApplicationId());
        startJobLogThread(instanceCode, instanceDto.getFlinkDriverUrl());
    }

    protected void endJob(String instanceCode, InstanceStatus status) {
        this.endJob(instanceCode, status, null);
    }

    /**
     * 结束job的收尾工作
     */
    protected void endJob(String instanceCode, InstanceStatus status, String errorMsg) {
        try {
            instanceService.instanceRunEnd(instanceCode, status, errorMsg);
            LOG.info("作业: {} 运行完成，更新实例状态：{}", instanceCode, status.getValue());

            driverService.updateServerFinished(FlinkDriverEnv.applicationId);

            if (InstanceStatus.FAILED == status && errorMsg != null) {
                instanceContentService.updateErrorMsg(instanceCode, errorMsg);
            }
        } catch (Exception e) {
            LOG.error("结束作业失败: " + e.getMessage(), e);
        }
    }

    protected void exitFailure(String instanceCode, String errorMsg) {
        LOG.error("Job {} Failed: {}", instanceCode, errorMsg);
        endJob(instanceCode, InstanceStatus.FAILED, errorMsg);
        LogUtils.sendTaskStatusFlag(TASK_ERROR_FLAG, errorMsg);
    }

    public void killJob(String instanceCode) {
        endJob(instanceCode, InstanceStatus.KILLED);
    }

    protected void exitSuccess(String instanceCode) {
        endJob(instanceCode, InstanceStatus.FINISHED);

        long time = System.currentTimeMillis() - startTime;
        LogUtils.info("任务执行成功, 耗时: {}", CommonUtils.convertTime(time));
        LogUtils.sendTaskStatusFlag(TASK_END_FLAG);
    }

    private String getErrorMsg(Throwable e) {
        LOG.error("作业失败原因: " + e.getClass().getName() + " " + e.getMessage(), e);
        Throwable root = ExceptionUtils.getRootCause(e);
        if (root == null) {
            root = e;
        }

        String errMsg;
        if (root instanceof FlinkJobException || root instanceof SecurityException
                || root instanceof FlinkJobServerException || root instanceof SQLException
                || root instanceof UnsupportedOperationException || root instanceof IllegalStateException
                || root instanceof IllegalArgumentException) {
            errMsg = root.getMessage();
        } else if (root instanceof NoSuchMethodError || root instanceof NoSuchFieldError) {
            String msg = StringUtils.substringBefore(root.getMessage(), "(");
            int index = StringUtils.lastIndexOf(msg, ".");
            String className = StringUtils.substring(msg, 0, index);
            errMsg = root.getMessage();
            try {
                String jarPath = Class.forName(className).getProtectionDomain().getCodeSource().getLocation().getPath();
                errMsg = errMsg + "\nclass path: " + jarPath;
            } catch (Exception ignored) {}
        } else {
            errMsg = ExceptionUtils.getStackTrace(root);
        }

        // 去掉异常名称前缀
        String cm = StringUtils.substringAfter(errMsg, "Exception: ");
        if (StringUtils.isNotBlank(cm)) {
            errMsg = cm;
        }

        return errMsg.trim();
    }

    private void printNoSuchError(Throwable e) {
        if (e instanceof NoSuchFieldError || e instanceof NoSuchMethodError
                || e instanceof NoSuchFieldException || e instanceof NoSuchMethodException) {
            String className = StringUtils.substringBeforeLast(e.getMessage(), ".");
            try {
                String classPath = Class.forName(className).getProtectionDomain()
                        .getCodeSource().getLocation().getPath();
                LogUtils.error(className + " location at " + classPath);
            } catch (Throwable ex) {
                LogUtils.error(ex.getMessage());
            }
        }
    }

    protected abstract void executeJobText(InstanceDto instanceDto) throws Exception;

    protected class Task extends Thread {

        private final InstanceDto instanceDto;

        public Task(InstanceDto instanceDto) {
            this.instanceDto = instanceDto;
        }

        @Override
        public void run() {
            String instanceCode = instanceDto.getInstanceCode();
            try {
                RelMetadataQueryBase.THREAD_PROVIDERS.set(FlinkDriverEnv.getMetadataProvider());

                LOG.info("Sql Job: {} begined, submit from {}", instanceCode, instanceDto.getFlinkDriverUrl());
                startJob(instanceDto);
                setConf(instanceDto.getJobConfig());

                executeJobText(instanceDto);

                exitSuccess(instanceCode);
            } catch (Throwable e) {
                if (!sparkDriverContext.isUserStopTask()) {
                    LOG.warn("Sql执行错误({}): {}", instanceCode, ExceptionUtils.getStackTrace(e));
                    printNoSuchError(e);

                    String errMsg = "任务(" + instanceCode + ")执行失败，失败原因:\n\t" + getErrorMsg(e);
                    exitFailure(instanceCode, errMsg);
                } else {
                    LogUtils.info("stopped by user");
                }

                LogUtils.sendTaskStatusFlag(TASK_ERROR_FLAG, e.getMessage());
                endJob(instanceCode, InstanceStatus.FAILED, e.getMessage());

                String errMsg = ExceptionUtils.getStackTrace(e).trim();
                LOG.error("Job: {} failed: {}", instanceCode, errMsg);
            } finally {
                sparkDriverContext.stopDriver(instanceDto);
            }

            LOG.info("Job: {} ended: {}:{}", instanceCode, NetUtils.getLocalHost(), serverPortService.getPort());
        }
    }

    /**
     * 启动job的日志线程
     */
    private void startJobLogThread(String instanceCode, String sparkJobServerUrl) {
        String url = sparkJobServerUrl + "/driver/startJobLogThread";
        try {
            Result<String> result = RestTemplateUtils.postEntry(restTemplate, url, "instanceCode", instanceCode);
            if (result.isSuccess()) {
                LOG.info("Job {} logThread Started", instanceCode);
            } else {
                throw new RuntimeException("Job logThread start failure: " + result.getMessage());
            }
        } catch (Exception e) {
            LOG.error("Job " + instanceCode + " logThread start failed, url: " + url, e);
            throw new RuntimeException("Job logThread start failure");
        }
    }

    private void setConf(String jobConfig) {
        if (StringUtils.isBlank(jobConfig)) {
            return;
        }

        try {
            Properties properties = new Properties();
            properties.load(new StringReader(jobConfig));

            for (Object key : properties.keySet()) {
                String propKey = (String) key;
                String value = properties.getProperty(propKey);
                LogUtils.info("set param {} = {}", propKey, value);
            }
        } catch (Exception e) {
            LOG.error("parse job config failure", e);
        }
    }
}
