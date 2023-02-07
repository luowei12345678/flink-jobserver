package io.github.melin.flink.jobserver.submit;

import com.gitee.melin.bee.core.support.Result;
import com.gitee.melin.bee.util.JsonUtils;
import com.gitee.melin.bee.util.NetUtils;
import com.gitee.melin.bee.util.RestTemplateUtils;
import io.github.melin.flink.jobserver.ConfigProperties;
import io.github.melin.flink.jobserver.api.FlinkJobServerException;
import io.github.melin.flink.jobserver.core.dto.InstanceDto;
import io.github.melin.flink.jobserver.core.entity.JobInstance;
import io.github.melin.flink.jobserver.core.enums.*;
import io.github.melin.flink.jobserver.core.exception.FlinkJobException;
import io.github.melin.flink.jobserver.core.exception.HttpClientException;
import io.github.melin.flink.jobserver.core.exception.ResouceLimitException;
import io.github.melin.flink.jobserver.core.exception.SwitchYarnQueueException;
import io.github.melin.flink.jobserver.core.service.ApplicationDriverService;
import io.github.melin.flink.jobserver.core.service.JobInstanceService;
import io.github.melin.flink.jobserver.submit.deployer.YarnApplicationDriverDeployer;
import io.github.melin.flink.jobserver.submit.dto.DriverInfo;
import io.github.melin.flink.jobserver.submit.dto.JobInstanceInfo;
import io.github.melin.flink.jobserver.submit.dto.JobSubmitResult;
import io.github.melin.flink.jobserver.submit.dto.SubmitYarnResult;
import io.github.melin.flink.jobserver.logs.FlinkLogService;
import io.github.melin.flink.jobserver.support.ClusterManager;
import io.github.melin.flink.jobserver.support.YarnClientService;
import io.github.melin.flink.jobserver.util.DateUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.github.melin.flink.jobserver.core.enums.DriverInstance.NEW_INSTANCE;

