/*
 * Copyright (c) 2022-2023 Felix Kirchmann.
 * Distributed under the MIT License (license terms are at http://opensource.org/licenses/MIT).
 *
 * NOTE: This was developed as a one-off - the code still needs a few days of love and refactoring to be properly usable.
 */

package jtr.distributed.server.web;

import freemarker.cache.ClassTemplateLoader;
import freemarker.cache.TemplateNameFormat;
import freemarker.template.Configuration;
import jtr.distributed.server.ServerMain;
import jtr.distributed.server.web.util.ExceptionLogger;
import jtr.distributed.server.web.util.JacksonObjectMapperProvider;
import org.glassfish.grizzly.http.server.CLStaticHttpHandler;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.mvc.freemarker.FreemarkerConfigurationFactory;
import org.glassfish.jersey.server.mvc.freemarker.FreemarkerMvcFeature;

import javax.servlet.ServletContext;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriBuilder;
import java.net.URI;

import static freemarker.template.Configuration.VERSION_2_3_23;

public class WebServer {
    public static final String PATH = "/jtr-distributed";

    public static final String TEMPLATE_BASE_CPATH = "/jtr/distributed/server/web/templates/";
    public static final String STATIC_RESOURCES_CPATH = "/jtr/distributed/server/web/static/";

    private final HttpServer httpServer;

    public WebServer(final String host, final int port, ServerMain serverMain)
    {
        URI uri = UriBuilder.fromUri("http://" + host + PATH).port(port).build();
        ResourceConfig config = new JTRDistributedWebApplication(serverMain);
        httpServer = GrizzlyHttpServerFactory.createHttpServer(uri, config);

        // Add static file capability
        HttpHandler httpHandler = new CLStaticHttpHandler(WebServer.class.getClassLoader(), STATIC_RESOURCES_CPATH);
        httpServer.getServerConfiguration().addHttpHandler(httpHandler, "/static/");
    }

    public static class JTRDistributedWebApplication extends ResourceConfig {
        @Context
        ServletContext servletContext;

        private final Configuration freemarkerConfig = new Configuration(VERSION_2_3_23);

        public JTRDistributedWebApplication(ServerMain serverMain) {
            this.freemarkerConfig.setTemplateLoader(new ClassTemplateLoader(this.getClassLoader(), ""));
            this.freemarkerConfig.setTemplateNameFormat(TemplateNameFormat.DEFAULT_2_4_0);
            this.freemarkerConfig.setAPIBuiltinEnabled(true);
            this.freemarkerConfig.setDefaultEncoding("UTF-8");

            this.property(FreemarkerMvcFeature.TEMPLATE_OBJECT_FACTORY,
                    (FreemarkerConfigurationFactory) () -> this.freemarkerConfig);
            this.property(FreemarkerMvcFeature.TEMPLATE_BASE_PATH, TEMPLATE_BASE_CPATH);
            this.register(FreemarkerMvcFeature.class);
            this.register(JacksonFeature.class);
            this.register(JacksonObjectMapperProvider.class);

            this.registerClasses(ExceptionLogger.class);
            this.registerInstances(
                    new WebApi(serverMain),
                    new WebUi(serverMain));
        }
    }
}
