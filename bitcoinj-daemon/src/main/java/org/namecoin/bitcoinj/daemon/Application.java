package org.namecoin.bitcoinj.daemon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * Spring Boot application container for **bitcoinj daemon**
 */
@Configuration
@EnableAutoConfiguration
@ComponentScan(basePackages={"com.msgilligan.bitcoinj.daemon", "org.namecoin.bitcoinj.daemon"}, excludeFilters=@Filter(type=FilterType.ASSIGNABLE_TYPE, classes={com.msgilligan.bitcoinj.daemon.Application.class, com.msgilligan.bitcoinj.daemon.config.BitcoinConfig.class}))
public class Application {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(Application.class);
        app.setShowBanner(false);
        ApplicationContext ctx = app.run(args);
    }
}
