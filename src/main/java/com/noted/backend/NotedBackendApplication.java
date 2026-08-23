package com.noted.backend;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // can cho job dong bo Drive chay nen dinh ky
@EnableAsync        // ghi file + goi Drive API khong block request chinh
public class NotedBackendApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure()
                .filename(".env") // khop voi file THAT su dung (README: cp .env.example -> .env)
                .ignoreIfMissing()    // Tránh lỗi nếu file không tồn tại ở môi trường Prod
                .load();

        dotenv.entries().forEach(entry ->
                System.setProperty(entry.getKey(), entry.getValue())
        );

        SpringApplication.run(NotedBackendApplication.class, args);
    }
}
