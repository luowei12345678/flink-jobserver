package io.github.melin.flink.jobserver.web.controller;

import com.gitee.melin.bee.util.HibernateUtils;
import io.github.melin.flink.jobserver.FlinkJobServerConf;
import io.github.melin.flink.jobserver.core.entity.Cluster;
import io.github.melin.flink.jobserver.core.enums.SchedulerType;
import io.github.melin.flink.jobserver.core.service.ApplicationDriverService;
import io.github.melin.flink.jobserver.core.service.ClusterService;
import com.gitee.melin.bee.core.support.Pagination;
import com.gitee.melin.bee.core.support.Result;
import com.google.common.collect.Lists;
import io.github.melin.flink.jobserver.core.service.SessionClusterService;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.criterion.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

@Controller
public class ClusterController {

    private static final Logger LOG = LoggerFactory.getLogger(ClusterController.class);

    @Autowired
    private ClusterService clusterService;

    @Autowired
    private ApplicationDriverService applicationDriverService;

    @Autowired
    private SessionClusterService sessionClusterService;

    @Autowired
    protected RestTemplate restTemplate;

    @RequestMapping("/cluster")
    public String cluster(ModelMap model) throws Exception {
        String confDefaultValue = FlinkJobServerConf.printConfWithDefaultValue();
        model.addAttribute("confDefaultValue", confDefaultValue);
        return "cluster";
    }

    @RequestMapping("/cluster/queryClusters")
    @ResponseBody
    public Pagination<Cluster> queryClusters(String code, int page, int limit, HttpServletRequest request) {
        String sort = request.getParameter("sort");
        String order = request.getParameter("order");

        Order order1 = Order.desc("gmtModified");
        if (StringUtils.isNotEmpty(sort)) {
            if ("asc".equals(order)) {
                order1 = Order.asc(sort);
            } else {
                order1 = Order.desc(sort);
            }
        }

        List<String> params = Lists.newArrayList();
        List<Object> values = Lists.newArrayList();
        if (StringUtils.isNotBlank(code)) {
            params.add("code");
            values.add(code);
        }
        return clusterService.findPageByNamedParamAndOrder(params, values,
                Lists.newArrayList(order1), page, limit);
    }

    @RequestMapping("/cluster/queryClusterNames")
    @ResponseBody
    public List<Cluster> queryClusters() {
        return clusterService.findAllEntity(
                HibernateUtils.projectionList("code", "name", "schedulerType"));
    }

    @RequestMapping("/cluster/queryCluster")
    @ResponseBody
    public Result<Cluster> queryCluster(Long clusterId) {
        try {
            Cluster cluster = clusterService.getEntity(clusterId);
            return Result.successDataResult(cluster);
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
            return Result.failureResult(e.getMessage());
        }
    }

