package com.encircle360.oss.bankbote.web;

import com.encircle360.oss.bankbote.config.ConfigurationCheck;
import com.encircle360.oss.bankbote.config.InvalidConfigurationException;
import com.encircle360.oss.bankbote.config.ProbeProperties;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Set;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.coyote.http11.Http11NioProtocol;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The optional probe port: a second listener of the same web server, for health probes from outside
 * the network namespace. It answers {@code GET} and {@code HEAD} of the liveness and readiness
 * groups, which show nothing but their status, and an empty 404 to every other request, so nothing
 * that starts a run or shows the last one is reachable through it. The main port stays on the
 * loopback address.
 *
 * <p>The listener is added at start from {@code bankbote.probes.*} rather than by a condition,
 * because the native image fixes every bean condition when it is built.
 */
@Component
public class ProbePort implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

  static final Set<String> PATHS =
      Set.of("/actuator/health/liveness", "/actuator/health/readiness");

  private final ProbeProperties probes;
  private final Environment environment;

  public ProbePort(ProbeProperties probes, Environment environment) {
    this.probes = probes;
    this.environment = environment;
  }

  @Override
  public void customize(TomcatServletWebServerFactory factory) {
    List<String> problems = ConfigurationCheck.probeProblems(probes, environment);
    if (!problems.isEmpty()) {
      throw new InvalidConfigurationException(problems);
    }
    if (probes.port() == null) {
      return;
    }
    Http11NioProtocol protocol = new Http11NioProtocol();
    if (probes.address() != null) {
      protocol.setAddress(InetAddress.ofLiteral(probes.address()));
    }
    Connector connector = new Connector(protocol);
    connector.setPort(probes.port());
    connector.setThrowOnFailure(true);
    factory.addAdditionalConnectors(connector);
    factory.addEngineValves(new ProbesOnly(connector));
  }

  /** Lets only the probes through on the probe listener; every other listener is untouched. */
  static final class ProbesOnly extends ValveBase {

    private final Connector probes;

    ProbesOnly(Connector probes) {
      super(true);
      this.probes = probes;
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
      if (request.getConnector() != probes || isProbe(request)) {
        getNext().invoke(request, response);
        return;
      }
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
    }

    /** The raw path, so that no encoding, path parameter or dot segment reaches another one. */
    private static boolean isProbe(Request request) {
      String method = request.getMethod();
      return ("GET".equals(method) || "HEAD".equals(method))
          && PATHS.contains(request.getRequestURI());
    }
  }
}
