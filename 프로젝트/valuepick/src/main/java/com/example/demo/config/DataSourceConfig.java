//package com.example.demo.config;
//
//
//import com.zaxxer.hikari.HikariDataSource;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//
//import javax.sql.DataSource;
//
//@Configuration
//public class DataSourceConfig {
//    @Bean
//    public DataSource dataSource()
//    {
//        HikariDataSource dataSource = new HikariDataSource();
//        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
//        dataSource.setJdbcUrl("jdbc:mysql://localhost:3306/investdb");
//        dataSource.setUsername("root");
//        dataSource.setPassword("1234");
//        return dataSource;
//    }
//}
