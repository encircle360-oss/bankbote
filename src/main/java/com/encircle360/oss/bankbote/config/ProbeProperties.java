package com.encircle360.oss.bankbote.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The optional probe port under {@code bankbote.probes.*}, for health probes from outside the
 * network namespace, such as those of the kubelet. {@link ConfigurationCheck#probeProblems} checks
 * it at start.
 *
 * @param port the port, or null for no probe port
 * @param address the IP address the probe port listens on, or null for every address
 */
@ConfigurationProperties("bankbote.probes")
public record ProbeProperties(Integer port, String address) {}
