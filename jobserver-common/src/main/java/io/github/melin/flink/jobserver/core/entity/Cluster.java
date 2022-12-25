package io.github.melin.flink.jobserver.core.entity;

import com.gitee.melin.bee.core.hibernate5.type.BooleanToIntConverter;
import io.github.melin.flink.jobserver.core.enums.SchedulerType;
import io.github.melin.flink.jobserver.core.enums.StorageType;
import com.gitee.melin.bee.model.IEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Type;

import javax.persistence.*;
import java.time.Instant;

@Getter
@Setter
@ToString
@NoArgsConstructor
@Entity
@Table(name = "fjs_cluster")
public class Cluster implements IEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    private String code;

    private String name;

    @Column(name = "storage_type")
    @Type(type = "com.gitee.melin.bee.core.enums.StringValuedEnumType",
            parameters = {@org.hibernate.annotations.Parameter(name = "enumClass",
                    value = "io.github.melin.flink.jobserver.core.enums.StorageType")})
    private StorageType storageType = StorageType.HDFS; //存储类型:HDFS、S3等文件系统

    @Column(name = "scheduler_type")
    @Type(type = "com.gitee.melin.bee.core.enums.StringValuedEnumType",
            parameters = {@org.hibernate.annotations.Parameter(name = "enumClass",
                    value = "io.github.melin.flink.jobserver.core.enums.SchedulerType")})
    private SchedulerType schedulerType; // 调度框架:YARN、K8S

    @Column(name = "kerberos_enabled")
    @Convert(converter = BooleanToIntConverter.class)
    private boolean kerberosEnabled = false;

    @Column(name = "kerberos_keytab")
    private byte[] kerberosKeytab;

    @Column(name = "kerberos_file_name")
    private String kerberosFileName;

    @Column(name = "kerberos_config")
    private String kerberosConfig;

    @Column(name = "kerberos_user")
    private String kerberosUser;

    @Column(name = "jobserver_config")
    private String jobserverConfig;

    @Column(name = "flink_config")
    private String flinkConfig;

    @Column(name = "core_config")
    private String coreConfig;

    @Column(name = "hdfs_config")
    private String hdfsConfig;

    @Column(name = "yarn_config")
    private String yarnConfig;

    @Column(name = "hive_config")
    private String hiveConfig;

    @Column(name = "Kubernetes_config")
    private String kubernetesConfig;

    @Column(name = "storage_config")
    private String storageConfig;

    @Convert(converter = BooleanToIntConverter.class)
    private boolean status = false;

    @Column(name = "creater", length = 45)
    private String creater;

    @Column(name = "modifier", length = 45)
    private String modifier;

    @Column(name = "gmt_created", nullable = false)
    private Instant gmtCreated;

    @Column(name = "gmt_modified")
    private Instant gmtModified;
}
