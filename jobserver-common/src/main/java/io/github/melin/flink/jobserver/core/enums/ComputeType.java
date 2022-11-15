package io.github.melin.flink.jobserver.core.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.gitee.melin.bee.core.enums.BaseStringEnum;
import com.gitee.melin.bee.core.enums.jackson.JacksonEnumStringSerializer;

/**
 * 任务计算资源类型
 * Created by admin on 2019/10/29 3:01 下午
 */
@JsonSerialize(using = JacksonEnumStringSerializer.class)
public enum ComputeType implements BaseStringEnum {
    YARN_BATCH("yarn_batch"),
    YARN_STREAM("yarn_stream"),
    K8S_BATCH("k8s_batch"),
    K8S_STREAM("k8s_stream");

    private String name;

    private ComputeType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @JsonValue
    @Override
    public String getValue() {
        return name;
    }
}
