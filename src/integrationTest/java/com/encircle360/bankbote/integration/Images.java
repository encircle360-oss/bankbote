package com.encircle360.bankbote.integration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testcontainers.utility.DockerImageName;

/**
 * The container images of the integration tests, from {@code
 * src/integrationTest/images/Dockerfile}, where each is pinned by tag and digest under its stage
 * name.
 */
public final class Images {

  private static final Pattern FROM = Pattern.compile("^FROM\\s+(\\S+)\\s+AS\\s+(\\S+)\\s*$");

  private Images() {}

  /**
   * The image of a stage, or the one a variable {@code BANKBOTE_IMAGE_<STAGE>} names, such as
   * {@code BANKBOTE_IMAGE_HIBISCUS_SERVER=hibiscus-server:local} for an image built from its
   * repository.
   */
  public static DockerImageName image(String stage) {
    String variable = "BANKBOTE_IMAGE_" + stage.toUpperCase(Locale.ROOT).replace('-', '_');
    String override = System.getenv(variable);
    if (override != null && !override.isBlank()) {
      return DockerImageName.parse(override.strip());
    }
    Path file =
        Path.of(System.getProperty("bankbote.images", "src/integrationTest/images/Dockerfile"));
    try {
      for (String line : Files.readAllLines(file)) {
        Matcher matcher = FROM.matcher(line.strip());
        if (matcher.matches() && matcher.group(2).equals(stage)) {
          return DockerImageName.parse(matcher.group(1));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    throw new IllegalArgumentException(file + " has no stage " + stage);
  }
}
