package com.xalgocapital.claude.springai.s04;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * s04 的 Spring AI 对照：用 advisor 链表达「挂在循环周围的扩展」。
 *
 * 手写版 s04 要自己写 HookRegistry、自己 register、自己在循环里 trigger。
 * 这里把这些交给框架：ChatClient 的调用会被 advisor 链自动环绕，
 * 自定义 LoggingAdvisor 的 before()/after() 就是两个 hook 点。
 */
@SpringBootApplication
public class SpringAiHooksApp {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiHooksApp.class, args);
    }

    @Bean
    CommandLineRunner demo(ChatClient.Builder builder) {
        return args -> {
            ChatClient chatClient = builder
                    .defaultAdvisors(new LoggingAdvisor())
                    .build();

            String answer = chatClient.prompt()
                    .user("用一句话说明什么是 hook。")
                    .call()
                    .content();

            System.out.println("\n最终回答: " + answer);
        };
    }
}
