package io.github.melin.flink.jobserver.core.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.gitee.melin.bee.core.enums.BaseStringEnum;
import com.gitee.melin.bee.core.enums.jackson.JacksonEnumStringSerializer;

/**
 * Created by admin on 2020/11/30 6:26 下午
 */
@JsonSerialize(using = JacksonEnumStringSerializer.class)
public enum DriverType implements BaseStringEnum {
    THRIFT_SERVER("thriftServer"),
    DRIVER_SERVER("driverServer");

    private String name;

    private DriverType(String name) {
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

    public static DriverType fromString(String code) {
        for (DriverType type : DriverType.values()) {
            if (type.getName().equalsIgnoreCase(code)) {
                return type;
            }
        }

        return null;
    }
}
