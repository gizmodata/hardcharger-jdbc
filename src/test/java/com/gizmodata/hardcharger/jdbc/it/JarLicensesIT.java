package com.gizmodata.hardcharger.jdbc.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gizmodata.hardcharger.jdbc.HardchargerDriver;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * The licence texts in the two jars that are released. The driver-only jar (Maven Central's) has
 * this driver's LICENSE and NOTICE. The standalone jar, which also contains the Chargewire JDBC
 * driver and everything that driver bundles, has the LICENSE, a NOTICE that is this driver's
 * followed by the Chargewire driver's and each component's, the Chargewire driver's listing of
 * third-party components, and every licence text the listing names; the listing must name exactly
 * the components in the jar. No server is involved.
 */
class JarLicensesIT {
  private static final Pattern LISTED =
      Pattern.compile("^([\\w.\\-]+:[\\w.\\-]+:[\\w.\\-]+) \\| ([\\w.\\-]+) \\| (META-INF/\\S+)$");

  private static final Pattern PATH = Pattern.compile("META-INF/[\\w./\\-]*[\\w\\-]");

  private static File standaloneJar() throws Exception {
    File jar =
        new File(
            HardchargerDriver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    assertTrue(
        jar.isFile() && jar.getName().equals("hardcharger-jdbc.jar"),
        "the integration tests run against the standalone jar, not " + jar);
    return jar;
  }

  private static String text(ZipFile zip, String name) throws IOException {
    ZipEntry entry = zip.getEntry(name);
    assertNotNull(entry, name + " is not in " + zip.getName());
    try (InputStream in = zip.getInputStream(entry)) {
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(text.strip().length() > 100, name + " is empty");
      return text;
    }
  }

  @Test
  void theDriverOnlyJarHasItsLicenceAndNotice() throws Exception {
    File jar =
        new File(
            standaloneJar().getParentFile(),
            "hardcharger-jdbc-" + System.getProperty("project.version") + ".jar");
    assertTrue(jar.isFile(), jar + " (the jar Maven Central gets) was not built");
    try (ZipFile zip = new ZipFile(jar)) {
      assertEquals(Files.readString(Path.of("LICENSE")), text(zip, "META-INF/LICENSE"));
      assertEquals(Files.readString(Path.of("NOTICE")), text(zip, "META-INF/NOTICE"));
      assertEquals(
          null,
          zip.getEntry("META-INF/THIRD-PARTY-NOTICES.txt"),
          "the driver-only jar bundles nothing");
    }
  }

  @Test
  void theStandaloneJarHasTheLicencesOfEverythingInIt() throws Exception {
    try (ZipFile zip = new ZipFile(standaloneJar())) {
      String licence = text(zip, "META-INF/LICENSE");
      assertEquals(Files.readString(Path.of("LICENSE")), licence, "the repository's LICENSE");
      assertTrue(
          licence.contains("Apache License") && licence.contains("Version 2.0, January 2004"));

      // This driver's notice, then the Chargewire driver's with each component's after it.
      String notice = text(zip, "META-INF/NOTICE");
      String own = Files.readString(Path.of("NOTICE"));
      assertTrue(notice.startsWith(own), notice.substring(0, Math.min(notice.length(), 80)));
      String rest = notice.substring(own.length()).stripLeading();
      assertTrue(
          rest.startsWith("Chargewire JDBC driver\nCopyright"),
          rest.substring(0, Math.min(rest.length(), 80)));
      for (String component :
          List.of(
              "org.apache.arrow:arrow-vector:",
              "com.fasterxml.jackson.core:jackson-core:",
              "commons-codec:commons-codec:",
              "Arrow Vectors",
              "Apache Commons Codec")) {
        assertTrue(rest.contains(component), "NOTICE has no " + component);
      }

      // The Chargewire driver's listing names exactly what this jar contains, beside the two
      // drivers themselves.
      TreeSet<String> contained = new TreeSet<>();
      for (ZipEntry entry : Collections.list(zip.entries())) {
        if (entry.getName().matches("META-INF/maven/[^/]+/[^/]+/pom\\.properties")) {
          Properties properties = new Properties();
          try (InputStream in = zip.getInputStream(entry)) {
            properties.load(in);
          }
          contained.add(
              properties.getProperty("groupId")
                  + ":"
                  + properties.getProperty("artifactId")
                  + ":"
                  + properties.getProperty("version"));
        }
      }
      assertTrue(contained.removeIf(a -> a.startsWith("com.gizmodata:hardcharger-jdbc:")));
      assertTrue(contained.removeIf(a -> a.startsWith("com.gizmodata:chargewire-jdbc:")));
      assertNotNull(zip.getEntry("com/github/luben/zstd/util/ZstdVersion.class"));
      String listing = text(zip, "META-INF/THIRD-PARTY-NOTICES.txt");
      TreeSet<String> listed = new TreeSet<>();
      String zstdJni = null;
      for (String line : listing.split("\n")) {
        Matcher matcher = LISTED.matcher(line.trim());
        if (matcher.matches()) {
          listed.add(matcher.group(1));
          if (matcher.group(1).startsWith("com.github.luben:zstd-jni:")) {
            zstdJni = matcher.group(1);
          }
        }
      }
      // zstd-jni isn't built with Maven, so it left no pom.properties: it is in the jar (the
      // class above) and the Chargewire driver's own build checks the version listed for it.
      assertNotNull(zstdJni, "the listing names zstd-jni");
      contained.add(zstdJni);
      assertEquals(
          contained,
          listed,
          "META-INF/THIRD-PARTY-NOTICES.txt must list every component in the jar, in its version"
              + " (first: what the jar contains)");

      // Every licence file the listing names is in the jar.
      Matcher path = PATH.matcher(listing);
      int named = 0;
      while (path.find()) {
        text(zip, path.group());
        named++;
      }
      assertTrue(named >= listed.size(), "the listing names " + named + " files");
      for (String name :
          List.of(
              "META-INF/licenses/slf4j/LICENSE.txt",
              "META-INF/licenses/zstd-jni/LICENSE",
              "META-INF/licenses/zstd/LICENSE")) {
        text(zip, name);
      }
      for (String name :
          List.of("META-INF/LICENSE.txt", "META-INF/NOTICE.txt", "META-INF/DEPENDENCIES")) {
        assertEquals(null, zip.getEntry(name), name + " of some component got into the jar");
      }
    }
  }
}
