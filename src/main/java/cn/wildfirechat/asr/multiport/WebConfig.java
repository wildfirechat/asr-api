package cn.wildfirechat.asr.multiport;

import cn.wildfirechat.asr.jpa.ApplicationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Value("${server.adminPort}")
    private int adminPort;

    @Value("${server.adminPathPrefix}")
    private String adminPathPrefix;

    @Value("${server.need_signature}")
    private boolean needSignature;


    @Autowired
    ApplicationRepository applicationRepository;

    @Bean
    public FilterRegistrationBean<InternalEndpointsFilter> trustedEndpointsFilter() {
        return new FilterRegistrationBean<>(new InternalEndpointsFilter(adminPort, adminPathPrefix, applicationRepository, needSignature));
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("/index.html");
    }
}