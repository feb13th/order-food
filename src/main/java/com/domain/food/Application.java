package com.domain.food;

import com.domain.food.config.ConfigProperties;
import com.domain.food.core.listener.ApplicationExitCommandProcessor;
import com.domain.food.core.listener.CommandLineListener;
import com.domain.food.core.listener.DaoCommandProcessor;
import com.domain.food.core.listener.ICommandLineProcessor;
import com.domain.food.utils.HttpUtil;
import org.springframework.beans.BeansException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@EnableSwagger2
@RestController
@SpringBootApplication
@EnableConfigurationProperties(ConfigProperties.class)
public class Application implements ApplicationContextAware {

    private ApplicationContext applicationContext;


    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @GetMapping("/")
    public void index() throws ServletException, IOException {
        HttpServletRequest request = HttpUtil.getHttpServletRequest();
        HttpServletResponse response = HttpUtil.getHttpServletResponse();
        request.getRequestDispatcher("/index.html").forward(request, response);
    }

    @Bean
    public Docket swaggerConfig() {
        return new Docket(DocumentationType.SWAGGER_2)
                .select()
                .apis(RequestHandlerSelectors.any())
                .paths(PathSelectors.any())
                .build();
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * 创建命令行监听器
     */
    @Bean
    public CommandLineListener commandLineListener() {
        CommandLineListener listener = new CommandLineListener();
        listener.setApplicationContext(applicationContext);
        return listener;
    }

    /**
     * 创建dao命令行处理器
     */
    @Bean
    public ICommandLineProcessor daoCommandListener() {
        DaoCommandProcessor listener = new DaoCommandProcessor();
        listener.setApplicationContext(applicationContext);
        return listener;
    }

    /**
     * 创建应用程序命令行处理器
     */
    @Bean
    public ICommandLineProcessor applicationExitCommandListener() {
        return new ApplicationExitCommandProcessor();
    }
}
