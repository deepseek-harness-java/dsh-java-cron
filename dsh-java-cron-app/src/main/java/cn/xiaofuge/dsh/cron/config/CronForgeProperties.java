package cn.xiaofuge.dsh.cron.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dsh.cronforge")
public record CronForgeProperties(
        String dataDirectory,
        Harness harness
) {
    public record Harness(String baseUrl) {
    }
}
