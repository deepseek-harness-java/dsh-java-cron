package cn.xiaofuge.dsh.cron.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CronForgeProperties.class)
public class ApplicationConfiguration {
}
