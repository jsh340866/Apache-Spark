package com.example.demo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.Executor;

// AsyncConfigurer 구현 - @Async void 메서드의 미처리 예외를 커스텀 핸들러로 받도록 등록
// (각 @Async("dartExecutor") 등 이름 지정 호출은 getAsyncExecutor()와 무관하게 그대로 동작함)
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    // 주가 수집용 스레드풀
    // corePoolSize=10: 기본 10개 스레드 유지
    // maxPoolSize=10: 최대 10개 고정
    // queueCapacity=20: 대기 작업 최대 20개
    @Bean("stockExecutor")
    public Executor stockExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(15);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("stock-");
        executor.initialize();
        return executor;
    }

    // DART API용 스레드풀
    // corePoolSize=5: 기본 5개 스레드 유지
    // maxPoolSize=15: 최대 15개까지 확장 (CompletableFuture 병렬 처리 고려)
    // queueCapacity=100: 대기 작업 최대 100개
    @Bean("dartExecutor")
    public Executor dartExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(15);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("dart-");
        executor.initialize();
        return executor;
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        return objectMapper;
    }
}