    @RequestMapping("/cluster/saveCluster")
    @ResponseBody
    public Result<Void> saveCluster(Cluster cluster, String keytabBase64) {
        if (SchedulerType.YARN == cluster.getSchedulerType()) {
            if (!StringUtils.contains(cluster.getYarnConfig(), "yarn.resourcemanager.webapp.address")
                    || !StringUtils.contains(cluster.getYarnConfig(), "yarn.resourcemanager.address")) {

                String msg = "yarn-site.xml 缺少 yarn.resourcemanager.webapp.address & yarn.resourcemanager.address 参数配置";
                return Result.failureResult(msg);
            }
        }

        if (cluster.isKerberosEnabled()) {
            if (StringUtils.isBlank(keytabBase64) || StringUtils.isBlank(cluster.getKerberosConfig())) {
                return Result.failureResult("kerberos 配置不能为空");
            }

            if (StringUtils.isNotBlank(cluster.getHdfsConfig())) {
                if (!StringUtils.contains(cluster.getHdfsConfig(), "dfs.namenode.kerberos.principal")) {
                    String msg = "开启kerberos 认证，hdfs-site.xml 缺少 dfs.namenode.kerberos.principal 参数配置";
                    return Result.failureResult(msg);
                }
            }

            byte[] bytes = keytabBytes(keytabBase64);
            cluster.setKerberosKeytab(bytes);
        }

        try {
            cluster.setGmtCreated(Instant.now());
            cluster.setGmtModified(Instant.now());

            if (cluster.getId() == null) {
                cluster.setCreater("jobserver");
                cluster.setModifier("jobserver");
                clusterService.insertEntity(cluster);
            } else {
                Cluster old = clusterService.getEntity(cluster.getId());
                old.setName(cluster.getName());
                old.setSchedulerType(cluster.getSchedulerType());
                old.setJobserverConfig(cluster.getJobserverConfig());
                old.setFlinkConfig(cluster.getFlinkConfig());
                old.setCoreConfig(cluster.getCoreConfig());
                old.setHdfsConfig(cluster.getHdfsConfig());
                old.setYarnConfig(cluster.getYarnConfig());
                old.setHiveConfig(cluster.getHiveConfig());
                old.setKerberosConfig(cluster.getKerberosConfig());
                old.setKerberosEnabled(cluster.isKerberosEnabled());
                old.setKubernetesConfig(cluster.getKubernetesConfig());
                old.setJmPodTemplate(cluster.getJmPodTemplate());
                old.setTmPodTemplate(cluster.getTmPodTemplate());
                old.setKerberosEnabled(cluster.isKerberosEnabled());
                old.setKerberosUser(cluster.getKerberosUser());
                old.setKerberosConfig(cluster.getKerberosConfig());
                old.setKerberosKeytab(cluster.getKerberosKeytab());
                old.setKerberosFileName(cluster.getKerberosFileName());
                old.setGmtModified(Instant.now());
                clusterService.updateEntity(old);
            }
            return Result.successResult();
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
            return Result.failureResult(e.getMessage());
        }
    }

    private byte[] keytabBytes(String keytabBase64) {
        byte[] keytabBytes = null;
        try {
            if (StringUtils.isNotBlank(keytabBase64)) {
                keytabBytes = Base64.getDecoder().decode(keytabBase64);
            }
            return keytabBytes;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @RequestMapping("/cluster/updateStatus")
    @ResponseBody
    public Result<Void> updateStatus(Long clusterId, Boolean status) {
        try {
            if (status == null) {
                return Result.failureResult("status is null");
            }

            Cluster cluster = clusterService.getEntity(clusterId);
            cluster.setStatus(status);
            clusterService.updateEntity(cluster);
            return Result.successResult();
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
            return Result.failureResult(e.getMessage());
        }
    }

    @RequestMapping("/cluster/deleteCluster")
    @ResponseBody
    public Result<Void> deleteCluster(Long clusterId) {
        try {
            Cluster cluster = clusterService.getEntity(clusterId);
            long appDriverCount = applicationDriverService.queryDriverCount(cluster.getCode());
            long sessionCount = sessionClusterService.queryDriverCount(cluster.getCode());

            if (appDriverCount > 0 || sessionCount > 0) {
                return Result.failureResult("The cluster " + cluster.getCode() + " is used and cannot be deleted");
            }

            clusterService.deleteEntity(cluster);
            return Result.successResult();
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
            return Result.failureResult(e.getMessage());
        }
    }

    @RequestMapping("/cluster/downloadKeytab")
    public void downloadKeytab(HttpServletResponse response, Long clusterId) throws IOException {
        Cluster cluster = clusterService.getEntity(clusterId);
        if (Objects.isNull(cluster)) {
            throw new RuntimeException("集群不存在");
        }

        OutputStream outputStream = null;
        try {
            String downloadFilename = cluster.getKerberosFileName();
            response.setContentType("application/x-download");
            response.setHeader("Location", downloadFilename);
            response.setHeader("Content-Disposition", "attachment; filename=" + downloadFilename);
            outputStream = response.getOutputStream();
            IOUtils.write(cluster.getKerberosKeytab(), outputStream);
        } catch (IOException e) {
            LOG.error("下载keytab 失败: " + e.getMessage(), e);
        } finally {
            if (outputStream != null) {
                outputStream.flush();
                IOUtils.closeQuietly(outputStream);
            }
        }
    }
}
