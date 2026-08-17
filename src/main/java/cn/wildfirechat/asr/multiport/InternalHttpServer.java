package cn.wildfirechat.asr.multiport;


import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class InternalHttpServer {

    @Value("${server.adminPort}")
    private int adminPort;

    @Bean
    public ServletWebServerFactory servletContainer() {
        Connector adminConnector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        adminConnector.setScheme("http");
        adminConnector.setPort(adminPort);

        TomcatServletWebServerFactory tomcat = new TomcatServletWebServerFactory();
        tomcat.addAdditionalTomcatConnectors(adminConnector);
        return tomcat;
    }
}
