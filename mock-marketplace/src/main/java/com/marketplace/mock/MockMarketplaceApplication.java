package com.marketplace.mock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.marketplace.mock.config.AppProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class MockMarketplaceApplication {

	public static void main(String[] args) {
		SpringApplication.run(MockMarketplaceApplication.class, args);
	}

}
