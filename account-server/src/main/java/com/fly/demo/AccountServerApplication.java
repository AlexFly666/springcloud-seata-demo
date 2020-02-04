package com.fly.demo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 账户服务
 * @author 王延飞
 */
// 在启动类中取消数据源的自动创建
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@MapperScan("com.fly.demo.dao")
@EnableDiscoveryClient
@EnableFeignClients
public class AccountServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(AccountServerApplication.class, args);
	}

}
