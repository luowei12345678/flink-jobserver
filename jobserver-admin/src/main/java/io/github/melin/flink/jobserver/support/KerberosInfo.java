package io.github.melin.flink.jobserver.support;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
@Builder(builderClassName = "Builder")
public class KerberosInfo {
    private boolean enabled;

    private boolean tempKerberos = false;

    private String principal;

    private String keytabFile;

    private String krb5File;
}
