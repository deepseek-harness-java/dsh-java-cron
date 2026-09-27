package cn.xiaofuge.dsh.cron;

import cn.xiaofuge.dsh.cron.config.CronForgeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(CronForgeProperties.class)
public class DshJavaCronForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(DshJavaCronForgeApplication.class, args);
    }
}