@Service
public class FlinkJobSubmitService implements InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(FlinkJobSubmitService.class);

    private static final Logger INST_LOG = LoggerFactory.getLogger("jobinstancelogs");

    @Autowired
    private JobInstanceService instanceService;

    @Autowired
    private YarnClientService yarnClientService;

    @Autowired
    private ApplicationDriverService applicationDriverService;

    @Autowired
    private YarnApplicationDriverDeployer driverDeployer;

    protected RestTemplate restTemplate;

    @Autowired
    private ConfigProperties configProperties;

    @Autowired
    private ClusterManager clusterManager;

    @Autowired
    private FlinkLogService flinkLogService;

    @Value("${server.port}")
    private int serverPort;

    @Value("${spring.profiles.active}")
    protected String profiles;

    private String sparkJobServerUrl;

    private String instanceLogPath;

    private final String hostName = NetUtils.getLocalHost();

    private final ExecutorService taskSubmitExecutor = Executors.newCachedThreadPool();

    @Override
    public void afterPropertiesSet() throws Exception {
        this.instanceLogPath = configProperties.getInstanceLogPath();

        sparkJobServerUrl = "http://" + NetUtils.getLocalHost() + ":" + serverPort;
        HttpComponentsClientHttpRequestFactory httpRequestFactory = new HttpComponentsClientHttpRequestFactory();
        httpRequestFactory.setConnectionRequestTimeout(10000);
        httpRequestFactory.setConnectTimeout(10000);
        httpRequestFactory.setReadTimeout(10000);
        restTemplate = new RestTemplate(httpRequestFactory);
        restTemplate.getMessageConverters().add(0, new MappingJackson2HttpMessageConverter());
    }

    /**
     * 提交任务到server
     *
     * @return (status, msg) 0:提交成功, msg:server地址; 1:创建新的server, msg:serverId;  2:提交错误,msg:错误信息
     */
    public JobSubmitResult submitJob(JobInstanceInfo instanceInfo) {
        String instanceCode = instanceInfo.getInstanceCode();
        try {
            return innerSubmit(instanceInfo);
        } catch (FlinkJobException e) {
            return resolveSubmitError(instanceCode, ExceptionUtils.getStackTrace(e));
        } catch (Throwable e) {
            LOG.error(e.getMessage(), e);
            return resolveSubmitError(instanceCode, ExceptionUtils.getStackTrace(e));
        }
    }

    private JobSubmitResult resolveSubmitError(String instanceCode, String errMsg) {
        LOG.error("task {} get server error: {}", instanceCode, errMsg);
        JobInstance instance = instanceService.updateJobStatusByCode(instanceCode, InstanceStatus.FAILED);

        final String scheduleDate = DateUtils.formatDate(instance.getScheduleTime());
        String path = instanceLogPath + "/" + scheduleDate + "/" + instanceCode + ".log";
        MDC.put("logFileName", path);
        INST_LOG.error("Unable to Submit Job {}, error: {}", instanceCode, errMsg);

        return new JobSubmitResult(DriverInstance.ERROR_INSTANCE, errMsg);
    }

    private JobSubmitResult innerSubmit(JobInstanceInfo instanceInfo) {
        Boolean isDevTask = instanceInfo.getInstanceType() == InstanceType.DEV;
        DriverInfo driverInfo = allocateDriver(instanceInfo, isDevTask);

        String yarnQueue = instanceInfo.getYarnQueue();
        String instanceCode = instanceInfo.getInstanceCode();
        String clusterCode = instanceInfo.getClusterCode();
        JobType jobType = instanceInfo.getJobType();

        if (StringUtils.isBlank(yarnQueue)) {
            yarnQueue = driverInfo.getYarnQueue();
            instanceInfo.setYarnQueue(yarnQueue);
        }

        DriverInstance status = driverInfo.getDriverInstType();
        // 分配到可用server地址
        if (status == DriverInstance.SHARE_INSTANCE) {
            String applicationId = driverInfo.getApplicationId();
            String driverAddress = driverInfo.getDriverAddress();
            try {
                if (!driverInfo.getYarnQueue().equals(yarnQueue)) {
                    LOG.info("{} 切换 yarn queue, {} -> {}", applicationId, driverInfo.getYarnQueue(), yarnQueue);
                    yarnClientService.moveApplicationAcrossQueues(clusterCode, applicationId, yarnQueue);
                }
                SubmitYarnResult result = new SubmitYarnResult(applicationId, driverAddress, yarnQueue);
                postTaskToServer(instanceInfo, result, driverInfo);
            } catch (SwitchYarnQueueException e) {
                applicationDriverService.updateDriverStatusIdle(applicationId);
                flinkLogService.removeLogThread(instanceCode);
                throw e;
            } catch (Exception e) {
                applicationDriverService.updateDriverStatusIdle(applicationId);
                flinkLogService.removeLogThread(instanceCode);
                LOG.error("post task to server error: " + ExceptionUtils.getStackTrace(e));
                if (ExceptionUtils.indexOfThrowable(e, SocketTimeoutException.class) > 0
                        && e.getMessage().contains("Read timed out")) {

                    LOG.error("server {} address {} Read timed out", applicationId, driverAddress);
                    yarnClientService.killApplicationOnYarn(clusterCode, applicationId);
                }

                String notifyMsg = "post to server " + applicationId + " error: " + e.getMessage();
                throw new HttpClientException(notifyMsg);
            }

            LOG.info("share jobserverid: {}, 实例code: {}", driverInfo.getDriverId(), instanceCode);
            return new JobSubmitResult(DriverInstance.SHARE_INSTANCE, driverAddress);
        } else if (status == NEW_INSTANCE) { //新的server正在提交
            Long jobserverId = driverInfo.getDriverId();
            instanceService.updateJobStatusByCode(instanceCode, InstanceStatus.SUBMITTING);
            taskSubmitExecutor.submit(() -> {
                try {
                    clusterManager.runSecured(clusterCode, () -> {
                        String applicationId = null;
                        try {
                            SubmitYarnResult result = driverDeployer.submitToYarn(instanceInfo, jobserverId);
                            applicationId = result.getApplicationId();
                            postTaskToServer(instanceInfo, result, driverInfo);
                            LOG.info("job {} has submited", instanceCode);
                        } catch (Exception e) {
                            flinkLogService.removeLogThread(instanceCode);

                            final String scheduleDate = DateUtils.formatDate(instanceInfo.getScheduleTime());
                            String path = configProperties.getInstanceLogPath() + "/" + scheduleDate + "/" + instanceCode + ".log";
                            MDC.put("logFileName", path);
                            LOG.error("hostName:{}, job: {}, type:{} submit to yarn error:{}",
                                    hostName, instanceCode, jobType, ExceptionUtils.getStackTrace(e));
                            INST_LOG.error("hostName:{}, job: {}, type:{} submit to yarn error:{}",
                                    hostName, instanceCode, jobType, ExceptionUtils.getStackTrace(e));

                            if (StringUtils.isNotBlank(applicationId)) {
                                try {
                                    yarnClientService.killYarnApp(clusterCode, applicationId);
                                    LOG.error("作业启动超时，退出提交，kill app {}", applicationId);
                                } catch (Exception ex) {
                                    LOG.error("作业启动超时，退出提交，kill app " + applicationId, ex);
                                }
                            }

                            submitFailureHandle(instanceInfo, jobserverId, e);
                        }

                        return null;
                    });
                } catch (Exception e) {
                    submitFailureHandle(instanceInfo, jobserverId, e);
                    LOG.error("hostName:{}, job: {}, type:{} submit to yarn error:{}",
                            hostName, instanceCode, jobType, ExceptionUtils.getStackTrace(e));
                }
            });

            LOG.info("new jobserverid: {}, 实例code: {}", jobserverId, instanceCode);
            return new JobSubmitResult(NEW_INSTANCE, jobserverId);
        } else {
            String errMsg = driverInfo.getMessage();
            return new JobSubmitResult(DriverInstance.ERROR_INSTANCE, errMsg);
        }
    }

    /**
     * 提交 instance 失败后处理
     */
    private void submitFailureHandle(JobInstanceInfo instanceInfo, Long driverId, Exception e) {
        InstanceType instanceType = instanceInfo.getInstanceType();
        JobInstance instance = instanceService.queryJobInstanceByCode(instanceInfo.getInstanceCode());

        LOG.info("delete driver: {}", driverId);
        applicationDriverService.deleteEntity(driverId);
        int retryCount = instance.getRetryCount();
        if (InstanceType.DEV != instanceType && retryCount < 2) {
            instance.setRetryCount(instance.getRetryCount() + 1);
            instance.setStatus(InstanceStatus.WAITING);
            INST_LOG.info("提交作业失败: " + e.getMessage() + "，等待重试...");
        } else {
            instance.setRetryCount(instance.getRetryCount() + 1);
            instance.setStatus(InstanceStatus.FAILED);
            INST_LOG.info("提交作业失败: " + e.getMessage());
        }
        instanceService.updateEntity(instance);
    }

    /**
     * 为Instance 分配可用的server
     */
    private DriverInfo allocateDriver(JobInstanceInfo instanceInfo, Boolean isDevTask) {
        boolean shareDriver = true;
        String instanceCode = instanceInfo.getInstanceCode();
        String clusterCode = instanceInfo.getClusterCode();
        try {
            boolean newDriver = checkStartNewDriver(instanceInfo.getJobConfig());
            if (instanceInfo.getRuntimeMode() == RuntimeMode.STREAMING) { // 流任务每次启动一个新的 driver
                newDriver = true;
            }
            if (newDriver) {
                Long driverId = driverDeployer.initSparkDriver(clusterCode, false);
                return new DriverInfo(NEW_INSTANCE, driverId);
            }

            DriverInfo driverInfo = driverDeployer.allocateDriver(clusterCode, shareDriver);
            driverInfo.setShareDriver(shareDriver);
            return driverInfo;
        } catch (FlinkJobException e) {
            String instanceLogPath = configProperties.getInstanceLogPath();
            String path = instanceLogPath + "/" + DateUtils.getCurrentDate() + "/" + instanceCode + ".log";
            MDC.put("logFileName", path);

            if (e instanceof ResouceLimitException) { //超过最大资源限制，继续等待运行
                if (isDevTask) {
                    INST_LOG.warn("{}, job {} stopped ", e.getMessage(), instanceCode);
                    instanceService.updateJobStatusByCode(instanceCode, InstanceStatus.KILLED);
                } else {
                    INST_LOG.warn("{}, job {} waiting... ", e.getMessage(), instanceCode);
                    instanceService.updateJobStatusByCode(instanceCode, InstanceStatus.WAITING);
                }
            } else {
                instanceService.updateJobStatusByCode(instanceCode, InstanceStatus.FAILED);
                String msg = ExceptionUtils.getStackTrace(e);
                INST_LOG.error("Job {}, error: {}", instanceCode, msg);
            }

            return new DriverInfo(DriverInstance.ERROR_INSTANCE, e.getMessage());
        }
    }

    /**
     * 如果设置参数前缀为: spark.sql, 不需要启动新的driver。
     */
    private boolean checkStartNewDriver(String jobConfig) {
        //@TODO
        return false;
    }

    /**
     * 发送任务请求给server
     */
    private void postTaskToServer(JobInstanceInfo instanceInfo, SubmitYarnResult submitYarnResult, DriverInfo driverInfo) throws Exception {
        String applicationId = submitYarnResult.getApplicationId();
        String jobText = instanceInfo.getJobText();
        String instanceCode = instanceInfo.getInstanceCode();
        String accessKey = instanceInfo.getAccessKey();
        String sparkDriverUrl = submitYarnResult.getFlinkDriverUrl();
        boolean shareDriver = driverInfo.isShareDriver();

        LOG.info("Task {} Get Server: {} at {}", instanceCode, applicationId, sparkDriverUrl);

        boolean hasLocked = instanceService.lockInstance(instanceCode);
        if (!hasLocked) {
            LOG.warn("作业已经在运行: {}", instanceCode);
            return;
        }

        Result<String> result = null;
        try {
            InstanceDto instanceDto = new InstanceDto();
            instanceDto.setApplicationId(applicationId);
            instanceDto.setDriverId(driverInfo.getDriverId());
            instanceDto.setShareDriver(shareDriver);
            instanceDto.setInstanceCode(instanceCode);
            instanceDto.setJobName(instanceInfo.getJobName());
            instanceDto.setJobType(instanceInfo.getJobType());
            instanceDto.setInstanceType(instanceInfo.getInstanceType());
            instanceDto.setJobText(jobText);
            instanceDto.setJobConfig(instanceInfo.getJobConfig());
            instanceDto.setAccessKey(accessKey);
            instanceDto.setYarnQueue(instanceInfo.getYarnQueue());
            instanceDto.setFlinkDriverUrl(sparkJobServerUrl);

            flinkLogService.createFlinkJobLog(instanceInfo, applicationId, shareDriver, sparkDriverUrl);
            String url = sparkDriverUrl + "/flinkDriver/runFlinkJob";
            StopWatch watch = StopWatch.createStarted();
            try {
                result = RestTemplateUtils.postEntry(restTemplate, url, instanceDto);
            } catch (Exception e) {
                TimeUnit.MILLISECONDS.sleep(100);
                result = RestTemplateUtils.postEntry(restTemplate, url, instanceDto);
            }
            watch.stop();

            instanceDto.setJobText(null);
            LOG.info("requet execute job times: {}ms, url : {}, params: {}",
                    watch.getTime(), url, JsonUtils.toJSONString(instanceDto));
        } catch (Exception e) {
            instanceService.unLockInstance(applicationId, instanceCode);
            throw new FlinkJobServerException("提交作业到 " + applicationId + " 失败: " + e.getMessage());
        }

        if (result != null && !result.isSuccess()) {
            flinkLogService.removeLogThread(instanceCode);
            instanceService.unLockInstance(applicationId, instanceCode);
            throw new FlinkJobServerException(result.getMessage());
        }
    }
}
